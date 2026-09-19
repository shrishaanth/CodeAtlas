package io.github.shrishaanth.codeatlas.analyze;

import io.github.shrishaanth.codeatlas.report.Report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ranks files by how much the rest of the code depends on them and how actively they change.
 * The formula and its initial weights are defined in docs/metrics.md, "Reading order (v1)".
 */
public final class ReadingOrder {

    public static final double W_CENTRALITY = 0.5;
    public static final double W_FAN_IN = 0.3;
    public static final double W_CHURN = 0.2;
    public static final int MIN_NON_BLANK_LINES = 5;

    private ReadingOrder() {
    }

    /**
     * @param graph      import graph over non-test files
     * @param candidates files eligible for ranking (non-test Python files with enough content)
     * @param commits    commits per file (missing means 0)
     */
    public static List<Report.ReadingItem> rank(ImportGraph graph, List<String> candidates,
                                                Map<String, Integer> commits) {
        Map<String, Double> pr = graph.pageRank(0.85, 50);
        double maxPr = candidates.stream().mapToDouble(p -> pr.getOrDefault(p, 0.0)).max().orElse(0);
        int maxFanIn = candidates.stream().mapToInt(p -> graph.importersOf(p).size()).max().orElse(0);
        int maxCommits = candidates.stream().mapToInt(p -> commits.getOrDefault(p, 0)).max().orElse(0);

        record Scored(String path, double score, double centrality, double fanIn, double churn,
                      int rawFanIn, int rawCommits) {
        }
        List<Scored> scored = new ArrayList<>();
        for (String p : candidates) {
            int fanIn = graph.importersOf(p).size();
            int c = commits.getOrDefault(p, 0);
            double centrality = maxPr > 0 ? pr.getOrDefault(p, 0.0) / maxPr : 0;
            double fanInN = logNorm(fanIn, maxFanIn);
            double churnN = logNorm(c, maxCommits);
            double score = W_CENTRALITY * centrality + W_FAN_IN * fanInN + W_CHURN * churnN;
            scored.add(new Scored(p, score, centrality, fanInN, churnN, fanIn, c));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparing(Scored::path));

        List<Report.ReadingItem> items = new ArrayList<>();
        for (int i = 0; i < scored.size(); i++) {
            Scored s = scored.get(i);
            Map<String, Double> parts = new LinkedHashMap<>();
            parts.put("centrality", round(s.centrality));
            parts.put("fanIn", round(s.fanIn));
            parts.put("churn", round(s.churn));
            items.add(new Report.ReadingItem(i + 1, s.path, round(s.score), parts, reasons(s.rawFanIn, s.rawCommits)));
        }
        return items;
    }

    private static List<String> reasons(int fanIn, int commits) {
        List<String> r = new ArrayList<>();
        r.add(fanIn == 0 ? "Not imported by other non-test files"
                : "Imported by " + fanIn + " non-test " + (fanIn == 1 ? "file" : "files"));
        r.add(commits == 0 ? "No commits recorded" : "Changed in " + commits + (commits == 1 ? " commit" : " commits"));
        return r;
    }

    /** log(1+x)/log(1+max): keeps a few very large values from flattening everything else to zero. */
    static double logNorm(int x, int max) {
        return max <= 0 ? 0 : Math.log1p(x) / Math.log1p(max);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
