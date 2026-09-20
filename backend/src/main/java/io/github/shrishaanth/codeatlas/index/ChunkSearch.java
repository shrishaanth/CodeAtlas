package io.github.shrishaanth.codeatlas.index;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns full-text candidates into the chunks an answer may use. The weights are in docs/metrics.md,
 * "Question answering"; they are *initial* and not yet measured.
 */
public final class ChunkSearch {

    public static final int DEFAULT_LIMIT = 8;
    public static final int MAX_PER_FILE = 3;
    public static final int CANDIDATES = 60;

    static final double W_SYMBOL_MATCH = 0.5;
    /** "signature" should find get_signature, and "signing" should find sign. */
    static final double W_SYMBOL_WORD_MATCH = 0.35;
    static final double W_PATH_MATCH = 0.3;
    static final double W_FILE_SCORE = 0.2;
    static final double PENALTY_TEST_OR_GENERATED = 0.3;

    private static final Pattern WORDS = Pattern.compile("[^a-z0-9_]+");
    private static final Pattern SYMBOL_WORDS = Pattern.compile("[^a-z0-9]+");

    /** @param score the final score; {@code textScore} is the part Postgres contributed */
    public record Result(CodeChunk chunk, double score, double textScore) {
    }

    private ChunkSearch() {
    }

    public static List<Result> rank(List<ChunkRepository.Match> matches, String question, int limit) {
        List<String> terms = terms(question);
        double maxText = matches.stream().mapToDouble(ChunkRepository.Match::textScore).max().orElse(0);

        List<Result> scored = new ArrayList<>();
        for (ChunkRepository.Match m : matches) {
            CodeChunk c = m.chunk();
            double score = maxText > 0 ? m.textScore() / maxText : 0;
            if (symbolMatches(c.symbol(), terms)) score += W_SYMBOL_MATCH;
            else if (symbolWordMatches(c.symbol(), terms)) score += W_SYMBOL_WORD_MATCH;
            if (pathMatches(c.path(), terms)) score += W_PATH_MATCH;
            score += W_FILE_SCORE * c.fileScore();
            if (c.test() || c.generated()) score -= PENALTY_TEST_OR_GENERATED;
            scored.add(new Result(c, round(score), round(m.textScore())));
        }
        scored.sort(Comparator.comparingDouble(Result::score).reversed()
                .thenComparing(r -> r.chunk().location()));

        // Spread the answer over several files instead of one long one. A chunk whose symbol the
        // question names outright is exempt: "where is save_session defined" must not lose the
        // definition because three other methods of the same file matched first.
        Map<String, Integer> perFile = new HashMap<>();
        List<Result> out = new ArrayList<>();
        for (Result r : scored) {
            boolean definitional = symbolMatches(r.chunk().symbol(), terms);
            int used = perFile.getOrDefault(r.chunk().path(), 0);
            if (!definitional && used >= MAX_PER_FILE) continue;
            perFile.put(r.chunk().path(), used + 1);
            out.add(r);
            if (out.size() == limit) break;
        }
        return out;
    }

    /** Words of the question, lower-cased, without punctuation and very short words. */
    public static List<String> terms(String question) {
        List<String> out = new ArrayList<>();
        for (String w : WORDS.split(question.toLowerCase(Locale.ROOT))) {
            if (w.length() >= 3) out.add(w);
        }
        return out;
    }

    static boolean symbolMatches(String symbol, List<String> terms) {
        if (symbol == null) return false;
        String lower = symbol.toLowerCase(Locale.ROOT);
        String last = lower.contains(".") ? lower.substring(lower.lastIndexOf('.') + 1) : lower;
        return terms.contains(lower) || terms.contains(last);
    }

    /** A word of the symbol matches a word of the question: get_signature for "signature". */
    static boolean symbolWordMatches(String symbol, List<String> terms) {
        if (symbol == null) return false;
        for (String word : splitWords(symbol)) {
            if (word.length() < 3) continue;
            for (String term : terms) {
                if (stem(term).equals(stem(word))) return true;
            }
        }
        return false;
    }

    /**
     * "TimestampSigner.get_signature" -> [timestamp, signer, get, signature].
     * Unlike question terms, a symbol is split on underscores too: they join words in code.
     */
    static List<String> splitWords(String symbol) {
        String spaced = symbol.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        return java.util.Arrays.stream(SYMBOL_WORDS.split(spaced.toLowerCase(Locale.ROOT)))
                .filter(w -> !w.isEmpty()).toList();
    }

    /** Crude suffix trimming so "signing", "signed" and "signs" all reach "sign". */
    static String stem(String word) {
        if (word.endsWith("ss")) return word; // "class", "less", "process"
        for (String suffix : new String[]{"ing", "ed", "es", "s"}) {
            if (word.length() > suffix.length() + 2 && word.endsWith(suffix)) {
                return word.substring(0, word.length() - suffix.length());
            }
        }
        return word;
    }

    static boolean pathMatches(String path, List<String> terms) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        String stem = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        return terms.contains(name) || terms.contains(stem);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
