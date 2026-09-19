package io.github.shrishaanth.codeatlas.analyze;

import io.github.shrishaanth.codeatlas.gitmine.People;
import io.github.shrishaanth.codeatlas.report.Report;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns blame line counts into ownership, bus factor and flags per file and directory.
 * Definitions are in docs/metrics.md, "Ownership".
 */
public final class Ownership {

    public static final int MAX_OWNERS_LISTED = 5;
    public static final int FLAG_MIN_LINES = 100;
    public static final double SINGLE_OWNER_SHARE = 0.8;
    public static final Duration ACTIVE_WINDOW = Duration.ofDays(365);

    public record Result(List<Report.Ownership> files, List<Report.Ownership> directories) {
    }

    private Ownership() {
    }

    /**
     * @param blameLines   path to (email to lines)
     * @param repoLastCommit reference point for "active": the repository's last commit, not today
     */
    public static Result compute(Map<String, Map<String, Integer>> blameLines, People people, Instant repoLastCommit) {
        Map<String, People.Person> byId = new HashMap<>();
        people.people().forEach(p -> byId.put(p.id(), p));

        Map<String, Map<String, Integer>> perFile = new TreeMap<>();
        Map<String, Map<String, Integer>> perDir = new TreeMap<>();
        for (var fileEntry : blameLines.entrySet()) {
            Map<String, Integer> byPerson = new HashMap<>();
            for (var e : fileEntry.getValue().entrySet()) {
                String id = people.idForEmail(e.getKey()).orElse(null);
                if (id == null || byId.get(id).bot()) continue; // bot lines are not ownership
                byPerson.merge(id, e.getValue(), Integer::sum);
            }
            if (byPerson.isEmpty()) continue;
            perFile.put(fileEntry.getKey(), byPerson);
            for (String dir : ancestors(fileEntry.getKey())) {
                Map<String, Integer> acc = perDir.computeIfAbsent(dir, k -> new HashMap<>());
                byPerson.forEach((id, n) -> acc.merge(id, n, Integer::sum));
            }
        }

        List<Report.Ownership> files = new ArrayList<>();
        perFile.forEach((path, counts) -> files.add(summarize(path, counts, byId, repoLastCommit)));
        List<Report.Ownership> dirs = new ArrayList<>();
        perDir.forEach((path, counts) -> dirs.add(summarize(path, counts, byId, repoLastCommit)));
        return new Result(files, dirs);
    }

    static Report.Ownership summarize(String path, Map<String, Integer> counts, Map<String, People.Person> byId,
                                      Instant repoLastCommit) {
        List<Map.Entry<String, Integer>> sorted = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .toList();
        int total = sorted.stream().mapToInt(Map.Entry::getValue).sum();

        int busFactor = 0, covered = 0;
        for (var e : sorted) {
            busFactor++;
            covered += e.getValue();
            if (2 * covered >= total) break;
        }

        List<Report.Owner> owners = new ArrayList<>();
        int listed = 0;
        for (var e : sorted.subList(0, Math.min(MAX_OWNERS_LISTED, sorted.size()))) {
            owners.add(new Report.Owner(e.getKey(), e.getValue(), round((double) e.getValue() / total)));
            listed += e.getValue();
        }

        People.Person top = byId.get(sorted.get(0).getKey());
        boolean topActive = top.lastCommitAt() != null
                && !top.lastCommitAt().isBefore(repoLastCommit.minus(ACTIVE_WINDOW));
        double topShare = (double) sorted.get(0).getValue() / total;
        List<String> flags = new ArrayList<>();
        if (total >= FLAG_MIN_LINES && busFactor == 1 && topShare >= SINGLE_OWNER_SHARE) {
            flags.add("single-owner");
            if (!topActive) flags.add("orphaned");
        }
        return new Report.Ownership(path, total, owners, total - listed, sorted.size(), busFactor, topActive, flags);
    }

    /** "a/b/c.py" gives ".", "a", "a/b". */
    static List<String> ancestors(String path) {
        List<String> out = new ArrayList<>();
        out.add(".");
        int slash = path.indexOf('/');
        while (slash >= 0) {
            out.add(path.substring(0, slash));
            slash = path.indexOf('/', slash + 1);
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
