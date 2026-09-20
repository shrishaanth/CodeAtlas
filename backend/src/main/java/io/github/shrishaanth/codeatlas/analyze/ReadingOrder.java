package io.github.shrishaanth.codeatlas.analyze;

import io.github.shrishaanth.codeatlas.report.Report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ranks files by what a newcomer should read first. Version 2: the weights and the choice of signals
 * come from the evaluation in docs/evaluation.md, which measured version 1 against independent
 * sources and found its PageRank centrality term contributed nothing.
 * The formula is specified in docs/metrics.md, "Reading order (v2)".
 */
public final class ReadingOrder {

    public static final double W_CHURN = 0.55;
    public static final double W_REACH = 0.30;
    public static final double W_FAN_IN = 0.15;
    /** Private modules (_compat.py) are implementation details; they ranked too high in v1. */
    public static final double PRIVATE_PENALTY = 0.20;
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
        // Reach: PageRank with edges reversed, so a file scores high when it pulls in much of the
        // codebase (an entry point), rather than when much of the codebase depends on it (a utility).
        ImportGraph reversed = new ImportGraph();
        graph.nodes().forEach(reversed::addNode);
        graph.nodes().forEach(from -> graph.importsOf(from).forEach(to -> reversed.addEdge(to, from)));
        Map<String, Double> reach = reversed.pageRank(0.85, 50);

        double maxReach = candidates.stream().mapToDouble(p -> reach.getOrDefault(p, 0.0)).max().orElse(0);
        int maxFanIn = candidates.stream().mapToInt(p -> graph.importersOf(p).size()).max().orElse(0);
        int maxCommits = candidates.stream().mapToInt(p -> commits.getOrDefault(p, 0)).max().orElse(0);

        record Scored(String path, double score, double churn, double reach, double fanIn,
                      boolean privateModule, int rawFanIn, int rawCommits) {
        }
        List<Scored> scored = new ArrayList<>();
        for (String p : candidates) {
            int fanIn = graph.importersOf(p).size();
            int c = commits.getOrDefault(p, 0);
            double churnN = logNorm(c, maxCommits);
            double reachN = maxReach > 0 ? reach.getOrDefault(p, 0.0) / maxReach : 0;
            double fanInN = logNorm(fanIn, maxFanIn);
            boolean privateModule = isPrivateModule(p);
            double score = W_CHURN * churnN + W_REACH * reachN + W_FAN_IN * fanInN
                    - (privateModule ? PRIVATE_PENALTY : 0);
            scored.add(new Scored(p, score, churnN, reachN, fanInN, privateModule, fanIn, c));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparing(Scored::path));

        List<Report.ReadingItem> items = new ArrayList<>();
        for (int i = 0; i < scored.size(); i++) {
            Scored s = scored.get(i);
            Map<String, Double> parts = new LinkedHashMap<>();
            parts.put("churn", round(s.churn()));
            parts.put("reach", round(s.reach()));
            parts.put("fanIn", round(s.fanIn()));
            items.add(new Report.ReadingItem(i + 1, s.path(), round(s.score()), parts,
                    reasons(s.rawFanIn(), s.rawCommits(), s.reach(), s.privateModule())));
        }
        return items;
    }

    /** A module whose name starts with one underscore: private by convention, but not a dunder. */
    static boolean isPrivateModule(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.startsWith("_") && !name.startsWith("__");
    }

    private static List<String> reasons(int fanIn, int commits, double reach, boolean privateModule) {
        List<String> r = new ArrayList<>();
        r.add(commits == 0 ? "No commits recorded" : "Changed in " + commits + (commits == 1 ? " commit" : " commits"));
        r.add(fanIn == 0 ? "Not imported by other non-test files"
                : "Imported by " + fanIn + " non-test " + (fanIn == 1 ? "file" : "files"));
        if (reach >= 0.5) r.add("Pulls in much of the codebase, directly or indirectly");
        if (privateModule) r.add("Private module, ranked lower");
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
