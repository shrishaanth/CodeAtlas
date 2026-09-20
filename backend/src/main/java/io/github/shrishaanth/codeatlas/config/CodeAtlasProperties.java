package io.github.shrishaanth.codeatlas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

/**
 * Settings bound from {@code codeatlas.*}. Every value can be overridden by an environment
 * variable (e.g. {@code CODEATLAS_CORS_ALLOWED_ORIGINS}), so nothing is hardcoded per environment.
 */
@ConfigurationProperties(prefix = "codeatlas")
public record CodeAtlasProperties(String version, Cors cors, Analysis analysis, Qa qa) {

    public CodeAtlasProperties {
        if (version == null || version.isBlank()) version = "dev";
        if (cors == null) cors = new Cors(List.of());
        if (analysis == null) analysis = new Analysis(null, false, 0, 0, 0, 0, 0);
        if (qa == null) qa = new Qa(null, null, null, 0, 0, 0, 0);
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
     * @param maxBlameFiles     files blamed per analysis (most-committed first)
     * @param threads           parallel blame workers; defaults to the number of CPUs
     */
    public record Analysis(Path workDir, boolean allowLocalPaths, int maxCommits, int cloneTimeoutSeconds,
                           int maxQueued, int maxBlameFiles, int threads) {
        public Analysis {
            if (workDir == null) workDir = Path.of(System.getProperty("java.io.tmpdir"), "codeatlas");
            if (maxCommits <= 0) maxCommits = 20_000;
            if (cloneTimeoutSeconds <= 0) cloneTimeoutSeconds = 120;
            if (maxQueued <= 0) maxQueued = 20;
            if (maxBlameFiles <= 0) maxBlameFiles = 3_000;
            if (threads <= 0) threads = Runtime.getRuntime().availableProcessors();
        }
    }

    /**
     * Question answering. Leave {@code model} unset and the application runs without a language
     * model: questions then return the matching code, which is the evidence an answer would cite.
     *
     * @param baseUrl             an OpenAI-compatible endpoint, e.g. http://localhost:11434/v1 for Ollama
     * @param apiKey              may be empty for a local model
     * @param maxContextChars     how much code is put in front of the model
     * @param maxQuestionsPerHour per caller; 0 disables the limit
     */
    public record Qa(String baseUrl, String apiKey, String model, int maxTokens, int timeoutSeconds,
                     int maxContextChars, int maxQuestionsPerHour) {
        public Qa {
            if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://localhost:11434/v1";
            // Reasoning models spend output tokens thinking before they write, so keep room for both.
            if (maxTokens <= 0) maxTokens = 1500;
            if (timeoutSeconds <= 0) timeoutSeconds = 60;
            if (maxContextChars <= 0) maxContextChars = 12_000;
            if (maxQuestionsPerHour < 0) maxQuestionsPerHour = 0;
        }
    }
}
