package io.github.shrishaanth.codeatlas.eval;

import io.github.shrishaanth.codeatlas.analyze.ImportGraph;
import io.github.shrishaanth.codeatlas.report.Report;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-file signals plus the ground truth for one repository, saved so reading-order variants can be
 * compared without analyzing the repositories again ({@link Tuner}).
 */
public record Features(String repo, List<FileFeatures> files, List<String> docsMentioned,
                       List<String> newcomerFiles) {

    /**
     * @param centrality        PageRank over non-test import edges, divided by the maximum
     * @param reverseCentrality the same with edges reversed: high for files that pull much of the codebase together
     * @param fanIn             non-test files importing this one
     * @param churn             non-merge commits
     * @param lines             total lines
     * @param privateModule     file name starts with an underscore (`_compat.py`), excluding dunders
     */
    public record FileFeatures(String path, double centrality, double reverseCentrality, int fanIn, int churn,
                               int lines, boolean privateModule) {
    }

    /** Collects the signals for the files the reading order ranked. */
    public static Features from(String repo, Report report, GroundTruth.Truth truth) {
        List<String> candidates = report.readingOrder().stream().map(Report.ReadingItem::path).toList();
        Map<String, Report.FileEntry> byPath = new HashMap<>();
        report.files().forEach(f -> byPath.put(f.path(), f));

        ImportGraph forward = new ImportGraph();
        ImportGraph backward = new ImportGraph();
        Set<String> nonTest = new java.util.HashSet<>();
        report.files().forEach(f -> {
            if (!f.isTest() && "python".equals(f.language())) nonTest.add(f.path());
        });
        nonTest.forEach(p -> {
            forward.addNode(p);
            backward.addNode(p);
        });
        Map<String, Integer> fanIn = new HashMap<>();
        for (Report.Edge e : report.edges()) {
            if (!e.kind().equals("import") || !nonTest.contains(e.source()) || !nonTest.contains(e.target())) continue;
            forward.addEdge(e.source(), e.target());
            backward.addEdge(e.target(), e.source());
            fanIn.merge(e.target(), 1, Integer::sum);
        }
        Map<String, Double> pr = forward.pageRank(0.85, 50);
        Map<String, Double> reverse = backward.pageRank(0.85, 50);
        double maxPr = candidates.stream().mapToDouble(p -> pr.getOrDefault(p, 0.0)).max().orElse(1);
        double maxReverse = candidates.stream().mapToDouble(p -> reverse.getOrDefault(p, 0.0)).max().orElse(1);

        List<FileFeatures> files = new ArrayList<>();
        for (String path : candidates) {
            Report.FileEntry f = byPath.get(path);
            String name = path.substring(path.lastIndexOf('/') + 1);
            files.add(new FileFeatures(path,
                    maxPr > 0 ? pr.getOrDefault(path, 0.0) / maxPr : 0,
                    maxReverse > 0 ? reverse.getOrDefault(path, 0.0) / maxReverse : 0,
                    fanIn.getOrDefault(path, 0),
                    f.git() == null ? 0 : f.git().commits(),
                    f.lines(),
                    name.startsWith("_") && !name.startsWith("__")));
        }
        return new Features(repo, files, List.copyOf(truth.docsMentioned()), truth.newcomerFiles());
    }
}
