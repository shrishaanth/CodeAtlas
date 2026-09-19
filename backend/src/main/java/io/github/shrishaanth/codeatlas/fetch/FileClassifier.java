package io.github.shrishaanth.codeatlas.fetch;

import java.util.Locale;
import java.util.Map;

/** Path-based rules: language by extension, and whether a file is a test. See docs/metrics.md. */
public final class FileClassifier {

    private static final Map<String, String> LANGUAGES = Map.ofEntries(
            Map.entry("py", "python"), Map.entry("pyi", "python"),
            Map.entry("java", "java"), Map.entry("kt", "kotlin"), Map.entry("scala", "scala"),
            Map.entry("js", "javascript"), Map.entry("jsx", "javascript"), Map.entry("mjs", "javascript"),
            Map.entry("cjs", "javascript"),
            Map.entry("ts", "typescript"), Map.entry("tsx", "typescript"),
            Map.entry("go", "go"), Map.entry("rs", "rust"), Map.entry("rb", "ruby"), Map.entry("php", "php"),
            Map.entry("c", "c"), Map.entry("h", "c"), Map.entry("cpp", "cpp"), Map.entry("cc", "cpp"),
            Map.entry("hpp", "cpp"), Map.entry("cs", "csharp"), Map.entry("swift", "swift"),
            Map.entry("sh", "shell"), Map.entry("bash", "shell"),
            Map.entry("html", "html"), Map.entry("css", "css"), Map.entry("scss", "css"),
            Map.entry("sql", "sql"), Map.entry("md", "markdown"), Map.entry("rst", "restructuredtext"),
            Map.entry("json", "json"), Map.entry("yml", "yaml"), Map.entry("yaml", "yaml"),
            Map.entry("toml", "toml"), Map.entry("xml", "xml"), Map.entry("ipynb", "jupyter"));

    /** Languages that are documentation or data rather than code (excluded from LOC, blame and hotspots). */
    private static final java.util.Set<String> NON_CODE = java.util.Set.of(
            "markdown", "restructuredtext", "json", "yaml", "toml", "xml", "jupyter");

    private FileClassifier() {
    }

    public static boolean isCode(String language) {
        return language != null && !NON_CODE.contains(language);
    }

    public static String language(String path) {
        String name = fileName(path);
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return null;
        return LANGUAGES.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    public static boolean isTest(String path) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            String dir = parts[i].toLowerCase(Locale.ROOT);
            if (dir.equals("test") || dir.equals("tests") || dir.equals("__tests__")) return true;
        }
        String name = parts[parts.length - 1];
        return name.equals("conftest.py")
                || (name.startsWith("test_") && name.endsWith(".py"))
                || name.endsWith("_test.py")
                || name.contains(".test.") || name.contains(".spec.")
                || name.endsWith("_test.go")
                || name.endsWith("Test.java") || name.endsWith("Tests.java");
    }

    // ---- generated and local files (docs/metrics.md, "Which files are analyzed") --------

    private static final java.util.Set<String> GENERATED_DIRS = java.util.Set.of(
            "__pycache__", "node_modules", ".pytest_cache", ".mypy_cache", ".tox", "htmlcov",
            ".ipynb_checkpoints", ".venv", "venv");
    /** {@code dist/} and {@code build/} sometimes hold real sources; only these outputs count there. */
    private static final java.util.Set<String> BUILD_OUTPUT_EXTENSIONS = java.util.Set.of(
            "js", "css", "html", "map", "pyc", "so", "o", "class", "jar", "whl", "gz", "zip");
    private static final java.util.Set<String> ENV_TEMPLATES = java.util.Set.of(
            ".env.example", ".env.sample", ".env.template", ".env.dist");

    /** Why a path looks like build output or a cache, or null. Content markers are checked separately. */
    public static String generatedReason(String path) {
        String[] parts = path.split("/");
        String name = parts[parts.length - 1];
        String lower = name.toLowerCase(Locale.ROOT);
        for (int i = 0; i < parts.length - 1; i++) {
            String dir = parts[i];
            if (GENERATED_DIRS.contains(dir)) return "build output or cache (" + dir + "/)";
            if (dir.endsWith(".egg-info")) return "build output or cache (" + dir + "/)";
            if ((dir.equals("dist") || dir.equals("build")) && BUILD_OUTPUT_EXTENSIONS.contains(extension(lower))) {
                return "build output (" + dir + "/)";
            }
        }
        if (lower.endsWith(".pyc") || lower.endsWith(".pyo")) return "compiled Python";
        if (lower.equals(".coverage") || lower.equals("coverage.xml")) return "test coverage output";
        if (lower.endsWith(".min.js") || lower.endsWith(".min.css")) return "minified file";
        if (lower.endsWith(".js.map") || lower.endsWith(".css.map")) return "source map";
        if (lower.equals(".ds_store") || lower.equals("thumbs.db")) return "operating system metadata";
        return null;
    }

    /** "environment file" or "database file" for files that are usually local-only, or null. */
    public static String localFileKind(String path) {
        String lower = fileName(path).toLowerCase(Locale.ROOT);
        if ((lower.equals(".env") || lower.startsWith(".env.")) && !ENV_TEMPLATES.contains(lower)) {
            return "environment file";
        }
        if (lower.endsWith(".sqlite") || lower.endsWith(".sqlite3") || lower.endsWith(".db")) {
            return lower.equals("thumbs.db") ? null : "database file";
        }
        return null;
    }

    private static String extension(String lowerName) {
        int dot = lowerName.lastIndexOf('.');
        return dot < 0 ? "" : lowerName.substring(dot + 1);
    }

    static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
