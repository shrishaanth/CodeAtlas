package io.github.shrishaanth.codeatlas.jobs;

import io.github.shrishaanth.codeatlas.testutil.TestRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Full path: API service -> job queue -> pipeline -> Postgres, against a real database in Docker. */
@SpringBootTest(properties = "codeatlas.analysis.allow-local-paths=true")
@Testcontainers
class AnalysisIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired
    private AnalysisService service;

    @Autowired
    private AnalysisRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void analyzesALocalRepositoryAndStoresTheReportVerbatim(@TempDir Path dir) throws Exception {
        try (TestRepo repo = TestRepo.create(dir)) {
            repo.commit("alice@x.org", "init", Map.of(
                    "pkg/__init__.py", "",
                    "pkg/core.py", "\"\"\"Core.\"\"\"\n\n\ndef add(a, b):\n    return a + b\n\n\n"
                            + "def sub(a, b):\n    return a - b\n",
                    "pkg/app.py", "\"\"\"App.\"\"\"\nfrom pkg.core import add\n\n\ndef run():\n"
                            + "    total = add(2, 3)\n    return total\n"));

            AnalysisStatus submitted = service.submit(dir.toString());
            AnalysisStatus again = service.submit(dir.toString());
            AnalysisStatus done = waitForFinish(submitted.id());

            assertThat(again.id()).as("a second click reuses the active analysis").isEqualTo(submitted.id());
            assertThat(done.status()).as(String.valueOf(done.error())).isEqualTo(AnalysisStatus.State.DONE);
            assertThat(done.percent()).isEqualTo(100);
            assertThat(done.repoName()).isEqualTo(dir.getFileName().toString());
            assertThat(done.commit()).hasSize(40);

            String json = service.reportJson(submitted.id()).orElseThrow();
            assertThat(json).as("key order preserved (json, not jsonb)").startsWith("{\"schemaVersion\":\"0.1\"");
            JsonNode report = jsonMapper.readTree(json);
            assertThat(report.get("edges").get(0).get("source").asString()).isEqualTo("pkg/app.py");
            assertThat(report.get("readingOrder").get(0).get("path").asString()).isEqualTo("pkg/core.py");
            assertThat(service.recent(5)).extracting(AnalysisStatus::id).contains(submitted.id());
        }
    }

    @Test
    void failuresAreRecordedWithAMessage(@TempDir Path dir) throws Exception {
        // A directory that is not a git repository.
        AnalysisStatus done = waitForFinish(service.submit(dir.toString()).id());

        assertThat(done.status()).isEqualTo(AnalysisStatus.State.FAILED);
        assertThat(done.error()).isNotBlank();
        assertThat(service.reportJson(done.id())).isEmpty();
    }

    @Test
    void rejectsInvalidSourcesWithoutQueueing() {
        assertThatThrownBy(() -> service.submit("https://gitlab.com/a/b")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void abandonedAnalysesAreFailedOnStartup() {
        UUID id = UUID.randomUUID();
        repository.insertQueued(id, "https://github.com/x/abandoned", Instant.now());
        jdbc.sql("UPDATE analysis SET status = 'RUNNING' WHERE id = :id").param("id", id).update();

        service.failAbandonedAnalyses();

        AnalysisStatus s = repository.find(id).orElseThrow();
        assertThat(s.status()).isEqualTo(AnalysisStatus.State.FAILED);
        assertThat(s.error()).contains("restarted");
    }

    private AnalysisStatus waitForFinish(UUID id) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(deadline)) {
            AnalysisStatus s = service.status(id).orElseThrow();
            if (!s.status().active()) return s;
            Thread.sleep(100);
        }
        throw new AssertionError("analysis did not finish in time");
    }
}
