package com.shijiangtai.m3classroom.domain.task;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

public record RubricWeights(Map<String, Integer> values) {

    public RubricWeights {
        if (values == null || values.isEmpty()) {
            throw invalid();
        }
        Map<String, Integer> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : values.entrySet()) {
            String dimension = entry.getKey();
            Integer value = entry.getValue();
            if (dimension == null || dimension.isBlank()
                    || value == null || value < 0 || value > 100) {
                throw invalid();
            }
            copy.put(dimension, value);
        }
        values = Collections.unmodifiableMap(copy);
    }

    public static RubricWeights of(TaskMode mode, Map<String, Integer> values) {
        if (mode == null || values == null
                || !values.keySet().equals(new LinkedHashSet<>(mode.dimensions()))) {
            throw invalid();
        }
        return new RubricWeights(values);
    }

    public static RubricWeights defaults(TaskMode mode) {
        if (mode == null) {
            throw invalid();
        }
        LinkedHashMap<String, Integer> values = new LinkedHashMap<>();
        switch (mode) {
            case FREE, SCENARIO -> {
                values.put("content", 30);
                values.put("expression", 20);
                values.put("interaction", 20);
                values.put("board", 15);
                values.put("time", 15);
            }
            case INTERVIEW -> {
                values.put("content", 40);
                values.put("expression", 30);
                values.put("time", 30);
            }
        }
        return new RubricWeights(values);
    }

    public int sum() {
        return values.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int of(String dimension) {
        Integer value = values.get(dimension);
        if (value == null) {
            throw invalid();
        }
        return value;
    }

    public Map<String, Integer> asMap() {
        return values;
    }

    private static DomainException invalid() {
        return new DomainException(ErrorCode.TASK_CONFIG_INVALID,
                "权重配置非法", Map.of("field", "rubric_weights"));
    }
}
