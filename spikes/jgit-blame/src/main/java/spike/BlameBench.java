package spike;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Throwaway spike: how long does it take to
 *  (1) walk all commits and list changed files per commit (needed for change coupling), and
 *  (2) blame every .py file at HEAD, with JGit vs the git CLI (needed for ownership)?
 * Also checks that both blame methods agree on line counts per author.
 */
public class BlameBench {

    public static void main(String[] args) throws Exception {
        File repoDir = new File(args[0]);
        try (Git git = Git.open(repoDir)) {
            Repository repo = git.getRepository();
            historyWalk(repo);

            List<String> files = pyFilesAtHead(repo);
            System.out.println("files to blame: " + files.size());

            long t0 = System.nanoTime();
            Map<String, Integer> jgitLines = new HashMap<>();
            for (String f : files) {
                BlameResult br = git.blame().setFilePath(f).setFollowFileRenames(true)
                        .setTextComparator(RawTextComparator.WS_IGNORE_ALL).call();
                if (br == null) continue;
                int n = br.getResultContents().size();
                for (int i = 0; i < n; i++) {
                    jgitLines.merge(br.getSourceAuthor(i).getEmailAddress().toLowerCase(), 1, Integer::sum);
                }
            }
            double jgitSecs = (System.nanoTime() - t0) / 1e9;
            System.out.printf("JGit blame:    %.1f s%n", jgitSecs);

            t0 = System.nanoTime();
            Map<String, Integer> cliLines = new HashMap<>();
            for (String f : files) {
                Process p = new ProcessBuilder("git", "blame", "--line-porcelain", "-w", "HEAD", "--", f)
                        .directory(repoDir).redirectErrorStream(false).start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.startsWith("author-mail ")) {
                            String mail = line.substring(12).replace("<", "").replace(">", "").toLowerCase();
                            cliLines.merge(mail, 1, Integer::sum);
                        }
                    }
                }
                p.waitFor();
            }
            double cliSecs = (System.nanoTime() - t0) / 1e9;
            System.out.printf("git CLI blame: %.1f s%n", cliSecs);

            int total = jgitLines.values().stream().mapToInt(Integer::intValue).sum();
            int cliTotal = cliLines.values().stream().mapToInt(Integer::intValue).sum();
            System.out.printf("lines attributed: jgit=%d cli=%d%n", total, cliTotal);
            System.out.println("top authors (jgit vs cli):");
            jgitLines.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(5)
                    .forEach(e -> System.out.printf("  %-40s %6d %6d%n", e.getKey(), e.getValue(),
                            cliLines.getOrDefault(e.getKey(), 0)));
        }
    }

    private static void historyWalk(Repository repo) throws Exception {
        long t0 = System.nanoTime();
        int commits = 0, fileChanges = 0;
        try (RevWalk walk = new RevWalk(repo);
             DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            df.setRepository(repo);
            df.setDetectRenames(true);
            walk.markStart(walk.parseCommit(repo.resolve("HEAD")));
            for (RevCommit c : walk) {
                commits++;
                if (c.getParentCount() != 1) continue; // skip root and merge commits for coupling
                List<DiffEntry> diffs = df.scan(c.getParent(0).getTree(), c.getTree());
                fileChanges += diffs.size();
            }
        }
        System.out.printf("history walk: %d commits, %d file changes, %.1f s%n",
                commits, fileChanges, (System.nanoTime() - t0) / 1e9);
    }

    private static List<String> pyFilesAtHead(Repository repo) throws Exception {
        List<String> out = new ArrayList<>();
        try (RevWalk rw = new RevWalk(repo); TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(rw.parseCommit(repo.resolve("HEAD")).getTree());
            tw.setRecursive(true);
            while (tw.next()) {
                if (tw.getPathString().endsWith(".py")) out.add(tw.getPathString());
            }
        }
        return out;
    }
}
