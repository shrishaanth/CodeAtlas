package io.github.shrishaanth.codeatlas.parse;

import java.util.List;

/**
 * @param firstErrorLine line of the first syntax error, or 0 if the file parsed cleanly.
 *                       Tree-sitter recovers from errors, so imports and symbols are still extracted.
 */
public record ParsedPythonFile(String path, List<PyImport> imports, List<PySymbol> symbols, int firstErrorLine) {

    public ParsedPythonFile {
        imports = List.copyOf(imports);
        symbols = List.copyOf(symbols);
    }
}
