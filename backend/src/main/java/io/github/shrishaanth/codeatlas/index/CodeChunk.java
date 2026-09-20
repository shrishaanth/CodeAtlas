package io.github.shrishaanth.codeatlas.index;

/**
 * A piece of a file that can be retrieved and cited on its own.
 *
 * @param kind      {@code function}, {@code class}, {@code method}, {@code module} (code outside any
 *                  symbol) or {@code text} (a window of a non-Python file)
 * @param symbol    the symbol's name, or null for module and text chunks
 * @param fileScore the file's reading-order score, used to prefer central files when several match
 */
public record CodeChunk(String path, int startLine, int endLine, String kind, String symbol, String text,
                        boolean test, boolean generated, double fileScore) {

    public int lines() {
        return endLine - startLine + 1;
    }

    /** "src/flask/app.py:76-1530" */
    public String location() {
        return path + ":" + startLine + "-" + endLine;
    }

    public boolean contains(String otherPath, int line) {
        return path.equals(otherPath) && line >= startLine && line <= endLine;
    }
}
