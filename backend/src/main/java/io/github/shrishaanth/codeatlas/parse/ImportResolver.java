package io.github.shrishaanth.codeatlas.parse;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Maps Python imports to files in the repository. The rules are specified in docs/metrics.md
 * ("Python import resolution"); keep the two in sync.
 */
public class ImportResolver {

    private final Set<String> files;
    private final Set<String> packageDirs = new HashSet<>();
    private final List<String> sourceRoots;
    /** Dotted names (from the repo root) indexed by every suffix of two or more components. */
    private Map<String, List<String>> suffixIndex;

    public ImportResolver(Collection<String> pythonPaths) {
        this.files = new HashSet<>(pythonPaths);
        for (String p : files) {
            if (p.endsWith("/__init__.py") || p.endsWith("/__init__.pyi")) packageDirs.add(parent(p));
        }
        Set<String> roots = new TreeSet<>();
        roots.add("");
        for (String pkg : packageDirs) {
            String dir = parent(pkg);
            if (!packageDirs.contains(dir)) roots.add(dir);
        }
        this.sourceRoots = List.copyOf(roots);
    }

    public List<String> sourceRoots() {
        return sourceRoots;
    }

    /** Resolves every module one import statement refers to. Duplicate targets are merged. */
    public List<ResolvedImport> resolve(String importerPath, PyImport imp) {
        List<ResolvedImport> out = imp.level() > 0 ? resolveRelative(importerPath, imp) : resolveAbsolute(importerPath, imp);
        Map<String, ResolvedImport> byTarget = new LinkedHashMap<>();
        for (ResolvedImport r : out) {
            String key = r.targetPath() != null ? r.targetPath() : "?" + r.module();
            byTarget.putIfAbsent(key, r);
        }
        return List.copyOf(byTarget.values());
    }

    // ---- absolute imports ------------------------------------------------------------

    private List<ResolvedImport> resolveAbsolute(String importer, PyImport imp) {
        List<String> roots = rootsFor(importer);
        if (!imp.fromImport()) {
            return List.of(resolveModule(importer, roots, imp, imp.module(), true));
        }
        List<ResolvedImport> out = new ArrayList<>();
        ResolvedImport base = null;
        for (String name : imp.names()) {
            if (!name.equals("*")) {
                String sub = imp.module() + "." + name;
                Hit hit = closest(importer, lookup(roots, importer, sub));
                if (hit != null) {
                    out.add(new ResolvedImport(imp, sub, hit.path, false, hit.method));
                    continue;
                }
            }
            if (base == null) base = resolveModule(importer, roots, imp, imp.module(), false);
            out.add(base);
        }
        if (out.isEmpty()) out.add(resolveModule(importer, roots, imp, imp.module(), false));
        return out;
    }

    /**
     * @param allowPrefix for {@code import a.b.c}, fall back to {@code a.b} then {@code a}
     *                    (importing a.b.c also imports its parent packages)
     */
    private ResolvedImport resolveModule(String importer, List<String> roots, PyImport imp, String module,
                                         boolean allowPrefix) {
        String candidate = module;
        while (true) {
            Hit hit = closest(importer, lookup(roots, importer, candidate));
            if (hit != null) return new ResolvedImport(imp, candidate, hit.path, false, hit.method);
            int dot = candidate.lastIndexOf('.');
            if (!allowPrefix || dot < 0) break;
            candidate = candidate.substring(0, dot);
        }
        // Namespace packages and unusual layouts: accept a unique match on the end of a file's
        // dotted name, but only for names with two or more parts ("utils" alone is too ambiguous).
        if (module.contains(".")) {
            List<String> matches = suffixIndex().getOrDefault(module, List.of());
            if (matches.size() == 1) return new ResolvedImport(imp, module, matches.get(0), false, "suffix");
        }
        String top = module.contains(".") ? module.substring(0, module.indexOf('.')) : module;
        boolean topInRepo = !lookup(roots, importer, top).isEmpty();
        return new ResolvedImport(imp, module, null, !topInRepo, null);
    }

    /** Source roots, plus the importer's own directory when the importer is a script (not in a package). */
    private List<String> rootsFor(String importer) {
        String dir = parent(importer);
        if (packageDirs.contains(dir) || sourceRoots.contains(dir)) return sourceRoots;
        List<String> roots = new ArrayList<>(sourceRoots);
        roots.add(dir);
        return roots;
    }

