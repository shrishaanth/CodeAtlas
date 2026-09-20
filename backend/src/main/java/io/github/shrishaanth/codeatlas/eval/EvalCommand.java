package io.github.shrishaanth.codeatlas.eval;

import io.github.shrishaanth.codeatlas.fetch.FetchedRepo;
import io.github.shrishaanth.codeatlas.fetch.RepoFetcher;
import io.github.shrishaanth.codeatlas.fetch.RepoSource;
import io.github.shrishaanth.codeatlas.gitmine.GitHistory;
import io.github.shrishaanth.codeatlas.gitmine.HistoryMiner;
import io.github.shrishaanth.codeatlas.pipeline.AnalysisPipeline;
import io.github.shrishaanth.codeatlas.pipeline.ProgressListener;
import io.github.shrishaanth.codeatlas.report.Report;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Runs CodeAtlas over the repositories in eval/repos.txt and compares its reading order and ownership
 * with independent sources (see {@link GroundTruth}). Writes eval/results.json and prints a summary.
 * <pre>
 * java -cp &lt;classpath&gt; io.github.shrishaanth.codeatlas.eval.EvalCommand eval/repos.txt eval/results.json [clone-dir]
 * </pre>
 * Clones are cached in the clone directory so a rerun does not download everything again.
 */
public final class EvalCommand {

    /** Fewest documentation-mentioned files a repository needs to take part in that comparison. */
    static final int MIN_DOCS_TRUTH = 3;

