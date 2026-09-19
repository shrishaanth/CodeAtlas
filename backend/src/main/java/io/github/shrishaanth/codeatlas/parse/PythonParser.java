package io.github.shrishaanth.codeatlas.parse;

import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSQuery;
import org.treesitter.TSQueryCapture;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterPython;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts imports and class/function definitions from Python source with Tree-sitter.
 * <p>
 * Matching is done with Tree-sitter queries so it runs in native code; walking every node from
 * Java was 5x slower in the spike (see spikes/README.md). Only the matched statements are
 * inspected from Java. Not thread-safe: use one instance per thread.
 */
public class PythonParser implements AutoCloseable {

    private static final String QUERY = """
            (import_statement) @import
            (import_from_statement) @import
            (class_definition name: (identifier) @name) @class
            (function_definition name: (identifier) @name) @function
            (ERROR) @error
            """;

    private final TSParser parser = new TSParser();
    private final TSQuery query;

    public PythonParser() {
        TreeSitterPython python = new TreeSitterPython();
        parser.setLanguage(python);
        query = new TSQuery(python, QUERY);
    }

    public ParsedPythonFile parse(String path, String source) {
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        TSTree tree = parser.parseString(null, source);
        TSNode root = tree.getRootNode();

        List<PyImport> imports = new ArrayList<>();
        List<PySymbol> symbols = new ArrayList<>();
        int firstErrorLine = 0;

        TSQueryCursor cursor = new TSQueryCursor();
        cursor.exec(query, root);
        TSQueryMatch match = new TSQueryMatch();
        while (cursor.nextMatch(match)) {
            TSNode main = null;
            TSNode name = null;
            String mainCapture = null;
            for (TSQueryCapture cap : match.getCaptures()) {
                String captureName = query.getCaptureNameForId(cap.getIndex());
                if (captureName.equals("name")) {
                    name = cap.getNode();
                } else {
                    main = cap.getNode();
                    mainCapture = captureName;
                }
            }
            if (main == null) continue;
            switch (mainCapture) {
                case "import" -> readImport(main, bytes, imports);
                case "class", "function" -> symbols.add(new PySymbol(mainCapture, text(name, bytes),
                        line(main), endLine(main), enclosingDefinitionName(main, bytes)));
                case "error" -> {
                    if (firstErrorLine == 0 || line(main) < firstErrorLine) firstErrorLine = line(main);
                }
                default -> { }
            }
        }
        if (firstErrorLine == 0 && root.hasError()) {
            // Tree-sitter repaired the error by inserting a MISSING token, which has no ERROR node.
            firstErrorLine = line(firstMissingNode(root));
        }
        return new ParsedPythonFile(path, imports, symbols, firstErrorLine);
    }

    private static void readImport(TSNode stmt, byte[] src, List<PyImport> out) {
        int line = line(stmt);
        String text = text(stmt, src).replaceAll("\\s+", " ").trim();

        if (stmt.getType().equals("import_statement")) {
            // import a.b, c as d
            for (int i = 0; i < stmt.getChildCount(); i++) {
                if (!"name".equals(stmt.getFieldNameForChild(i))) continue;
                String module = dottedName(stmt.getChild(i), src);
                if (module != null) out.add(new PyImport(line, text, false, 0, module, List.of()));
            }
            return;
        }

        // from <module_name> import <name>, <name> | *
        TSNode moduleNode = stmt.getChildByFieldName("module_name");
        if (moduleNode.isNull()) return;
        int level = 0;
        String module;
        if (moduleNode.getType().equals("relative_import")) {
            module = "";
            for (int i = 0; i < moduleNode.getNamedChildCount(); i++) {
                TSNode part = moduleNode.getNamedChild(i);
                if (part.getType().equals("import_prefix")) {
                    level = text(part, src).trim().length();
                } else if (part.getType().equals("dotted_name")) {
                    module = text(part, src);
                }
            }
        } else {
            module = text(moduleNode, src);
        }

        List<String> names = new ArrayList<>();
        for (int i = 0; i < stmt.getChildCount(); i++) {
            TSNode child = stmt.getChild(i);
            if (child.getType().equals("wildcard_import")) {
                names.add("*");
            } else if ("name".equals(stmt.getFieldNameForChild(i))) {
                String n = dottedName(child, src);
                if (n != null) names.add(n);
            }
        }
        out.add(new PyImport(line, text, true, level, module.replaceAll("\\s+", ""), names));
    }

    /** Name from a {@code dotted_name} or the original name of an {@code aliased_import}. */
    private static String dottedName(TSNode node, byte[] src) {
        return switch (node.getType()) {
            case "dotted_name" -> text(node, src).replaceAll("\\s+", "");
            case "aliased_import" -> {
                TSNode n = node.getChildByFieldName("name");
                yield n.isNull() ? null : text(n, src).replaceAll("\\s+", "");
            }
            default -> null;
        };
    }

    /** Follows only children that contain an error, so this visits a handful of nodes, not the tree. */
    private static TSNode firstMissingNode(TSNode node) {
        if (node.isMissing()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode child = node.getChild(i);
            if (child.isMissing() || child.hasError()) return firstMissingNode(child);
        }
        return node;
    }

    private static String enclosingDefinitionName(TSNode node, byte[] src) {
        for (TSNode p = node.getParent(); p != null && !p.isNull(); p = p.getParent()) {
            String t = p.getType();
            if (t.equals("class_definition") || t.equals("function_definition")) {
                return text(p.getChildByFieldName("name"), src);
            }
        }
        return null;
    }

    private static int line(TSNode n) {
        return n.getStartPoint().getRow() + 1;
    }

    private static int endLine(TSNode n) {
        return n.getEndPoint().getRow() + 1;
    }

    /** Tree-sitter reports UTF-8 byte offsets, so slice the byte array, not the String. */
    private static String text(TSNode n, byte[] src) {
        if (n == null || n.isNull()) return "";
        return new String(src, n.getStartByte(), n.getEndByte() - n.getStartByte(), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        query.close();
    }
}
