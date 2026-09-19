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

    private FileClassifier() {
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
            if (dir.equals("test") || dir.equals("tests")) return true;
        }
        String name = parts[parts.length - 1];
        return name.equals("conftest.py")
                || (name.startsWith("test_") && name.endsWith(".py"))
                || name.endsWith("_test.py");
    }

    static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
