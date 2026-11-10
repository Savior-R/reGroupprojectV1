package com.shijiangtai.m4eval.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fixed dimension / sub-item rubric (06 §3.1, {@code m4-rubric-v1}).
 *
 * <p>Sub-items and their weights are not configurable. Teachers may only adjust dimension weights
 * in the task configuration, which M4 snapshots and never changes.
 */
public final class DimensionCatalog {

    public static final String RUBRIC_VERSION = "m4-rubric-v1";

    public static final String CONTENT = "content";
    public static final String EXPRESSION = "expression";
    public static final String INTERACTION = "interaction";
    public static final String BOARD = "board";
    public static final String TIME = "time";

    private static final List<String> FREE_DIMENSIONS =
            List.of(CONTENT, EXPRESSION, INTERACTION, BOARD, TIME);
    private static final List<String> INTERVIEW_DIMENSIONS =
            List.of(CONTENT, EXPRESSION, TIME);

    private static final Map<String, Map<String, Integer>> SUB_WEIGHTS = subWeights();

    private DimensionCatalog() {
    }

    public static List<String> applicableDimensions(EvaluationMode mode) {
        return mode == EvaluationMode.INTERVIEW ? INTERVIEW_DIMENSIONS : FREE_DIMENSIONS;
    }

    public static Set<String> subItems(String dimension) {
        Map<String, Integer> weights = SUB_WEIGHTS.get(dimension);
        return weights == null ? Set.of() : weights.keySet();
    }

    public static Map<String, Integer> subWeights(String dimension) {
        Map<String, Integer> weights = SUB_WEIGHTS.get(dimension);
        return weights == null ? Map.of() : weights;
    }

    public static boolean isKnownDimension(String dimension) {
        return SUB_WEIGHTS.containsKey(dimension);
    }

    private static Map<String, Map<String, Integer>> subWeights() {
        Map<String, Map<String, Integer>> all = new LinkedHashMap<>();
        all.put(CONTENT, ordered(
                "accuracy", 30,
                "logic", 20,
                "key_points", 20,
                "terminology", 15,
                "completeness", 15));
        all.put(EXPRESSION, ordered(
                "speech_rate", 20,
                "pause", 20,
                "filler", 20,
                "volume", 20,
                "fluency", 20));
        all.put(INTERACTION, ordered(
                "question_design", 30,
                "feedback", 30,
                "discipline", 20,
                "rhythm", 20));
        all.put(BOARD, ordered(
                "structure", 30,
                "completeness", 25,
                "consistency", 25,
                "usage", 20));
        all.put(TIME, ordered(
                "phases", 25,
                "allocation", 30,
                "pacing", 25,
                "overtime", 20));
        return Map.copyOf(all);
    }

    private static Map<String, Integer> ordered(Object... pairs) {
        LinkedHashMap<String, Integer> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return Map.copyOf(values);
    }
}
