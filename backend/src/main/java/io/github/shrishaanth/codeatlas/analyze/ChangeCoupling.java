package io.github.shrishaanth.codeatlas.analyze;

import io.github.shrishaanth.codeatlas.gitmine.GitHistory;
import io.github.shrishaanth.codeatlas.report.Report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds files and areas that change in the same commits. Definitions and thresholds are in
 * docs/metrics.md, "Change coupling".
 */
public final class ChangeCoupling {

    public static final int MAX_FILES_PER_COMMIT = 30;
    public static final int MIN_FILE_COMMITS = 5;
    public static final int MIN_TOGETHER = 5;
    public static final double MIN_DEGREE = 0.3;
    public static final int MAX_FILE_PAIRS = 300;
    public static final int MIN_AREA_TOGETHER = 3;
    public static final int MAX_AREA_PAIRS = 50;

    private static final Set<String> CONTAINERS = Set.of(
            "src", "lib", "libs", "packages", "services", "apps", "modules", "components");

    private ChangeCoupling() {
    }

    /**
     * @param imports import graph; its nodes are the files whose imports are known, so pairs involving
     *                any other file get an unknown ({@code null}) import edge rather than a false "no"
     */
    public static Report.Coupling compute(List<GitHistory.Commit> commits, ImportGraph imports) {
        List<GitHistory.Commit> considered = commits.stream()
                .filter(c -> c.filesChanged() <= MAX_FILES_PER_COMMIT)
                .toList();

        // Per-file counts over the same commits as the pairs, so large skipped commits affect neither.
        Map<String, Integer> fileCommits = new HashMap<>();
        Map<String, Integer> areaCommits = new HashMap<>();
        for (GitHistory.Commit c : considered) {
            c.paths().forEach(p -> fileCommits.merge(p, 1, Integer::sum));
            areasOf(c).forEach(a -> areaCommits.merge(a, 1, Integer::sum));
        }

        Map<Pair, Integer> filePairs = new HashMap<>();
        Map<Pair, Integer> areaPairs = new HashMap<>();
        for (GitHistory.Commit c : considered) {
            List<String> eligible = c.paths().stream()
                    .filter(p -> fileCommits.get(p) >= MIN_FILE_COMMITS)
                    .sorted().distinct().toList();
            countPairs(eligible, filePairs);
            countPairs(List.copyOf(areasOf(c)), areaPairs);
        }

        List<Report.FilePair> files = new ArrayList<>();
        filePairs.forEach((pair, together) -> {
            if (together < MIN_TOGETHER) return;
            int a = fileCommits.get(pair.a), b = fileCommits.get(pair.b);
            double degree = degree(together, a, b);
            if (degree < MIN_DEGREE) return;
            Boolean importEdge = !imports.nodes().contains(pair.a) || !imports.nodes().contains(pair.b) ? null
                    : imports.importsOf(pair.a).contains(pair.b) || imports.importsOf(pair.b).contains(pair.a);
            files.add(new Report.FilePair(pair.a, pair.b, together, a, b, round(degree), importEdge));
        });
        files.sort(Comparator.comparingDouble(Report.FilePair::degree).reversed()
                .thenComparing(Comparator.comparingInt(Report.FilePair::together).reversed())
                .thenComparing(Report.FilePair::a).thenComparing(Report.FilePair::b));

        List<Report.AreaPair> areas = new ArrayList<>();
        areaPairs.forEach((pair, together) -> {
            if (together < MIN_AREA_TOGETHER) return;
            int a = areaCommits.get(pair.a), b = areaCommits.get(pair.b);
            areas.add(new Report.AreaPair(pair.a, pair.b, together, a, b, round(degree(together, a, b))));
        });
        areas.sort(Comparator.comparingInt(Report.AreaPair::together).reversed()
                .thenComparing(Report.AreaPair::a).thenComparing(Report.AreaPair::b));

        return new Report.Coupling(
                List.copyOf(files.subList(0, Math.min(MAX_FILE_PAIRS, files.size()))),
                List.copyOf(areas.subList(0, Math.min(MAX_AREA_PAIRS, areas.size()))),
                commits.size() - considered.size());
    }

    /** together / average(a, b): 1 when both files only ever change together. */
    static double degree(int together, int a, int b) {
        return together / ((a + b) / 2.0);
    }

    /** Top-level area of a path; see docs/metrics.md. */
    static String area(String path) {
        String[] parts = path.split("/");
        if (parts.length == 1) return "(root)";
        if (CONTAINERS.contains(parts[0]) && parts.length > 2) return parts[0] + "/" + parts[1];
        return parts[0];
    }

    private static Set<String> areasOf(GitHistory.Commit c) {
        Set<String> out = new TreeSet<>();
        c.paths().forEach(p -> out.add(area(p)));
        return out;
    }

    private static void countPairs(List<String> sortedDistinct, Map<Pair, Integer> counts) {
        for (int i = 0; i < sortedDistinct.size(); i++) {
            for (int j = i + 1; j < sortedDistinct.size(); j++) {
                counts.merge(new Pair(sortedDistinct.get(i), sortedDistinct.get(j)), 1, Integer::sum);
            }
        }
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    private record Pair(String a, String b) {
    }
}
