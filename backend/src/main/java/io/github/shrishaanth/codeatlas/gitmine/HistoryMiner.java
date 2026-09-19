package io.github.shrishaanth.codeatlas.gitmine;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntConsumer;

/**
 * Walks the commit history once and collects per-file and per-author facts.
 * Rules (merge commits, renames, caps) are in docs/metrics.md, "Git history".
 */
public class HistoryMiner {

    private final int maxCommits;

    public HistoryMiner(int maxCommits) {
        this.maxCommits = maxCommits;
    }

    /**
     * @param currentPaths files present at HEAD; history is attributed to these paths
     * @param progress     called with the number of commits processed so far (every 500 commits)
     */
    public GitHistory mine(Repository repo, RevCommit head, Collection<String> currentPaths, IntConsumer progress)
            throws IOException {
        // Historical path -> path at HEAD. Grows as renames are discovered walking backwards.
        Map<String, String> toCurrent = new HashMap<>();
        for (String p : currentPaths) toCurrent.put(p, p);

        Map<String, FileAcc> files = new HashMap<>();
        Map<String, AuthorAcc> authors = new HashMap<>();
        List<GitHistory.Commit> commits = new ArrayList<>();
        int walked = 0;
        boolean truncated = false;
        Instant first = null, last = null;

        try (RevWalk walk = new RevWalk(repo);
             DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            df.setRepository(repo);
            df.setDetectRenames(true);
            // Children before parents, so a rename is always seen before the older commits it affects.
            walk.sort(RevSort.TOPO, true);
            walk.sort(RevSort.COMMIT_TIME_DESC, true);
            walk.markStart(walk.parseCommit(head));

            for (RevCommit c : walk) {
                if (walked == maxCommits) {
                    truncated = true;
                    break;
                }
                walked++;
                if (walked % 500 == 0 && progress != null) progress.accept(walked);

                PersonIdent author = c.getAuthorIdent();
                Instant when = author.getWhenAsInstant();
                if (first == null || when.isBefore(first)) first = when;
                if (last == null || when.isAfter(last)) last = when;
                String email = author.getEmailAddress().toLowerCase(Locale.ROOT);
                authors.computeIfAbsent(email, e -> new AuthorAcc(email, author.getName())).commits++;

                // Merge commits repeat changes already counted in the merged commits.
                if (c.getParentCount() > 1) continue;

                List<DiffEntry> diffs = df.scan(c.getParentCount() == 0 ? null : c.getParent(0).getTree(), c.getTree());
                Set<String> touched = new LinkedHashSet<>();
                for (DiffEntry d : diffs) {
                    String current = switch (d.getChangeType()) {
                        case DELETE -> toCurrent.get(d.getOldPath());
                        case RENAME -> {
                            String cur = toCurrent.get(d.getNewPath());
                            // Older commits touched the file under its old name.
                            if (cur != null) toCurrent.putIfAbsent(d.getOldPath(), cur);
                            yield cur;
                        }
                        default -> toCurrent.get(d.getNewPath());
                    };
                    if (current != null) touched.add(current);
                }
                for (String path : touched) {
                    files.computeIfAbsent(path, FileAcc::new).add(email, when);
                }
                commits.add(new GitHistory.Commit(c.getName(), email, when, List.copyOf(touched), diffs.size()));
            }
        }
        if (progress != null) progress.accept(walked);

        Map<String, GitHistory.FileHistory> fileHistories = new HashMap<>();
        files.forEach((p, acc) -> fileHistories.put(p, acc.toHistory()));
        Map<String, GitHistory.Author> authorMap = new HashMap<>();
        authors.forEach((e, acc) -> authorMap.put(e, new GitHistory.Author(e, acc.name, acc.commits)));
        return new GitHistory(walked, truncated, first, last, Map.copyOf(authorMap), Map.copyOf(fileHistories),
                List.copyOf(commits));
    }

    private static final class FileAcc {
        final String path;
        int commits;
        final Set<String> authors = new TreeSet<>();
        Instant first, last;

        FileAcc(String path) {
            this.path = path;
        }

        void add(String email, Instant when) {
            commits++;
            authors.add(email);
            if (first == null || when.isBefore(first)) first = when;
            if (last == null || when.isAfter(last)) last = when;
        }

        GitHistory.FileHistory toHistory() {
            return new GitHistory.FileHistory(path, commits, Set.copyOf(authors), first, last);
        }
    }

    private static final class AuthorAcc {
        final String email;
        final String name; // most recent name, since commits are walked newest first
        int commits;

        AuthorAcc(String email, String name) {
            this.email = email;
            this.name = name;
        }
    }
}
