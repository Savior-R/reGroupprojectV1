package com.shijiangtai.m3classroom.infrastructure.perception;

import com.shijiangtai.m3classroom.application.port.ClassifyResult;
import com.shijiangtai.m3classroom.application.port.ContentClient;
import com.shijiangtai.m3classroom.application.port.ContentRequest;
import com.shijiangtai.m3classroom.application.port.ContentResponse;
import com.shijiangtai.m3classroom.application.port.OcrClient;
import com.shijiangtai.m3classroom.application.port.OcrResult;
import com.shijiangtai.m3classroom.application.port.TeacherSpeechClassifier;
import com.shijiangtai.m3classroom.domain.session.OcrBox;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * JDK HTTP adapter to the Python perception service: OCR and teacher-speech classification.
 *
 * <p>Failures (I/O, timeout, non-2xx, malformed or incomplete response) surface as
 * {@link RuntimeException} with a service-specific message; callers degrade rather than change
 * session state.
 */
@Component
@ConditionalOnProperty(name = "m3.ai.base-url")
public class PythonPerceptionHttpClient
        implements OcrClient, TeacherSpeechClassifier, ContentClient {

    private static final String OCR_PATH = "/internal/v1/ocr";
    private static final String CLASSIFY_PATH = "/internal/v1/classroom/classify";
    private static final String QUESTION_PATH = "/internal/v1/classroom/question";
    private static final String ANSWER_PATH = "/internal/v1/classroom/answer";
    private static final String OCR_UNAVAILABLE = "OCR 服务不可用";
    private static final String CLASSIFY_UNAVAILABLE = "教师话语分类服务不可用";
    private static final String CONTENT_UNAVAILABLE = "内容生成服务不可用";
    private static final Duration CONTENT_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final PerceptionAiProperties properties;
    private final String baseUrl;

    public PythonPerceptionHttpClient(
            PerceptionAiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.baseUrl = stripTrailingSlash(properties.baseUrl());
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.asrConnectTimeout())
                .build();
    }

    @Override
    public OcrResult recognize(byte[] jpeg) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("image_base64", Base64.getEncoder().encodeToString(jpeg));
        JsonNode response = post(OCR_PATH, body, properties.ocrTimeout(), OCR_UNAVAILABLE);
        try {
            return mapOcr(response);
        } catch (RuntimeException exception) {
            throw new RuntimeException(OCR_UNAVAILABLE, exception);
        }
    }

    @Override
    public ClassifyResult classify(String text, List<String> recentTexts, String mode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        body.put("recent_texts", recentTexts == null ? List.of() : recentTexts);
        body.put("mode", mode);
        JsonNode response =
                post(CLASSIFY_PATH, body, properties.classifyTimeout(), CLASSIFY_UNAVAILABLE);
        try {
            return mapClassify(response);
        } catch (RuntimeException exception) {
            throw new RuntimeException(CLASSIFY_UNAVAILABLE, exception);
        }
    }

    @Override
    public ContentResponse question(ContentRequest request) {
        Map<String, Object> body = contentBody(request);
        body.put("question_kind", "student_question");
        body.put("max_chars", 120);
        return contentResponse(post(
                QUESTION_PATH, body, CONTENT_TIMEOUT, CONTENT_UNAVAILABLE));
    }

    @Override
    public ContentResponse answer(ContentRequest request) {
        Map<String, Object> body = contentBody(request);
        List<String> recent = request.recentTexts();
        body.put("question", recent.isEmpty() ? "" : recent.get(recent.size() - 1));
        body.put("teacher_feedback", null);
        body.put("max_chars", 180);
        return contentResponse(post(
                ANSWER_PATH, body, CONTENT_TIMEOUT, CONTENT_UNAVAILABLE));
    }

    private Map<String, Object> contentBody(ContentRequest request) {
        Map<String, Object> student = new LinkedHashMap<>();
        student.put("student_id", request.studentName());
        student.put("name", request.studentName());
        student.put("personality", request.personality() == null ? "" : request.personality());
        student.put("ability", request.ability());
        student.put("activity", 0.5);
        student.put("question_rate", 0.5);
        student.put("attention", 0.5);
        student.put("discipline", 0.5);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("session_id", request.sessionId());
        context.put("task_id", request.taskId());
        context.put("mode", request.mode());
        context.put("topic", request.topic());
        context.put("recent_texts", request.recentTexts());
        context.put("screen_text", request.screenText() == null ? "" : request.screenText());
        context.put("scenario", "");
        context.put("student", student);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("context", context);
        return body;
    }

    private ContentResponse contentResponse(JsonNode response) {
        try {
            double confidence = response.has("confidence")
                    && response.get("confidence").isNumber()
                    ? response.get("confidence").asDouble() : 0.0;
            return new ContentResponse(
                    requiredText(response, "text"),
                    confidence,
                    requiredText(response, "model_version"),
                    requiredText(response, "prompt_version"));
        } catch (RuntimeException exception) {
            throw new RuntimeException(CONTENT_UNAVAILABLE, exception);
        }
    }

    private OcrResult mapOcr(JsonNode response) {
        List<OcrBox> boxes = new ArrayList<>();
        JsonNode boxesNode = response.get("boxes");
        if (boxesNode != null && !boxesNode.isNull()) {
            if (!boxesNode.isArray()) {
                throw new IllegalStateException("boxes must be an array");
            }
            for (JsonNode box : boxesNode) {
                boxes.add(new OcrBox(
                        requiredText(box, "text"),
                        requiredDouble(box, "x1"),
                        requiredDouble(box, "y1"),
                        requiredDouble(box, "x2"),
                        requiredDouble(box, "y2"),
                        decimalOrNull(box.get("confidence"))));
            }
        }
        return new OcrResult(
                requiredText(response, "text"),
                List.copyOf(boxes),
                decimalOrNull(response.get("confidence")),
                requiredText(response, "model_version"));
    }

    private ClassifyResult mapClassify(JsonNode response) {
        return new ClassifyResult(
                requiredText(response, "label"),
                requiredDecimal(response, "confidence"),
                requiredText(response, "model_version"),
                requiredText(response, "prompt_version"));
    }

    private JsonNode post(
            String path, Map<String, Object> body, Duration timeout, String unavailableMessage) {
        String json = objectMapper.writeValueAsString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        String serviceToken = properties.serviceToken();
        if (serviceToken != null && !serviceToken.isBlank()) {
            builder.header("X-Service-Token", serviceToken);
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(unavailableMessage, exception);
        } catch (IOException exception) {
            throw new RuntimeException(unavailableMessage, exception);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException(unavailableMessage);
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (RuntimeException exception) {
            throw new RuntimeException(unavailableMessage, exception);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalStateException("missing response field: " + field);
        }
        return value.asString();
    }

    private static double requiredDouble(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new IllegalStateException("missing response field: " + field);
        }
        return value.asDouble();
    }

    private static BigDecimal requiredDecimal(JsonNode node, String field) {
        BigDecimal value = decimalOrNull(node.get(field));
        if (value == null) {
            throw new IllegalStateException("missing response field: " + field);
        }
        return value;
    }

    private static BigDecimal decimalOrNull(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            throw new IllegalStateException("response field is not numeric");
        }
        return BigDecimal.valueOf(value.asDouble());
    }

    private static String stripTrailingSlash(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        int end = baseUrl.length();
        while (end > 0 && baseUrl.charAt(end - 1) == '/') {
            end--;
        }
        return baseUrl.substring(0, end);
    }
}
