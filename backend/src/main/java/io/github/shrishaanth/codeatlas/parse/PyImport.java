package io.github.shrishaanth.codeatlas.parse;

import java.util.List;

/**
 * One imported module as written in the source, before resolution.
 * {@code import a.b, c} produces two of these; {@code from ..x import y, z} produces one.
 *
 * @param line       1-based line of the import statement
 * @param text       the statement, whitespace-collapsed
 * @param fromImport true for {@code from ... import ...}
 * @param level      number of leading dots for relative imports, 0 for absolute
 * @param module     dotted module without leading dots; empty for {@code from . import x}
 * @param names      names after {@code import} in a from-import ({@code *} for wildcard); empty otherwise
 */
public record PyImport(int line, String text, boolean fromImport, int level, String module, List<String> names) {

    public PyImport {
        names = List.copyOf(names);
    }
}
