package io.github.shrishaanth.codeatlas.qa;

import io.github.shrishaanth.codeatlas.config.CodeAtlasProperties;
import io.github.shrishaanth.codeatlas.index.ChunkRepository;
import io.github.shrishaanth.codeatlas.index.ChunkSearch;
import io.github.shrishaanth.codeatlas.index.CodeChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Answers questions about an analyzed repository. Retrieval is the product: the matching code is
 * always returned, and a language model, if one is configured, may only add prose on top of it.
 * See docs/metrics.md, "Question answering".
 */
@Service
public class QaService {

    private static final Logger log = LoggerFactory.getLogger(QaService.class);

    private static final String SYSTEM_PROMPT = """
            You help a developer understand an unfamiliar codebase.
            Rules:
            - Answer only from the numbered excerpts below. They are the whole of your knowledge here.
            - Cite the file and line for every claim, as path:line or path:line-line, e.g. src/app/main.py:42.
            - If the excerpts do not answer the question, say so plainly and name what is missing.
            - Be concise: at most 150 words. No preamble, no apology, no invented file names.
            """;

    private final ChunkRepository chunks;
    private final ObjectProvider<LlmClient> llm;
    private final CodeAtlasProperties.Qa config;
    private final Clock clock;
    private final Map<String, int[]> asksPerHour = new HashMap<>(); // caller -> {hour, count}

    public QaService(ChunkRepository chunks, ObjectProvider<LlmClient> llm, CodeAtlasProperties properties,
                     Clock clock) {
        this.chunks = chunks;
        this.llm = llm;
        this.config = properties.qa();
        this.clock = clock;
    }

    public boolean modelConfigured() {
        return llm.getIfAvailable() != null;
    }

    public String modelName() {
        LlmClient client = llm.getIfAvailable();
        return client == null ? null : client.model();
    }

    /** The code that matches a question, best first, with no model involved. */
    public List<ChunkSearch.Result> search(UUID analysisId, String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        List<ChunkRepository.Match> matches = chunks.search(analysisId, query, ChunkSearch.CANDIDATES);
        return ChunkSearch.rank(matches, query, Math.min(Math.max(limit, 1), 20));
    }

    /**
     * @param caller who is asking (an IP address), for the hourly limit; null skips the limit
     * @throws TooManyQuestionsException when the caller is over the limit
     */
    public Answer ask(UUID analysisId, String question, String caller) {
        List<ChunkSearch.Result> results = search(analysisId, question, ChunkSearch.DEFAULT_LIMIT);
        List<Answer.Source> sources = results.stream().map(r -> Answer.Source.of(r.chunk(), r.score())).toList();
        if (results.isEmpty()) {
            return new Answer(question, null, null,
                    "Nothing in this repository matched that question. Try naming a file, class or function.",
                    List.of(), List.of());
        }

        LlmClient client = llm.getIfAvailable();
        if (client == null) {
            return new Answer(question, null, null,
                    "No language model is configured, so these are the places in the code that match.",
                    List.of(), sources);
        }
        checkLimit(caller);

        List<CodeChunk> given = results.stream().map(ChunkSearch.Result::chunk).toList();
        try {
            String reply = client.complete(SYSTEM_PROMPT, prompt(question, given));
            List<Answer.Citation> citations = Citations.verify(reply, given);
            return new Answer(question, reply.strip(), client.model(), null, citations, sources);
        } catch (OpenAiCompatibleClient.LlmUnavailableException e) {
            log.warn("Question could not be answered by the model: {}", e.getMessage());
            return new Answer(question, null, null,
                    "The language model could not be reached, so these are the matching places in the code.",
                    List.of(), sources);
        }
    }

    /** Numbered excerpts with their exact locations, trimmed to the configured context budget. */
    String prompt(String question, List<CodeChunk> given) {
        StringBuilder sb = new StringBuilder("Question: ").append(question).append("\n\nExcerpts:\n");
        int budget = config.maxContextChars();
        for (int i = 0; i < given.size(); i++) {
            CodeChunk c = given.get(i);
            String text = c.text();
            if (text.length() > budget) text = text.substring(0, Math.max(0, budget)) + "\n... (truncated)\n";
            budget -= text.length();
            sb.append('[').append(i + 1).append("] ").append(c.location());
            if (c.symbol() != null) sb.append("  ").append(c.kind()).append(' ').append(c.symbol());
            sb.append("\n```\n").append(text).append("```\n\n");
            if (budget <= 0) break;
        }
        return sb.toString();
    }

    private synchronized void checkLimit(String caller) {
        if (caller == null || config.maxQuestionsPerHour() <= 0) return;
        int hour = (int) (clock.instant().getEpochSecond() / 3600);
        int[] state = asksPerHour.computeIfAbsent(caller, k -> new int[]{hour, 0});
        if (state[0] != hour) {
            state[0] = hour;
            state[1] = 0;
        }
        if (state[1] >= config.maxQuestionsPerHour()) throw new TooManyQuestionsException();
        state[1]++;
        if (asksPerHour.size() > 10_000) asksPerHour.clear(); // crude, but this map must not grow forever
    }

    /** Clears counters so a long-running server does not keep yesterday's callers. */
    public synchronized void forget(Duration olderThan) {
        int cutoff = (int) (clock.instant().minus(olderThan).getEpochSecond() / 3600);
        new ArrayList<>(asksPerHour.entrySet()).forEach(e -> {
            if (e.getValue()[0] < cutoff) asksPerHour.remove(e.getKey());
        });
    }

    public static class TooManyQuestionsException extends RuntimeException {
        public TooManyQuestionsException() {
            super("Too many questions from here in the last hour. Try again later.");
        }
    }

    /** Only used to make the hourly window testable. */
    Instant now() {
        return clock.instant();
    }
}
