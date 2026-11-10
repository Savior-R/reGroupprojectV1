package com.shijiangtai.m6training.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.shijiangtai.m6training.application.command.VirtualStudentCommand;
import com.shijiangtai.m6training.domain.TrainingConfigSnapshot;
import com.shijiangtai.m6training.domain.TrainingDefaults;
import com.shijiangtai.m6training.domain.TrainingDuration;
import com.shijiangtai.m6training.domain.TrainingEffectiveConfig;
import com.shijiangtai.m6training.domain.TrainingError;
import com.shijiangtai.m6training.domain.TrainingException;
import com.shijiangtai.m6training.domain.TrainingLevel;
import com.shijiangtai.m6training.domain.TrainingReferenceMaterial;
import com.shijiangtai.m6training.domain.TrainingTemplate;
import com.shijiangtai.m6training.domain.TrainingVirtualStudent;
import com.shijiangtai.m6training.domain.port.SourceTaskPort.SourceTask;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Builds immutable template / effective snapshots (08 §3.2, §5.4).
 *
 * <p>Template snapshots are cloned from the source task's published {@code config_snapshot}; a
 * template never reads the task again. Effective snapshots are built once per training session and
 * never recomputed.
 */
@Component
public class TrainingConfigFactory {
    private final ObjectMapper objectMapper;

    public TrainingConfigFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public TrainingConfigSnapshot fromSourceTask(
            SourceTask task, UUID targetCourseId, TrainingLevel level) {
        M3SnapshotPayload payload;
        try {
            payload = objectMapper.readValue(task.configSnapshotJson(), M3SnapshotPayload.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new TrainingException(TrainingError.TRAINING_SOURCE_INVALID,
                    "源任务快照无法解析", Map.of("source_task_id", task.id().toString()));
        }
        if (payload == null || payload.duration() == null) {
            throw new TrainingException(TrainingError.TRAINING_SOURCE_INVALID,
                    "源任务快照缺少时长", Map.of("source_task_id", task.id().toString()));
        }
        TrainingDuration duration = new TrainingDuration(
                payload.duration().recommendedSeconds(), payload.duration().maxSeconds());
        if (level == TrainingLevel.MOCK_EXAM) {
            duration = duration.fixedToMax();
        }
        List<TrainingVirtualStudent> students = payload.virtualStudents() == null
                ? List.of()
                : payload.virtualStudents().stream().map(this::toDomain).toList();
        TrainingReferenceMaterial material = payload.referenceMaterial() == null
                ? null : toDomain(payload.referenceMaterial());
        return new TrainingConfigSnapshot(
                TrainingDefaults.SCHEMA_VERSION,
                task.id(),
                task.configVersion(),
                targetCourseId,
                level,
                TrainingDefaults.CLASSROOM_MODE,
                payload.topic(),
                payload.textbookVersion(),
                duration,
                payload.weights() == null ? Map.of() : payload.weights(),
                students,
                material);
    }

    /** Template session: fixed topic / duration / weights / material; students replace as a group. */
    public TrainingEffectiveConfig fromTemplate(
            TrainingTemplate template, List<TrainingVirtualStudent> students) {
        TrainingConfigSnapshot config = template.config();
        List<TrainingVirtualStudent> effectiveStudents =
                students == null || students.isEmpty()
                        ? config.virtualStudents() : students;
        return new TrainingEffectiveConfig(
                TrainingDefaults.SCHEMA_VERSION,
                template.id(),
                template.trainingLevel(),
                template.courseId(),
                config.topic(),
                config.duration(),
                config.weights(),
                effectiveStudents,
                config.referenceMaterial());
    }

    /** Free session: defaults for duration / weights; student supplies topic, material, students. */
    public TrainingEffectiveConfig free(
            TrainingLevel level,
            UUID courseId,
            String topic,
            List<TrainingVirtualStudent> students,
            TrainingReferenceMaterial material) {
        String effectiveTopic = topic == null || topic.isBlank()
                ? TrainingDefaults.DEFAULT_TOPIC : topic.trim();
        if (effectiveTopic.length() > TrainingDefaults.MAX_TOPIC_LENGTH) {
            throw new TrainingException(TrainingError.VALIDATION_ERROR,
                    "课题不能超过 100 字", Map.of("field", "topic"));
        }
        return new TrainingEffectiveConfig(
                TrainingDefaults.SCHEMA_VERSION,
                null,
                level,
                courseId,
                effectiveTopic,
                TrainingDefaults.defaultDuration(level),
                TrainingDefaults.weights(),
                students == null || students.isEmpty()
                        ? TrainingDefaults.defaultVirtualStudents() : students,
                material);
    }

    public List<TrainingVirtualStudent> toDomain(List<VirtualStudentCommand> students) {
        if (students == null || students.isEmpty()) {
            return List.of();
        }
        return students.stream().map(this::toDomain).toList();
    }

    private TrainingVirtualStudent toDomain(VirtualStudentCommand student) {
        return new TrainingVirtualStudent(
                student.name(),
                student.avatar(),
                student.personalityText(),
                student.activity(),
                student.questionRate(),
                student.ability(),
                student.attentionBaseline(),
                student.disciplineProbability(),
                student.seatNo() == null ? 0 : student.seatNo(),
                student.sortOrder() == null ? 0 : student.sortOrder());
    }

    private TrainingVirtualStudent toDomain(M3VirtualStudentSnapshot student) {
        return new TrainingVirtualStudent(
                student.name(),
                student.avatar(),
                student.personalityText(),
                student.activity(),
                student.questionRate(),
                student.ability(),
                student.attentionBaseline(),
                student.disciplineProbability(),
                student.seatNo(),
                student.sortOrder());
    }

    private TrainingReferenceMaterial toDomain(M3MaterialSnapshot material) {
        return new TrainingReferenceMaterial(
                material.filename(),
                material.objectKey(),
                material.mime(),
                material.size(),
                material.parsedText(),
                material.charCount());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record M3SnapshotPayload(
            int schemaVersion,
            UUID taskId,
            UUID courseId,
            UUID schoolId,
            String mode,
            String topic,
            String textbookVersion,
            M3DurationSnapshot duration,
            Map<String, Integer> weights,
            M3MaterialSnapshot referenceMaterial,
            List<M3VirtualStudentSnapshot> virtualStudents) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record M3DurationSnapshot(int recommendedSeconds, int maxSeconds) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record M3MaterialSnapshot(
            UUID materialId,
            String filename,
            String objectKey,
            String mime,
            long size,
            String parsedText,
            int charCount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record M3VirtualStudentSnapshot(
            UUID virtualStudentId,
            String name,
            String avatar,
            String personalityText,
            BigDecimal activity,
            BigDecimal questionRate,
            BigDecimal ability,
            BigDecimal attentionBaseline,
            BigDecimal disciplineProbability,
            int seatNo,
            int sortOrder) {
    }
}
