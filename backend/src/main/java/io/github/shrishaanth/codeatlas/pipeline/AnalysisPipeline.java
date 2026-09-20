package io.github.shrishaanth.codeatlas.pipeline;

import io.github.shrishaanth.codeatlas.analyze.ChangeCoupling;
import io.github.shrishaanth.codeatlas.analyze.Findings;
import io.github.shrishaanth.codeatlas.analyze.Hotspots;
import io.github.shrishaanth.codeatlas.analyze.ImportGraph;
import io.github.shrishaanth.codeatlas.analyze.Layers;
import io.github.shrishaanth.codeatlas.analyze.Ownership;
import io.github.shrishaanth.codeatlas.analyze.ReadingOrder;
import io.github.shrishaanth.codeatlas.fetch.FetchedRepo;
import io.github.shrishaanth.codeatlas.fetch.FileInventory;
import io.github.shrishaanth.codeatlas.fetch.SourceFile;
import io.github.shrishaanth.codeatlas.gitmine.BlameMiner;
import io.github.shrishaanth.codeatlas.gitmine.GitHistory;
import io.github.shrishaanth.codeatlas.gitmine.HistoryMiner;
import io.github.shrishaanth.codeatlas.gitmine.IdentityResolver;
import io.github.shrishaanth.codeatlas.gitmine.Mailmap;
import io.github.shrishaanth.codeatlas.gitmine.People;
import io.github.shrishaanth.codeatlas.index.Chunker;
import io.github.shrishaanth.codeatlas.index.CodeChunk;
import io.github.shrishaanth.codeatlas.parse.ImportResolver;
import io.github.shrishaanth.codeatlas.parse.ParsedPythonFile;
import io.github.shrishaanth.codeatlas.parse.PythonParser;
import io.github.shrishaanth.codeatlas.parse.ResolvedImport;
import io.github.shrishaanth.codeatlas.report.Report;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Runs every analysis stage on an opened repository and assembles the report.
 * Pure Java with no Spring dependencies, so the web app, tests and later a CLI can all use it.
 */
public class AnalysisPipeline {

    /**
     * @param maxCommits    history walk cap
     * @param maxBlameFiles blame cap (the most-committed files are blamed first)
     * @param threads       parallel blame workers
     */
    public record Options(int maxCommits, int maxBlameFiles, int threads) {
        public static Options defaults() {
            return new Options(20_000, 3_000, Runtime.getRuntime().availableProcessors());
        }
    }

    /** Chunks are only kept for the newest analyses, so this cap only protects memory on huge repos. */
    public static final int MAX_CHUNKS = 50_000;

    /** @param chunks retrievable pieces of the code, for question answering */
    public record Result(Report report, List<CodeChunk> chunks) {
    }

    private final Options options;
    private final String toolVersion;
    private final Clock clock;

    public AnalysisPipeline(Options options, String toolVersion, Clock clock) {
        this.options = options;
        this.toolVersion = toolVersion;
        this.clock = clock;
    }

    public Result run(FetchedRepo repo, ProgressListener progress) throws IOException {
        try {
            return runStages(repo, progress);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Analysis interrupted", e);
        }
    }

