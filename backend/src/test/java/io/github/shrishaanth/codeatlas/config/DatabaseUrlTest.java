package io.github.shrishaanth.codeatlas.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseUrlTest {

    @Test
    void convertsTheUrlHostingProvidersGiveOut() {
        Map<String, String> props = DatabaseUrl.springProperties(
                "postgres://codeatlas_user:s3cret@dpg-abc123-a.frankfurt-postgres.render.com:5432/codeatlas_db");

        assertThat(props).containsExactly(
                Map.entry("spring.datasource.url",
                        "jdbc:postgresql://dpg-abc123-a.frankfurt-postgres.render.com:5432/codeatlas_db"),
                Map.entry("spring.datasource.username", "codeatlas_user"),
                Map.entry("spring.datasource.password", "s3cret"));
    }

    @Test
    void defaultsThePortAndKeepsQueryParameters() {
        Map<String, String> props = DatabaseUrl.springProperties(
                "postgresql://user:pw@host/db?sslmode=require");

        assertThat(props).containsEntry("spring.datasource.url",
                "jdbc:postgresql://host:5432/db?sslmode=require");
    }

    @Test
    void leavesAJdbcUrlAndNonsenseAlone() {
        assertThat(DatabaseUrl.springProperties("jdbc:postgresql://localhost:5432/codeatlas")).isEmpty();
        assertThat(DatabaseUrl.springProperties(null)).isEmpty();
        assertThat(DatabaseUrl.springProperties("  ")).isEmpty();
        assertThat(DatabaseUrl.springProperties("mysql://host/db")).isEmpty();
    }
}
