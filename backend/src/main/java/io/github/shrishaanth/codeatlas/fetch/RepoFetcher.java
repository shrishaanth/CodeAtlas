package io.github.shrishaanth.codeatlas.fetch;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Opens a local repository or clones a GitHub one.
 * Clones are bare: the analysis reads file contents from git objects at HEAD, so no working
 * tree is needed. That roughly halves disk use and guarantees only committed files are seen.
 */
public class RepoFetcher {

    private final Path workDir;
    private final int timeoutSeconds;

    public RepoFetcher(Path workDir, int timeoutSeconds) {
        this.workDir = workDir;
        this.timeoutSeconds = timeoutSeconds;
    }

    public FetchedRepo fetch(RepoSource source) throws IOException {
        if (source instanceof RepoSource.GitHub gh) return cloneGitHub(gh);
        if (source instanceof RepoSource.Local local) return openLocal(local);
        throw new IllegalArgumentException("Unsupported source: " + source);
    }

    private FetchedRepo cloneGitHub(RepoSource.GitHub gh) throws IOException {
        Files.createDirectories(workDir);
        Path dir = Files.createTempDirectory(workDir, gh.repo() + "-");
        try {
            Git git = Git.cloneRepository()
                    .setURI(gh.cloneUrl())
                    .setDirectory(dir.toFile())
                    .setBare(true)
                    .setTimeout(timeoutSeconds)
                    .call();
            return open(gh, git.getRepository(), dir);
        } catch (GitAPIException | RuntimeException e) {
            deleteQuietly(dir);
            throw new IOException("Could not clone " + gh.display() + ": " + e.getMessage(), e);
        } catch (IOException e) {
            deleteQuietly(dir);
            throw e;
        }
    }

    private FetchedRepo openLocal(RepoSource.Local local) throws IOException {
        Repository repo = new FileRepositoryBuilder()
                .findGitDir(local.path().toFile())
                .setMustExist(true)
                .build();
        return open(local, repo, null);
    }

    private static FetchedRepo open(RepoSource source, Repository repo, Path cloneDir) throws IOException {
        ObjectId headId = repo.resolve(Constants.HEAD);
        if (headId == null) {
            repo.close();
            throw new IOException("Repository has no commits");
        }
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit head = walk.parseCommit(headId);
            return new FetchedRepo(source, repo, head, repo.getBranch(), cloneDir);
        }
    }

    private static void deleteQuietly(Path dir) {
        try {
            FileUtils.delete(dir.toFile(), FileUtils.RECURSIVE | FileUtils.SKIP_MISSING);
        } catch (IOException ignored) {
            // Best effort: a leftover temp directory is not worth masking the real error.
        }
    }
}
