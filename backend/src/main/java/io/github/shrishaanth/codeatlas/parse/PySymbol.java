package io.github.shrishaanth.codeatlas.parse;

/**
 * A class or function definition.
 *
 * @param kind      {@code class} or {@code function} (methods are functions with a class parent)
 * @param parent    name of the nearest enclosing class or function, or null at module level
 */
public record PySymbol(String kind, String name, int startLine, int endLine, String parent) {
}
