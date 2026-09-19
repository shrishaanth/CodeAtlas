package io.github.shrishaanth.codeatlas.pipeline;

import io.github.shrishaanth.codeatlas.fetch.FetchedRepo;
import io.github.shrishaanth.codeatlas.fetch.RepoFetcher;
import io.github.shrishaanth.codeatlas.fetch.RepoSource;
import io.github.shrishaanth.codeatlas.report.Report;
import io.github.shrishaanth.codeatlas.testutil.TestRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisPipelineTest {

    private static final String MODELS = """
            class User:
                def __init__(self, name):
                    self.name = name

                def greet(self):
                    return "hi " + self.name
            """;

    @Test
    void producesAReportFromARealRepository(@TempDir Path dir) throws Exception {
        try (TestRepo repo = TestRepo.create(dir)) {
            repo.commit("alice@x.org", "init", Map.of(
                    "app/__init__.py", "",
                    "app/models.py", MODELS,
                    "app/views.py", "\"\"\"Views.\"\"\"\nfrom .models import User\nimport os\n\n\ndef index():\n"
                            + "    name = os.environ.get('NAME', 'x')\n    return User(name)\n",
                    "app/cli.py", "\"\"\"CLI.\"\"\"\nfrom app.views import index\nfrom app import models\n\n\ndef main():\n"
                            + "    print(models.User)\n    index()\n",
                    "tests/test_models.py", "from app.models import User\n\n\ndef test_user():\n    assert User('a')\n",
                    "README.md", "# demo\nline\n"));
            repo.commit("bob@x.org", "tweak models", Map.of("app/models.py", MODELS + "\n\nVERSION = 2\n"));

            List<String> stages = new ArrayList<>();
            Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
            Report report;
            try (FetchedRepo fetched = new RepoFetcher(dir, 30).fetch(new RepoSource.Local(dir))) {
                report = new AnalysisPipeline(1000, "test", clock).run(fetched,
                        (stage, pct, detail) -> stages.add(stage));
            }

            assertThat(stages).startsWith("inventory").endsWith("done").contains("parse", "history", "analyze");
            assertThat(report.overview().files()).isEqualTo(6);
            assertThat(report.overview().commits()).isEqualTo(2);
            assertThat(report.overview().contributors()).isEqualTo(2);
            assertThat(report.overview().testFileRatio()).isEqualTo(0.25); // 1 test file, 4 source files

            assertThat(report.edges()).extracting(e -> e.source() + " -> " + e.target()).containsExactly(
                    "app/cli.py -> app/models.py",
                    "app/cli.py -> app/views.py",
                    "app/views.py -> app/models.py",
                    "tests/test_models.py -> app/models.py");

            // Tests and the empty __init__.py are not ranked; models.py is depended on most.
            assertThat(report.readingOrder()).extracting(Report.ReadingItem::path)
                    .containsExactly("app/models.py", "app/views.py", "app/cli.py");
            assertThat(report.readingOrder().get(0).reasons())
                    .containsExactly("Imported by 2 non-test files", "Changed in 2 commits");

            Report.FileEntry views = report.files().stream().filter(f -> f.path().equals("app/views.py")).findFirst().orElseThrow();
            assertThat(views.imports()).extracting(Report.Import::module, Report.Import::resolvedPath, Report.Import::external)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(".models", "app/models.py", false),
                            org.assertj.core.groups.Tuple.tuple("os", null, true));
            assertThat(views.symbols()).extracting(Report.Symbol::name).containsExactly("index");
            assertThat(views.git().commits()).isEqualTo(1);

            assertThat(report.people().authors()).extracting(Report.Author::id, Report.Author::commits)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("a1", 1), org.assertj.core.groups.Tuple.tuple("a2", 1));
        }
    }

    @Test
    void serializesWithSchemaFieldNames(@TempDir Path dir) throws Exception {
        try (TestRepo repo = TestRepo.create(dir)) {
            repo.commit("alice@x.org", "init", Map.of("tests/test_a.py", "def test():\n    pass\n"));
            Report report;
            try (FetchedRepo fetched = new RepoFetcher(dir, 30).fetch(new RepoSource.Local(dir))) {
                report = new AnalysisPipeline(1000, "test", Clock.systemUTC()).run(fetched, ProgressListener.NONE);
            }

            JsonNode json = JsonMapper.builder().build().readTree(JsonMapper.builder().build().writeValueAsString(report));

            assertThat(json.get("schemaVersion").asString()).isEqualTo("0.1");
            assertThat(json.get("files").get(0).has("isTest")).isTrue();
            assertThat(json.get("files").get(0).get("isTest").asBoolean()).isTrue();
            assertThat(json.get("repo").get("analyzedAt").isString()).as("ISO-8601 string, not a number").isTrue();
        }
    }
}
