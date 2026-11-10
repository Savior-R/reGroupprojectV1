package com.shijiangtai.m6training.infrastructure.persistence.jpa;

import com.shijiangtai.m6training.domain.TrainingError;
import com.shijiangtai.m6training.domain.TrainingException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Serializes snapshots to the jsonb columns using the application's SNAKE_CASE mapper. */
@Component
public class TrainingJsonCodec {
    private final ObjectMapper objectMapper;

    public TrainingJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException exception) {
            throw corrupt("快照无法序列化", exception);
        }
    }

    public List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    public <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            throw corrupt("快照为空", null);
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (RuntimeException exception) {
            throw corrupt("快照无法解析", exception);
        }
    }

    private TrainingException corrupt(String message, Exception cause) {
        Map<String, Object> details = cause == null
                ? Map.of() : Map.of("cause", cause.getClass().getSimpleName());
        return new TrainingException(TrainingError.INTERNAL_ERROR, message, details);
    }
}
