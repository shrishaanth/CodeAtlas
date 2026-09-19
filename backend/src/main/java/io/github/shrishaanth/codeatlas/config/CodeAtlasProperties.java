package io.github.shrishaanth.codeatlas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Settings bound from {@code codeatlas.*}. Every value can be overridden by an environment
 * variable (e.g. {@code CODEATLAS_CORS_ALLOWED_ORIGINS}), so nothing is hardcoded per environment.
 */
@ConfigurationProperties(prefix = "codeatlas")
public record CodeAtlasProperties(String version, Cors cors) {

    public CodeAtlasProperties {
        if (version == null || version.isBlank()) version = "dev";
        if (cors == null) cors = new Cors(List.of());
    }

    public record Cors(List<String> allowedOrigins) {
        public Cors {
            allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        }
    }
}
