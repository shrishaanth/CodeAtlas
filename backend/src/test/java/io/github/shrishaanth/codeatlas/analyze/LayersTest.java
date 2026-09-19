package io.github.shrishaanth.codeatlas.analyze;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class LayersTest {

    private static final Function<String, String> DIR = p -> p.contains("/") ? p.substring(0, p.lastIndexOf('/')) : ".";

    @Test
    void assignsLayersFromFoundationsUp() {
        ImportGraph g = new ImportGraph();
        g.addEdge("app/views.py", "models/user.py");
        g.addEdge("models/user.py", "core/db.py");
        g.addEdge("app/views.py", "core/db.py");
        g.addEdge("cli/main.py", "app/views.py");
        g.addNode("core/util.py");

        Layers.Result r = Layers.compute(g, DIR);

        assertThat(r.layers()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "core", 0, "models", 1, "app", 2, "cli", 3));
        assertThat(r.cycles()).isEmpty();
    }

    @Test
    void reportsDirectoryCyclesWithExampleImports() {
        ImportGraph g = new ImportGraph();
        g.addEdge("a/x.py", "b/y.py");
        g.addEdge("b/y.py", "c/z.py");
        g.addEdge("c/z.py", "a/w.py");
        g.addEdge("d/top.py", "a/x.py");

        Layers.Result r = Layers.compute(g, DIR);

        assertThat(r.cycles()).hasSize(1);
        Layers.Cycle c = r.cycles().get(0);
        assertThat(c.dirs()).containsExactly("a", "b", "c");
        assertThat(c.dependencies()).extracting(d -> d.exampleFrom() + " -> " + d.exampleTo())
                .containsExactly("a/x.py -> b/y.py", "b/y.py -> c/z.py", "c/z.py -> a/w.py");
        assertThat(r.layers().get("a")).isEqualTo(r.layers().get("c")).isZero();
        assertThat(r.layers().get("d")).isEqualTo(1);
    }

    @Test
    void ignoresPackageReExportsFromInitFiles() {
        // Typical package: __init__ re-exports the submodule, the submodule uses the package.
        ImportGraph g = new ImportGraph();
        g.addEdge("pkg/__init__.py", "pkg/json/tag.py");
        g.addEdge("pkg/json/tag.py", "pkg/globals.py");

        Layers.Result r = Layers.compute(g, DIR);

        assertThat(r.cycles()).isEmpty();
        assertThat(r.layers()).containsEntry("pkg", 0).containsEntry("pkg/json", 1);
    }

    @Test
    void tarjanHandlesSelfContainedAndDisconnectedNodes() {
        List<List<String>> sccs = Layers.stronglyConnected(List.of("a", "b", "c"),
                n -> switch (n) {
                    case "a" -> List.of("b");
                    case "b" -> List.of("a");
                    default -> List.of();
                });

        assertThat(sccs).hasSize(2);
        assertThat(sccs).anySatisfy(s -> assertThat(s).containsExactlyInAnyOrder("a", "b"));
    }
}
