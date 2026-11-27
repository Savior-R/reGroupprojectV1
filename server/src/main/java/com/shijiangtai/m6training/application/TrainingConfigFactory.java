package com.shijiangtai.m6training.application;

import com.shijiangtai.m6training.application.command.VirtualStudentCommand;
import com.shijiangtai.m6training.domain.TrainingConfigSnapshot;
import com.shijiangtai.m6training.domain.TrainingDefaults;
import com.shijiangtai.m6training.domain.TrainingDuration;
import com.shijiangtai.m6training.domain.TrainingEffectiveConfig;
import com.shijiangtai.m6training.domain.TrainingError;
import com.shijiangtai.m6training.domain.TrainingException;
import com.shijiangtai.m6training.domain.TrainingLevel;
import com.shijiangtai.m6training.domain.TrainingMaterial;
import com.shijiangtai.m6training.domain.TrainingReferenceMaterial;
import com.shijiangtai.m6training.domain.TrainingTemplate;
import com.shijiangtai.m6training.domain.TrainingVirtualStudent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;

/**
 * Builds immutable template / effective snapshots (08 §3.3, §5.6).
 *
 * <p>Template snapshots are written once at creation and edited in place until a teacher
 * publishes the template. Effective snapshots are built once per training session and never
 * recomputed.
 */
@Component
public class TrainingConfigFactory {
    private static final Set<String> DIMENSIONS =
            Set.of("content", "expression", "interaction", "board", "time");

    /** Builds a schema-version-2 template snapshot with defaults for omitted fields. */
    public TrainingConfigSnapshot snapshot(
            TrainingLevel level,
            String topic,
            String textbookVersion,
            Integer recommendedSeconds,
            Integer maxSeconds,
            Map<String, Integer> weights,
            List<TrainingVirtualStudent> students,
            TrainingReferenceMaterial material) {
        if (level == null) {
            throw invalid("training_level", "训练档位必填");
        }
        if (topic == null || topic.isBlank()) {
            throw invalid("topic", "课题不能为空");
        }
        TrainingDuration duration = duration(level, recommendedSeconds, maxSeconds);
        Map<String, Integer> normalizedWeights = weights(weights);
        return TrainingConfigSnapshot.of(
                level,
                topic,
                textbookVersion,
                duration,
                normalizedWeights,
                students,
                material);
    }

    /** Session from a personal template or teacher publication: fixed config, student roster replaced. */
    public TrainingEffectiveConfig forTemplate(
            TrainingTemplate template,
            UUID courseId,
            UUID publicationId,
            List<TrainingVirtualStudent> students) {
        TrainingConfigSnapshot config = template.config();
        List<TrainingVirtualStudent> effectiveStudents =
                students == null || students.isEmpty()
                        ? config.virtualStudents() : students;
        return new TrainingEffectiveConfig(
                TrainingDefaults.SCHEMA_VERSION,
                template.id(),
                publicationId,
                template.trainingLevel(),
                courseId,
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
            throw invalid("topic", "课题不能超过 100 字");
        }
        return new TrainingEffectiveConfig(
                TrainingDefaults.SCHEMA_VERSION,
                null,
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
        return IntStream.range(0, students.size())
                .mapToObj(index -> toDomain(students.get(index), index + 1))
                .toList();
    }

    public TrainingReferenceMaterial toReference(TrainingMaterial material) {
        if (material == null) {
            return null;
        }
        return new TrainingReferenceMaterial(
                material.filename(),
                material.objectKey(),
                material.mime(),
                material.size(),
                material.parsedText(),
                material.charCount());
    }

    public TrainingDuration duration(
            TrainingLevel level, Integer recommendedSeconds, Integer maxSeconds) {
        TrainingDuration defaults = TrainingDefaults.defaultDuration(level);
        int recommended = recommendedSeconds == null
                ? defaults.recommendedSeconds() : recommendedSeconds;
        int max = maxSeconds == null ? defaults.maxSeconds() : maxSeconds;
        TrainingDuration duration = new TrainingDuration(recommended, max);
        return level == TrainingLevel.MOCK_EXAM ? duration.fixedToMax() : duration;
    }

    private Map<String, Integer> weights(Map<String, Integer> weights) {
        if (weights == null || weights.isEmpty()) {
            return TrainingDefaults.weights();
        }
        Map<String, Integer> normalized = new LinkedHashMap<>();
        int sum = 0;
        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            if (!DIMENSIONS.contains(entry.getKey())) {
                throw invalid("weights", "未知评分维度 " + entry.getKey());
            }
            Integer value = entry.getValue();
            if (value == null || value < 0 || value > 100) {
                throw invalid("weights", "权重必须在 0—100 之间");
            }
            normalized.put(entry.getKey(), value);
            sum += value;
        }
        if (normalized.size() != DIMENSIONS.size()) {
            throw invalid("weights", "必须提供五个评分维度");
        }
        if (sum != 100) {
            throw invalid("weights", "权重合计必须为 100");
        }
        return normalized;
    }

    private TrainingVirtualStudent toDomain(VirtualStudentCommand student, int position) {
        return new TrainingVirtualStudent(
                student.name(),
                student.avatar(),
                student.personalityText(),
                student.activity(),
                student.questionRate(),
                student.ability(),
                student.attentionBaseline(),
                student.disciplineProbability(),
                student.seatNo() == null ? position : student.seatNo(),
                student.sortOrder() == null ? position : student.sortOrder());
    }

    private static TrainingException invalid(String field, String message) {
        return new TrainingException(TrainingError.TRAINING_TEMPLATE_INVALID,
                message, Map.of("field", field));
    }
}