    private EvalCommand() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: EvalCommand <repos.txt> <results.json> [clone-dir]");
            System.exit(2);
        }
        Path list = Path.of(args[0]);
        Path out = Path.of(args[1]);
        Path cloneDir = Path.of(args.length > 2 ? args[2] : System.getProperty("java.io.tmpdir") + "/codeatlas-eval");
        Files.createDirectories(cloneDir);

        List<String> repos = Files.readAllLines(list).stream()
                .map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
        System.err.printf("Evaluating %d repositories, clones cached in %s%n", repos.size(), cloneDir);

        List<EvalResult.RepoResult> results = new ArrayList<>();
        List<Features> features = new ArrayList<>();
        for (int i = 0; i < repos.size(); i++) {
            String url = repos.get(i);
            System.err.printf("[%2d/%d] %s%n", i + 1, repos.size(), url);
            try {
                results.add(evaluate(url, cloneDir, features));
            } catch (Exception e) {
                System.err.println("        failed: " + e);
                results.add(new EvalResult.RepoResult(shortName(url), String.valueOf(e.getMessage()), null, null,
                        null, null, null, null, null, null, null, null, null, null));
            }
        }
        EvalResult result = new EvalResult(Clock.systemUTC().instant(), "eval", results);
        tools.jackson.databind.json.JsonMapper mapper = tools.jackson.databind.json.JsonMapper.builder()
                .enable(tools.jackson.databind.SerializationFeature.INDENT_OUTPUT).build();
        Files.createDirectories(out.toAbsolutePath().getParent());
        mapper.writeValue(out.toFile(), result);
        // Per-file signals for Tuner, so formula variants can be compared without analyzing again.
        Path featuresFile = out.resolveSibling("features.json");
        mapper.writeValue(featuresFile.toFile(), features);
        System.err.println("wrote " + out + " and " + featuresFile);
        System.out.println(Summary.markdown(result));
    }

    private static EvalResult.RepoResult evaluate(String url, Path cloneDir, List<Features> features)
            throws Exception {
        RepoSource.GitHub source = (RepoSource.GitHub) RepoSource.parse(url, false);
        Path bare = cloneDir.resolve(source.owner() + "__" + source.repo() + ".git");
        if (!Files.isDirectory(bare)) {
            try (Git git = Git.cloneRepository().setURI(source.cloneUrl()).setDirectory(bare.toFile())
                    .setBare(true).setTimeout(600).call()) {
                git.getRepository().close();
            }
        }

        long t0 = System.nanoTime();
        Report report;
        GitHistory history;
        Map<String, String> cache = new LinkedHashMap<>();
        try (FetchedRepo repo = new RepoFetcher(cloneDir, 600).fetch(new RepoSource.Local(bare))) {
            report = new AnalysisPipeline(AnalysisPipeline.Options.defaults(), "eval", Clock.systemUTC())
                    .run(repo, ProgressListener.NONE);
            // A second history walk: the pipeline keeps its own, but the report does not carry commits.
            history = new HistoryMiner(AnalysisPipeline.Options.defaults().maxCommits())
                    .mine(repo.repository(), repo.head(),
                            report.files().stream().map(Report.FileEntry::path).toList(), null);
            long millis = (System.nanoTime() - t0) / 1_000_000;

            Set<String> rankable = report.readingOrder().stream().map(Report.ReadingItem::path)
                    .collect(Collectors.toCollection(TreeSet::new));
            GroundTruth.Truth truth = GroundTruth.collect(report, history,
                    path -> readCached(repo, cache, path), rankable);
            features.add(Features.from(source.owner() + "/" + source.repo(), report, truth));
            return score(source, report, truth, millis);
        }
    }

    private static String readCached(FetchedRepo repo, Map<String, String> cache, String path) {
        return cache.computeIfAbsent(path, p -> {
            try (TreeWalk tw = TreeWalk.forPath(repo.repository(), p, repo.head().getTree())) {
                if (tw == null) return "";
                ObjectId id = tw.getObjectId(0);
                return new String(repo.repository().open(id).getBytes(), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                return "";
            }
        });
    }

    static EvalResult.RepoResult score(RepoSource.GitHub source, Report report, GroundTruth.Truth truth,
                                       long millis) {
        Map<String, List<String>> rankings = Rankings.all(report);
        Map<String, Double> docsPercentile = new LinkedHashMap<>();
        Map<String, Integer> docsHits = new LinkedHashMap<>();
        Map<String, Double> newcomerPercentile = new LinkedHashMap<>();
        // One or two mentions are noise; a repository needs a handful before the comparison means anything.
        boolean enoughDocs = truth.docsMentioned().size() >= MIN_DOCS_TRUTH;
        rankings.forEach((name, ranking) -> {
            Double docs = enoughDocs ? Rankings.meanPercentile(ranking, truth.docsMentioned()) : null;
            if (docs != null) {
                docsPercentile.put(name, docs);
                docsHits.put(name, Rankings.hitsInTop(ranking, truth.docsMentioned(), Rankings.TOP_K));
            }
            Double newcomers = Rankings.meanPercentile(ranking, truth.newcomerFiles());
            if (newcomers != null) newcomerPercentile.put(name, newcomers);
        });

        return new EvalResult.RepoResult(source.owner() + "/" + source.repo(), null,
                report.overview().commits(), report.overview().contributors(),
                (int) report.files().stream().filter(f -> "python".equals(f.language())).count(),
                report.readingOrder().size(), millis,
                truth.docsMentioned().size(), docsPercentile.isEmpty() ? null : docsPercentile,
                docsHits.isEmpty() ? null : docsHits,
                truth.newcomerFiles().size(), newcomerPercentile.isEmpty() ? null : newcomerPercentile,
                ownership(report, truth), report.findings() == null ? null : report.findings().size());
    }

    /** Compares the top owners from blame with the handles CODEOWNERS names for the same directory. */
    static EvalResult.Ownership ownership(Report report, GroundTruth.Truth truth) {
        if (truth.codeowners().isEmpty() || report.people() == null
                || report.people().directoryOwnership() == null) {
            return null;
        }
        Map<String, Report.Author> byId = new LinkedHashMap<>();
        report.people().authors().forEach(a -> byId.put(a.id(), a));
        Map<String, Set<String>> expected = GroundTruth.ownersForDirectories(truth.codeowners(),
                report.people().directoryOwnership());

        int covered = 0, linked = 0, topMatch = 0, topThree = 0;
        for (Report.Ownership dir : report.people().directoryOwnership()) {
            Set<String> handles = expected.get(dir.path());
            if (handles == null || dir.owners().isEmpty()) continue;
            covered++;
            // Only count directories where at least one handle belongs to someone who committed here:
            // teams and people who never committed cannot be matched by any method.
            boolean anyLinked = handles.stream().anyMatch(h -> report.people().authors().stream()
                    .anyMatch(person -> GroundTruth.matchesHandle(person, h)));
            if (!anyLinked) continue;
            linked++;
            List<String> top3 = dir.owners().stream().limit(3).map(o -> o.authorId()).toList();
            if (handles.stream().anyMatch(h -> GroundTruth.matchesHandle(byId.get(top3.get(0)), h))) topMatch++;
            if (top3.stream().anyMatch(id -> handles.stream().anyMatch(h -> GroundTruth.matchesHandle(byId.get(id), h)))) {
                topThree++;
            }
        }
        return new EvalResult.Ownership(covered, linked, topMatch, topThree);
    }

    private static String shortName(String url) {
        return url.replace("https://github.com/", "");
    }
}
