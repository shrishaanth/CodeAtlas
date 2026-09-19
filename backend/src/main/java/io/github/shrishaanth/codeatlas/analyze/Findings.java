package io.github.shrishaanth.codeatlas.analyze;

import io.github.shrishaanth.codeatlas.fetch.FileClassifier;
import io.github.shrishaanth.codeatlas.fetch.SourceFile;
import io.github.shrishaanth.codeatlas.parse.ParsedPythonFile;
import io.github.shrishaanth.codeatlas.parse.PySymbol;
import io.github.shrishaanth.codeatlas.report.Report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Concrete, checkable observations with evidence. Every rule and threshold is defined in
 * docs/metrics.md, "Findings"; keep the two in sync.
 */
public final class Findings {

    public static final int MAX_PER_KIND = 50;

    // Near-duplicate files
    static final int MIN_SIGNIFICANT_LINES = 10;
    static final int MIN_LINE_LENGTH = 8;
    static final int COMMON_LINE_FILES = 20;
    static final double MIN_CONTAINMENT = 0.8;
    // Repeated functions
    static final int MIN_BODY_LINES = 6;
    // Areas without tests
    static final int MIN_AREA_LINES = 300;

    private static final Set<String> ENTRY_POINT_NAMES = Set.of(
            "__init__.py", "__main__.py", "setup.py", "conftest.py", "manage.py", "wsgi.py", "asgi.py");
    private static final Set<String> UNTESTABLE = Set.of("css", "html");
    private static final Set<String> ENTRY_POINT_DIRS = Set.of("scripts", "bin", "examples", "docs", "migrations");
    private static final Pattern MAIN_GUARD = Pattern.compile(
            "__name__\\s*==\\s*['\"]__main__['\"]|['\"]__main__['\"]\\s*==\\s*__name__");
    private static final Pattern CONFIG_MODULE = Pattern.compile("([A-Za-z_][\\w.]*)\\s*:\\s*[A-Za-z_]\\w*");
    private static final Pattern DOTTED = Pattern.compile("\\b[A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)+\\b");

    /**
     * @param graph       resolved imports between all Python files, tests included
     * @param importLines line of the first import from one file to another, keyed "from\u0000to"
     * @param text        reads a file's content (only called for files that need it)
     */
    public record Input(List<SourceFile> files, Map<String, ParsedPythonFile> parsed, ImportGraph graph,
                        Layers.Result layers, Map<String, Integer> importLines, Function<String, String> text) {
    }

    /** @param omitted findings left out per kind because of the cap */
    public record Result(List<Report.Finding> findings, Map<String, Integer> omitted) {
    }

    private Findings() {
    }

    public static Result compute(Input in) {
        Map<String, List<Report.Finding>> byKind = new LinkedHashMap<>();
        List<Report.Finding> duplicates = duplicateFiles(in);
        duplicates.addAll(sameNamedNestedModules(in, duplicates));
        byKind.put("duplicate-module", duplicates);
        List<Report.Finding> repeated = repeatedFunctions(in);
        repeated.addAll(similarNamesAcrossAreas(in));
        byKind.put("repeated-logic", repeated);
        byKind.put("import-cycle", importCycles(in));
        byKind.put("generated-file-committed", committedGeneratedFiles(in));
        byKind.put("missing-tests", areasWithoutTests(in));
        byKind.put("unreferenced-file", unreferencedFiles(in));

        List<Report.Finding> out = new ArrayList<>();
        Map<String, Integer> omitted = new TreeMap<>();
        byKind.forEach((kind, list) -> {
            for (int i = 0; i < Math.min(MAX_PER_KIND, list.size()); i++) {
                Report.Finding f = list.get(i);
                out.add(new Report.Finding(kind + "-" + (i + 1), f.kind(), f.severity(), f.title(), f.detail(),
                        f.evidence()));
            }
            if (list.size() > MAX_PER_KIND) omitted.put(kind, list.size() - MAX_PER_KIND);
        });
        return new Result(out, omitted);
    }

