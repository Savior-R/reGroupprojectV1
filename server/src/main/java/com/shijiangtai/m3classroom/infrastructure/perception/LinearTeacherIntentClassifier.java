package com.shijiangtai.m3classroom.infrastructure.perception;

import com.shijiangtai.m3classroom.domain.classroom.TeacherIntent;
import com.shijiangtai.m3classroom.domain.classroom.TeacherIntentClassifier;
import com.shijiangtai.m3classroom.domain.classroom.TeacherIntentHit;
import com.shijiangtai.m3classroom.domain.classroom.TeacherUtteranceContext;
import com.shijiangtai.m3classroom.domain.classroom.TeacherUtteranceIntent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Local inference for the offline-trained char n-gram linear model.
 *
 * <p>The model is a plain JSON artifact exported by
 * {@code ai/training/teacher_intent/train.py}: character 1–3 gram TF-IDF plus one
 * independent logistic regression per intent label. This class mirrors the
 * reference Python implementation in {@code training/teacher_intent/infer.py}
 * and the text normalization in {@code training/teacher_intent/text.py}.
 *
 * <p>Names are replaced with {@code 某同学} before vectorization, so the model
 * never memorizes real student names. The model only produces intent scores;
 * the actor still applies the state-aware thresholds and never lets a
 * low-probability label become an action.
 */
public final class LinearTeacherIntentClassifier implements TeacherIntentClassifier {

    public static final String STUDENT_TOKEN = "某同学";
    public static final double DEFAULT_HIT_FLOOR = 0.25;

    private static final List<TeacherIntent> PRECEDENCE = List.of(
            TeacherIntent.END_QA,
            TeacherIntent.END_DIALOGUE,
            TeacherIntent.MANAGE_DISCIPLINE,
            TeacherIntent.CALL_ON,
            TeacherIntent.PROMPT_STUDENT_QUESTION,
            TeacherIntent.ASK_STUDENT,
            TeacherIntent.ASK_CLASS,
            TeacherIntent.COMMENT_ANSWER,
            TeacherIntent.OTHER);

    private final List<TeacherIntent> labels;
    private final Map<String, Integer> vocabulary;
    private final double[] idf;
    private final double[][] weights;
    private final double[] intercepts;
    private final int ngramMin;
    private final int ngramMax;
    private final String modelVersion;
    private final double hitFloor;

    public LinearTeacherIntentClassifier(Path modelPath, ObjectMapper objectMapper) {
        this(readModel(modelPath, objectMapper));
    }

    private LinearTeacherIntentClassifier(Model model) {
        this.labels = model.labels();
        this.vocabulary = model.vocabulary();
        this.idf = model.idf();
        this.weights = model.weights();
        this.intercepts = model.intercepts();
        this.ngramMin = model.ngramMin();
        this.ngramMax = model.ngramMax();
        this.modelVersion = model.version();
        this.hitFloor = DEFAULT_HIT_FLOOR;
    }

    @Override
    public boolean drivesLecturingQuestions() {
        return true;
    }

    @Override
    public TeacherUtteranceIntent classify(TeacherUtteranceContext context) {
        Objects.requireNonNull(context, "context");
        String prepared = prepare(context.text(), context.candidates());
        int[] codePoints = prepared.codePoints().toArray();
        Map<Integer, Integer> counts = new HashMap<>();
        for (int size = ngramMin; size <= ngramMax; size++) {
            for (int offset = 0; offset + size <= codePoints.length; offset++) {
                String ngram = new String(codePoints, offset, size);
                Integer index = vocabulary.get(ngram);
                if (index != null) {
                    counts.merge(index, 1, Integer::sum);
                }
            }
        }
        double squaredNorm = 0.0;
        Map<Integer, Double> sparse = new HashMap<>();
        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            double value = entry.getValue() * idf[entry.getKey()];
            sparse.put(entry.getKey(), value);
            squaredNorm += value * value;
        }
        double norm = Math.sqrt(squaredNorm);

        double[] scores = new double[labels.size()];
        for (int labelIndex = 0; labelIndex < labels.size(); labelIndex++) {
            double score = intercepts[labelIndex];
            if (norm > 0.0) {
                for (Map.Entry<Integer, Double> entry : sparse.entrySet()) {
                    score += (entry.getValue() / norm) * weights[labelIndex][entry.getKey()];
                }
            }
            scores[labelIndex] = sigmoid(score);
        }

