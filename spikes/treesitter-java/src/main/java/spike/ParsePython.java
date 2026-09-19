package spike;

import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterPython;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Throwaway spike: can we parse Python from Java 17 with Tree-sitter and pull out
 * imports and top-level/nested symbols with line ranges?
 */
public class ParsePython {

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args.length > 0 ? args[0] : "samples/sample.py");
        byte[] bytes = Files.readAllBytes(file);
        String source = new String(bytes, StandardCharsets.UTF_8);

        TSParser parser = new TSParser();
        parser.setLanguage(new TreeSitterPython());
        long t0 = System.nanoTime();
        TSTree tree = parser.parseString(null, source);
        long micros = (System.nanoTime() - t0) / 1000;

        TSNode root = tree.getRootNode();
        System.out.printf("Parsed %s (%d bytes) in %d us, hasError=%s%n",
                file, bytes.length, micros, root.hasError());
        walk(root, bytes, 0);
    }

    private static void walk(TSNode node, byte[] src, int depth) {
        String type = node.getType();
        int line = node.getStartPoint().getRow() + 1;
        int endLine = node.getEndPoint().getRow() + 1;

        switch (type) {
            case "import_statement", "import_from_statement" ->
                System.out.printf("IMPORT  L%-3d %s%n", line, text(node, src).replaceAll("\\s+", " "));
            case "class_definition", "function_definition" -> {
                String name = text(node.getChildByFieldName("name"), src);
                System.out.printf("%-7s L%d-%d %s%s%n",
                        type.startsWith("class") ? "CLASS" : "DEF", line, endLine, "  ".repeat(depth), name);
                depth++;
            }
            default -> { }
        }
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            walk(node.getNamedChild(i), src, depth);
        }
    }

    private static String text(TSNode node, byte[] src) {
        // Tree-sitter reports byte offsets, so slice bytes, not chars.
        return new String(src, node.getStartByte(), node.getEndByte() - node.getStartByte(), StandardCharsets.UTF_8);
    }
}
