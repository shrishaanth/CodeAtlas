package io.github.shrishaanth.codeatlas.qa;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Talks to any endpoint that speaks the OpenAI chat-completions API: Ollama running locally
 * (free, no key) and the hosted providers all do. Configured entirely by {@code codeatlas.qa.*}.
 */
public class OpenAiCompatibleClient implements LlmClient {

    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final Duration timeout;

    public OpenAiCompatibleClient(String baseUrl, String apiKey, String model, int maxTokens, Duration timeout) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String complete(String system, String user) {
        String body = json.writeValueAsString(Map.of(
                "model", model,
                "temperature", 0,
                "max_tokens", maxTokens,
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user))));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (apiKey != null && !apiKey.isBlank()) request.header("Authorization", "Bearer " + apiKey);

        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new LlmUnavailableException("The model endpoint returned HTTP " + response.statusCode());
            }
            JsonNode node = json.readTree(response.body());
            JsonNode content = node.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new LlmUnavailableException("The model endpoint returned no message");
            }
            return content.asString();
        } catch (java.io.IOException e) {
            throw new LlmUnavailableException("Could not reach the model endpoint: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("Interrupted while waiting for the model");
        }
    }

    @Override
    public String model() {
        return model;
    }

    /** The endpoint could not be reached or refused; the caller falls back to search results. */
    public static class LlmUnavailableException extends RuntimeException {
        public LlmUnavailableException(String message) {
            super(message);
        }
    }
}
