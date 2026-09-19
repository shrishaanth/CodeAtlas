package io.github.shrishaanth.codeatlas.analyze;

import io.github.shrishaanth.codeatlas.report.Report;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ReadingOrderTest {

    @Test
    void pageRankFlowsToDependedOnFilesAndSumsToOne() {
        ImportGraph g = new ImportGraph();
        g.addEdge("a.py", "core.py");
        g.addEdge("b.py", "core.py");
        g.addEdge("c.py", "core.py");
        g.addEdge("core.py", "base.py");
        g.addNode("lonely.py");

        Map<String, Double> pr = g.pageRank(0.85, 50);

        assertThat(pr.values().stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(1.0, within(1e-9));
        // base.py is imported only once, but by the most central file, so it ranks highest.
        assertThat(pr.get("base.py")).isGreaterThan(pr.get("core.py"));
        assertThat(pr.get("core.py")).isGreaterThan(pr.get("a.py"));
        assertThat(pr.get("a.py")).isCloseTo(pr.get("lonely.py"), within(1e-12));
    }

    @Test
    void ignoresSelfAndDuplicateEdges() {
        ImportGraph g = new ImportGraph();
        g.addEdge("a.py", "a.py");
        g.addEdge("a.py", "b.py");
        g.addEdge("a.py", "b.py");

        assertThat(g.edgeCount()).isEqualTo(1);
        assertThat(g.importersOf("b.py")).containsExactly("a.py");
    }

    @Test
    void ranksByWeightedPartsAndExplainsEachItem() {
        ImportGraph g = new ImportGraph();
        g.addEdge("app.py", "models.py");
        g.addEdge("views.py", "models.py");
        g.addEdge("views.py", "app.py");
        g.addNode("script.py");

        List<Report.ReadingItem> items = ReadingOrder.rank(g,
                List.of("app.py", "models.py", "script.py", "views.py"),
                Map.of("app.py", 50, "models.py", 10, "views.py", 5, "script.py", 1));

        assertThat(items).extracting(Report.ReadingItem::path)
                .containsExactly("models.py", "app.py", "views.py", "script.py");
        Report.ReadingItem top = items.get(0);
        assertThat(top.rank()).isEqualTo(1);
        assertThat(top.parts()).containsKeys("centrality", "fanIn", "churn");
        assertThat(top.parts().get("centrality")).isEqualTo(1.0);
        assertThat(top.parts().get("fanIn")).isEqualTo(1.0);
        assertThat(top.reasons()).containsExactly("Imported by 2 non-test files", "Changed in 10 commits");
        double expected = ReadingOrder.W_CENTRALITY * 1.0 + ReadingOrder.W_FAN_IN * 1.0
                + ReadingOrder.W_CHURN * ReadingOrder.logNorm(10, 50);
        assertThat(top.score()).isCloseTo(expected, within(0.001));
    }

    @Test
    void tiesAreBrokenByPathForDeterminism() {
        ImportGraph g = new ImportGraph();
        g.addNode("b.py");
        g.addNode("a.py");

        List<Report.ReadingItem> items = ReadingOrder.rank(g, List.of("b.py", "a.py"), Map.of());

        assertThat(items).extracting(Report.ReadingItem::path).containsExactly("a.py", "b.py");
    }
}