    // ---- near-duplicate files ------------------------------------------------------------

    static List<Report.Finding> duplicateFiles(Input in) {
        Map<String, Set<String>> linesOf = new TreeMap<>();
        for (SourceFile f : in.files()) {
            if (!f.isCode() || f.test() || f.generated() || f.binary() || f.skipReason() != null) continue;
            if (f.nonBlankLines() < MIN_SIGNIFICANT_LINES) continue;
            Set<String> sig = significantLines(in.text().apply(f.path()));
            if (sig.size() >= MIN_SIGNIFICANT_LINES) linesOf.put(f.path(), sig);
        }
        Map<String, List<String>> filesWithLine = new HashMap<>();
        linesOf.forEach((path, lines) -> lines.forEach(l -> filesWithLine.computeIfAbsent(l, k -> new ArrayList<>()).add(path)));

        Map<String, Map<String, Integer>> shared = new HashMap<>();
        for (List<String> holders : filesWithLine.values()) {
            if (holders.size() < 2 || holders.size() > COMMON_LINE_FILES) continue;
            for (int i = 0; i < holders.size(); i++) {
                for (int j = i + 1; j < holders.size(); j++) {
                    shared.computeIfAbsent(holders.get(i), k -> new HashMap<>()).merge(holders.get(j), 1, Integer::sum);
                }
            }
        }

        // Group files that are near-duplicates of each other (union-find over qualifying pairs).
        Map<String, String> parent = new HashMap<>();
        Map<String, Double> best = new HashMap<>();
        Map<String, Integer> bestShared = new HashMap<>();
        shared.forEach((a, m) -> m.forEach((b, n) -> {
            double containment = (double) n / Math.min(linesOf.get(a).size(), linesOf.get(b).size());
            if (n < MIN_SIGNIFICANT_LINES || containment < MIN_CONTAINMENT) return;
            union(parent, a, b);
            String root = find(parent, a);
            best.merge(root, containment, Math::max);
            bestShared.merge(root, n, Math::max);
        }));
        Map<String, List<String>> groups = new TreeMap<>();
        for (String p : parent.keySet()) groups.computeIfAbsent(find(parent, p), k -> new ArrayList<>()).add(p);

        List<Report.Finding> out = new ArrayList<>();
        for (var e : groups.entrySet()) {
            List<String> members = e.getValue().stream().sorted().toList();
            // Recompute from the final root: the best values were merged under intermediate roots.
            double c = members.stream().mapToDouble(m -> best.getOrDefault(m, 0.0)).max().orElse(0);
            int n = members.stream().mapToInt(m -> bestShared.getOrDefault(m, 0)).max().orElse(0);
            String title = members.size() == 2
                    ? "Near-duplicate files: " + name(members.get(0)) + " and " + name(members.get(1))
                    : members.size() + " near-duplicate files named like " + name(members.get(0));
            String detail = String.format("Up to %d%% of the smaller file's significant lines (%d lines) appear in "
                    + "another file of the group. Consider keeping one copy.", Math.round(c * 100), n);
            out.add(finding("duplicate-module", "warn", title, detail, members.stream().map(Findings::wholeFile).toList()));
        }
        out.sort(Comparator.comparingInt((Report.Finding f) -> f.evidence().size()).reversed()
                .thenComparing(Report.Finding::title));
        return out;
    }

    static Set<String> significantLines(String text) {
        Set<String> out = new HashSet<>();
        for (String raw : text.split("\\R")) {
            String l = raw.strip();
            if (l.length() < MIN_LINE_LENGTH || l.startsWith("#") || l.startsWith("//")) continue;
            out.add(l);
        }
        return out;
    }

    // ---- repeated functions --------------------------------------------------------------

