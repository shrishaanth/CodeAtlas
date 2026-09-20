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

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String startServer(int status, String body, AtomicReference<String> captured) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
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
    void reportsAnUnreachableOrRefusingEndpoint() throws Exception {
        String url = startServer(429, "{\"error\":\"rate limited\"}", new AtomicReference<>());
        LlmClient client = new OpenAiCompatibleClient(url, null, "m", 100, Duration.ofSeconds(5));

        assertThatThrownBy(() -> client.complete("s", "u"))
                .isInstanceOf(OpenAiCompatibleClient.LlmUnavailableException.class)
                .hasMessageContaining("429");

        LlmClient dead = new OpenAiCompatibleClient("http://127.0.0.1:1/v1", null, "m", 100, Duration.ofSeconds(2));
        assertThatThrownBy(() -> dead.complete("s", "u"))
                .isInstanceOf(OpenAiCompatibleClient.LlmUnavailableException.class);
    }

    @Test
    void reportsAReplyWithoutAMessage() throws Exception {
        String url = startServer(200, "{\"choices\":[]}", new AtomicReference<>());
        LlmClient client = new OpenAiCompatibleClient(url, null, "m", 100, Duration.ofSeconds(5));

        assertThatThrownBy(() -> client.complete("s", "u")).hasMessageContaining("no message");
    }
}
