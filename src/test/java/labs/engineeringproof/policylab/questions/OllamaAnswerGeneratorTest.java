package labs.engineeringproof.policylab.questions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import labs.engineeringproof.policylab.policies.PolicyChunk;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class OllamaAnswerGeneratorTest {
    private static final String QUESTION = "fixture question private marker";
    private static final String PASSAGE = "fixture evidence private marker";
    private static final String ANSWER = "fixture answer private marker";
    private static final List<PolicyChunk> EVIDENCE = List.of(
            new PolicyChunk("c1", "d1", "Guide", "v1", "Billing", PASSAGE));
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void parsesTheStructuredDraftAndLogsUsageWithoutContent(CapturedOutput output) throws Exception {
        var requestId = UUID.randomUUID();
        var draft = new AnswerGenerator.Draft("answered", ANSWER, List.of("c1"));
        var server = server(exchange -> {
            try (exchange) {
                var request = json.readTree(exchange.getRequestBody());
                var format = request.get("format").get("properties");
                assertThat(format.get("chunkIds").get("items").get("enum").get(0).asText()).isEqualTo("c1");
                assertThat(format.get("status").get("enum").get(0).asText()).isEqualTo("answered");
                assertThat(request.get("messages").get(1).get("content").asText()).contains(QUESTION, PASSAGE);
                respond(exchange, json.writeValueAsString(draft));
            }
        });
        try {
            assertThat(generator(server, Duration.ofSeconds(3)).generate(requestId, QUESTION, EVIDENCE)).isEqualTo(draft);
            assertThat(output).contains("requestId=" + requestId, "modelEvidenceChunkIds=[c1]", "totalTokens=28")
                    .doesNotContain(QUESTION, PASSAGE, ANSWER);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsInvalidModelJson() throws IOException {
        var server = server(exchange -> {
            try (exchange) {
                respond(exchange, "{invalid");
            }
        });
        try {
            assertThatThrownBy(() -> generator(server, Duration.ofSeconds(3))
                    .generate(UUID.randomUUID(), QUESTION, EVIDENCE))
                    .isInstanceOfSatisfying(QuestionFailure.class,
                            failure -> assertThat(failure.code()).isEqualTo("invalid_model_output"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void timesOutWithoutRetryWhenTheModelDoesNotSendHeaders() throws Exception {
        assertTimeout(false);
    }

    @Test
    void timesOutWithoutRetryWhenTheModelDoesNotFinishTheBody() throws Exception {
        assertTimeout(true);
    }

    private void assertTimeout(boolean sendHeaders) throws Exception {
        var calls = new AtomicInteger();
        var release = new CountDownLatch(1);
        var server = server(exchange -> {
            calls.incrementAndGet();
            try (exchange) {
                if (sendHeaders) {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write("{".getBytes(UTF_8));
                    exchange.getResponseBody().flush();
                }
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            var generator = generator(server, Duration.ofMillis(700));
            long started = System.nanoTime();
            assertThatThrownBy(() -> generator.generate(UUID.randomUUID(), QUESTION, EVIDENCE))
                    .isInstanceOfSatisfying(QuestionFailure.class,
                            failure -> assertThat(failure.code()).isEqualTo("model_unavailable"));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    private OllamaAnswerGenerator generator(HttpServer server, Duration timeout) {
        return new OllamaAnswerGenerator("http://127.0.0.1:" + server.getAddress().getPort(), "fixture", timeout, json);
    }

    private static HttpServer server(HttpHandler handler) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", handler);
        server.start();
        return server;
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, String content) throws IOException {
        var response = json.createObjectNode();
        response.put("model", "fixture");
        response.put("done", true);
        response.put("done_reason", "stop");
        response.put("prompt_eval_count", 20);
        response.put("eval_count", 8);
        response.putObject("message").put("role", "assistant").put("content", content);
        byte[] body = json.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
    }
}