    static List<Report.Finding> repeatedFunctions(Input in) {
        record Occurrence(String path, PySymbol symbol, int bodyLines) {
        }
        Map<String, List<Occurrence>> byBody = new HashMap<>();
        Map<String, SourceFile> filesByPath = new HashMap<>();
        in.files().forEach(f -> filesByPath.put(f.path(), f));
        for (ParsedPythonFile pf : in.parsed().values()) {
            SourceFile f = filesByPath.get(pf.path());
            if (f == null || f.test() || f.generated()) continue;
            List<PySymbol> functions = pf.symbols().stream().filter(s -> s.kind().equals("function")).toList();
            if (functions.isEmpty()) continue;
            String[] lines = in.text().apply(pf.path()).split("\\R", -1);
            for (PySymbol s : functions) {
                List<String> body = new ArrayList<>();
                for (int i = s.startLine(); i < Math.min(s.endLine(), lines.length); i++) { // skips the def line
                    String l = lines[i].strip();
                    if (!l.isEmpty() && !l.startsWith("#")) body.add(l);
                }
                if (body.size() < MIN_BODY_LINES) continue;
                byBody.computeIfAbsent(String.join("\n", body), k -> new ArrayList<>())
                        .add(new Occurrence(pf.path(), s, body.size()));
            }
        }

        List<List<Occurrence>> groups = byBody.values().stream()
                .filter(g -> g.stream().map(Occurrence::path).distinct().count() >= 2)
                .sorted(Comparator.comparingInt((List<Occurrence> g) -> g.get(0).bodyLines() * g.size()).reversed()
                        .thenComparing(g -> g.get(0).path()))
                .toList();

        // A duplicated outer function also duplicates its nested functions; report only the outer one.
        Map<String, List<int[]>> covered = new HashMap<>();
        List<Report.Finding> out = new ArrayList<>();
        for (List<Occurrence> g : groups) {
            boolean allCovered = g.stream().allMatch(o -> covered.getOrDefault(o.path(), List.of()).stream()
                    .anyMatch(r -> r[0] <= o.symbol().startLine() && o.symbol().endLine() <= r[1]));
            if (allCovered) continue;
            g.forEach(o -> covered.computeIfAbsent(o.path(), k -> new ArrayList<>())
                    .add(new int[]{o.symbol().startLine(), o.symbol().endLine()}));
            List<Occurrence> sorted = g.stream().sorted(Comparator.comparing(Occurrence::path)
                    .thenComparingInt(o -> o.symbol().startLine())).toList();
            List<String> names = sorted.stream().map(o -> o.symbol().name()).distinct().toList();
            String title = sorted.size() + " identical copies of " + (names.size() == 1
                    ? "function " + names.get(0) : "a function (" + String.join(", ", names) + ")");
            String detail = "The same " + sorted.get(0).bodyLines() + "-line body (ignoring whitespace and comments) "
                    + "appears in " + sorted.stream().map(Occurrence::path).distinct().count()
                    + " files. A shared helper would keep them from drifting apart.";
            out.add(finding("repeated-logic", "info", title, detail, sorted.stream()
                    .map(o -> new Report.Evidence(o.path(), o.symbol().startLine(), o.symbol().endLine(), null))
                    .toList()));
        }
        return out;
    }

    // ---- import cycles ---------------------------------------------------------------------

    static List<Report.Finding> importCycles(Input in) {
        List<Report.Finding> out = new ArrayList<>();
        for (Layers.Cycle c : in.layers().cycles()) {
            List<Report.Evidence> evidence = c.dependencies().stream()
                    .map(d -> {
                        Integer line = in.importLines().get(d.exampleFrom() + "\u0000" + d.exampleTo());
                        return new Report.Evidence(d.exampleFrom(), line, line, "imports " + d.exampleTo());
                    }).toList();
            String title = "Import cycle between " + c.dirs().size() + " directories: " + String.join(", ", c.dirs());
            String detail = "Each of these directories depends on the others through imports, so none can be "
                    + "understood, tested or moved on its own. Each evidence line is one import in the cycle.";
            out.add(finding("import-cycle", "warn", title, detail, evidence));
        }
        return out;
    }

