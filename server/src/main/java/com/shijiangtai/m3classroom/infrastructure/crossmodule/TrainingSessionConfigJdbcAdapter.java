package com.shijiangtai.m3classroom.infrastructure.crossmodule;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.shijiangtai.m3classroom.application.port.SchoolDirectoryPort;
import com.shijiangtai.m3classroom.application.port.TrainingSessionConfigPort;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads M6's effective training config for the M3 classroom runtime.
 *
 * <p>This is the symmetrical seam to M6's {@code TrainingClassroomJdbcAdapter}: M6 writes the
 * training session row, M3 reads the M6 config snapshot when it builds the session actor.
 */
@Component
public class TrainingSessionConfigJdbcAdapter implements TrainingSessionConfigPort {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SchoolDirectoryPort schoolDirectory;

    public TrainingSessionConfigJdbcAdapter(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            SchoolDirectoryPort schoolDirectory) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.schoolDirectory = schoolDirectory;
    }

    @Override
    public Optional<TrainingSessionConfig> find(UUID sessionId) {
        List<Row> rows = jdbcTemplate.query("""
                SELECT s.course_id, s.student_account_id, m.effective_config_snapshot, m.created_at
                FROM m6_training.training_session_meta m
                JOIN m3_classroom.sessions s ON s.id = m.session_id
                WHERE m.session_id = ?
                """,
                (rs, rowNum) -> new Row(
                        rs.getObject("course_id", UUID.class),
                        rs.getObject("student_account_id", UUID.class),
                        rs.getString("effective_config_snapshot"),
                        instant(rs.getTimestamp("created_at"))),
                sessionId);
        return rows.stream().findFirst().map(row -> toConfig(sessionId, row));
    }

    private TrainingSessionConfig toConfig(UUID sessionId, Row row) {
        ConfigJson config = objectMapper.readValue(row.snapshot(), ConfigJson.class);
        UUID schoolId = schoolDirectory.findSchoolByAccount(row.studentAccountId())
                .orElseThrow(() -> new DomainException(ErrorCode.INTERNAL_ERROR,
                        "训练学生缺少学校", Map.of("session_id", sessionId.toString())));

        Map<String, Integer> weights = config.weights() == null
                ? Map.of()
                : new LinkedHashMap<>(config.weights());
        int recommended = config.duration() == null
                ? 900 : config.duration().recommendedSeconds();
        int max = config.duration() == null ? 1200 : config.duration().maxSeconds();
        if (max < recommended) {
            max = recommended;
        }

        List<VirtualStudent> students = new ArrayList<>();
        if (config.virtualStudents() != null) {
            for (int index = 0; index < config.virtualStudents().size(); index++) {
                StudentJson student = config.virtualStudents().get(index);
                students.add(new VirtualStudent(
                        UUID.nameUUIDFromBytes(
                                (sessionId + ":" + index).getBytes(StandardCharsets.UTF_8)),
                        text(student.name(), "学生 " + (index + 1)),
                        text(student.avatar(), "avatar-01"),
                        text(student.personalityText(), ""),
                        ratio(student.activity()),
                        ratio(student.questionRate()),
                        ratio(student.ability()),
                        ratio(student.attentionBaseline()),
                        ratio(student.disciplineProbability()),
                        student.seatNo() < 1 ? index + 1 : student.seatNo(),
                        student.sortOrder() < 1 ? index + 1 : student.sortOrder()));
            }
        }

        return new TrainingSessionConfig(
                row.courseId(),
                schoolId,
                row.studentAccountId(),
                text(config.topic(), "训练"),
                recommended,
                max,
                weights,
                List.copyOf(students),
                row.createdAt() == null ? Instant.now() : row.createdAt());
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static BigDecimal ratio(BigDecimal value) {
        if (value == null) {
            return new BigDecimal("0.5");
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (value.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return value;
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record Row(
            UUID courseId,
            UUID studentAccountId,
            String snapshot,
            Instant createdAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ConfigJson(
            String topic,
            DurationJson duration,
            Map<String, Integer> weights,
            @JsonProperty("virtual_students") List<StudentJson> virtualStudents) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DurationJson(
            @JsonProperty("recommended_seconds") int recommendedSeconds,
            @JsonProperty("max_seconds") int maxSeconds) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StudentJson(
            String name,
            String avatar,
            @JsonProperty("personality_text") String personalityText,
            BigDecimal activity,
            @JsonProperty("question_rate") BigDecimal questionRate,
            BigDecimal ability,
            @JsonProperty("attention_baseline") BigDecimal attentionBaseline,
            @JsonProperty("discipline_probability") BigDecimal disciplineProbability,
            @JsonProperty("seat_no") int seatNo,
            @JsonProperty("sort_order") int sortOrder) {
    }
}
