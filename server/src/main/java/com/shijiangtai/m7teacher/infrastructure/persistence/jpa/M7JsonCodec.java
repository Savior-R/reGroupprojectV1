package com.shijiangtai.m7teacher.infrastructure.persistence.jpa;

import com.shijiangtai.m7teacher.domain.M7ErrorCode;
import com.shijiangtai.m7teacher.domain.M7Exception;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** JSONB codec for M7 dimension maps and M3 rubric weights. */
@Component
public class M7JsonCodec {

    private final ObjectMapper objectMapper;

    public M7JsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String writeDimensions(Map<String, BigDecimal> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(dimensions);
        } catch (Exception exception) {
            throw codecError(exception);
        }
    }

    public Map<String, BigDecimal> readDimensions(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(json, Map.class);
            LinkedHashMap<String, BigDecimal> values = new LinkedHashMap<>();
            raw.forEach((key, value) -> {
                if (value instanceof Number number) {
                    values.put(key, new BigDecimal(number.toString()));
                }
            });
            return values;
        } catch (Exception exception) {
            throw codecError(exception);
        }
    }

    public Map<String, Integer> readWeights(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(json, Map.class);
            LinkedHashMap<String, Integer> values = new LinkedHashMap<>();
            raw.forEach((key, value) -> {
                if (value instanceof Number number) {
                    values.put(key, number.intValue());
                }
            });
            return values;
        } catch (Exception exception) {
            throw codecError(exception);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Integer> readWeightsFromSnapshot(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(snapshotJson, Map.class);
            Object weights = raw.get("weights");
            if (!(weights instanceof Map<?, ?> map)) {
                return Map.of();
            }
            LinkedHashMap<String, Integer> values = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (key instanceof String dimension && value instanceof Number number) {
                    values.put(dimension, number.intValue());
                }
            });
            return values;
        } catch (Exception exception) {
            throw codecError(exception);
        }
    }

    private static M7Exception codecError(Exception exception) {
        return new M7Exception(M7ErrorCode.DATA_INCONSISTENT,
                "M7 JSON 数据无法解析", Map.of("cause", exception.getClass().getSimpleName()));
    }
}
