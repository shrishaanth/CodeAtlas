package io.github.shrishaanth.codeatlas.analyze;

import io.github.shrishaanth.codeatlas.gitmine.GitHistory;
import io.github.shrishaanth.codeatlas.report.Report;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ChangeCouplingTest {

    private static int seq;

    private static GitHistory.Commit commit(String... paths) {
        return new GitHistory.Commit("c" + (seq++), "a@x.org", Instant.EPOCH, List.of(paths), paths.length);
    }

    private static void repeat(List<GitHistory.Commit> out, int n, String... paths) {
        IntStream.range(0, n).forEach(i -> out.add(commit(paths)));
    }

    @Test
    void findsFilesThatChangeTogetherAndMarksHiddenDependencies() {
        List<GitHistory.Commit> commits = new ArrayList<>();
        repeat(commits, 6, "backend/api.py", "frontend/api.ts");   // no import between them
        repeat(commits, 2, "backend/api.py");
        repeat(commits, 5, "src/app/models.py", "src/app/views.py");
        repeat(commits, 5, "src/app/views.py");
        repeat(commits, 20, "CHANGES.rst");
        repeat(commits, 4, "CHANGES.rst", "backend/api.py");       // coupled a little, diluted by 24 commits

        ImportGraph imports = new ImportGraph();
        imports.addEdge("src/app/views.py", "src/app/models.py");
        imports.addNode("backend/api.py"); // parsed; frontend/api.ts is not

        Report.Coupling c = ChangeCoupling.compute(commits, imports);

        assertThat(c.files()).extracting(Report.FilePair::a, Report.FilePair::b).containsExactly(
                org.assertj.core.groups.Tuple.tuple("backend/api.py", "frontend/api.ts"),
                org.assertj.core.groups.Tuple.tuple("src/app/models.py", "src/app/views.py"));
        Report.FilePair hidden = c.files().get(0);
        assertThat(hidden.together()).isEqualTo(6);
        assertThat(hidden.aCommits()).isEqualTo(12);
        assertThat(hidden.bCommits()).isEqualTo(6);
        assertThat(hidden.degree()).isEqualTo(0.667); // 6 / avg(12, 6)
        assertThat(hidden.hasImportEdge()).as("unknown: the .ts file is not parsed").isNull();
        assertThat(c.files().get(1).hasImportEdge()).isTrue();
    }

    @Test
    void reportsNoImportEdgeOnlyWhenBothFilesAreParsed() {
        List<GitHistory.Commit> commits = new ArrayList<>();
        repeat(commits, 5, "pkg/a.py", "pkg/b.py");
        ImportGraph imports = new ImportGraph();
        imports.addNode("pkg/a.py");
        imports.addNode("pkg/b.py");

        assertThat(ChangeCoupling.compute(commits, imports).files().get(0).hasImportEdge()).isFalse();
    }

    @Test
    void skipsLargeCommitsAndRarelyChangedFiles() {
        List<GitHistory.Commit> commits = new ArrayList<>();
        String[] big = IntStream.range(0, 31).mapToObj(i -> "f" + i + ".py").toArray(String[]::new);
        repeat(commits, 10, big);                        // a bulk reformat, repeated: must not couple anything
        repeat(commits, 4, "rare_a.py", "rare_b.py");     // together 4 < 5 and under 5 commits each

        Report.Coupling c = ChangeCoupling.compute(commits, new ImportGraph());

        assertThat(c.files()).isEmpty();
        assertThat(c.skippedLargeCommits()).isEqualTo(10);
    }

    @Test
    void groupsPathsIntoAreas() {
        assertThat(ChangeCoupling.area("setup.py")).isEqualTo("(root)");
        assertThat(ChangeCoupling.area("backend/app/main.py")).isEqualTo("backend");
        assertThat(ChangeCoupling.area("src/flask/app.py")).isEqualTo("src/flask");
        assertThat(ChangeCoupling.area("src/app.py")).as("container with a file directly inside").isEqualTo("src");
        assertThat(ChangeCoupling.area("services/ml/serve.py")).isEqualTo("services/ml");
    }

    @Test
    void couplesAreasTouchedInTheSameCommit() {
        List<GitHistory.Commit> commits = new ArrayList<>();
        repeat(commits, 3, "backend/a.py", "ml-service/b.py");
        repeat(commits, 2, "backend/a.py", "ml-service/c.py", "frontend/x.ts");
        repeat(commits, 2, "frontend/x.ts");

        Report.Coupling c = ChangeCoupling.compute(commits, new ImportGraph());

        assertThat(c.components()).extracting(Report.AreaPair::a, Report.AreaPair::b, Report.AreaPair::together)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("backend", "ml-service", 5));
        assertThat(c.components().get(0).degree()).isEqualTo(1.0);
    }
}
