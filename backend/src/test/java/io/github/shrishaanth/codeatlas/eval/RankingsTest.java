package io.github.shrishaanth.codeatlas.eval;

import io.github.shrishaanth.codeatlas.report.Report;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RankingsTest {

    @Test
    void percentileIsOneAtTheTopAndZeroAtTheBottom() {
        List<String> ranking = List.of("a", "b", "c", "d", "e");

        assertThat(Rankings.meanPercentile(ranking, Set.of("a"))).isEqualTo(1.0);
        assertThat(Rankings.meanPercentile(ranking, Set.of("e"))).isEqualTo(0.0);
        assertThat(Rankings.meanPercentile(ranking, Set.of("c"))).isEqualTo(0.5);
        assertThat(Rankings.meanPercentile(ranking, Set.of("a", "e"))).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void percentileIgnoresGroundTruthOutsideTheRankingAndIsNullWithoutAny() {
        List<String> ranking = List.of("a", "b", "c");

        assertThat(Rankings.meanPercentile(ranking, Set.of("a", "not-ranked"))).isEqualTo(1.0);
        assertThat(Rankings.meanPercentile(ranking, Set.of("not-ranked"))).isNull();
        assertThat(Rankings.meanPercentile(List.of("only"), Set.of("only"))).isNull();
    }

    @Test
    void countsGroundTruthFilesInTheTopK() {
        List<String> ranking = List.of("a", "b", "c", "d");

        assertThat(Rankings.hitsInTop(ranking, Set.of("a", "d"), 2)).isEqualTo(1);
        assertThat(Rankings.hitsInTop(ranking, Set.of("a", "b"), 2)).isEqualTo(2);
    }

    @Test
    void buildsBaselineRankingsOverTheSameCandidates() {
        Report report = new Report("0.1", null, null, null,
                List.of(file("big.py", 500, 2), file("central.py", 50, 40), file("busy.py", 80, 9)),
                List.of(), List.of(
                        new Report.Edge("busy.py", "central.py", "import", 1),
                        new Report.Edge("big.py", "central.py", "import", 1)),
                List.of(item(1, "central.py"), item(2, "busy.py"), item(3, "big.py")), null, null, null, null);

        Map<String, List<String>> rankings = Rankings.all(report);

        assertThat(rankings.keySet()).containsExactly("codeatlas", "size", "fan-in", "commits", "random");
        assertThat(rankings.get("codeatlas")).containsExactly("central.py", "busy.py", "big.py");
        assertThat(rankings.get("size")).containsExactly("big.py", "busy.py", "central.py");
        assertThat(rankings.get("fan-in")).containsExactly("central.py", "big.py", "busy.py");
        assertThat(rankings.get("commits")).containsExactly("central.py", "busy.py", "big.py");
        assertThat(rankings.get("random")).containsExactlyInAnyOrder("central.py", "busy.py", "big.py");
    }

    @Test
    void medianHandlesEvenAndOddCounts() {
        assertThat(Summary.median(List.of(0.1, 0.5, 0.9))).isEqualTo(0.5);
        assertThat(Summary.median(List.of(0.2, 0.4))).isCloseTo(0.3, within(1e-9));
        assertThat(Summary.median(List.of())).isNull();
    }

    private static Report.FileEntry file(String path, int lines, int commits) {
        return new Report.FileEntry(path, "python", lines, ".", false, false, null, null,
                new Report.FileGit(commits, 1, Instant.EPOCH, Instant.EPOCH));
    }

    private static Report.ReadingItem item(int rank, String path) {
        return new Report.ReadingItem(rank, path, 1.0 / rank, Map.of(), List.of());
    }
}
