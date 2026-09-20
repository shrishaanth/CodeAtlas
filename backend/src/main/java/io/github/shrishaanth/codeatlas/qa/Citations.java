package io.github.shrishaanth.codeatlas.qa;

import io.github.shrishaanth.codeatlas.index.CodeChunk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds {@code path:line} references in an answer and checks each one against the code the model was
 * actually given. An answer that cites anything else is marked unverified rather than trusted.
 */
public final class Citations {

    /** path:line or path:line-line, where the path has an extension, e.g. src/flask/app.py:76-120 */
    private static final Pattern CITATION = Pattern.compile(
            "([A-Za-z0-9_./\\\\-]+\\.[A-Za-z0-9]+):(\\d+)(?:\\s*[-–]\\s*(\\d+))?");

    private Citations() {
    }

    public static List<Answer.Citation> verify(String answer, List<CodeChunk> given) {
        if (answer == null) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        List<Answer.Citation> out = new ArrayList<>();
        Matcher m = CITATION.matcher(answer);
        while (m.find()) {
            String path = m.group(1).replace('\\', '/');
            int start = Integer.parseInt(m.group(2));
            Integer end = m.group(3) == null ? null : Integer.parseInt(m.group(3));
            if (!seen.add(path + ":" + start + "-" + end)) continue;
            out.add(new Answer.Citation(path, start, end, status(path, start, end, given)));
        }
        return out;
    }

    /**
     * How much the citation can be trusted:
     * <ul>
     *   <li>{@code exact}: it names an excerpt the model was given, so it points at real code.</li>
     *   <li>{@code inside}: the lines fall within an excerpt, but not at its boundaries. The excerpt
     *       is real, the exact lines are the model's own arithmetic and are often wrong.</li>
     *   <li>{@code unsupported}: the lines are outside everything the model was given.</li>
     * </ul>
     */
    static String status(String path, int start, Integer end, List<CodeChunk> given) {
        for (CodeChunk c : given) {
            if (c.path().equals(path) && c.startLine() == start && (end == null || c.endLine() == end)) {
                return Answer.Citation.EXACT;
            }
        }
        boolean inside = given.stream().anyMatch(c -> c.contains(path, start))
                && (end == null || given.stream().anyMatch(c -> c.contains(path, end)));
        return inside ? Answer.Citation.INSIDE : Answer.Citation.UNSUPPORTED;
    }

    public static long unsupported(List<Answer.Citation> citations) {
        return citations.stream().filter(c -> c.status().equals(Answer.Citation.UNSUPPORTED)).count();
    }
}
