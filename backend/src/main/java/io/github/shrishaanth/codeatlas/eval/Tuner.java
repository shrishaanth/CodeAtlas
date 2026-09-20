package io.github.shrishaanth.codeatlas.eval;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.CollectionType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Searches for a better reading-order formula, honestly: variants are compared on half the
 * repositories (training), and the winner is then measured on the half never used for the search
 * (held-out). A variant is only worth adopting if it also wins there.
 * <pre>
 * java -cp &lt;classpath&gt; io.github.shrishaanth.codeatlas.eval.Tuner eval/features.json
 * </pre>
 */
public final class Tuner {

    /** Repositories alternate between training and held-out by position, so both halves mix sizes and owners. */
    static boolean isTraining(int index) {
        return index % 2 == 0;
    }

    /** A weighted combination of normalized signals, optionally penalising private modules. */
    record Variant(String name, double centrality, double reverseCentrality, double fanIn, double churn,
                   double size, double privatePenalty) {

        List<String> rank(Features f) {
            int maxFanIn = f.files().stream().mapToInt(Features.FileFeatures::fanIn).max().orElse(0);
            int maxChurn = f.files().stream().mapToInt(Features.FileFeatures::churn).max().orElse(0);
            int maxLines = f.files().stream().mapToInt(Features.FileFeatures::lines).max().orElse(0);
            record Scored(String path, double score) {
            }
            return f.files().stream()
                    .map(x -> new Scored(x.path(),
                            centrality * x.centrality()
                                    + reverseCentrality * x.reverseCentrality()
                                    + fanIn * logNorm(x.fanIn(), maxFanIn)
                                    + churn * logNorm(x.churn(), maxChurn)
                                    + size * logNorm(x.lines(), maxLines)
                                    - (x.privateModule() ? privatePenalty : 0)))
                    .sorted(Comparator.comparingDouble(Scored::score).reversed().thenComparing(Scored::path))
                    .map(Scored::path).toList();
        }
    }

    private Tuner() {
    }

    static double logNorm(int x, int max) {
        return max <= 0 ? 0 : Math.log1p(x) / Math.log1p(max);
    }

    public static void main(String[] args) throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();
        CollectionType type = mapper.getTypeFactory().constructCollectionType(List.class, Features.class);
        List<Features> all = mapper.readValue(Files.readString(Path.of(args[0])), type);

        List<Features> training = new ArrayList<>();
        List<Features> heldOut = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            (isTraining(i) ? training : heldOut).add(all.get(i));
        }
        System.out.printf("Training on %d repositories, holding out %d.%n%n", training.size(), heldOut.size());

        List<Variant> variants = candidates();
        List<Map.Entry<Variant, Double>> scored = new ArrayList<>();
        for (Variant v : variants) scored.add(Map.entry(v, score(v, training)));
        scored.sort(Map.Entry.<Variant, Double>comparingByValue().reversed());

        System.out.println("### Variants on the training half\n");
        System.out.println("| Variant | centrality | reverse | fan-in | churn | size | private penalty | score |");
        System.out.println("|---|---:|---:|---:|---:|---:|---:|---:|");
        for (var e : scored.subList(0, Math.min(12, scored.size()))) {
            Variant v = e.getKey();
            System.out.printf("| %s | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f | %.3f |%n", v.name(), v.centrality(),
                    v.reverseCentrality(), v.fanIn(), v.churn(), v.size(), v.privatePenalty(), e.getValue());
        }

        Variant best = scored.get(0).getKey();
        System.out.println("\n### Held-out repositories (never used to choose the formula)\n");
        System.out.println("Both measures separately, because the newcomer measure favours churn by construction:");
        System.out.println("a file can only appear in someone's first commit if it has commits.\n");
        System.out.println("| Formula | combined | docs-mentioned | newcomer commits |");
        System.out.println("|---|---:|---:|---:|");
        row("current", named("current"), heldOut);
        row("tuned (" + best.name() + ")", best, heldOut);
        row("tuned, rounded weights", named("rounded"), heldOut);
        for (String baseline : List.of("size-only", "churn-only", "fan-in-only", "centrality-only", "reverse-only")) {
            row("baseline " + baseline, named(baseline), heldOut);
        }
    }

    private static void row(String label, Variant v, List<Features> repos) {
        System.out.printf("| %s | %.3f | %.3f | %.3f |%n", label, score(v, repos),
                score(v, repos, true, false), score(v, repos, false, true));
    }

    /** Mean over repositories of the two ground-truth percentiles, ignoring repositories without them. */
    static double score(Variant v, List<Features> repos) {
        return score(v, repos, true, true);
    }

    static double score(Variant v, List<Features> repos, boolean useDocs, boolean useNewcomers) {
        List<Double> perRepo = new ArrayList<>();
        for (Features f : repos) {
            List<String> ranking = v.rank(f);
            List<Double> parts = new ArrayList<>();
            if (useDocs && f.docsMentioned().size() >= EvalCommand.MIN_DOCS_TRUTH) {
                Double docs = Rankings.meanPercentile(ranking, Set.copyOf(f.docsMentioned()));
                if (docs != null) parts.add(docs);
            }
            if (useNewcomers) {
                Double newcomers = Rankings.meanPercentile(ranking, Set.copyOf(f.newcomerFiles()));
                if (newcomers != null) parts.add(newcomers);
            }
            if (!parts.isEmpty()) perRepo.add(parts.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        }
        return perRepo.isEmpty() ? 0 : Rankings.round(perRepo.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    static Variant named(String name) {
        return candidates().stream().filter(v -> v.name().equals(name)).findFirst().orElseThrow();
    }

    /** The current formula, single signals, and a grid over the combinations worth trying. */
    static List<Variant> candidates() {
        Map<String, Variant> out = new LinkedHashMap<>();
        add(out, new Variant("current", 0.5, 0, 0.3, 0.2, 0, 0));
        // The tuned weights rounded to readable numbers, which is what would actually be shipped.
        add(out, new Variant("rounded", 0, 0.3, 0.15, 0.55, 0, 0.2));
        add(out, new Variant("size-only", 0, 0, 0, 0, 1, 0));
        add(out, new Variant("churn-only", 0, 0, 0, 1, 0, 0));
        add(out, new Variant("fan-in-only", 0, 0, 1, 0, 0, 0));
        add(out, new Variant("centrality-only", 1, 0, 0, 0, 0, 0));
        add(out, new Variant("reverse-only", 0, 1, 0, 0, 0, 0));
        double[] steps = {0, 0.25, 0.5, 0.75, 1};
        for (double c : steps) {
            for (double r : new double[]{0, 0.25, 0.5}) {
                for (double f : steps) {
                    for (double ch : steps) {
                        for (double s : steps) {
                            double sum = c + r + f + ch + s;
                            if (sum == 0) continue;
                            for (double penalty : new double[]{0, 0.2}) {
                                String name = String.format("c%.2f r%.2f f%.2f ch%.2f s%.2f p%.1f", c / sum, r / sum,
                                        f / sum, ch / sum, s / sum, penalty);
                                add(out, new Variant(name, c / sum, r / sum, f / sum, ch / sum, s / sum, penalty));
                            }
                        }
                    }
                }
            }
        }
        return List.copyOf(out.values());
    }

    private static void add(Map<String, Variant> out, Variant v) {
        out.putIfAbsent(v.name(), v);
    }
}
