package spike;

import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSQuery;
import org.treesitter.TSQueryCapture;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;
import org.treesitter.TSTree;
import org.treesitter.TSTreeCursor;
import org.treesitter.TreeSitterPython;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Throwaway spike: parse every .py file under a directory and report throughput,
 * files with syntax errors, and total imports/symbols found.
 */
public class ParseTree {

    public static void main(String[] args) throws Exception {
        Path rootDir = Path.of(args[0]);
        List<Path> files;
        try (Stream<Path> s = Files.walk(rootDir)) {
            files = s.filter(p -> p.toString().endsWith(".py"))
                     .filter(p -> !p.toString().contains(".git"))
                     .toList();
        }

        TreeSitterPython python = new TreeSitterPython();
        TSParser parser = new TSParser();
        parser.setLanguage(python);
        boolean useQuery = args.length < 2 || !args[1].equals("cursor");
        TSQuery query = new TSQuery(python, QUERY);

        long bytes = 0, imports = 0, symbols = 0;
        int errorFiles = 0;
        long parseNanos = 0, walkNanos = 0;
        long t0 = System.nanoTime();
        for (Path f : files) {
            byte[] src = Files.readAllBytes(f);
            bytes += src.length;
            long p0 = System.nanoTime();
            TSTree tree = parser.parseString(null, new String(src, StandardCharsets.UTF_8));
            parseNanos += System.nanoTime() - p0;
            TSNode root = tree.getRootNode();
            if (root.hasError()) {
                errorFiles++;
                if (errorFiles <= 5) System.out.println("  syntax error: " + rootDir.relativize(f));
            }
            long w0 = System.nanoTime();
            long[] c = useQuery ? countWithQuery(query, root) : countWithCursor(root);
            walkNanos += System.nanoTime() - w0;
            imports += c[0];
            symbols += c[1];
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        System.out.printf("%d files, %.1f MB, %.2f s (%.0f files/s)%n", files.size(), bytes / 1e6, secs, files.size() / secs);
        System.out.printf("parse=%.2f s, walk=%.2f s%n", parseNanos / 1e9, walkNanos / 1e9);
        System.out.printf("imports=%d symbols=%d filesWithErrors=%d%n", imports, symbols, errorFiles);
    }

    private static final String QUERY = """
            (import_statement) @import
            (import_from_statement) @import
            (class_definition) @symbol
            (function_definition) @symbol
            """;

    /** Matching runs in native code; only matched nodes cross the JNI boundary. */
    private static long[] countWithQuery(TSQuery query, TSNode root) {
        long[] c = new long[2];
        TSQueryCursor qc = new TSQueryCursor();
        qc.exec(query, root);
        TSQueryMatch m = new TSQueryMatch();
        while (qc.nextMatch(m)) {
            for (TSQueryCapture cap : m.getCaptures()) {
                if (query.getCaptureNameForId(cap.getIndex()).equals("import")) c[0]++; else c[1]++;
            }
        }
        return c;
    }

    /** Pre-order walk with a cursor: one native step per node instead of indexed child lookups. */
    private static long[] countWithCursor(TSNode root) {
        long[] c = new long[2];
        TSTreeCursor cur = new TSTreeCursor(root);
        while (true) {
            String t = cur.currentNode().getType();
            if (t.equals("import_statement") || t.equals("import_from_statement")) c[0]++;
            if (t.equals("class_definition") || t.equals("function_definition")) c[1]++;
            if (cur.gotoFirstChild() || cur.gotoNextSibling()) continue;
            while (true) {
                if (!cur.gotoParent()) return c;
                if (cur.gotoNextSibling()) break;
            }
        }
    }

    private static long[] count(TSNode node) {
        long[] c = new long[2];
        String t = node.getType();
        if (t.equals("import_statement") || t.equals("import_from_statement")) c[0]++;
        if (t.equals("class_definition") || t.equals("function_definition")) c[1]++;
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            long[] sub = count(node.getNamedChild(i));
            c[0] += sub[0];
            c[1] += sub[1];
        }
        return c;
    }
}
