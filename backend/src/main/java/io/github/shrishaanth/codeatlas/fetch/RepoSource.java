package io.github.shrishaanth.codeatlas.fetch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a repository comes from: a public GitHub URL or a local path.
 * Parsing is strict because the live server clones whatever URL a visitor submits.
 */
public sealed interface RepoSource {

    /** Human-readable repository name, e.g. {@code flask}. */
    String name();

    /** What goes into the report's {@code repo.source} field. */
    String display();

    record GitHub(String owner, String repo) implements RepoSource {
        public String cloneUrl() {
            return "https://github.com/" + owner + "/" + repo + ".git";
        }

        @Override
        public String name() {
            return repo;
        }

        @Override
        public String display() {
            return "https://github.com/" + owner + "/" + repo;
        }
    }

    record Local(Path path) implements RepoSource {
        @Override
        public String name() {
            Path fileName = path.toAbsolutePath().normalize().getFileName();
            return fileName == null ? "repository" : fileName.toString();
        }

        @Override
        public String display() {
            return "local";
        }
    }

    Pattern GITHUB_URL = Pattern.compile(
            "^https://github\\.com/([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))/([A-Za-z0-9._-]{1,100}?)(?:\\.git)?/?$");

    /**
     * @param input      a URL such as {@code https://github.com/pallets/flask} or a filesystem path
     * @param allowLocal whether filesystem paths are accepted (off on the public server)
     * @throws IllegalArgumentException if the input is not an accepted source
     */
    static RepoSource parse(String input, boolean allowLocal) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Repository is required");
        }
        String trimmed = input.trim();
        Matcher m = GITHUB_URL.matcher(trimmed);
        if (m.matches()) {
            String repo = m.group(2);
            if (repo.equals(".") || repo.equals("..")) {
                throw new IllegalArgumentException("Not a valid GitHub repository URL: " + trimmed);
            }
            return new GitHub(m.group(1), repo);
        }
        if (trimmed.contains("://") || trimmed.startsWith("git@")) {
            throw new IllegalArgumentException(
                    "Only public GitHub repositories are supported, as https://github.com/<owner>/<repo>");
        }
        if (!allowLocal) {
            throw new IllegalArgumentException(
                    "Local paths are disabled on this server. Use https://github.com/<owner>/<repo>");
        }
        Path path = Path.of(trimmed);
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Not a directory: " + trimmed);
        }
        return new Local(path);
    }
}
