package io.github.shrishaanth.codeatlas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

/**
 * Settings bound from {@code codeatlas.*}. Every value can be overridden by an environment
 * variable (e.g. {@code CODEATLAS_CORS_ALLOWED_ORIGINS}), so nothing is hardcoded per environment.
 */
@ConfigurationProperties(prefix = "codeatlas")
public record CodeAtlasProperties(String version, Cors cors, Analysis analysis) {

    public CodeAtlasProperties {
        if (version == null || version.isBlank()) version = "dev";
        if (cors == null) cors = new Cors(List.of());
        if (analysis == null) analysis = new Analysis(null, false, 0, 0, 0);
    }

    public record Cors(List<String> allowedOrigins) {
        public Cors {
            allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        }
    }

    /**
     * @param workDir           where clones are created (deleted after each analysis)
     * @param allowLocalPaths   accept filesystem paths as sources; must stay off on a public server
     * @param maxCommits        history walk cap per analysis
     * @param cloneTimeoutSeconds network timeout for cloning
     * @param maxQueued         analyses allowed to wait; further requests are refused
     */
    public record Analysis(Path workDir, boolean allowLocalPaths, int maxCommits, int cloneTimeoutSeconds,
                           int maxQueued) {
        public Analysis {
            if (workDir == null) workDir = Path.of(System.getProperty("java.io.tmpdir"), "codeatlas");
            if (maxCommits <= 0) maxCommits = 20_000;
            if (cloneTimeoutSeconds <= 0) cloneTimeoutSeconds = 120;
            if (maxQueued <= 0) maxQueued = 20;
        }
    }
}
