package com.shijiangtai.m4eval.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.m4eval.application.view.EvaluationInputManifestView;
import com.shijiangtai.m4eval.application.view.IssueView;
import com.shijiangtai.m4eval.application.view.ReportView;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wire contract for the report and internal manifest DTOs (06 §9.2 / §9.3).
 *
 * <p>{@code spring.jackson.property-naming-strategy=SNAKE_CASE} is a global application setting;
 * this test pins the field names the Python worker and the frontend rely on.
 */
class EvaluationDtoContractTest {

    private static final UUID EVALUATION_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SUBMISSION_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SESSION_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID STREAM_ID =
            UUID.fromString("55555555-5555-5555-5555-555555555555");

    private final ObjectMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    @Test
    void reportUsesTheDocumentedSnakeCaseFields() {
        ReportView report = new ReportView(
                EVALUATION_ID,
                SUBMISSION_ID,
                null,
                SESSION_ID,
                "free",
                "partial",
                null,
                Map.of("content", 30, "expression", 20, "interaction", 20, "board", 15, "time", 15),
                new ReportView.RadarView(
                        List.of("content", "expression"),
                        java.util.Arrays.asList(new BigDecimal("85.0"), null)),
                List.of(new ReportView.DimensionView(
                        "content",
                        new BigDecimal("85.0"),
                        Map.of("logic", new BigDecimal("85.0")),
                        Map.of("logic", new BigDecimal("0.86")),
                        new BigDecimal("0.86"))),
                List.of("expression"),
                List.of(new IssueView(
                        UUID.fromString("66666666-6666-6666-6666-666666666666"),
                        "content", 12_000L, 18_000L, "high", "证据", "建议", true, 1)),
                List.of("REFERENCE_MISSING"),
                List.of("AUDIO_MISSING"),
                new ReportView.MetaView(
                        "deepseek-flash", "m4-eval-v1", "m4-rubric-v1", 1, 2,
                        null, null, null));

        JsonNode json = mapper.readTree(mapper.writeValueAsString(report));

        assertThat(fieldNames(json)).containsExactlyInAnyOrder(
                "evaluation_id", "submission_id", "training_record_id", "session_id", "mode",
                "status", "total_score", "weights", "radar", "dimensions", "missing_dimensions",
                "suggestions", "warnings", "partial_reasons", "meta");
        assertThat(json.has("evaluationId")).isFalse();
        assertThat(json.get("total_score").isNull()).isTrue();

        assertThat(fieldNames(json.get("radar"))).containsExactly("axes", "values");
        assertThat(json.get("radar").get("values").get(1).isNull()).isTrue();

        JsonNode dimension = json.get("dimensions").get(0);
        assertThat(fieldNames(dimension)).containsExactlyInAnyOrder(
                "dimension", "score", "sub_scores", "sub_confidences", "confidence");

        JsonNode suggestion = json.get("suggestions").get(0);
        assertThat(fieldNames(suggestion)).containsExactlyInAnyOrder(
                "issue_id", "dimension", "start_ms", "end_ms", "severity", "evidence",
                "suggestion", "is_suggestion", "suggestion_rank");
        assertThat(suggestion.get("start_ms").asLong()).isEqualTo(12_000L);

        JsonNode meta = json.get("meta");
        assertThat(fieldNames(meta)).containsExactlyInAnyOrder(
                "model_version", "prompt_version", "rubric_version", "config_version",
                "attempt_count", "created_at", "started_at", "completed_at");
        assertThat(meta.get("config_version").asInt()).isEqualTo(1);
    }

    @Test
    void manifestUsesTheDocumentedSnakeCaseFields() {
        EvaluationInputManifestView manifest = new EvaluationInputManifestView(
                EVALUATION_ID,
                SESSION_ID,
                "task_submission",
                SUBMISSION_ID,
                "free",
                new EvaluationInputManifestView.SessionView(
                        "task", "ended", 900_000L,
                        Map.of("camera", 120, "screen", 0, "audio", 0),
                        "2026-11-20T08:00:00Z", "2026-11-20T08:15:00Z"),
                Map.of("topic", "分数的初步认识", "weights", Map.of("content", 30)),
                List.of(new EvaluationInputManifestView.RecordingView(
                        STREAM_ID, "audio", "https://minio.test/audio?sig=x",
                        900_000L, 0L, "audio/webm")),
                new EvaluationInputManifestView.CountsView(4, 2, 3),
                Map.of("content", 30, "expression", 20, "interaction", 20, "board", 15, "time", 15),
                "m4-rubric-v1",
                Map.of("transcript_complete", true, "missing", List.of()));

        JsonNode json = mapper.readTree(mapper.writeValueAsString(manifest));

        assertThat(fieldNames(json)).containsExactlyInAnyOrder(
                "evaluation_id", "session_id", "source_type", "source_id", "mode", "session",
                "config", "recordings", "counts", "weights", "rubric_version", "flags");
        assertThat(fieldNames(json.get("session"))).containsExactlyInAnyOrder(
                "session_type", "status", "duration_ms", "stream_offsets", "started_at",
                "ended_at");
        assertThat(fieldNames(json.get("recordings").get(0))).containsExactlyInAnyOrder(
                "stream_id", "stream_type", "url", "duration_ms", "offset_ms", "mime");
        assertThat(fieldNames(json.get("counts"))).containsExactlyInAnyOrder(
                "texts", "frames", "events");
        assertThat(json.get("counts").get("texts").asLong()).isEqualTo(4);
    }

    private static List<String> fieldNames(JsonNode node) {
        return node.propertyNames().stream().toList();
    }
}
