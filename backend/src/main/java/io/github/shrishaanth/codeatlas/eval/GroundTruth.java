package io.github.shrishaanth.codeatlas.eval;

import io.github.shrishaanth.codeatlas.gitmine.GitHistory;
import io.github.shrishaanth.codeatlas.report.Report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Independent answers to compare CodeAtlas against, taken from sources it does not use itself:
 * the project's own documentation, its CODEOWNERS file, and where newcomers actually started.
 * Method and caveats are in docs/evaluation.md.
 */
public final class GroundTruth {

    /** Documentation files that tell a newcomer where to look. */
    private static final Pattern DOC_FILE = Pattern.compile(
            "^(readme|contributing|architecture|hacking|development|design|internals)(\\.[a-z]+)?$|"
                    + "^docs?/(contributing|architecture|development|design|internals|structure|overview)"
                    + "[a-z0-9_/-]*(\\.[a-z]+)?$",
            Pattern.CASE_INSENSITIVE);
    /** Path-like tokens: at least one slash or a .py suffix, e.g. src/flask/app.py, flask/cli.py. */
    private static final Pattern PATH_TOKEN = Pattern.compile("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*\\.py\\b");
    private static final Pattern CODEOWNERS_LINE = Pattern.compile("^\\s*([^#\\s]+)\\s+(.+?)\\s*$");
    private static final Pattern OWNER_HANDLE = Pattern.compile("@([A-Za-z0-9-]+(?:/[A-Za-z0-9-]+)?)");

    /**
     * @param docsMentioned  source files named in the project's docs
     * @param newcomerFiles  source files touched by people in their first commit, most common first
     * @param codeowners     directory to GitHub handles responsible for it
     */
    public record Truth(Set<String> docsMentioned, List<String> newcomerFiles, Map<String, Set<String>> codeowners) {
    }

    private GroundTruth() {
    }

    /**
     * @param text     reads a repository file
     * @param rankable the files the reading order could rank (ground truth is restricted to these)
     */
    public static Truth collect(Report report, GitHistory history, java.util.function.Function<String, String> text,
                                Set<String> rankable) {
        return new Truth(docsMentioned(report, text, rankable), newcomerFiles(history, rankable),
                codeowners(report, text));
    }

    /** Files named by path in the project's own documentation. */
    static Set<String> docsMentioned(Report report, java.util.function.Function<String, String> text,
                                     Set<String> rankable) {
        Set<String> out = new TreeSet<>();
        for (Report.FileEntry f : report.files()) {
            if (!DOC_FILE.matcher(f.path()).matches()) continue;
            Matcher m = PATH_TOKEN.matcher(text.apply(f.path()));
            while (m.find()) {
                resolveMention(m.group(), rankable).ifPresent(out::add);
            }
        }
        return out;
    }

    /** A mention resolves only if exactly one rankable file ends with it, so ambiguous names are dropped. */
    static java.util.Optional<String> resolveMention(String mention, Set<String> rankable) {
        String needle = mention.startsWith("./") ? mention.substring(2) : mention;
        if (rankable.contains(needle)) return java.util.Optional.of(needle);
        List<String> matches = rankable.stream().filter(p -> p.endsWith("/" + needle)).limit(2).toList();
        return matches.size() == 1 ? java.util.Optional.of(matches.get(0)) : java.util.Optional.empty();
    }