    private Result runStages(FetchedRepo repo, ProgressListener progress) throws IOException, InterruptedException {
        Repository git = repo.repository();

        // 1. Inventory ---------------------------------------------------------------
        progress.onProgress("inventory", 2, "Listing files");
        List<SourceFile> files = new FileInventory().scan(git, repo.head());
        files.sort(Comparator.comparing(SourceFile::path));

        // 2. Parse -------------------------------------------------------------------
        List<SourceFile> python = files.stream().filter(SourceFile::isPython).toList();
        Map<String, ParsedPythonFile> parsed = new HashMap<>();
        Map<String, String> pythonText = new HashMap<>(); // kept for findings, so files are read once
        try (PythonParser parser = new PythonParser()) {
            int done = 0;
            for (SourceFile f : python) {
                if (f.parseable()) {
                    String text = FileInventory.readText(git, f.blobId());
                    pythonText.put(f.path(), text);
                    parsed.put(f.path(), parser.parse(f.path(), text));
                }
                done++;
                if (done % 100 == 0 || done == python.size()) {
                    progress.onProgress("parse", 5 + 35 * done / Math.max(1, python.size()),
                            "Parsed " + done + " of " + python.size() + " Python files");
                }
            }
        }

        // 3. Resolve imports into a graph --------------------------------------------
        ImportResolver resolver = new ImportResolver(python.stream().map(SourceFile::path).toList());
        ImportGraph graph = new ImportGraph();
        Map<String, List<ResolvedImport>> resolved = new HashMap<>();
        Map<String, Integer> importLines = new HashMap<>(); // "from\u0000to" -> first import line
        for (SourceFile f : python) {
            graph.addNode(f.path());
            ParsedPythonFile pf = parsed.get(f.path());
            if (pf == null) continue;
            List<ResolvedImport> list = new ArrayList<>();
            pf.imports().forEach(imp -> list.addAll(resolver.resolve(f.path(), imp)));
            resolved.put(f.path(), list);
            for (ResolvedImport r : list) {
                if (!r.resolved()) continue;
                graph.addEdge(f.path(), r.targetPath());
                importLines.putIfAbsent(f.path() + "\u0000" + r.targetPath(), r.imp().line());
            }
        }

        // 4. Git history -------------------------------------------------------------
        int totalCommits = countCommits(git, repo.head());
        progress.onProgress("history", 30, "Reading " + totalCommits + " commits");
        GitHistory history = new HistoryMiner(options.maxCommits()).mine(git, repo.head(),
                files.stream().map(SourceFile::path).toList(),
                n -> progress.onProgress("history", 30 + 25 * n / Math.max(1, totalCommits),
                        "Read " + n + " of " + totalCommits + " commits"));

        // 5. Blame and people ----------------------------------------------------------
        List<SourceFile> blameCandidates = files.stream()
                .filter(f -> f.isCode() && f.parseable() && !f.generated())
                .sorted(Comparator.comparingInt((SourceFile f) -> commitsOf(history, f.path())).reversed()
                        .thenComparing(SourceFile::path))
                .toList();
        List<String> toBlame = blameCandidates.stream().limit(options.maxBlameFiles()).map(SourceFile::path).toList();
        List<String> overCap = blameCandidates.stream().skip(options.maxBlameFiles()).map(SourceFile::path).toList();
        progress.onProgress("blame", 56, "Blaming " + toBlame.size() + " files");
        BlameMiner.Result blame = new BlameMiner(options.threads()).mine(git, repo.head(), toBlame,
                n -> progress.onProgress("blame", 56 + 36 * n / Math.max(1, toBlame.size()),
                        "Blamed " + n + " of " + toBlame.size() + " files"));

        Map<String, GitHistory.Author> identities = new HashMap<>(blame.identities());
        identities.putAll(history.authors()); // history has every name and real commit counts; prefer it
        People people = IdentityResolver.resolve(identities.values(), Mailmap.fromCommit(git, repo.head()));
        Ownership.Result ownership = history.lastCommitAt() == null ? new Ownership.Result(List.of(), List.of())
                : Ownership.compute(blame.lines(), people, history.lastCommitAt());

        // 6. Analyze -----------------------------------------------------------------
        progress.onProgress("analyze", 94, "Ranking files");
        List<String> candidates = python.stream()
                .filter(f -> !f.test() && f.nonBlankLines() >= ReadingOrder.MIN_NON_BLANK_LINES)
                .map(SourceFile::path).toList();
        List<String> nonTest = python.stream().filter(f -> !f.test()).map(SourceFile::path).toList();
        Map<String, Integer> commitsPerFile = new HashMap<>();
        history.files().forEach((p, h) -> commitsPerFile.put(p, h.commits()));
        List<Report.ReadingItem> readingOrder = ReadingOrder.rank(graph.restrictTo(nonTest), candidates, commitsPerFile);

        Report.Coupling coupling = ChangeCoupling.compute(history.commits(), graph);
        List<Report.Hotspot> hotspots = Hotspots.rank(files, commitsPerFile);
        // Root-level files (config, scripts) share no real module, so each is its own unit for layers and
        // cycles; grouping them as "." would invent cycles through unrelated files.
        Layers.Result layers = Layers.compute(graph.restrictTo(nonTest),
                p -> componentOf(p).equals(".") ? p : componentOf(p));

        progress.onProgress("findings", 96, "Looking for findings");
        Map<String, SourceFile> byPath = new HashMap<>();
        files.forEach(f -> byPath.put(f.path(), f));
        Findings.Result findings = Findings.compute(new Findings.Input(files, parsed, graph, layers, importLines,
                path -> pythonText.computeIfAbsent(path, p -> readQuietly(git, byPath.get(p)))));

        progress.onProgress("report", 98, "Assembling report");
        Report report = new Report(
                Report.SCHEMA_VERSION,
                new Report.RepoInfo(repo.source().display(), repo.source().name(), repo.head().getName(),
                        repo.branch(), clock.instant(), toolVersion),
                limits(files, parsed, history, blame, overCap, findings.omitted()),
                overview(files, python, history, people),
                fileEntries(files, parsed, resolved, history, people),
                components(files, layers.layers()),
                edges(graph, coupling),
                readingOrder,
                people(people, ownership),
                coupling,
                hotspots,
                findings.findings());
        progress.onProgress("chunks", 99, "Indexing code for search");
        List<CodeChunk> chunks = chunks(files, parsed, readingOrder, path -> pythonText.computeIfAbsent(path,
                p -> readQuietly(git, byPath.get(p))));

        progress.onProgress("done", 100, "Analysis complete");
        return new Result(report, chunks);
    }

