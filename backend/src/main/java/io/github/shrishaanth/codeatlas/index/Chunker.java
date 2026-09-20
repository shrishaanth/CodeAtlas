package io.github.shrishaanth.codeatlas.index;

import io.github.shrishaanth.codeatlas.fetch.SourceFile;
import io.github.shrishaanth.codeatlas.parse.ParsedPythonFile;
import io.github.shrishaanth.codeatlas.parse.PySymbol;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Splits files into chunks that can be cited: by symbol for Python, by line window otherwise.
 * Rules are in docs/metrics.md, "Question answering".
 */
public final class Chunker {

    public static final int MAX_CHUNK_LINES = 200;
    /**
     * A class longer than this is indexed as its methods. It is well below {@link #MAX_CHUNK_LINES}
     * because an answer citing a 190-line excerpt cannot be checked in any useful way: the model
     * then invents line numbers inside it (seen with itsdangerous' 191-line Signer class).
     */
    public static final int SPLIT_CLASS_LINES = 60;
    public static final int TEXT_WINDOW_LINES = 100;
    /** Below this, a chunk carries too little to be worth retrieving on its own. */
    public static final int MIN_CHUNK_LINES = 2;

    private Chunker() {
    }

    /** @param parsed the parsed file, or null for files that are not Python */
    public static List<CodeChunk> chunk(SourceFile file, String text, ParsedPythonFile parsed, double fileScore) {
        String[] lines = splitLines(text);
        if (lines.length == 0 || text.isBlank()) return List.of();
        if (parsed == null) return windows(file, lines, fileScore);

        List<PySymbol> top = new ArrayList<>();
        for (PySymbol s : parsed.symbols()) {
            if (s.parent() == null) top.add(s);
        }
        top.sort(Comparator.comparingInt(PySymbol::startLine));

        List<CodeChunk> out = new ArrayList<>();
        int covered = 0; // last line already placed in a chunk
        for (PySymbol s : top) {
            addBetween(out, file, lines, covered + 1, s.startLine() - 1, fileScore);
            if (s.kind().equals("class") && s.endLine() - s.startLine() + 1 > SPLIT_CLASS_LINES) {
                // A long class is more useful as its methods: each is a separate, citable answer.
                out.addAll(methodsOf(file, lines, parsed, s, fileScore));
            } else {
                out.add(chunkOf(file, lines, s.startLine(), s.endLine(), s.kind(), s.name(), fileScore));
            }
            covered = Math.max(covered, s.endLine());
        }
        addBetween(out, file, lines, covered + 1, lines.length, fileScore);
        out.removeIf(c -> c.lines() < MIN_CHUNK_LINES && c.symbol() == null);
        return out;
    }

    private static List<CodeChunk> methodsOf(SourceFile file, String[] lines, ParsedPythonFile parsed,
                                             PySymbol clazz, double fileScore) {
        List<PySymbol> methods = parsed.symbols().stream()
                .filter(m -> clazz.name().equals(m.parent()))
                .filter(m -> m.startLine() >= clazz.startLine() && m.endLine() <= clazz.endLine())
                .sorted(Comparator.comparingInt(PySymbol::startLine))
                .toList();
        if (methods.isEmpty()) {
            return List.of(chunkOf(file, lines, clazz.startLine(),
                    Math.min(clazz.endLine(), clazz.startLine() + MAX_CHUNK_LINES - 1), "class", clazz.name(), fileScore));
        }
        List<CodeChunk> out = new ArrayList<>();
        // The class statement and everything before the first method: the docstring and attributes.
        out.add(chunkOf(file, lines, clazz.startLine(), methods.get(0).startLine() - 1, "class", clazz.name(), fileScore));
        for (PySymbol m : methods) {
            out.add(chunkOf(file, lines, m.startLine(), m.endLine(), "method", clazz.name() + "." + m.name(), fileScore));
        }
        return out;
    }

    /** Code between symbols (imports, constants) as module chunks of at most {@link #MAX_CHUNK_LINES}. */
    private static void addBetween(List<CodeChunk> out, SourceFile file, String[] lines, int from, int to,
                                   double fileScore) {
        if (to < from) return;
        for (int start = from; start <= to; start += MAX_CHUNK_LINES) {
            int end = Math.min(to, start + MAX_CHUNK_LINES - 1);
            if (isBlank(lines, start, end)) continue;
            out.add(chunkOf(file, lines, start, end, "module", null, fileScore));
        }
    }

    private static List<CodeChunk> windows(SourceFile file, String[] lines, double fileScore) {
        List<CodeChunk> out = new ArrayList<>();
        for (int start = 1; start <= lines.length; start += TEXT_WINDOW_LINES) {
            int end = Math.min(lines.length, start + TEXT_WINDOW_LINES - 1);
            if (isBlank(lines, start, end)) continue;
            out.add(chunkOf(file, lines, start, end, "text", null, fileScore));
        }
        return out;
    }

    /** A file ending with a newline has N lines, not N+1; line numbers must match the rest of the report. */
    static String[] splitLines(String text) {
        String[] lines = text.split("\\R", -1);
        return lines.length > 1 && lines[lines.length - 1].isEmpty()
                ? java.util.Arrays.copyOf(lines, lines.length - 1) : lines;
    }

    private static boolean isBlank(String[] lines, int from, int to) {
        for (int i = from; i <= to && i <= lines.length; i++) {
            if (!lines[i - 1].isBlank()) return false;
        }
        return true;
    }

    /** Lines are 1-based and inclusive, matching every line number in the report. */
    private static CodeChunk chunkOf(SourceFile file, String[] lines, int startLine, int endLine, String kind,
                                     String symbol, double fileScore) {
        int from = Math.max(1, startLine);
        int to = Math.min(lines.length, endLine);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            sb.append(lines[i - 1]).append('\n');
        }
        return new CodeChunk(file.path(), from, to, kind, symbol, sb.toString(), file.test(), file.generated(),
                fileScore);
    }
}
