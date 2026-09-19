package io.github.shrishaanth.codeatlas.testutil;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

/** Builds small real git repositories on disk for tests, with controlled authors and dates. */
public final class TestRepo implements AutoCloseable {

    private final Git git;
    private final Path dir;
    private Instant clock = Instant.parse("2024-01-01T00:00:00Z");

    private TestRepo(Git git, Path dir) {
        this.git = git;
        this.dir = dir;
    }

    public static TestRepo create(Path dir) throws Exception {
        return new TestRepo(Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call(), dir);
    }

    public Path dir() {
        return dir;
    }

    public Git git() {
        return git;
    }

    /** Writes the given files (path to content; null content deletes) and commits them as one commit. */
    public RevCommit commit(String authorEmail, String message, Map<String, String> files) throws Exception {
        for (var e : files.entrySet()) {
            Path p = dir.resolve(e.getKey());
            if (e.getValue() == null) {
                git.rm().addFilepattern(e.getKey()).call();
            } else {
                Files.createDirectories(p.getParent());
                Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
                git.add().addFilepattern(e.getKey()).call();
            }
        }
        clock = clock.plusSeconds(3600);
        PersonIdent who = new PersonIdent(authorEmail.substring(0, authorEmail.indexOf('@')), authorEmail,
                clock, ZoneOffset.UTC);
        return git.commit().setMessage(message).setAuthor(who).setCommitter(who).call();
    }

    /** Renames a file with git mv semantics (content unchanged) and commits. */
    public RevCommit rename(String authorEmail, String from, String to) throws Exception {
        Path src = dir.resolve(from);
        Path dst = dir.resolve(to);
        Files.createDirectories(dst.getParent());
        Files.move(src, dst);
        git.rm().addFilepattern(from).call();
        git.add().addFilepattern(to).call();
        clock = clock.plusSeconds(3600);
        PersonIdent who = new PersonIdent(authorEmail.substring(0, authorEmail.indexOf('@')), authorEmail,
                clock, ZoneOffset.UTC);
        return git.commit().setMessage("rename " + from).setAuthor(who).setCommitter(who).call();
    }

    @Override
    public void close() {
        git.close();
    }
}