    /** Splits every readable text file into citable chunks (docs/metrics.md, "Question answering"). */
    private static List<CodeChunk> chunks(List<SourceFile> files, Map<String, ParsedPythonFile> parsed,
                                          List<Report.ReadingItem> readingOrder,
                                          java.util.function.Function<String, String> text) {
        Map<String, Double> fileScore = new HashMap<>();
        readingOrder.forEach(item -> fileScore.put(item.path(), item.score()));
        List<CodeChunk> out = new ArrayList<>();
        for (SourceFile f : files) {
            if (f.binary() || f.skipReason() != null || f.language() == null) continue;
            String content = text.apply(f.path());
            if (content.isEmpty()) continue;
            out.addAll(Chunker.chunk(f, content, parsed.get(f.path()), fileScore.getOrDefault(f.path(), 0.0)));
            if (out.size() >= MAX_CHUNKS) break;
        }
        return out;
    }

    private int countCommits(Repository git, RevCommit head) throws IOException {
        try (RevWalk walk = new RevWalk(git)) {
            walk.setRetainBody(false);
            walk.markStart(walk.parseCommit(head));
            int n = 0;
            for (RevCommit ignored : walk) {
                if (++n == options.maxCommits()) break;
            }
            return n;
        }
    }

    private static int commitsOf(GitHistory history, String path) {
        GitHistory.FileHistory h = history.files().get(path);
        return h == null ? 0 : h.commits();
    }

    private Report.Limits limits(List<SourceFile> files, Map<String, ParsedPythonFile> parsed, GitHistory history,
                                 BlameMiner.Result blame, List<String> overBlameCap,
                                 Map<String, Integer> findingsOmitted) {
        List<Report.SkippedFile> skipped = new ArrayList<>();
        files.stream().filter(f -> f.skipReason() != null)
                .forEach(f -> skipped.add(new Report.SkippedFile(f.path(), f.skipReason())));
        overBlameCap.forEach(p -> skipped.add(new Report.SkippedFile(p, "blame cap")));
        blame.failed().forEach((p, msg) -> skipped.add(new Report.SkippedFile(p, "blame failed: " + msg)));
        skipped.sort(Comparator.comparing(Report.SkippedFile::path));
        List<Report.ParseError> errors = parsed.values().stream()
                .filter(p -> p.firstErrorLine() > 0)
                .map(p -> new Report.ParseError(p.path(), p.firstErrorLine()))
                .sorted(Comparator.comparing(Report.ParseError::path))
                .toList();
        return new Report.Limits(options.maxCommits(), history.truncated(), blame.ignoredRevisions(), skipped, errors,
                findingsOmitted);
    }

