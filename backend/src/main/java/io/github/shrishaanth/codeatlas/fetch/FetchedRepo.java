package io.github.shrishaanth.codeatlas.fetch;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.util.FileUtils;

import java.io.IOException;
import java.nio.file.Path;

/**
 * An opened repository and the commit being analyzed. Closing it releases the repository and,
 * for clones, deletes the clone from disk.
 */
public record FetchedRepo(RepoSource source, Repository repository, RevCommit head, String branch,
                          Path cloneDir) implements AutoCloseable {

    @Override
    public void close() throws IOException {
        repository.close();
        if (cloneDir != null) {
            FileUtils.delete(cloneDir.toFile(), FileUtils.RECURSIVE | FileUtils.RETRY | FileUtils.SKIP_MISSING);
        }
    }
}