    // ---- committed generated and local files -------------------------------------------

    static List<Report.Finding> committedGeneratedFiles(Input in) {
        Map<String, List<String>> byReason = new TreeMap<>();
        List<String> envFiles = new ArrayList<>();
        List<String> databases = new ArrayList<>();
        for (SourceFile f : in.files()) {
            if (f.generated()) byReason.computeIfAbsent(f.generatedReason(), k -> new ArrayList<>()).add(f.path());
            String local = FileClassifier.localFileKind(f.path());
            if ("environment file".equals(local)) envFiles.add(f.path());
            if ("database file".equals(local)) databases.add(f.path());
        }
        List<Report.Finding> out = new ArrayList<>();
        for (String env : envFiles) {
            if (FileClassifier.isTest(env)) {
                out.add(finding("generated-file-committed", "info", "Environment file committed in tests: " + env,
                        "Inside a test directory, so most likely a fixture for testing configuration loading. "
                                + "Its contents were not read; make sure it holds no real secrets.",
                        List.of(new Report.Evidence(env, null, null, null))));
                continue;
            }
            out.add(finding("generated-file-committed", "warn", "Environment file committed: " + env,
                    "Environment files usually hold local settings and secrets and are normally kept out of git. "
                            + "Its contents were not read. If it held real secrets, rotate them: they stay in history.",
                    List.of(new Report.Evidence(env, null, null, null))));
        }
        byReason.forEach((reason, paths) -> {
            boolean onPurpose = reason.equals("marked as generated") || reason.equals("minified file")
                    || reason.equals("source map");
            String title = paths.size() == 1 ? "Generated file committed (" + reason + "): " + paths.get(0)
                    : paths.size() + " generated files committed (" + reason + ")";
            String detail = onPurpose
                    ? "These look generated. That is often deliberate (vendored or generated code); check that they are "
                    + "regenerated rather than edited by hand."
                    : "Build output and caches are normally produced locally and listed in .gitignore.";
            out.add(finding("generated-file-committed", onPurpose ? "info" : "warn", title, detail,
                    sample(paths)));
        });
        if (!databases.isEmpty()) {
            out.add(finding("generated-file-committed", "info",
                    databases.size() == 1 ? "Database file committed: " + databases.get(0)
                            : databases.size() + " database files committed",
                    "Database files change whenever the app runs; commit them only if they are fixtures.",
                    sample(databases)));
        }
        // Possible leaked secrets first, then other warnings, then the often-deliberate ones; larger groups first.
        out.sort(Comparator.comparingInt((Report.Finding f) -> f.title().startsWith("Environment file") ? 0
                        : f.severity().equals("warn") ? 1 : 2)
                .thenComparing(Comparator.comparingInt((Report.Finding f) -> f.evidence().size()).reversed()));
        return out;
    }

    // ---- areas without tests -----------------------------------------------------------