    private static Report.Overview overview(List<SourceFile> files, List<SourceFile> python, GitHistory history,
                                            People people) {
        Map<String, int[]> byLanguage = new TreeMap<>();
        int loc = 0;
        for (SourceFile f : files) {
            if (f.language() == null || f.binary()) continue;
            int[] acc = byLanguage.computeIfAbsent(f.language(), k -> new int[2]);
            acc[0]++;
            acc[1] += f.lines();
            if (f.isCode()) loc += f.lines();
        }
        List<Report.LanguageStat> languages = byLanguage.entrySet().stream()
                .map(e -> new Report.LanguageStat(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .sorted(Comparator.comparingInt(Report.LanguageStat::lines).reversed()
                        .thenComparing(Report.LanguageStat::language))
                .toList();
        long tests = python.stream().filter(SourceFile::test).count();
        long sources = python.size() - tests;
        Double testRatio = sources == 0 ? null : Math.round(1000.0 * tests / sources) / 1000.0;
        int humans = (int) people.people().stream().filter(p -> !p.bot()).count();
        return new Report.Overview(history.commitsWalked(), humans, history.firstCommitAt(),
                history.lastCommitAt(), files.size(), loc, languages, testRatio);
    }

    private static List<Report.FileEntry> fileEntries(List<SourceFile> files, Map<String, ParsedPythonFile> parsed,
                                                      Map<String, List<ResolvedImport>> resolved,
                                                      GitHistory history, People people) {
        java.util.Set<String> botIds = new java.util.HashSet<>();
        people.people().stream().filter(People.Person::bot).forEach(p -> botIds.add(p.id()));
        List<Report.FileEntry> out = new ArrayList<>(files.size());
        for (SourceFile f : files) {
            ParsedPythonFile pf = parsed.get(f.path());
            List<Report.Symbol> symbols = pf == null ? null : pf.symbols().stream()
                    .map(s -> new Report.Symbol(s.kind(), s.name(), s.startLine(), s.endLine(), s.parent()))
                    .toList();
            List<Report.Import> imports = pf == null ? null : resolved.getOrDefault(f.path(), List.of()).stream()
                    .map(r -> new Report.Import(r.imp().text(), r.imp().line(), r.module(), r.targetPath(), r.external()))
                    .toList();
            GitHistory.FileHistory h = history.files().get(f.path());
            // Distinct people after identity merging, bots excluded, consistent with overview.contributors.
            Report.FileGit git = h == null ? null : new Report.FileGit(h.commits(),
                    (int) h.authorEmails().stream().map(e -> people.idForEmail(e).orElse(e))
                            .filter(id -> !botIds.contains(id)).distinct().count(),
                    h.firstChangedAt(), h.lastChangedAt());
            out.add(new Report.FileEntry(f.path(), f.language(), f.lines(), componentOf(f.path()), f.test(), f.generated(),
                    symbols, imports, git));
        }
        return out;
    }

    /** v1 components are directories; "." is the repository root. */
    static String componentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "." : path.substring(0, slash);
    }

    private static String readQuietly(Repository git, SourceFile f) {
        if (f == null || f.binary() || f.skipReason() != null) return "";
        try {
            return FileInventory.readText(git, f.blobId());
        } catch (IOException e) {
            return "";
        }
    }

    private static List<Report.Component> components(List<SourceFile> files, Map<String, Integer> layers) {
        Map<String, int[]> acc = new TreeMap<>();
        for (SourceFile f : files) {
            int[] a = acc.computeIfAbsent(componentOf(f.path()), k -> new int[2]);
            a[0]++;
            a[1] += f.lines();
        }
        List<Report.Component> out = new ArrayList<>();
        acc.forEach((id, a) -> out.add(new Report.Component(id, id, a[0], a[1], layers.get(id))));
        return out;
    }

    private static List<Report.Edge> edges(ImportGraph graph, Report.Coupling coupling) {
        List<Report.Edge> out = new ArrayList<>();
        for (String from : graph.nodes()) {
            graph.importsOf(from).stream().sorted()
                    .forEach(to -> out.add(new Report.Edge(from, to, "import", 1)));
        }
        coupling.files().forEach(p -> out.add(new Report.Edge(p.a(), p.b(), "cochange", p.together())));
        return out;
    }

    private static Report.People people(People people, Ownership.Result ownership) {
        List<Report.Author> authors = people.people().stream()
                .map(p -> new Report.Author(p.id(), p.name(), p.emails(), p.commits(), p.bot(), p.lastCommitAt()))
                .toList();
        return new Report.People(authors, ownership.files(), ownership.directories());
    }
}
