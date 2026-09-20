package io.github.shrishaanth.codeatlas.jobs;

import io.github.shrishaanth.codeatlas.config.CodeAtlasProperties;
import io.github.shrishaanth.codeatlas.fetch.FetchedRepo;
import io.github.shrishaanth.codeatlas.fetch.RepoFetcher;
import io.github.shrishaanth.codeatlas.fetch.RepoSource;
import io.github.shrishaanth.codeatlas.index.ChunkRepository;
import io.github.shrishaanth.codeatlas.pipeline.AnalysisPipeline;
import io.github.shrishaanth.codeatlas.pipeline.ProgressListener;
import io.github.shrishaanth.codeatlas.report.Report;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Queues analyses and runs them one at a time in the background.
 * One worker is deliberate: free hosting tiers have one small CPU and ~512 MB of memory.
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);
    private static final long PROGRESS_WRITE_INTERVAL_MS = 500;

    private final AnalysisRepository repository;
    private final ChunkRepository chunks;
    private final CodeAtlasProperties.Analysis config;
    private final String toolVersion;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final ExecutorService executor;

    /** Chunks are kept only for the newest analyses: they hold the repository's code. */
    private static final int KEEP_INDEXED_ANALYSES = 20;

    public AnalysisService(AnalysisRepository repository, ChunkRepository chunks, CodeAtlasProperties properties,
                           JsonMapper jsonMapper, Clock clock) {
        this.repository = repository;
        this.chunks = chunks;
        this.config = properties.analysis();
        this.toolVersion = properties.version();
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(config.maxQueued()),
                r -> {
                    Thread t = new Thread(r, "analysis-worker");
                    t.setDaemon(true);
                    return t;
                });
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void failAbandonedAnalyses() {
        int n = repository.failAbandoned(clock.instant());
        if (n > 0) log.info("Marked {} unfinished analyses from a previous run as failed", n);
    }

    /**
     * Queues an analysis, or returns the one already queued or running for the same repository.
     *
     * @throws IllegalArgumentException if the input is not an accepted repository source
     * @throws QueueFullException       if too many analyses are waiting
     */
    public AnalysisStatus submit(String input) {
        RepoSource source = RepoSource.parse(input, config.allowLocalPaths());
        String key = source instanceof RepoSource.Local local ? local.path().toAbsolutePath().normalize().toString()
                : source.display();
        Optional<AnalysisStatus> active = repository.findActive(key);
        if (active.isPresent()) return active.get();

        UUID id = UUID.randomUUID();
        repository.insertQueued(id, key, clock.instant());
        try {
            executor.execute(() -> run(id, source));
        } catch (RejectedExecutionException e) {
            repository.markFailed(id, "Server busy: too many analyses queued", clock.instant());
            throw new QueueFullException();
        }
        return repository.find(id).orElseThrow();
    }

    public Optional<AnalysisStatus> status(UUID id) {
        return repository.find(id);
    }

    public Optional<String> reportJson(UUID id) {
        return repository.findReportJson(id);
    }

    public List<AnalysisStatus> recent(int limit) {
        return repository.recentDone(Math.min(Math.max(limit, 1), 100));
    }

    private void run(UUID id, RepoSource source) {
        repository.markRunning(id, clock.instant());
        long t0 = System.nanoTime();
        try (FetchedRepo repo = new RepoFetcher(config.workDir(), config.cloneTimeoutSeconds()).fetch(source)) {
            AnalysisPipeline.Result result = new AnalysisPipeline(
                    new AnalysisPipeline.Options(config.maxCommits(), config.maxBlameFiles(), config.threads()),
                    toolVersion, clock)
                    .run(repo, throttled(id));
            Report report = result.report();
            repository.markDone(id, report.repo().name(), report.repo().commit(),
                    jsonMapper.writeValueAsString(report), clock.instant());
            chunks.save(id, result.chunks());
            int removed = chunks.deleteOlderThan(KEEP_INDEXED_ANALYSES);
            if (removed > 0) log.info("Removed {} chunks of older analyses", removed);
            log.info("Analysis {} of {} done in {} ms", id, source.display(), (System.nanoTime() - t0) / 1_000_000);
        } catch (Exception | OutOfMemoryError e) {
            log.warn("Analysis {} of {} failed", id, source.display(), e);
            repository.markFailed(id, userMessage(e), clock.instant());
        }
    }

    /** Writes progress to the database at most twice a second, plus every stage change. */
    private ProgressListener throttled(UUID id) {
        return new ProgressListener() {
            private long lastWrite;
            private String lastStage;

            @Override
            public void onProgress(String stage, int percent, String detail) {
                long now = System.currentTimeMillis();
                if (!stage.equals(lastStage) || now - lastWrite >= PROGRESS_WRITE_INTERVAL_MS) {
                    repository.updateProgress(id, stage, percent, detail);
                    lastWrite = now;
                    lastStage = stage;
                }
            }
        };
    }

    private static String userMessage(Throwable e) {
        if (e instanceof OutOfMemoryError) return "The repository is too large for this server";
        String msg = e.getMessage();
        return msg == null || msg.isBlank() ? "Analysis failed (" + e.getClass().getSimpleName() + ")" : msg;
    }

    public static class QueueFullException extends RuntimeException {
        public QueueFullException() {
            super("The server is busy with other analyses. Try again in a few minutes.");
        }
    }
}
