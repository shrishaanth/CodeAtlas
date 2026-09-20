package io.github.shrishaanth.codeatlas.eval;

import io.github.shrishaanth.codeatlas.report.Report;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The reading order and the simple baselines it must beat, plus the measures used to compare them.
 * Method and caveats: docs/evaluation.md.
 */
public final class Rankings {

    public static final int TOP_K = 10;
    private static final long RANDOM_SEED = 42;

    private Rankings() {
    }

    /** Every ranking over the same candidate files, best first. */
    public static Map<String, List<String>> all(Report report) {
        List<String> candidates = report.readingOrder().stream().map(Report.ReadingItem::path).toList();
        Map<String, Report.FileEntry> byPath = new HashMap<>();
        report.files().forEach(f -> byPath.put(f.path(), f));
        Map<String, Integer> fanIn = new HashMap<>();
        for (Report.Edge e : report.edges()) {
            if (e.kind().equals("import") && !isTest(byPath.get(e.source()))) {
                fanIn.merge(e.target(), 1, Integer::sum);
            }
        }

        Map<String, List<String>> out = new LinkedHashMap<>();
        out.put("codeatlas", candidates);
        out.put("size", sorted(candidates, p -> byPath.get(p).lines()));
        out.put("fan-in", sorted(candidates, p -> fanIn.getOrDefault(p, 0)));
        out.put("commits", sorted(candidates, p -> byPath.get(p).git() == null ? 0 : byPath.get(p).git().commits()));
        List<String> shuffled = new ArrayList<>(candidates);
        java.util.Collections.shuffle(shuffled, new Random(RANDOM_SEED));
        out.put("random", List.copyOf(shuffled));
        return out;
    }

    private static boolean isTest(Report.FileEntry f) {
        return f != null && f.isTest();
    }

    private static List<String> sorted(List<String> candidates, java.util.function.ToIntFunction<String> key) {
        return candidates.stream()
                .sorted(Comparator.comparingInt(key).reversed().thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    /**
     * Mean percentile of the ground-truth files in a ranking: 1.0 means they are all at the top,
     * 0.5 is what random ordering gives, 0.0 means they are all at the bottom.
     * Returns null when the ranking has fewer than two files or no ground truth is present.
     */
    public static Double meanPercentile(List<String> ranking, Collection<String> truth) {
        if (ranking.size() < 2) return null;
        Set<String> present = new java.util.HashSet<>(truth);
        present.retainAll(new java.util.HashSet<>(ranking));
        if (present.isEmpty()) return null;
        double sum = 0;
        for (int i = 0; i < ranking.size(); i++) {
            if (present.contains(ranking.get(i))) sum += 1.0 - (double) i / (ranking.size() - 1);
        }
        return round(sum / present.size());
    }

    /** How many of the top K files are ground truth. */
    public static int hitsInTop(List<String> ranking, Collection<String> truth, int k) {
        Set<String> t = new java.util.HashSet<>(truth);
        return (int) ranking.stream().limit(k).filter(t::contains).count();
    }

    static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
