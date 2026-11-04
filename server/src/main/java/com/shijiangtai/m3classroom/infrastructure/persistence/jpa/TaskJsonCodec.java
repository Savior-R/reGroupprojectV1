package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import com.shijiangtai.m3classroom.domain.task.QuestionConfig;
import com.shijiangtai.m3classroom.domain.task.ReferenceMaterial;
import com.shijiangtai.m3classroom.domain.task.RubricWeights;
import com.shijiangtai.m3classroom.domain.task.ScenarioConfig;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshot;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.QuestionId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class TaskJsonCodec {
    private final ObjectMapper objectMapper;

    public TaskJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String writeTaskSnapshot(TaskSnapshot snapshot) {
        return write(snapshot);
    }

    public TaskSnapshot readTaskSnapshot(String json) {
        return read(json, TaskSnapshot.class);
    }

    public String writeReferenceMaterial(ReferenceMaterial material) {
        return write(material);
    }

    public ReferenceMaterial readReferenceMaterial(String json) {
        return read(json, ReferenceMaterial.class);
    }

    public String writeRubricWeights(RubricWeights weights) {
        return write(weights.asMap());
    }

    @SuppressWarnings("unchecked")
    public RubricWeights readRubricWeights(String json) {
        try {
            Map<String, Object> raw = objectMapper.readValue(json, Map.class);
            LinkedHashMap<String, Integer> values = new LinkedHashMap<>();
            raw.forEach((key, value) -> values.put(key, ((Number) value).intValue()));
            return new RubricWeights(values);
        } catch (Exception exception) {
            throw codecError(exception);
        }
    }

    public String writeQuestionConfig(QuestionConfig config) {
        return write(new QuestionConfigJson(
                config.questionIds().stream().map(QuestionId::value).toList(),
                config.totalDurationSeconds()));
    }

    public QuestionConfig readQuestionConfig(String json) {
        QuestionConfigJson data = read(json, QuestionConfigJson.class);
        return new QuestionConfig(
                data.questionIds().stream().map(QuestionId::new).toList(),
                data.totalDurationSeconds());
    }

    public String writeScenarioConfig(ScenarioConfig scenario) {
        return write(scenario);
    }

    public ScenarioConfig readScenarioConfig(String json) {
        return read(json, ScenarioConfig.class);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw codecError(exception);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception exception) {
            throw codecError(exception);
        }
    }

    private DomainException codecError(Exception exception) {
        return new DomainException(ErrorCode.INTERNAL_ERROR,
                "任务 JSON 编解码失败", Map.of("cause", exception.getClass().getSimpleName()));
    }

    private record QuestionConfigJson(List<UUID> questionIds, int totalDurationSeconds) {
    }
}
