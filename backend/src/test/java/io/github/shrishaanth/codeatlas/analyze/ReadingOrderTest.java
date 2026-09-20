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
    void ranksByChurnReachAndFanInAndExplainsEachItem() {
        ImportGraph g = new ImportGraph();
        g.addEdge("app.py", "models.py");     // app pulls in models
        g.addEdge("views.py", "models.py");
        g.addEdge("views.py", "app.py");      // views pulls in app and, through it, models
        g.addNode("script.py");

        List<Report.ReadingItem> items = ReadingOrder.rank(g,
                List.of("app.py", "models.py", "script.py", "views.py"),
                Map.of("app.py", 50, "models.py", 10, "views.py", 5, "script.py", 1));

        // app.py leads on churn; models.py follows on fan-in; views.py has the highest reach but
        // little history, and script.py has nothing.
        assertThat(items).extracting(Report.ReadingItem::path)
                .containsExactly("app.py", "models.py", "views.py", "script.py");
        Map<String, Double> reach = new java.util.HashMap<>();
        items.forEach(i -> reach.put(i.path(), i.parts().get("reach")));
        assertThat(reach.get("views.py")).as("pulls in the most").isEqualTo(1.0);
        assertThat(reach.get("models.py")).as("pulls in nothing").isLessThan(reach.get("app.py"));
        Report.ReadingItem top = items.get(0);
        assertThat(top.rank()).isEqualTo(1);
        assertThat(top.parts()).containsOnlyKeys("churn", "reach", "fanIn");
        assertThat(top.parts().get("churn")).isEqualTo(1.0);
        assertThat(top.reasons()).contains("Changed in 50 commits", "Imported by 1 non-test file");
        double expected = ReadingOrder.W_CHURN * 1.0
                + ReadingOrder.W_REACH * top.parts().get("reach")
                + ReadingOrder.W_FAN_IN * ReadingOrder.logNorm(1, 2);
        assertThat(top.score()).isCloseTo(expected, within(0.001));
    }

    @Test
    void privateModulesRankLowerThanTheirPublicEquivalent() {
        ImportGraph g = new ImportGraph();
        g.addEdge("public.py", "_compat.py");
        g.addNode("other.py");

        List<Report.ReadingItem> items = ReadingOrder.rank(g, List.of("_compat.py", "public.py", "other.py"),
                Map.of("_compat.py", 20, "public.py", 20, "other.py", 1));

        assertThat(items).extracting(Report.ReadingItem::path).startsWith("public.py");
        Report.ReadingItem privateItem = items.stream().filter(i -> i.path().equals("_compat.py")).findFirst().orElseThrow();
        assertThat(privateItem.reasons()).contains("Private module, ranked lower");
        assertThat(privateItem.score())
                .isCloseTo(rawScore(privateItem) - ReadingOrder.PRIVATE_PENALTY, within(0.001));
    }

    @Test
    void tiesAreBrokenByPathForDeterminism() {
        ImportGraph g = new ImportGraph();
        g.addNode("b.py");
        g.addNode("a.py");

        List<Report.ReadingItem> items = ReadingOrder.rank(g, List.of("b.py", "a.py"), Map.of());

        assertThat(items).extracting(Report.ReadingItem::path).containsExactly("a.py", "b.py");
    }

    private static double rawScore(Report.ReadingItem item) {
        return ReadingOrder.W_CHURN * item.parts().get("churn")
                + ReadingOrder.W_REACH * item.parts().get("reach")
                + ReadingOrder.W_FAN_IN * item.parts().get("fanIn");
    }
}
