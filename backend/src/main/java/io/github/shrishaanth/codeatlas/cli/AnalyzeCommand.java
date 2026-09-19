package io.github.shrishaanth.codeatlas.cli;

import io.github.shrishaanth.codeatlas.fetch.FetchedRepo;
import io.github.shrishaanth.codeatlas.fetch.RepoFetcher;
import io.github.shrishaanth.codeatlas.fetch.RepoSource;
import io.github.shrishaanth.codeatlas.pipeline.AnalysisPipeline;
import io.github.shrishaanth.codeatlas.report.Report;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

/**
 * Offline analysis without the web server or database: writes a report JSON file.
 * Used to produce the static demo reports and the evaluation runs.
 * <pre>
 * java -cp &lt;classpath&gt; io.github.shrishaanth.codeatlas.cli.AnalyzeCommand &lt;github-url|path&gt; &lt;out.json&gt;
 * </pre>
 */
public final class AnalyzeCommand {

    private AnalyzeCommand() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: AnalyzeCommand <github-url|local-path> <out.json>");
            System.exit(2);
        }
        RepoSource source = RepoSource.parse(args[0], true);
        Path out = Path.of(args[1]);
        Path workDir = Files.createTempDirectory("codeatlas-cli-");

        long t0 = System.nanoTime();
        Report report;
        try (FetchedRepo repo = new RepoFetcher(workDir, 300).fetch(source)) {
            long fetched = System.nanoTime();
            System.err.printf("fetched in %.1f s%n", (fetched - t0) / 1e9);
            report = new AnalysisPipeline(20_000, "cli", Clock.systemUTC()).run(repo,
                    (stage, pct, detail) -> System.err.printf("[%3d%%] %-9s %s%n", pct, stage, detail));
        } finally {
            Files.deleteIfExists(workDir);
        }
        JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        mapper.writeValue(out.toFile(), report);
        System.err.printf("wrote %s (%d KB) in %.1f s total%n", out, Files.size(out) / 1024, (System.nanoTime() - t0) / 1e9);
    }
}
