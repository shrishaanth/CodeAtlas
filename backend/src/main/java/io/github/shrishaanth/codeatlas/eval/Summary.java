package io.github.shrishaanth.codeatlas.eval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Turns an evaluation run into the tables that go into docs/evaluation.md. */
public final class Summary {

    static final List<String> RANKINGS = List.of("codeatlas", "size", "fan-in", "commits", "random");

    private Summary() {
    }

    public static String markdown(EvalResult result) {
        List<EvalResult.RepoResult> ok = result.repos().stream().filter(r -> r.error() == null).toList();
        List<EvalResult.RepoResult> failed = result.repos().stream().filter(r -> r.error() != null).toList();
        StringBuilder sb = new StringBuilder();

        sb.append("### Reading order vs the project's own documentation\n\n");
        sb.append("Mean percentile of documentation-mentioned files (1.0 = all at the top, 0.5 = random).\n\n");
        sb.append(compareTable(ok, r -> r.docsPercentile(), "docsMentioned", r -> r.docsMentioned()));

        sb.append("\n### Reading order vs where newcomers started\n\n");
        sb.append("Mean percentile of files people touched in their first commit.\n\n");
        sb.append(compareTable(ok, r -> r.newcomerPercentile(), "files", r -> r.newcomerFiles()));

        sb.append("\n### Ownership vs CODEOWNERS\n\n");
        List<EvalResult.RepoResult> withOwners = ok.stream().filter(r -> r.ownership() != null).toList();
        if (withOwners.isEmpty()) {
            sb.append("No repository in the set has a CODEOWNERS file.\n");
        } else {
            sb.append("| Repo | Directories covered | Linked to a git identity | Top owner matches | Top 3 contains a match |\n");
            sb.append("|---|---:|---:|---:|---:|\n");
            int c = 0, l = 0, t = 0, t3 = 0;
            for (EvalResult.RepoResult r : withOwners) {
                EvalResult.Ownership o = r.ownership();
                sb.append(String.format("| %s | %d | %d | %d | %d |%n", r.repo(), o.directoriesWithOwners(),
                        o.linkedDirectories(), o.topOwnerMatches(), o.topThreeMatches()));
                c += o.directoriesWithOwners();
                l += o.linkedDirectories();
                t += o.topOwnerMatches();
                t3 += o.topThreeMatches();
            }
            sb.append(String.format("| **total** | %d | %d | %d (%s) | %d (%s) |%n", c, l, t, percent(t, l),
                    t3, percent(t3, l)));
        }

        sb.append("\n### Repositories\n\n");
        sb.append("| Repo | Commits | People | Python files | Ranked | Findings | Analysis |\n|---|---:|---:|---:|---:|---:|---:|\n");
        for (EvalResult.RepoResult r : ok) {
            sb.append(String.format("| %s | %s | %s | %s | %s | %s | %.1f s |%n", r.repo(), n(r.commits()),
                    n(r.people()), n(r.pythonFiles()), n(r.candidates()), n(r.findings()),
                    r.analysisMillis() / 1000.0));
        }
        for (EvalResult.RepoResult r : failed) {
            sb.append(String.format("| %s | failed: %s | | | | | |%n", r.repo(), r.error()));
        }
        return sb.toString();
    }

    /** One row per repository plus a median row, with a column per ranking. */
    private static String compareTable(List<EvalResult.RepoResult> repos,
                                       Function<EvalResult.RepoResult, Map<String, Double>> values,
                                       String countLabel, Function<EvalResult.RepoResult, Integer> count) {
        List<EvalResult.RepoResult> rows = repos.stream().filter(r -> values.apply(r) != null).toList();
        if (rows.isEmpty()) return "No repository had enough ground truth for this comparison.\n";

        StringBuilder sb = new StringBuilder("| Repo | " + countLabel + " | "
                + String.join(" | ", RANKINGS) + " |\n|---|---:|" + "---:|".repeat(RANKINGS.size()) + "\n");
        Map<String, List<Double>> byRanking = new LinkedHashMap<>();
        for (EvalResult.RepoResult r : rows) {
            sb.append("| ").append(r.repo()).append(" | ").append(n(count.apply(r)));
            for (String ranking : RANKINGS) {
                Double v = values.apply(r).get(ranking);
                sb.append(" | ").append(v == null ? "" : String.format("%.2f", v));
                if (v != null) byRanking.computeIfAbsent(ranking, k -> new ArrayList<>()).add(v);
            }
            sb.append(" |\n");
        }
        sb.append("| **median** | ").append(rows.size()).append(" repos");
        for (String ranking : RANKINGS) {
            Double m = median(byRanking.getOrDefault(ranking, List.of()));
            sb.append(" | ").append(m == null ? "" : String.format("**%.2f**", m));
        }
        sb.append(" |\n");
        return sb.toString();
    }

    static Double median(List<Double> values) {
        if (values.isEmpty()) return null;
        List<Double> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    private static String percent(int part, int total) {
        return total == 0 ? "n/a" : Math.round(100.0 * part / total) + "%";
    }

    private static String n(Integer v) {
        return v == null ? "" : String.valueOf(v);
    }
}
