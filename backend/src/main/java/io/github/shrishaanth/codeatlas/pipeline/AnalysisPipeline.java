package io.github.shrishaanth.codeatlas.pipeline;

import io.github.shrishaanth.codeatlas.analyze.ImportGraph;
import io.github.shrishaanth.codeatlas.analyze.ReadingOrder;
import io.github.shrishaanth.codeatlas.fetch.FetchedRepo;
import io.github.shrishaanth.codeatlas.fetch.FileInventory;
import io.github.shrishaanth.codeatlas.fetch.SourceFile;
import io.github.shrishaanth.codeatlas.gitmine.GitHistory;
import io.github.shrishaanth.codeatlas.gitmine.HistoryMiner;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Runs every analysis stage on an opened repository and assembles the report.
 * Pure Java with no Spring dependencies, so the web app, tests and later a CLI can all use it.
 */
public class AnalysisPipeline {

    /** Languages counted as documentation or data, not code, for lines-of-code totals. */
    private static final Set<String> NON_CODE = Set.of(
            "markdown", "restructuredtext", "json", "yaml", "toml", "xml", "jupyter");

    private final int maxCommits;
    private final String toolVersion;
    private final Clock clock;

    public AnalysisPipeline(int maxCommits, String toolVersion, Clock clock) {
        this.maxCommits = maxCommits;
        this.toolVersion = toolVersion;
        this.clock = clock;
    }

