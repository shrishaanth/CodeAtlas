package io.github.shrishaanth.codeatlas.analyze;

import io.github.shrishaanth.codeatlas.fetch.SourceFile;
import io.github.shrishaanth.codeatlas.report.Report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Code that is both complicated and frequently changed. Definition in docs/metrics.md, "Hotspots".
 */
public final class Hotspots {

    public static final int MAX_HOTSPOTS = 50;

    private Hotspots() {
    }

    /** @param commits commits per current path (missing means 0) */
    public static List<Report.Hotspot> rank(List<SourceFile> files, Map<String, Integer> commits) {
        List<SourceFile> candidates = files.stream()
                .filter(f -> f.isCode() && !f.test() && !f.binary() && !f.generated() && f.skipReason() == null)
                .filter(f -> commits.getOrDefault(f.path(), 0) > 0)
                .toList();
        int maxCommits = candidates.stream().mapToInt(f -> commits.get(f.path())).max().orElse(0);
        int maxComplexity = candidates.stream().mapToInt(SourceFile::indentComplexity).max().orElse(0);

        record Scored(SourceFile file, int commits, double score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (SourceFile f : candidates) {
            int c = commits.get(f.path());
            double score = ReadingOrder.logNorm(c, maxCommits) * ReadingOrder.logNorm(f.indentComplexity(), maxComplexity);
            if (score > 0) scored.add(new Scored(f, c, score));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparing(s -> s.file().path()));

        List<Report.Hotspot> out = new ArrayList<>();
        for (int i = 0; i < Math.min(MAX_HOTSPOTS, scored.size()); i++) {
            Scored s = scored.get(i);
            out.add(new Report.Hotspot(i + 1, s.file().path(), Math.round(s.score() * 1000) / 1000.0,
                    s.commits(), s.file().lines(), s.file().indentComplexity()));
        }
        return out;
    }
}