    static List<Report.Finding> areasWithoutTests(Input in) {
        Map<String, Integer> codeLines = new TreeMap<>();
        Set<String> areasWithTests = new HashSet<>();
        for (SourceFile f : in.files()) {
            String area = ChangeCoupling.area(f.path());
            if (f.test()) {
                areasWithTests.add(area);
            } else if (isTestable(f)) {
                codeLines.merge(area, f.lines(), Integer::sum);
            }
        }
        // Python areas are also covered when a test elsewhere imports them.
        Set<String> testedFromOutside = new HashSet<>();
        for (SourceFile f : in.files()) {
            if (!f.test()) continue;
            in.graph().importsOf(f.path()).forEach(t -> testedFromOutside.add(ChangeCoupling.area(t)));
        }

        List<Report.Finding> out = new ArrayList<>();
        codeLines.forEach((area, lines) -> {
            if (area.equals("(root)") || lines < MIN_AREA_LINES) return;
            // Documentation, examples and scripts are not product code that tests are expected for.
            if (ENTRY_POINT_DIRS.contains(area.substring(area.lastIndexOf('/') + 1))) return;
            if (areasWithTests.contains(area) || testedFromOutside.contains(area)) return;
            List<Report.Evidence> largest = in.files().stream()
                    .filter(f -> !f.test() && isTestable(f) && ChangeCoupling.area(f.path()).equals(area))
                    .sorted(Comparator.comparingInt(SourceFile::lines).reversed()).limit(5)
                    .map(Findings::wholeFile).toList();
            out.add(finding("missing-tests", "info", "No tests found for " + area,
                    area + " has " + lines + " lines of code, no test files, and no test elsewhere imports it. "
                            + "Its largest files are listed.", largest));
        });
        out.sort(Comparator.comparingInt((Report.Finding f) -> -totalLines(f)));
        return out;
    }

    // ---- files nothing imports -------------------------------------------------------------

    static List<Report.Finding> unreferencedFiles(Input in) {
        Set<String> configModules = configEntryPoints(in);
        List<SourceFile> candidates = in.files().stream()
                .filter(f -> f.isPython() && !f.test() && !f.generated() && in.parsed().containsKey(f.path()))
                .filter(f -> in.graph().importersOf(f.path()).stream().allMatch(p -> isTest(in, p)))
                .filter(f -> !isLikelyEntryPoint(f.path()))
                .filter(f -> !declaredInConfig(f.path(), configModules))
                .filter(f -> !MAIN_GUARD.matcher(in.text().apply(f.path())).find())
                .sorted(Comparator.comparingInt(SourceFile::lines).reversed().thenComparing(SourceFile::path))
                .toList();
        List<Report.Finding> out = new ArrayList<>();
        for (SourceFile f : candidates) {
            int tests = in.graph().importersOf(f.path()).size();
            if (tests == 0) {
                out.add(finding("unreferenced-file", "info", "No static import found: " + f.path(),
                        "No file in the repository imports this module, and it does not look like an entry point. "
                                + "It may be unused, or loaded by name at runtime (plugins, framework conventions), "
                                + "which static analysis cannot see.",
                        List.of(wholeFile(f))));
            } else {
                out.add(finding("unreferenced-file", "info", "Only tests import " + f.path(),
                        tests + (tests == 1 ? " test file imports" : " test files import") + " this module, but no "
                                + "other code does. In an application that usually means it is unused; in a library "
                                + "it may be public API meant for users.",
                        List.of(wholeFile(f))));
            }
        }
        return out;
    }

    private static boolean isTest(Input in, String path) {
        return in.files().stream().anyMatch(f -> f.path().equals(path) && f.test());
    }

    /** Code a unit test could exercise: CSS and HTML are code by language but not by testing practice. */
    private static boolean isTestable(SourceFile f) {
        return f.isCode() && !f.generated() && !UNTESTABLE.contains(f.language());
    }

    static boolean isLikelyEntryPoint(String path) {
        String[] parts = path.split("/");
        if (ENTRY_POINT_NAMES.contains(parts[parts.length - 1])) return true;
        if (path.equals("docs/conf.py") || path.endsWith("/docs/conf.py")) return true;
        for (int i = 0; i < parts.length - 1; i++) {
            if (ENTRY_POINT_DIRS.contains(parts[i])) return true;
        }
        return false;
    }

    /** Module names mentioned in packaging config: "pkg.cli:main" and dotted names like "pkg.plugins". */
    static Set<String> configEntryPoints(Input in) {
        Set<String> out = new TreeSet<>();
        for (SourceFile f : in.files()) {
            String name = f.path().substring(f.path().lastIndexOf('/') + 1);
            if (!name.equals("pyproject.toml") && !name.equals("setup.cfg") && !name.equals("setup.py")) continue;
            if (f.binary() || f.skipReason() != null) continue;
            String text = in.text().apply(f.path());
            Matcher m = CONFIG_MODULE.matcher(text);
            while (m.find()) out.add(m.group(1));
            Matcher d = DOTTED.matcher(text);
            while (d.find()) out.add(d.group());
        }
        return out;
    }

