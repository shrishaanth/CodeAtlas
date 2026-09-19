package io.github.shrishaanth.codeatlas.gitmine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Git's {@code .mailmap} format, which JGit does not read. Supported line forms (see gitmailmap(5)):
 * <pre>
 * Proper Name &lt;commit@email&gt;
 * &lt;proper@email&gt; &lt;commit@email&gt;
 * Proper Name &lt;proper@email&gt; &lt;commit@email&gt;
 * Proper Name &lt;proper@email&gt; Commit Name &lt;commit@email&gt;
 * </pre>
 */
public final class Mailmap {

    public static final Mailmap EMPTY = new Mailmap(List.of());

    /** An identity after mapping. */
    public record Identity(String name, String email) {
    }

    private record Entry(String properName, String properEmail, String commitName, String commitEmail) {
    }

    // name? <email> (name? <email>)?
    private static final Pattern LINE = Pattern.compile(
            "^\\s*([^<#]*?)\\s*<([^>]*)>\\s*(?:([^<#]*?)\\s*<([^>]*)>)?\\s*(?:#.*)?$");

    private final List<Entry> entries;

    private Mailmap(List<Entry> entries) {
        this.entries = entries;
    }

    public static Mailmap parse(String text) {
        List<Entry> entries = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            Matcher m = LINE.matcher(line);
            if (!m.matches()) continue;
            String name1 = blankToNull(m.group(1));
            String email1 = lower(m.group(2));
            if (m.group(4) == null) {
                // "Proper Name <commit@email>": only the name is replaced.
                if (name1 != null) entries.add(new Entry(name1, null, null, email1));
            } else {
                entries.add(new Entry(name1, email1.isEmpty() ? null : email1, blankToNull(m.group(3)), lower(m.group(4))));
            }
        }
        return new Mailmap(List.copyOf(entries));
    }

    /** Maps a commit identity; entries that also match the commit name win over email-only ones. */
    public Identity map(String name, String email) {
        String e = lower(email);
        Entry best = null;
        for (Entry entry : entries) {
            if (!entry.commitEmail.equals(e)) continue;
            if (entry.commitName != null) {
                if (entry.commitName.equalsIgnoreCase(name)) {
                    best = entry;
                    break;
                }
            } else if (best == null) {
                best = entry;
            }
        }
        if (best == null) return new Identity(name, e);
        return new Identity(best.properName != null ? best.properName : name,
                best.properEmail != null ? best.properEmail : e);
    }

    /** The {@code .mailmap} committed at the given commit, or {@link #EMPTY}. */
    public static Mailmap fromCommit(org.eclipse.jgit.lib.Repository repo, org.eclipse.jgit.revwalk.RevCommit commit)
            throws java.io.IOException {
        try (var tw = org.eclipse.jgit.treewalk.TreeWalk.forPath(repo, ".mailmap", commit.getTree())) {
            if (tw == null) return EMPTY;
            return parse(new String(repo.open(tw.getObjectId(0)).getBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String lower(String s) {
        return s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
    }
}
