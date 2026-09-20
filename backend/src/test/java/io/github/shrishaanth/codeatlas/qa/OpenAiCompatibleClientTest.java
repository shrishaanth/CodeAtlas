package io.github.shrishaanth.codeatlas.qa;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Checks the client against a stub endpoint, so the wire format is verified without a real model. */
class OpenAiCompatibleClientTest {

    private HttpServer server;
    private final java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String startServer(int status, String body, AtomicReference<String> captured) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                    + "\nAuth: " + exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @Test
    void sendsAChatRequestAndReadsTheReply() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        String url = startServer(200, """
                {"choices":[{"message":{"role":"assistant","content":"It lives in app.py:12."}}]}""", request);

        String reply = new OpenAiCompatibleClient(url + "/", "secret-key", "qwen2.5-coder:7b", 600,
                Duration.ofSeconds(5)).complete("system rules", "the question");

        assertThat(reply).isEqualTo("It lives in app.py:12.");
        assertThat(request.get()).contains("\"model\":\"qwen2.5-coder:7b\"")
                .contains("system rules").contains("the question")
                .contains("Auth: Bearer secret-key");
    }

    @Test
    void worksWithoutAnApiKeyAsALocalModelNeedsNone() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        String url = startServer(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}", request);

        new OpenAiCompatibleClient(url, "", "llama3.1:8b", 100, Duration.ofSeconds(5))
                .complete("s", "u");

        assertThat(request.get()).contains("Auth: null");
    }

    @Test
    void givesUpAfterRetryingABusyEndpoint() throws Exception {
        String url = startServer(429, "{\"error\":\"rate limited\"}", new AtomicReference<>());
        LlmClient client = new OpenAiCompatibleClient(url, null, "m", 100, Duration.ofSeconds(5));

        assertThatThrownBy(() -> client.complete("s", "u"))
                .isInstanceOf(OpenAiCompatibleClient.LlmUnavailableException.class)
                .hasMessageContaining("busy").hasMessageContaining("429");
        assertThat(requests.get()).as("retried, not given up at once").isEqualTo(OpenAiCompatibleClient.ATTEMPTS);
    }

    @Test
    void retriesTransientFailuresAndSucceeds() throws Exception {
        // Seen with Gemini's free tier: an occasional 503 that succeeds on the next attempt.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            int n = requests.incrementAndGet();
            byte[] out = (n == 1 ? "{\"error\":\"overloaded\"}"
                    : "{\"choices\":[{\"message\":{\"content\":\"second time lucky\"}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(n == 1 ? 503 : 200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";

        String reply = new OpenAiCompatibleClient(url, null, "m", 100, Duration.ofSeconds(5)).complete("s", "u");

        assertThat(reply).isEqualTo("second time lucky");
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void anEmptyReplyIsRetriedThenReported() throws Exception {
        // A reasoning model can spend its whole token budget before writing anything.
        String url = startServer(200, "{\"choices\":[{\"message\":{\"content\":\"\"},\"finish_reason\":\"length\"}]}",
                new AtomicReference<>());
        LlmClient client = new OpenAiCompatibleClient(url, null, "m", 100, Duration.ofSeconds(5));

        assertThatThrownBy(() -> client.complete("s", "u"))
                .hasMessageContaining("empty message").hasMessageContaining("length");
    }

    @Test
    void reportsAnEndpointThatIsNotThere() {
        LlmClient dead = new OpenAiCompatibleClient("http://127.0.0.1:1/v1", null, "m", 100, Duration.ofSeconds(2));

        assertThatThrownBy(() -> dead.complete("s", "u"))
                .isInstanceOf(OpenAiCompatibleClient.LlmUnavailableException.class);
    }
}
