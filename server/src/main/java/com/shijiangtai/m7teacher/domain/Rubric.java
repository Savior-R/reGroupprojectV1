package com.shijiangtai.m7teacher.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dimension weights for one task, read from M3 {@code rubric_weights} / {@code config_snapshot}
 * (07 §4.1, §5.2). M7 never writes them back.
 */
public record Rubric(TaskModeView mode, Map<String, Integer> weights) {

    public Rubric {
        weights = Collections.unmodifiableMap(new LinkedHashMap<>(weights));
    }

    /**
     * Builds the rubric from the M3 snapshot, falling back to M3 defaults when the snapshot is
     * absent. An invalid snapshot is a data-consistency failure, never a silent 0 score.
     */
    public static Rubric fromSnapshot(String mode, Map<String, Integer> snapshotWeights) {
        TaskModeView taskMode = TaskModeView.fromWire(mode);
        if (snapshotWeights == null || snapshotWeights.isEmpty()) {
            return new Rubric(taskMode, taskMode.defaultWeights());
        }
        LinkedHashMap<String, Integer> ordered = new LinkedHashMap<>();
        for (String dimension : taskMode.dimensions()) {
            Integer weight = snapshotWeights.get(dimension);
            if (weight == null) {
                throw inconsistent("任务维度权重缺失", dimension);
            }
            ordered.put(dimension, weight);
        }
        if (snapshotWeights.size() != ordered.size()) {
            throw inconsistent("任务维度权重包含未知维度", null);
        }
        for (Map.Entry<String, Integer> entry : ordered.entrySet()) {
            if (entry.getValue() == null || entry.getValue() < 0 || entry.getValue() > 100) {
                throw inconsistent("任务维度权重越界", entry.getKey());
            }
        }
        int sum = ordered.values().stream().mapToInt(Integer::intValue).sum();
        if (sum != 100) {
            throw inconsistent("任务维度权重之和必须为 100", null);
        }
        return new Rubric(taskMode, ordered);
    }

    public List<String> dimensions() {
        return mode.dimensions();
    }

    public int weightOf(String dimension) {
        return weights.getOrDefault(dimension, 0);
    }

    private static M7Exception inconsistent(String message, String dimension) {
        return dimension == null
                ? new M7Exception(M7ErrorCode.DATA_INCONSISTENT, message)
                : new M7Exception(M7ErrorCode.DATA_INCONSISTENT, message,
                        Map.of("dimension", dimension));
    }
}
