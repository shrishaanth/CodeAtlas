package io.github.shrishaanth.codeatlas.fetch;

import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Lists the files tracked at a commit and measures them. Rules are in docs/metrics.md. */
public class FileInventory {

    public static final long MAX_PARSE_BYTES = 1_000_000;
    private static final int BINARY_SNIFF_BYTES = 8_000;

    public List<SourceFile> scan(Repository repo, RevCommit commit) throws IOException {
        List<SourceFile> files = new ArrayList<>();
        try (TreeWalk tw = new TreeWalk(repo); ObjectReader reader = repo.newObjectReader()) {
            tw.addTree(commit.getTree());
            tw.setRecursive(true);
            while (tw.next()) {
                FileMode mode = tw.getFileMode(0);
                // Skip submodules (gitlinks) and symlinks: neither has file content of its own.
                if (mode == FileMode.GITLINK || mode == FileMode.SYMLINK) continue;
                files.add(measure(tw.getPathString(), tw.getObjectId(0), reader));
            }
        }
        return files;
    }

    private SourceFile measure(String path, ObjectId blobId, ObjectReader reader) throws IOException {
        ObjectLoader loader = reader.open(blobId);
        long size = loader.getSize();
        String language = FileClassifier.language(path);
        boolean test = FileClassifier.isTest(path);

        if (size > MAX_PARSE_BYTES) {
            // Too big to parse; still count it, but don't load it all into memory.
            return new SourceFile(path, blobId, language, size, false, 0, 0, test, "larger than 1 MB");
        }
        byte[] bytes = loader.getBytes();
        if (looksBinary(bytes)) {
            return new SourceFile(path, blobId, language, size, true, 0, 0, test, null);
        }
        int[] counts = countLines(bytes);
        return new SourceFile(path, blobId, language, size, false, counts[0], counts[1], test, null);
    }

    public static String readText(Repository repo, ObjectId blobId) throws IOException {
        return new String(repo.open(blobId).getBytes(), StandardCharsets.UTF_8);
    }

    static boolean looksBinary(byte[] bytes) {
        int n = Math.min(bytes.length, BINARY_SNIFF_BYTES);
        for (int i = 0; i < n; i++) {
            if (bytes[i] == 0) return true;
        }
        return false;
    }

    /** Returns {total lines, non-blank lines}. A final line without a trailing newline still counts. */
    static int[] countLines(byte[] bytes) {
        int lines = 0, nonBlank = 0;
        boolean lineHasContent = false, lineStarted = false;
        for (byte b : bytes) {
            if (b == '\n') {
                lines++;
                if (lineHasContent) nonBlank++;
                lineHasContent = false;
                lineStarted = false;
            } else {
                lineStarted = true;
                if (b != ' ' && b != '\t' && b != '\r' && b != '\f') lineHasContent = true;
            }
        }
        if (lineStarted) {
            lines++;
            if (lineHasContent) nonBlank++;
        }
        return new int[]{lines, nonBlank};
    }
}
