package com.shijiangtai.m3classroom.infrastructure.perception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.application.port.ClassifyResult;
import com.shijiangtai.m3classroom.application.port.OcrResult;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PythonPerceptionHttpClientTest {

    private static final String SERVICE_TOKEN = "test-service-token";

    private final JsonMapper mapper = JsonMapper.builder().build();

    private HttpServer server;
    private volatile RecordedRequest recorded;
    private volatile int responseStatus;
    private volatile String responseBody;
    private volatile long responseDelayMillis;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            recorded = new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("X-Service-Token"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    new String(requestBody, StandardCharsets.UTF_8));
            if (responseDelayMillis > 0) {
                try {
                    Thread.sleep(responseDelayMillis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(responseStatus, body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            } catch (IOException ignored) {
                // the client may have timed out and closed the connection
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void ocrPostsBase64AndMapsBoxes() {
        respond(200, """
                {"text":"牛顿第二定律","boxes":[
                  {"text":"牛顿","x1":10.5,"y1":20.0,"x2":30.5,"y2":40.0,"confidence":0.98},
                  {"text":"第二定律","x1":50.25,"y1":60.0,"x2":70.0,"y2":80.0,"confidence":0.91}],
                 "confidence":0.94,"model_version":"qwen-vl-ocr","latency_ms":123}
                """);
        PythonPerceptionHttpClient client = client(Duration.ofSeconds(15));
        byte[] jpeg = new byte[] {1, 2, 3, 4};

        OcrResult result = client.recognize(jpeg);

        assertThat(result.text()).isEqualTo("牛顿第二定律");
        assertThat(result.boxes()).hasSize(2);
        assertThat(result.boxes().get(0).text()).isEqualTo("牛顿");
        assertThat(result.boxes().get(0).x1()).isEqualTo(10.5);
        assertThat(result.boxes().get(0).y1()).isEqualTo(20.0);
        assertThat(result.boxes().get(0).x2()).isEqualTo(30.5);
        assertThat(result.boxes().get(0).y2()).isEqualTo(40.0);
        assertThat(result.boxes().get(0).confidence()).isEqualByComparingTo("0.98");
        assertThat(result.boxes().get(1).x1()).isEqualTo(50.25);
        assertThat(result.confidence()).isEqualByComparingTo("0.94");
        assertThat(result.modelVersion()).isEqualTo("qwen-vl-ocr");

        assertThat(recorded.method()).isEqualTo("POST");
        assertThat(recorded.path()).isEqualTo("/internal/v1/ocr");
        assertThat(recorded.serviceToken()).isEqualTo(SERVICE_TOKEN);
        assertThat(recorded.contentType()).isEqualTo("application/json");
        JsonNode requestJson = mapper.readTree(recorded.body());
        assertThat(requestJson.get("image_base64").asString())
                .isEqualTo(Base64.getEncoder().encodeToString(jpeg));
        assertThat(requestJson.has("imageBase64")).isFalse();
    }

    @Test
    void classifyPostsSnakeCaseAndMapsLabel() {
        respond(200, """
                {"label":"question","confidence":0.87,
                 "model_version":"gpt-4o-mini","prompt_version":"classroom-classify-v1"}
                """);
        PythonPerceptionHttpClient client = client(Duration.ofSeconds(15));

        ClassifyResult result = client.classify("老师刚才讲了什么？", List.of("a"), "free");

        assertThat(result.label()).isEqualTo("question");
        assertThat(result.confidence()).isEqualByComparingTo("0.87");
        assertThat(result.modelVersion()).isEqualTo("gpt-4o-mini");
        assertThat(result.promptVersion()).isEqualTo("classroom-classify-v1");

        assertThat(recorded.method()).isEqualTo("POST");
        assertThat(recorded.path()).isEqualTo("/internal/v1/classroom/classify");
        assertThat(recorded.serviceToken()).isEqualTo(SERVICE_TOKEN);
        assertThat(recorded.body())
                .isEqualTo("{\"text\":\"老师刚才讲了什么？\",\"recent_texts\":[\"a\"],\"mode\":\"free\"}");
    }

    @Test
    void non200ThrowsRuntimeException() {
        respond(503, "{\"error\":\"unavailable\"}");
        PythonPerceptionHttpClient client = client(Duration.ofSeconds(15));

        assertThatThrownBy(() -> client.recognize(new byte[] {1}))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("OCR 服务不可用");
        assertThatThrownBy(() -> client.classify("你好", List.of(), "free"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("教师话语分类服务不可用");
    }

    @Test
    void timeoutThrowsRuntimeException() {
        respond(200, "{\"text\":\"迟到的响应\",\"boxes\":[],\"model_version\":\"test\"}");
        responseDelayMillis = 300;
        PythonPerceptionHttpClient client =
                client(Duration.ofMillis(50));

        assertThatThrownBy(() -> client.recognize(new byte[] {1}))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("OCR 服务不可用");
    }

    private PythonPerceptionHttpClient client(Duration ocrTimeout) {
        PerceptionAiProperties properties = new PerceptionAiProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                SERVICE_TOKEN,
                ocrTimeout,
                null,
                null);
        return new PythonPerceptionHttpClient(properties, mapper);
    }

    private void respond(int status, String body) {
        responseStatus = status;
        responseBody = body;
        responseDelayMillis = 0;
    }

    private record RecordedRequest(
            String method, String path, String serviceToken, String contentType, String body) {
    }
}
