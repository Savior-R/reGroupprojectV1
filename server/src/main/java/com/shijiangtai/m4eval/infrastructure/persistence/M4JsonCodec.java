package com.shijiangtai.m4eval.infrastructure.persistence;

import com.shijiangtai.m4eval.domain.EvaluationErrorCode;
import com.shijiangtai.m4eval.domain.EvaluationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Small JSONB codec used by the M4 JDBC adapters. */
@Component
public class M4JsonCodec {

    private final ObjectMapper objectMapper;

    public M4JsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            throw codecError(exception);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception exception) {
            throw codecError(exception);
        }
    }

    public Map<String, Integer> readIntMap(String json) {
        Map<String, Object> raw = readMap(json);
        LinkedHashMap<String, Integer> values = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (value instanceof Number number) {
                values.put(key, number.intValue());
            }
        });
        return Map.copyOf(values);
    }

    public Map<String, java.math.BigDecimal> readDecimalMap(String json) {
        Map<String, Object> raw = readMap(json);
        LinkedHashMap<String, java.math.BigDecimal> values = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (value instanceof Number number) {
                values.put(key, new java.math.BigDecimal(number.toString()));
            }
        });
        return Map.copyOf(values);
    }

    public List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<?> raw = objectMapper.readValue(json, List.class);
            List<String> values = new ArrayList<>();
            for (Object value : raw) {
                if (value != null) {
                    values.add(String.valueOf(value));
                }
            }
            return List.copyOf(values);
        } catch (Exception exception) {
            throw codecError(exception);
        }
    }

    private static EvaluationException codecError(Exception exception) {
        return new EvaluationException(
                EvaluationErrorCode.EVALUATION_INVALID_RESULT,
                "M4 JSONB 编解码失败: " + exception.getMessage());
    }
}
