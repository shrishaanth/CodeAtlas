package io.github.shrishaanth.codeatlas.gitmine;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * Blames files at HEAD to find who last changed each current line.
 * Rules are in docs/metrics.md, "Ownership": whitespace-only changes ignored, renames followed,
 * commits in {@code .git-blame-ignore-revs} skipped.
 */
public class BlameMiner {

    public static final String IGNORE_REVS_FILE = ".git-blame-ignore-revs";

    /**
     * @param lines      path to (lower-cased author email to line count)
     * @param identities authors seen in blame, so blame-only emails (e.g. beyond a history cap) can be named
     * @param failed     paths whose blame threw, with the error message
     */
    public record Result(Map<String, Map<String, Integer>> lines, Map<String, GitHistory.Author> identities,
                         List<String> ignoredRevisions, Map<String, String> failed) {
    }

    private final int threads;

    public BlameMiner(int threads) {
        this.threads = Math.max(1, threads);
    }

    /** @param progress called with the number of files blamed so far */
    public Result mine(Repository repo, RevCommit head, List<String> paths, IntConsumer progress)
            throws IOException, InterruptedException {
        List<ObjectId> ignore = ignoredRevisions(repo, head);
        Map<String, Map<String, Integer>> lines = new ConcurrentHashMap<>();
        Map<String, GitHistory.Author> identities = new ConcurrentHashMap<>();
        Map<String, String> failed = new ConcurrentHashMap<>();
        AtomicInteger done = new AtomicInteger();

        // JGit's Repository is safe for concurrent reads; each task gets its own Git/blame state.
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "blame");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (String path : paths) {
                futures.add(pool.submit(() -> {
                    try {
                        blame(repo, head, path, ignore, lines, identities);
                    } catch (Exception e) {
                        failed.put(path, String.valueOf(e.getMessage()));
                    }
                    int n = done.incrementAndGet();
                    if (progress != null && (n % 25 == 0 || n == paths.size())) progress.accept(n);
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new IOException(e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return new Result(Map.copyOf(lines), Map.copyOf(identities),
                ignore.stream().map(ObjectId::getName).toList(), Map.copyOf(failed));
    }

    private static void blame(Repository repo, RevCommit head, String path, List<ObjectId> ignore,
                              Map<String, Map<String, Integer>> out, Map<String, GitHistory.Author> identities)
            throws Exception {
        BlameResult br = new Git(repo).blame()
                .setStartCommit(head)
                .setFilePath(path)
                .setFollowFileRenames(true)
                .setTextComparator(RawTextComparator.WS_IGNORE_ALL)
                .setIgnoreRevs(ignore)
                .call();
        if (br == null) return;
        Map<String, Integer> counts = new HashMap<>();
        int n = br.getResultContents().size();
        for (int i = 0; i < n; i++) {
            PersonIdent who = br.getSourceAuthor(i);
            if (who == null) continue;
            String email = who.getEmailAddress().toLowerCase(Locale.ROOT);
            counts.merge(email, 1, Integer::sum);
            Instant when = who.getWhenAsInstant();
            identities.merge(email, new GitHistory.Author(email, who.getName(), java.util.Set.of(who.getName()), 0, when),
                    (a, b) -> a.lastCommitAt().isAfter(b.lastCommitAt()) ? a : b);
        }
        out.put(path, Map.copyOf(counts));
    }

    /** Commits listed in .git-blame-ignore-revs at HEAD that exist in the repository. */
    static List<ObjectId> ignoredRevisions(Repository repo, RevCommit head) throws IOException {
        String text;
        try (TreeWalk tw = TreeWalk.forPath(repo, IGNORE_REVS_FILE, head.getTree())) {
            if (tw == null) return List.of();
            text = new String(repo.open(tw.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
        }
        Map<String, ObjectId> found = new LinkedHashMap<>();
        try (RevWalk rw = new RevWalk(repo)) {
            for (String raw : text.split("\\R")) {
                String line = raw.replaceAll("#.*", "").strip();
                if (!line.matches("[0-9a-fA-F]{40}")) continue;
                try {
                    RevCommit c = rw.parseCommit(ObjectId.fromString(line));
                    found.put(c.getName(), c.getId());
                } catch (IOException missing) {
                    // Listed but not in this clone (e.g. from a rewritten branch): nothing to ignore.
                }
            }
        }
        return List.copyOf(found.values());
    }
}