    private List<Hit> lookup(List<String> roots, String importer, String dotted) {
        List<Hit> hits = new ArrayList<>();
        String scriptDir = parent(importer);
        boolean scriptDirIsExtra = !sourceRoots.contains(scriptDir);
        for (String root : roots) {
            String path = moduleFile(root, dotted);
            if (path != null) {
                boolean viaScriptDir = scriptDirIsExtra && root.equals(scriptDir);
                hits.add(new Hit(path, viaScriptDir ? "script-dir" : "root"));
            }
        }
        return hits;
    }

    // ---- relative imports ------------------------------------------------------------

    private List<ResolvedImport> resolveRelative(String importer, PyImport imp) {
        String dots = ".".repeat(imp.level());
        String base = parent(importer);
        for (int i = 1; i < imp.level(); i++) {
            if (base.isEmpty()) {
                // More dots than directories: Python would raise ImportError.
                return List.of(new ResolvedImport(imp, dots + imp.module(), null, false, null));
            }
            base = parent(base);
        }
        String moduleFile = imp.module().isEmpty() ? packageInit(base) : moduleFile(base, imp.module());

        List<ResolvedImport> out = new ArrayList<>();
        for (String name : imp.names()) {
            if (name.equals("*")) continue;
            String dotted = imp.module().isEmpty() ? name : imp.module() + "." + name;
            String sub = moduleFile(base, dotted);
            if (sub != null) out.add(new ResolvedImport(imp, dots + dotted, sub, false, "relative"));
        }
        boolean everyNameIsSubmodule = !imp.names().isEmpty() && out.size() == imp.names().size();
        if (!everyNameIsSubmodule) {
            out.add(new ResolvedImport(imp, dots + imp.module(), moduleFile, false,
                    moduleFile == null ? null : "relative"));
        }
        return out;
    }

    // ---- helpers ---------------------------------------------------------------------

    /** File for a dotted module under a directory; a package beats a same-named module, as in Python. */
    private String moduleFile(String dir, String dotted) {
        if (dotted.isEmpty()) return null;
        String prefix = (dir.isEmpty() ? "" : dir + "/") + dotted.replace('.', '/');
        for (String candidate : new String[]{
                prefix + "/__init__.py", prefix + ".py", prefix + "/__init__.pyi", prefix + ".pyi"}) {
            if (files.contains(candidate)) return candidate;
        }
        return null;
    }

    private String packageInit(String dir) {
        String prefix = dir.isEmpty() ? "" : dir + "/";
        if (files.contains(prefix + "__init__.py")) return prefix + "__init__.py";
        if (files.contains(prefix + "__init__.pyi")) return prefix + "__init__.pyi";
        return null;
    }

    /** When several roots contain the module, prefer the file sharing the most directories with the importer. */
    private static Hit closest(String importer, List<Hit> hits) {
        if (hits.isEmpty()) return null;
        String[] from = importer.split("/");
        return hits.stream()
                .max(Comparator.<Hit>comparingInt(h -> sharedDirs(from, h.path.split("/")))
                        .thenComparing(h -> h.path, Comparator.reverseOrder()))
                .orElseThrow();
    }

    private static int sharedDirs(String[] a, String[] b) {
        int n = 0;
        while (n < a.length - 1 && n < b.length - 1 && a[n].equals(b[n])) n++;
        return n;
    }

    private Map<String, List<String>> suffixIndex() {
        if (suffixIndex == null) {
            suffixIndex = new HashMap<>();
            for (String path : files) {
                // A stub next to its module (x.pyi beside x.py) is the same module, not a second match.
                if (path.endsWith(".pyi") && files.contains(path.substring(0, path.length() - 1))) continue;
                String dotted = path.replaceAll("(/__init__)?\\.pyi?$", "").replace('/', '.');
                String[] parts = dotted.split("\\.");
                for (int start = 0; start <= parts.length - 2; start++) {
                    String suffix = String.join(".", java.util.Arrays.copyOfRange(parts, start, parts.length));
                    suffixIndex.computeIfAbsent(suffix, k -> new ArrayList<>()).add(path);
                }
            }
        }
        return suffixIndex;
    }

    static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private record Hit(String path, String method) {
    }
}
