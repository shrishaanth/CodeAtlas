package io.github.shrishaanth.codeatlas.eval;

import io.github.shrishaanth.codeatlas.gitmine.GitHistory;
import io.github.shrishaanth.codeatlas.report.Report;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GroundTruthTest {

    private static Report reportWith(String... paths) {
        List<Report.FileEntry> files = java.util.Arrays.stream(paths)
                .map(p -> new Report.FileEntry(p, null, 10, ".", false, false, null, null, null)).toList();
        return new Report("0.1", null, null, null, files, List.of(), List.of(), List.of(), null, null, null, null);
    }

    private static Report.Author author(String id, String name, String... emails) {
        return new Report.Author(id, name, List.of(emails), 10, false, Instant.EPOCH);
    }

    @Test
    void takesFileMentionsFromDocumentationOnly() {
        Report report = reportWith("CONTRIBUTING.md", "docs/architecture.rst", "src/flask/app.py",
                "src/flask/cli.py", "CHANGES.rst", "other.md");
        Map<String, String> text = Map.of(
                "CONTRIBUTING.md", "Start with src/flask/app.py, then read cli.py for the command line.",
                "docs/architecture.rst", "The dispatcher lives in app.py.",
                "CHANGES.rst", "Fixed a bug in src/flask/cli.py",  // changelog is not guidance
                "other.md", "see src/flask/cli.py");

        Set<String> mentioned = GroundTruth.docsMentioned(report, p -> text.getOrDefault(p, ""),
                Set.of("src/flask/app.py", "src/flask/cli.py"));

        assertThat(mentioned).containsExactly("src/flask/app.py", "src/flask/cli.py");
    }

    @Test
    void ignoresMentionsThatCouldBeSeveralFiles() {
        Report report = reportWith("README.md", "a/util.py", "b/util.py", "a/unique.py");
        Map<String, String> text = Map.of("README.md", "See util.py and unique.py");

        Set<String> mentioned = GroundTruth.docsMentioned(report, p -> text.getOrDefault(p, ""),
                Set.of("a/util.py", "b/util.py", "a/unique.py"));

        assertThat(mentioned).containsExactly("a/unique.py");
    }

    @Test
    void countsFilesFromEachPersonsFirstCommit() {
        // Newest first, as the miner returns them: the last commit of an author is their first.
        List<GitHistory.Commit> commits = List.of(
                commit("new@x.org", "core.py"),
                commit("old@x.org", "core.py", "docs.py"),
                commit("new@x.org", "start.py"),          // new@x.org started here
                commit("old@x.org", "start.py"));         // old@x.org started here

        List<String> files = GroundTruth.newcomerFiles(historyOf(commits), Set.of("core.py", "start.py", "docs.py"));

        assertThat(files).containsExactly("start.py");
    }

    @Test
    void readsCodeownersPatternsAsDirectories() {
        Report report = reportWith(".github/CODEOWNERS");
        Map<String, String> text = Map.of(".github/CODEOWNERS", """
                # comment
                *       @default-owner
                /src/flask/  @davidism @pallets/flask-team
                docs/*  @doc-writer
                setup.py @packaging-person
                """);

        Map<String, Set<String>> owners = GroundTruth.codeowners(report, p -> text.getOrDefault(p, ""));

        assertThat(owners).containsOnlyKeys(".", "src/flask", "docs");
        assertThat(owners.get("src/flask")).containsExactly("davidism", "pallets/flask-team");
        assertThat(owners.get(".")).containsExactlyInAnyOrder("default-owner", "packaging-person");
    }

    @Test
    void matchesHandlesToPeopleBySeveralRoutes() {
        assertThat(GroundTruth.matchesHandle(author("a1", "David Lord", "davidism@gmail.com"), "davidism")).isTrue();
        assertThat(GroundTruth.matchesHandle(author("a1", "Jane", "1234+janedoe@users.noreply.github.com"), "janedoe")).isTrue();
        assertThat(GroundTruth.matchesHandle(author("a1", "Jane Doe", "x@y.org"), "jane-doe")).isTrue();
        assertThat(GroundTruth.matchesHandle(author("a1", "Jane Doe", "x@y.org"), "pallets/core")).isFalse();
        assertThat(GroundTruth.matchesHandle(author("a1", "Someone", "s@y.org"), "davidism")).isFalse();
    }

    @Test
    void appliesTheMostSpecificCodeownersEntryToEachDirectory() {
        Map<String, Set<String>> codeowners = Map.of(
                ".", Set.of("everyone"), "src", Set.of("src-owner"), "src/flask/json", Set.of("json-owner"));
        List<Report.Ownership> dirs = List.of(ownership("src/flask"), ownership("src/flask/json"), ownership("docs"));

        Map<String, Set<String>> resolved = GroundTruth.ownersForDirectories(codeowners, dirs);

        assertThat(resolved.get("src/flask")).containsExactly("src-owner");
        assertThat(resolved.get("src/flask/json")).containsExactly("json-owner");
        assertThat(resolved.get("docs")).containsExactly("everyone");
    }

    private static Report.Ownership ownership(String path) {
        return new Report.Ownership(path, 100, List.of(new Report.Owner("a1", 100, 1.0)), 0, 1, 1, true, List.of());
    }

    private static GitHistory.Commit commit(String email, String... paths) {
        return new GitHistory.Commit("c", email, Instant.EPOCH, List.of(paths), paths.length);
    }

    private static GitHistory historyOf(List<GitHistory.Commit> commits) {
        return new GitHistory(commits.size(), false, Instant.EPOCH, Instant.EPOCH, Map.of(), Map.of(), commits);
    }
}