    /** Files people touched in their first commit: where newcomers actually started, by frequency. */
    static List<String> newcomerFiles(GitHistory history, Set<String> rankable) {
        // Commits are newest first, so the last commit seen for an author is their first one.
        Map<String, List<String>> firstCommit = new HashMap<>();
        for (GitHistory.Commit c : history.commits()) {
            if (c.paths().isEmpty() || c.paths().size() > 20) continue; // huge first commits say little
            firstCommit.put(c.authorEmail(), c.paths());
        }
        Map<String, Integer> counts = new TreeMap<>();
        firstCommit.values().forEach(paths -> paths.stream().filter(rankable::contains)
                .forEach(p -> counts.merge(p, 1, Integer::sum)));
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey).toList();
    }

    /** CODEOWNERS patterns reduced to directories, with the GitHub handles named for them. */
    static Map<String, Set<String>> codeowners(Report report, java.util.function.Function<String, String> text) {
        Map<String, Set<String>> out = new TreeMap<>();
        for (Report.FileEntry f : report.files()) {
            String name = f.path().toLowerCase(Locale.ROOT);
            if (!name.equals("codeowners") && !name.endsWith("/codeowners")) continue;
            for (String raw : text.apply(f.path()).split("\\R")) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                Matcher m = CODEOWNERS_LINE.matcher(line);
                if (!m.matches()) continue;
                Set<String> handles = new TreeSet<>();
                Matcher h = OWNER_HANDLE.matcher(m.group(2));
                while (h.find()) handles.add(h.group(1).toLowerCase(Locale.ROOT));
                if (handles.isEmpty()) continue;
                String dir = patternToDirectory(m.group(1));
                if (dir != null) out.computeIfAbsent(dir, k -> new TreeSet<>()).addAll(handles);
            }
        }
        return out;
    }

    /** "/src/flask/" -> "src/flask"; "docs/*" -> "docs"; "*" -> "."; a file pattern -> its directory. */
    static String patternToDirectory(String pattern) {
        String p = pattern.trim();
        if (p.equals("*") || p.equals("/") || p.equals("**")) return ".";
        if (p.startsWith("/")) p = p.substring(1);
        if (p.contains("*") || p.contains("?")) {
            int slash = p.indexOf('*') < 0 ? p.length() : p.lastIndexOf('/', p.indexOf('*'));
            p = slash <= 0 ? "." : p.substring(0, slash);
        }
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.isEmpty()) return ".";
        // A pattern naming one file stands for its directory.
        if (p.matches(".*\\.[A-Za-z0-9]+$")) {
            int slash = p.lastIndexOf('/');
            p = slash < 0 ? "." : p.substring(0, slash);
        }
        return p.isEmpty() ? "." : p;
    }

    /**
     * Whether a person could be the GitHub handle: handle equals an email local part, appears in a
     * GitHub noreply address, or matches the display name with spaces and punctuation removed.
     */
    static boolean matchesHandle(Report.Author person, String handle) {
        String h = handle.contains("/") ? handle.substring(handle.indexOf('/') + 1) : handle; // team -> its name
        String flat = h.toLowerCase(Locale.ROOT).replace("-", "");
        for (String email : person.emails()) {
            String local = email.substring(0, Math.max(0, email.indexOf('@'))).toLowerCase(Locale.ROOT);
            if (local.equals(h) || local.replace("-", "").equals(flat)) return true;
            if (email.endsWith("users.noreply.github.com")) {
                String withoutId = local.contains("+") ? local.substring(local.indexOf('+') + 1) : local;
                if (withoutId.equals(h)) return true;
            }
        }
        String name = person.name() == null ? "" : person.name().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return !name.isEmpty() && name.equals(flat);
    }

    /** Directories in the report that a CODEOWNERS entry covers, longest (most specific) entry wins. */
    static Map<String, Set<String>> ownersForDirectories(Map<String, Set<String>> codeowners,
                                                         List<Report.Ownership> directories) {
        Map<String, Set<String>> out = new TreeMap<>();
        List<String> patterns = new ArrayList<>(codeowners.keySet());
        patterns.sort(Comparator.comparingInt(String::length).reversed());
        for (Report.Ownership dir : directories) {
            for (String pattern : patterns) {
                if (pattern.equals(".") || dir.path().equals(pattern) || dir.path().startsWith(pattern + "/")) {
                    out.put(dir.path(), new HashSet<>(codeowners.get(pattern)));
                    break;
                }
            }
        }
        return out;
    }
}
