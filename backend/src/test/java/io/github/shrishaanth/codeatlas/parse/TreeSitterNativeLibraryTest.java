package io.github.shrishaanth.codeatlas.parse;

import org.junit.jupiter.api.Test;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TreeSitterPython;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Guards the one platform-specific dependency: the Tree-sitter JNI library must load on
 * every OS we build or run on (Windows dev machines, Linux CI and the Linux Docker image).
 */
class TreeSitterNativeLibraryTest {

    @Test
    void parsesPythonWithBundledNativeLibrary() {
        TSParser parser = new TSParser();
        parser.setLanguage(new TreeSitterPython());

        TSNode root = parser.parseString(null, "import os\n\ndef f():\n    return os.sep\n").getRootNode();

        assertFalse(root.hasError());
        assertEquals("module", root.getType());
        assertEquals("import_statement", root.getNamedChild(0).getType());
        assertEquals("function_definition", root.getNamedChild(1).getType());
    }
}