    /** True if the file's dotted path ends with a module named in config (single names only in "x:y" form). */
    static boolean declaredInConfig(String path, Set<String> modules) {
        String dotted = path.replaceAll("\\.py$", "").replace('/', '.');
        for (String m : modules) {
            if (dotted.equals(m) || dotted.endsWith("." + m)) return true;
        }
        return false;
    }

    // ---- same-named modules, one inside the other ----------------------------------------

    /**
     * Two non-test Python modules with the same file name where one directory contains the other
     * (src/hrp.py and src/core/hrp.py). Sibling directories (services/a/app.py, services/b/app.py)
     * are not reported. Pairs already reported as near-duplicates are skipped.
     */
    static List<Report.Finding> sameNamedNestedModules(Input in, List<Report.Finding> nearDuplicates) {
        Set<String> alreadyGrouped = new HashSet<>();
        nearDuplicates.forEach(f -> f.evidence().forEach(e -> alreadyGrouped.add(e.path())));
        Map<String, List<SourceFile>> byName = new TreeMap<>();
        for (SourceFile f : in.files()) {
            if (!f.isPython() || f.test() || f.generated() || isLikelyEntryPoint(f.path())) continue;
            byName.computeIfAbsent(name(f.path()), k -> new ArrayList<>()).add(f);
        }
        List<Report.Finding> out = new ArrayList<>();
        byName.forEach((name, list) -> {
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    SourceFile a = list.get(i), b = list.get(j);
                    if (alreadyGrouped.contains(a.path()) && alreadyGrouped.contains(b.path())) continue;
                    String da = dir(a.path()), db = dir(b.path());
                    if (!isAncestor(da, db) && !isAncestor(db, da)) continue;
                    // One building on the other (flask/app.py importing flask/sansio/app.py) is a design, not two versions.
                    if (in.graph().importsOf(a.path()).contains(b.path())
                            || in.graph().importsOf(b.path()).contains(a.path())) continue;
                    Set<String> la = significantLines(in.text().apply(a.path()));
                    Set<String> lb = significantLines(in.text().apply(b.path()));
                    Set<String> common = new HashSet<>(la);
                    common.retainAll(lb);
                    int pct = (int) Math.round(100.0 * common.size() / Math.max(1, Math.min(la.size(), lb.size())));
                    String detail = String.format("Same name, one directory inside the other, but only %d%% of their "
                            + "significant lines match: likely two versions of the same module. %s is imported by %s, "
                            + "%s by %s; check which one is meant to be used.", pct,
                            a.path(), files(in.graph().importersOf(a.path()).size()),
                            b.path(), files(in.graph().importersOf(b.path()).size()));
                    out.add(finding("duplicate-module", "info", "Two modules named " + name, detail,
                            List.of(wholeFile(a), wholeFile(b))));
                }
            }
        });
        return out;
    }

    private static String files(int n) {
        return n == 0 ? "no file" : n == 1 ? "1 file" : n + " files";
    }

    private static String dir(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static boolean isAncestor(String ancestor, String dir) {
        return !ancestor.equals(dir) && (ancestor.isEmpty() || dir.startsWith(ancestor + "/"));
    }

    // ---- similar file names across areas --------------------------------------------------

    /** Role words removed from file names before comparing ("tmdb_client" and "tmdbService" both become "tmdb"). */
    private static final List<String> ROLE_WORDS = List.of(
            "service", "services", "client", "clients", "tool", "tools", "api", "helper", "helpers",
            "util", "utils", "wrapper", "manager", "handler", "adapter");
    /** Stems every service has; sharing them says nothing. */
    private static final Set<String> GENERIC_STEMS = Set.of(
            "app", "main", "index", "init", "config", "settings", "models", "model", "views", "view", "base", "core",
            "types", "constants", "routes", "router", "server", "cli", "db", "database", "schemas", "schema",
            "setup", "logger", "logging", "errors", "exceptions", "middleware", "auth", "user", "users", "common",
            "shared", "store", "hooks", "components", "styles", "style", "test", "tests", "conftest", "requirements");
    static final int MIN_SIMILAR_FILES = 3;

    static List<Report.Finding> similarNamesAcrossAreas(Input in) {
        Map<String, List<SourceFile>> byStem = new TreeMap<>();
        for (SourceFile f : in.files()) {
            if (!isTestable(f) || f.test()) continue;
            String stem = nameStem(f.path());
            if (stem.length() >= 3 && !GENERIC_STEMS.contains(stem)) {
                byStem.computeIfAbsent(stem, k -> new ArrayList<>()).add(f);
            }
        }
        List<Report.Finding> out = new ArrayList<>();
        byStem.forEach((stem, list) -> {
            long areas = list.stream().map(f -> ChangeCoupling.area(f.path())).distinct().count();
            if (list.size() < MIN_SIMILAR_FILES || areas < 2) return;
            List<SourceFile> sorted = list.stream().sorted(Comparator.comparing(SourceFile::path)).toList();
            out.add(finding("repeated-logic", "info",
                    sorted.size() + " files named after \"" + stem + "\" in " + areas + " areas",
                    "Their names suggest each area has its own code for the same thing (for example, its own client "
                            + "for one external API). This is a hint from file names only; compare the files before "
                            + "merging anything.",
                    sorted.stream().map(Findings::wholeFile).toList()));
        });
        out.sort(Comparator.comparingInt((Report.Finding f) -> f.evidence().size()).reversed());
        return out;
    }

    /** "services/ml/tmdb_client.py" -> "tmdb"; "backend/tmdbService.js" -> "tmdb". */
    static String nameStem(String path) {
        String n = name(path);
        int dot = n.indexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        // Split camelCase and separators into words, drop role words, join the rest.
        String[] words = n.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT).split("[_\\-\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty() && !ROLE_WORDS.contains(w)) sb.append(w);
        }
        return sb.toString();
    }

    // ---- helpers ------------------------------------------------------------------------

    private static Report.Finding finding(String kind, String severity, String title, String detail,
                                          List<Report.Evidence> evidence) {
        return new Report.Finding(null, kind, severity, title, detail, evidence);
    }

    private static Report.Evidence wholeFile(SourceFile f) {
        return new Report.Evidence(f.path(), f.lines() > 0 ? 1 : null, f.lines() > 0 ? f.lines() : null, null);
    }

    private static Report.Evidence wholeFile(String path) {
        return new Report.Evidence(path, null, null, null);
    }

    private static List<Report.Evidence> sample(List<String> paths) {
        List<Report.Evidence> out = new ArrayList<>();
        paths.stream().sorted().limit(10).forEach(p -> out.add(new Report.Evidence(p, null, null, null)));
        if (paths.size() > 10) {
            out.add(new Report.Evidence(null, null, null, "and " + (paths.size() - 10) + " more"));
        }
        return out;
    }

    private static int totalLines(Report.Finding f) {
        return f.evidence().stream().mapToInt(e -> e.endLine() == null ? 0 : e.endLine()).sum();
    }

    private static String name(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String find(Map<String, String> parent, String x) {
        parent.putIfAbsent(x, x);
        while (!parent.get(x).equals(x)) {
            parent.put(x, parent.get(parent.get(x)));
            x = parent.get(x);
        }
        return x;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        String ra = find(parent, a), rb = find(parent, b);
        if (!ra.equals(rb)) parent.put(rb, ra);
    }
}
