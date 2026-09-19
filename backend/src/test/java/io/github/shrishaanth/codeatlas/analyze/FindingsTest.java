package io.github.shrishaanth.codeatlas.analyze;

import io.github.shrishaanth.codeatlas.fetch.FileClassifier;
import io.github.shrishaanth.codeatlas.fetch.SourceFile;
import io.github.shrishaanth.codeatlas.parse.ImportResolver;
import io.github.shrishaanth.codeatlas.parse.ParsedPythonFile;
import io.github.shrishaanth.codeatlas.parse.PythonParser;
import io.github.shrishaanth.codeatlas.report.Report;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class FindingsTest {

    /** Builds findings input the way the pipeline does, from in-memory file contents. */
    private static Findings.Input input(Map<String, String> contents) {
        Map<String, String> sorted = new TreeMap<>(contents);
        List<SourceFile> files = new ArrayList<>();
        Map<String, ParsedPythonFile> parsed = new HashMap<>();
        ImportGraph graph = new ImportGraph();
        Map<String, Integer> importLines = new HashMap<>();
        try (PythonParser parser = new PythonParser()) {
            sorted.forEach((path, text) -> {
                long lines = text.lines().count();
                long nonBlank = text.lines().filter(l -> !l.isBlank()).count();
                files.add(new SourceFile(path, null, FileClassifier.language(path), text.length(), false,
                        (int) lines, (int) nonBlank, 0, FileClassifier.isTest(path), null,
                        FileClassifier.generatedReason(path)));
                if (path.endsWith(".py")) parsed.put(path, parser.parse(path, text));
            });
        }
        ImportResolver resolver = new ImportResolver(parsed.keySet());
        parsed.forEach((path, pf) -> {
            graph.addNode(path);
            pf.imports().forEach(imp -> resolver.resolve(path, imp).forEach(r -> {
                if (r.resolved()) {
                    graph.addEdge(path, r.targetPath());
                    importLines.putIfAbsent(path + "\u0000" + r.targetPath(), imp.line());
                }
            }));
        });
        List<String> nonTest = files.stream().filter(f -> !f.test()).map(SourceFile::path).toList();
        Layers.Result layers = Layers.compute(graph.restrictTo(nonTest),
                p -> p.contains("/") ? p.substring(0, p.lastIndexOf('/')) : ".");
        return new Findings.Input(files, parsed, graph, layers, importLines, sorted::get);
    }

    private static List<Report.Finding> ofKind(Findings.Result r, String kind) {
        return r.findings().stream().filter(f -> f.kind().equals(kind)).toList();
    }

    private static String module(String name, int functions) {
        StringBuilder sb = new StringBuilder("\"\"\"Module " + name + ".\"\"\"\nimport math\n\n");
        for (int i = 0; i < functions; i++) {
            sb.append("\n\ndef compute_value_").append(i).append("(weights, returns):\n")
                    .append("    total_weight = sum(weights)\n")
                    .append("    normalized = [w / total_weight for w in weights]\n")
                    .append("    result = sum(n * r for n, r in zip(normalized, returns)) + ").append(i).append("\n")
                    .append("    return math.sqrt(abs(result))\n");
        }
        return sb.toString();
    }

    @Test
    void groupsNearDuplicateFilesWithEvidence() {
        String hrp = module("hrp", 4);
        Findings.Result r = Findings.compute(input(Map.of(
                "src/hrp.py", hrp,
                "src/core/hrp.py", hrp + "\n\nEXTRA_SETTING_VALUE = 42\n",
                "src/other.py", module("other", 1).replace("compute_value", "other_value"))));

        List<Report.Finding> dups = ofKind(r, "duplicate-module");
        assertThat(dups).hasSize(1);
        assertThat(dups.get(0).severity()).isEqualTo("warn");
        assertThat(dups.get(0).evidence()).extracting(Report.Evidence::path)
                .containsExactly("src/core/hrp.py", "src/hrp.py");
        assertThat(dups.get(0).id()).isEqualTo("duplicate-module-1");
    }

    @Test
    void findsIdenticalFunctionBodiesAcrossFilesEvenWhenRenamed() {
        String body = """
                    key = os.environ["TMDB_API_KEY"]
                    url = f"https://api.themoviedb.org/3/movie/{movie_id}"
                    response = requests.get(url, params={"api_key": key}, timeout=10)
                    response.raise_for_status()
                    data = response.json()
                    return {"title": data["title"], "year": data["release_date"][:4]}
                """;
        Findings.Result r = Findings.compute(input(Map.of(
                "backend/tmdb.py", "import os\nimport requests\n\n\ndef fetch_movie(movie_id):\n" + body,
                "ml-service/tmdb_client.py", "import os\nimport requests\n\n\ndef get_movie(movie_id):\n" + body,
                "tests/test_tmdb.py", "def test_x(movie_id):\n" + body)));

        List<Report.Finding> repeated = ofKind(r, "repeated-logic");
        assertThat(repeated).hasSize(1);
        assertThat(repeated.get(0).title()).contains("2 identical copies").contains("fetch_movie").contains("get_movie");
        assertThat(repeated.get(0).evidence()).extracting(Report.Evidence::path, Report.Evidence::startLine)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("backend/tmdb.py", 5),
                        org.assertj.core.groups.Tuple.tuple("ml-service/tmdb_client.py", 5));
    }

    @Test
    void reportsUnreferencedModulesButNotEntryPoints() {
        Findings.Result r = Findings.compute(input(Map.of(
                "pkg/__init__.py", "",
                "pkg/core.py", "X = 1\n",
                "pkg/used.py", "from pkg.core import X\n",
                "pkg/domain.py", "class Domain:\n    pass\n",
                "pkg/cli.py", "def main():\n    pass\n",
                "run.py", "from pkg import used\nif __name__ == \"__main__\":\n    pass\n",
                "scripts/tool.py", "print(1)\n",
                "pyproject.toml", "[project.scripts]\ncodeatlas-demo = \"pkg.cli:main\"\n")));

        assertThat(ofKind(r, "unreferenced-file")).extracting(f -> f.evidence().get(0).path())
                .containsExactly("pkg/domain.py");
        assertThat(ofKind(r, "unreferenced-file").get(0).title()).startsWith("No static import found");
    }

    @Test
    void flagsLargeAreasWithoutTests() {
        String big = "x = 1\n".repeat(320);
        Findings.Result r = Findings.compute(input(Map.of(
                "backend/app.py", big,
                "backend/tests/test_app.py", "def test():\n    pass\n",
                "ml-service/model.py", big,
                "recommender/core.py", big,
                "tests/test_recommender.py", "from recommender.core import x\n",
                "tiny/a.py", "x = 1\n",
                "docs/conf.py", big,
                "examples/demo.py", big)));

        assertThat(ofKind(r, "missing-tests")).extracting(Report.Finding::title)
                .containsExactly("No tests found for ml-service");
    }

    @Test
    void reportsCommittedGeneratedAndLocalFilesGroupedByCause() {
        Map<String, String> files = new HashMap<>();
        files.put("app/main.py", "x = 1\n");
        files.put("app/__pycache__/main.cpython-311.pyc", "bytes");
        files.put("app/__pycache__/util.cpython-311.pyc", "bytes");
        files.put("backend/.env", "SECRET=should-never-be-read");
        files.put(".env.example", "SECRET=");
        files.put("static/lib.min.js", "var a=1;");

        List<Report.Finding> gen = ofKind(Findings.compute(input(files)), "generated-file-committed");

        assertThat(gen).extracting(Report.Finding::severity, Report.Finding::title).containsExactly(
                org.assertj.core.groups.Tuple.tuple("warn", "Environment file committed: backend/.env"),
                org.assertj.core.groups.Tuple.tuple("warn", "2 generated files committed (build output or cache (__pycache__/))"),
                org.assertj.core.groups.Tuple.tuple("info", "Generated file committed (minified file): static/lib.min.js"));
        assertThat(gen.get(0).detail()).doesNotContain("should-never-be-read");
    }

    @Test
    void turnsDirectoryCyclesIntoFindingsWithImportLines() {
        Findings.Result r = Findings.compute(input(Map.of(
                "a/x.py", "import os\nfrom b.y import Y\n",
                "b/y.py", "from a.x import os\nY = 1\n")));

        List<Report.Finding> cycles = ofKind(r, "import-cycle");
        assertThat(cycles).hasSize(1);
        assertThat(cycles.get(0).evidence()).extracting(Report.Evidence::path, Report.Evidence::startLine,
                Report.Evidence::note).containsExactly(
                org.assertj.core.groups.Tuple.tuple("a/x.py", 2, "imports b/y.py"),
                org.assertj.core.groups.Tuple.tuple("b/y.py", 1, "imports a/x.py"));
    }

    @Test
    void reportsSameNamedModulesNestedInsideEachOtherButNotSiblingServices() {
        Findings.Result r = Findings.compute(input(Map.of(
                "src/hrp.py", module("functional", 2),
                "src/core/hrp.py", "class HierarchicalRiskParity:\n    def allocate(self, cov):\n        return cov\n",
                "src/core/user.py", "from src.core.hrp import HierarchicalRiskParity\n",
                "services/a/app.py", "x = 1\n",
                "services/b/app.py", "y = 2\n")));

        List<Report.Finding> dups = ofKind(r, "duplicate-module");
        assertThat(dups).extracting(Report.Finding::title).containsExactly("Two modules named hrp.py");
        assertThat(dups.get(0).severity()).isEqualTo("info");
        assertThat(dups.get(0).detail()).contains("src/core/hrp.py is imported by 1 file, src/hrp.py by no file");
    }

    @Test
    void sameNamedModulesThatImportEachOtherAreADesignNotADuplicate() {
        // Flask: flask/app.py builds on flask/sansio/app.py.
        Findings.Result r = Findings.compute(input(Map.of(
                "src/flask/__init__.py", "",
                "src/flask/sansio/__init__.py", "",
                "src/flask/app.py", "from .sansio.app import App\n\n\nclass Flask(App):\n    pass\n",
                "src/flask/sansio/app.py", "class App:\n    pass\n")));

        assertThat(ofKind(r, "duplicate-module")).isEmpty();
    }

    @Test
    void envFilesInTestsAreLikelyFixtures() {
        List<Report.Finding> gen = ofKind(Findings.compute(input(Map.of(
                "tests/test_apps/.env", "FOO=bar", "app.py", "x = 1\n"))), "generated-file-committed");

        assertThat(gen).extracting(Report.Finding::severity, Report.Finding::title).containsExactly(
                org.assertj.core.groups.Tuple.tuple("info", "Environment file committed in tests: tests/test_apps/.env"));
    }

    @Test
    void spotsTheSameExternalClientRewrittenInSeveralAreas() {
        Findings.Result r = Findings.compute(input(Map.of(
                "backend/src/services/tmdbService.js", "x",
                "services/ml-service/tmdb_client.py", "x = 1\n",
                "services/ml-service/clustering/tmdb_service.py", "x = 1\n",
                "services/recommendation-service/src/services/tmdbClient.js", "x",
                "services/a/app.py", "x = 1\n", "services/b/app.py", "x = 1\n", "services/c/app.py", "x = 1\n")));

        List<Report.Finding> names = ofKind(r, "repeated-logic");
        assertThat(names).extracting(Report.Finding::title)
                .containsExactly("4 files named after \"tmdb\" in 3 areas");
    }

    @Test
    void derivesNameStemsAcrossNamingStyles() {
        assertThat(Findings.nameStem("a/tmdbService.js")).isEqualTo("tmdb");
        assertThat(Findings.nameStem("a/tmdb_client.py")).isEqualTo("tmdb");
        assertThat(Findings.nameStem("a/payment-gateway-api.ts")).isEqualTo("paymentgateway");
        assertThat(Findings.nameStem("a/utils.py")).isEmpty();
    }

    @Test
    void saysSoWhenOnlyTestsImportAModule() {
        Findings.Result r = Findings.compute(input(Map.of(
                "src/domain.py", "class Weights:\n    pass\n",
                "tests/test_domain.py", "from src.domain import Weights\n")));

        assertThat(ofKind(r, "unreferenced-file")).extracting(Report.Finding::title)
                .containsExactly("Only tests import src/domain.py");
    }

    @Test
    void recognisesHashNamedCacheEntries() {
        assertThat(FileClassifier.generatedReason("svc/cache/3e5a472df573dc695289f1adde2d59e0.json"))
                .isEqualTo("cache entry (hash-named file)");
        assertThat(FileClassifier.generatedReason("svc/cache/semantic_cache.py")).isNull();
        assertThat(FileClassifier.generatedReason("data/deadbeef.json")).as("too short to be a hash").isNull();
    }

    @Test
    void stylesheetsDoNotCountAsUntestedCode() {
        Findings.Result r = Findings.compute(input(Map.of(
                "frontend/src/big.css", ".a { color: red; }\n".repeat(400),
                "frontend/src/app.js", "let x = 1;\n".repeat(10))));

        assertThat(ofKind(r, "missing-tests")).isEmpty();
    }

    @Test
    void capsFindingsPerKindAndCountsTheRest() {
        Map<String, String> files = new HashMap<>();
        for (int i = 0; i < Findings.MAX_PER_KIND + 7; i++) files.put("mod" + i + ".py", "class C" + i + ":\n    pass\n");

        Findings.Result r = Findings.compute(input(files));

        assertThat(ofKind(r, "unreferenced-file")).hasSize(Findings.MAX_PER_KIND);
        assertThat(r.omitted()).containsEntry("unreferenced-file", 7);
    }
}