    public Report run(FetchedRepo repo, ProgressListener progress) throws IOException {
        Repository git = repo.repository();

        // 1. Inventory ---------------------------------------------------------------
        progress.onProgress("inventory", 2, "Listing files");
        List<SourceFile> files = new FileInventory().scan(git, repo.head());
        files.sort(Comparator.comparing(SourceFile::path));

        // 2. Parse -------------------------------------------------------------------
        List<SourceFile> python = files.stream().filter(SourceFile::isPython).toList();
        Map<String, ParsedPythonFile> parsed = new HashMap<>();
        try (PythonParser parser = new PythonParser()) {
            int done = 0;
            for (SourceFile f : python) {
                if (f.parseable()) {
                    parsed.put(f.path(), parser.parse(f.path(), FileInventory.readText(git, f.blobId())));
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
        for (SourceFile f : python) {
            graph.addNode(f.path());
            ParsedPythonFile pf = parsed.get(f.path());
            if (pf == null) continue;
            List<ResolvedImport> list = new ArrayList<>();
            pf.imports().forEach(imp -> list.addAll(resolver.resolve(f.path(), imp)));
            resolved.put(f.path(), list);
            for (ResolvedImport r : list) {
                if (r.resolved()) graph.addEdge(f.path(), r.targetPath());
            }
        }

        // 4. Git history -------------------------------------------------------------
        int totalCommits = countCommits(git, repo.head());
        progress.onProgress("history", 42, "Reading " + totalCommits + " commits");
        GitHistory history = new HistoryMiner(maxCommits).mine(git, repo.head(),
                files.stream().map(SourceFile::path).toList(),
                n -> progress.onProgress("history", 42 + 50 * n / Math.max(1, totalCommits),
                        "Read " + n + " of " + totalCommits + " commits"));

        // 5. Analyze -----------------------------------------------------------------
        progress.onProgress("analyze", 94, "Ranking files");
        List<String> candidates = python.stream()
                .filter(f -> !f.test() && f.nonBlankLines() >= ReadingOrder.MIN_NON_BLANK_LINES)
                .map(SourceFile::path).toList();
        List<String> nonTest = python.stream().filter(f -> !f.test()).map(SourceFile::path).toList();
        Map<String, Integer> commitsPerFile = new HashMap<>();
        history.files().forEach((p, h) -> commitsPerFile.put(p, h.commits()));
        List<Report.ReadingItem> readingOrder = ReadingOrder.rank(graph.restrictTo(nonTest), candidates, commitsPerFile);

        progress.onProgress("report", 98, "Assembling report");
        Report report = new Report(
                Report.SCHEMA_VERSION,
                new Report.RepoInfo(repo.source().display(), repo.source().name(), repo.head().getName(),
                        repo.branch(), clock.instant(), toolVersion),
                limits(files, parsed, history),
                overview(files, python, history),
                fileEntries(files, parsed, resolved, history),
                components(files),
                edges(graph),
                readingOrder,
                people(history));
        progress.onProgress("done", 100, "Analysis complete");
        return report;
    }

    private int countCommits(Repository git, RevCommit head) throws IOException {
        try (RevWalk walk = new RevWalk(git)) {
            walk.setRetainBody(false);
            walk.markStart(walk.parseCommit(head));
            int n = 0;
            for (RevCommit ignored : walk) {
                if (++n == maxCommits) break;
            }
            return n;
        }
    }

    private Report.Limits limits(List<SourceFile> files, Map<String, ParsedPythonFile> parsed, GitHistory history) {
        List<Report.SkippedFile> skipped = files.stream()
                .filter(f -> f.skipReason() != null)
                .map(f -> new Report.SkippedFile(f.path(), f.skipReason()))
                .toList();
        List<Report.ParseError> errors = parsed.values().stream()
                .filter(p -> p.firstErrorLine() > 0)
                .map(p -> new Report.ParseError(p.path(), p.firstErrorLine()))
                .sorted(Comparator.comparing(Report.ParseError::path))
                .toList();
        return new Report.Limits(maxCommits, history.truncated(), skipped, errors);
    }

    private static Report.Overview overview(List<SourceFile> files, List<SourceFile> python, GitHistory history) {
        Map<String, int[]> byLanguage = new TreeMap<>();
        int loc = 0;
        for (SourceFile f : files) {
            if (f.language() == null || f.binary()) continue;
            int[] acc = byLanguage.computeIfAbsent(f.language(), k -> new int[2]);
            acc[0]++;
            acc[1] += f.lines();
            if (!NON_CODE.contains(f.language())) loc += f.lines();
        }
        List<Report.LanguageStat> languages = byLanguage.entrySet().stream()
                .map(e -> new Report.LanguageStat(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .sorted(Comparator.comparingInt(Report.LanguageStat::lines).reversed()
                        .thenComparing(Report.LanguageStat::language))
                .toList();
        long tests = python.stream().filter(SourceFile::test).count();
        long sources = python.size() - tests;
        Double testRatio = sources == 0 ? null : Math.round(1000.0 * tests / sources) / 1000.0;
        return new Report.Overview(history.commitsWalked(), history.authors().size(), history.firstCommitAt(),
                history.lastCommitAt(), files.size(), loc, languages, testRatio);
    }

    private static List<Report.FileEntry> fileEntries(List<SourceFile> files, Map<String, ParsedPythonFile> parsed,
                                                      Map<String, List<ResolvedImport>> resolved,
                                                      GitHistory history) {
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
            Report.FileGit git = h == null ? null
                    : new Report.FileGit(h.commits(), h.authorEmails().size(), h.firstChangedAt(), h.lastChangedAt());
            out.add(new Report.FileEntry(f.path(), f.language(), f.lines(), componentOf(f.path()), f.test(),
                    symbols, imports, git));
        }
        return out;
    }

    /** v1 components are directories; "." is the repository root. */
    static String componentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "." : path.substring(0, slash);
    }

    private static List<Report.Component> components(List<SourceFile> files) {
        Map<String, int[]> acc = new TreeMap<>();
        for (SourceFile f : files) {
            int[] a = acc.computeIfAbsent(componentOf(f.path()), k -> new int[2]);
            a[0]++;
            a[1] += f.lines();
        }
        List<Report.Component> out = new ArrayList<>();
        acc.forEach((id, a) -> out.add(new Report.Component(id, id, a[0], a[1])));
        return out;
    }

    private static List<Report.Edge> edges(ImportGraph graph) {
        List<Report.Edge> out = new ArrayList<>();
        for (String from : graph.nodes()) {
            graph.importsOf(from).stream().sorted()
                    .forEach(to -> out.add(new Report.Edge(from, to, "import", 1)));
        }
        return out;
    }

    private static Report.People people(GitHistory history) {
        List<GitHistory.Author> sorted = history.authors().values().stream()
                .sorted(Comparator.comparingInt(GitHistory.Author::commits).reversed()
                        .thenComparing(GitHistory.Author::email))
                .toList();
        Map<String, Report.Author> out = new LinkedHashMap<>();
        for (GitHistory.Author a : sorted) {
            String id = "a" + (out.size() + 1);
            out.put(id, new Report.Author(id, a.name(), List.of(a.email()), a.commits()));
        }
        return new Report.People(List.copyOf(out.values()));
    }
}
