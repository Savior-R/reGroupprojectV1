package com.shijiangtai.m4eval.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/** Default dimension weights per mode (06 §3.2). Teachers may adjust them per task. */
public final class WeightDefaults {

    private WeightDefaults() {
    }

    public static Map<String, Integer> forMode(EvaluationMode mode) {
        LinkedHashMap<String, Integer> weights = new LinkedHashMap<>();
        switch (mode) {
            case INTERVIEW -> {
                weights.put(DimensionCatalog.CONTENT, 40);
                weights.put(DimensionCatalog.EXPRESSION, 30);
                weights.put(DimensionCatalog.TIME, 30);
            }
            case FREE, SCENARIO -> {
                weights.put(DimensionCatalog.CONTENT, 30);
                weights.put(DimensionCatalog.EXPRESSION, 20);
                weights.put(DimensionCatalog.INTERACTION, 20);
                weights.put(DimensionCatalog.BOARD, 15);
                weights.put(DimensionCatalog.TIME, 15);
            }
        }
        return Map.copyOf(weights);
    }

    /**
     * Normalises a snapshot weight map: keeps only applicable dimensions, falls back to defaults
     * when empty, and rejects a sum other than 100.
     */
    public static Map<String, Integer> normalize(
            EvaluationMode mode, Map<String, Integer> snapshotWeights) {
        Map<String, Integer> defaults = forMode(mode);
        if (snapshotWeights == null || snapshotWeights.isEmpty()) {
            return defaults;
        }
        LinkedHashMap<String, Integer> normalized = new LinkedHashMap<>();
        int sum = 0;
        for (String dimension : DimensionCatalog.applicableDimensions(mode)) {
            Integer weight = snapshotWeights.get(dimension);
            if (weight == null) {
                weight = defaults.getOrDefault(dimension, 0);
            }
            if (weight < 0) {
                throw new IllegalArgumentException("weight must not be negative: " + dimension);
            }
            normalized.put(dimension, weight);
            sum += weight;
        }
        if (sum != 100) {
            throw new IllegalArgumentException("weights must sum to 100, was " + sum);
        }
        return Map.copyOf(normalized);
    }
}
