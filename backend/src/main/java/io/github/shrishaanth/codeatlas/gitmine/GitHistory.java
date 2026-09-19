package io.github.shrishaanth.codeatlas.gitmine;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the commit history says about the files present at HEAD.
 *
 * @param commitsWalked   all commits walked, including merges
 * @param truncated       true if the walk stopped at the commit cap
 * @param authors         by lower-cased email
 * @param files           by current path; only files present at HEAD that some commit touched
 * @param commits         non-merge commits, newest first, with the current paths they changed
 */
public record GitHistory(int commitsWalked, boolean truncated, Instant firstCommitAt, Instant lastCommitAt,
                         Map<String, Author> authors, Map<String, FileHistory> files, List<Commit> commits) {

    public record Author(String email, String name, int commits) {
    }

    public record FileHistory(String path, int commits, Set<String> authorEmails,
                              Instant firstChangedAt, Instant lastChangedAt) {
    }

    /** @param paths current paths of files changed, after following renames; files deleted since are dropped */
    public record Commit(String id, String authorEmail, Instant time, List<String> paths, int filesChanged) {
    }
}
