package io.github.shrishaanth.codeatlas.analyze;

import io.github.shrishaanth.codeatlas.fetch.SourceFile;
import io.github.shrishaanth.codeatlas.report.Report;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HotspotsTest {

    private static SourceFile file(String path, String language, int lines, int complexity, boolean test) {
        return new SourceFile(path, null, language, lines * 10L, false, lines, lines, complexity, test, null);
    }

    @Test
    void ranksFilesThatAreBothBusyAndComplex() {
        List<SourceFile> files = List.of(
                file("core.py", "python", 900, 1200, false),        // complex and busy
                file("busy_flat.py", "python", 50, 10, false),      // busy but flat
                file("complex_quiet.py", "python", 800, 1500, false), // complex but rarely touched
                file("tests/test_core.py", "python", 900, 2000, true),
                file("CHANGES.rst", "restructuredtext", 3000, 500, false),
                file("never.py", "python", 100, 100, false));

        List<Report.Hotspot> hot = Hotspots.rank(files, Map.of(
                "core.py", 200, "busy_flat.py", 300, "complex_quiet.py", 2,
                "tests/test_core.py", 400, "CHANGES.rst", 900));

        assertThat(hot).extracting(Report.Hotspot::path)
                .containsExactly("core.py", "busy_flat.py", "complex_quiet.py");
        Report.Hotspot top = hot.get(0);
        assertThat(top.rank()).isEqualTo(1);
        assertThat(top.commits()).isEqualTo(200);
        assertThat(top.complexity()).isEqualTo(1200);
        double expected = ReadingOrder.logNorm(200, 300) * ReadingOrder.logNorm(1200, 1500);
        assertThat(top.score()).isCloseTo(expected, within(0.001));
    }
}