        List<TeacherIntentHit> hits = new ArrayList<>();
        for (int index = 0; index < labels.size(); index++) {
            if (scores[index] >= hitFloor) {
                hits.add(new TeacherIntentHit(labels.get(index), scores[index]));
            }
        }
        TeacherIntent primary;
        double confidence;
        if (hits.isEmpty()) {
            int otherIndex = otherIndex();
            primary = TeacherIntent.OTHER;
            confidence = otherIndex >= 0 ? scores[otherIndex] : 0.5;
            hits.add(new TeacherIntentHit(primary, confidence));
        } else {
            hits.sort(Comparator.comparingDouble(TeacherIntentHit::confidence).reversed());
            TeacherIntentHit best = hits.get(0);
            primary = best.intent();
            confidence = best.confidence();
            for (TeacherIntentHit hit : hits) {
                if (hit.confidence() == confidence
                        && precedence(hit.intent()) < precedence(primary)) {
                    primary = hit.intent();
                }
            }
        }
        return new TeacherUtteranceIntent(
                hits, primary, confidence, null, null, modelVersion);
    }

    private int otherIndex() {
        return labels.indexOf(TeacherIntent.OTHER);
    }

    private static int precedence(TeacherIntent intent) {
        int index = PRECEDENCE.indexOf(intent);
        return index < 0 ? PRECEDENCE.size() : index;
    }

    private static double sigmoid(double value) {
        if (value >= 0.0) {
            double exponent = Math.exp(-value);
            return 1.0 / (1.0 + exponent);
        }
        double exponent = Math.exp(value);
        return exponent / (1.0 + exponent);
    }

    static String prepare(String text, List<TeacherUtteranceContext.CandidateStudent> candidates) {
        String masked = text == null ? "" : text;
        if (candidates != null && !candidates.isEmpty()) {
            Set<String> names = new HashSet<>();
            for (TeacherUtteranceContext.CandidateStudent candidate : candidates) {
                if (candidate.name() != null && !candidate.name().isBlank()) {
                    names.add(candidate.name());
                }
            }
            List<String> ordered = new ArrayList<>(names);
            ordered.sort(Comparator.comparingInt(String::length).reversed());
            for (String name : ordered) {
                masked = masked.replace(name, STUDENT_TOKEN);
            }
        }
        StringBuilder normalized = new StringBuilder();
        masked.codePoints()
                .filter(codePoint -> Character.isLetterOrDigit(codePoint) || codePoint == '_')
                .forEach(normalized::appendCodePoint);
        return normalized.toString();
    }

    private static Model readModel(Path path, ObjectMapper objectMapper) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(objectMapper, "objectMapper");
        try {
            JsonNode root = objectMapper.readTree(Files.readString(path, StandardCharsets.UTF_8));
            String format = root.get("format").asString();
            if (!"linear-char-ngram-v1".equals(format)) {
                throw new IllegalStateException("unsupported teacher-intent model format: " + format);
            }
            String version = root.get("version").asString();
            List<TeacherIntent> labels = new ArrayList<>();
            for (JsonNode label : root.get("labels")) {
                labels.add(TeacherIntent.fromWire(label.asString()));
            }
            if (labels.isEmpty()) {
                throw new IllegalStateException("teacher-intent model has no labels");
            }
            JsonNode vocabularyNode = root.get("vocabulary");
            Map<String, Integer> vocabulary = new HashMap<>();
            for (int index = 0; index < vocabularyNode.size(); index++) {
                vocabulary.put(vocabularyNode.get(index).asString(), index);
            }
            double[] idf = doubles(root.get("idf"), vocabulary.size(), "idf");
            double[][] weights = new double[labels.size()][vocabulary.size()];
            JsonNode weightsNode = root.get("weights");
            if (weightsNode.size() != labels.size()) {
                throw new IllegalStateException("teacher-intent weights/labels mismatch");
            }
            for (int labelIndex = 0; labelIndex < labels.size(); labelIndex++) {
                weights[labelIndex] = doubles(
                        weightsNode.get(labelIndex), vocabulary.size(), "weights[" + labelIndex + "]");
            }
            double[] intercepts = doubles(root.get("intercepts"), labels.size(), "intercepts");
            int ngramMin = root.has("ngram_min") ? root.get("ngram_min").asInt() : 1;
            int ngramMax = root.has("ngram_max") ? root.get("ngram_max").asInt() : 3;
            if (ngramMin < 1 || ngramMax < ngramMin) {
                throw new IllegalStateException("invalid teacher-intent ngram range");
            }
            return new Model(labels, vocabulary, idf, weights, intercepts,
                    ngramMin, ngramMax, version);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException(
                    "failed to load teacher-intent model: " + path, exception);
        }
    }

    private static double[] doubles(JsonNode node, int expected, String field) {
        if (node == null || !node.isArray() || node.size() != expected) {
            throw new IllegalStateException("invalid teacher-intent model field: " + field);
        }
        double[] values = new double[expected];
        for (int index = 0; index < expected; index++) {
            values[index] = node.get(index).asDouble();
        }
        return values;
    }

    private record Model(
            List<TeacherIntent> labels,
            Map<String, Integer> vocabulary,
            double[] idf,
            double[][] weights,
            double[] intercepts,
            int ngramMin,
            int ngramMax,
            String version) {
    }
}
