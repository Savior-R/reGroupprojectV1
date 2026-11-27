package com.shijiangtai.m3classroom.domain.classroom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Combines the local linear model with the deterministic rules.
 *
 * <p>Each label keeps the higher confidence from the two classifiers. Rules
 * remain the source of the student-name slot and comment polarity, because the
 * first linear model only predicts utterance-level intents. This preserves the
 * high-precision phrases (`坐下`, `请坐`, `张三你来说`) while adding the model's
 * paraphrase recall. If the model returns no useful label, the rules result is
 * still present in the merge.
 */
public final class HybridTeacherIntentClassifier implements TeacherIntentClassifier {

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

    private final TeacherIntentClassifier model;
    private final TeacherIntentClassifier rules;
    private final Set<TeacherIntent> modelLabels;

    public HybridTeacherIntentClassifier(
            TeacherIntentClassifier model, TeacherIntentClassifier rules) {
        this(model, rules, Set.of());
    }

    /**
     * @param modelLabels labels whose model confidence may override the rules; empty means all
     *     labels. High-risk labels (call_on / end_dialogue / end_qa) should normally be kept on
     *     rules until a real-data evaluation proves the model safe.
     */
    public HybridTeacherIntentClassifier(
            TeacherIntentClassifier model,
            TeacherIntentClassifier rules,
            Set<TeacherIntent> modelLabels) {
        this.model = Objects.requireNonNull(model, "model");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.modelLabels = modelLabels == null ? Set.of() : Set.copyOf(modelLabels);
    }

    @Override
    public boolean drivesLecturingQuestions() {
        return model.drivesLecturingQuestions()
                && (modelLabels.isEmpty()
                || modelLabels.contains(TeacherIntent.ASK_CLASS)
                || modelLabels.contains(TeacherIntent.ASK_STUDENT));
    }

    @Override
    public TeacherUtteranceIntent classify(TeacherUtteranceContext context) {
        TeacherUtteranceIntent modelResult = model.classify(context);
        TeacherUtteranceIntent ruleResult = rules.classify(context);
        if (modelResult == null) {
            return ruleResult;
        }

        Map<TeacherIntent, Double> merged = new LinkedHashMap<>();
        for (TeacherIntentHit hit : ruleResult.hits()) {
            merged.merge(hit.intent(), hit.confidence(), Math::max);
        }
        for (TeacherIntentHit hit : modelResult.hits()) {
            if (modelLabels.isEmpty() || modelLabels.contains(hit.intent())) {
                merged.merge(hit.intent(), hit.confidence(), Math::max);
            }
        }
        List<TeacherIntentHit> hits = new ArrayList<>();
        for (Map.Entry<TeacherIntent, Double> entry : merged.entrySet()) {
            hits.add(new TeacherIntentHit(entry.getKey(), entry.getValue()));
        }
        if (hits.isEmpty()) {
            return ruleResult;
        }
        hits.sort(Comparator.comparingDouble(TeacherIntentHit::confidence).reversed());
        TeacherIntentHit best = hits.get(0);
        TeacherIntent primary = best.intent();
        for (TeacherIntentHit hit : hits) {
            if (hit.confidence() == best.confidence()
                    && precedence(hit.intent()) < precedence(primary)) {
                primary = hit.intent();
            }
        }
        String studentName = ruleResult.studentName() != null
                ? ruleResult.studentName() : modelResult.studentName();
        String polarity = ruleResult.polarity() != null
                ? ruleResult.polarity() : modelResult.polarity();
        String version = modelResult.modelVersion() + "+rules";
        return new TeacherUtteranceIntent(
                hits, primary, best.confidence(), studentName, polarity, version);
    }

    private static int precedence(TeacherIntent intent) {
        int index = PRECEDENCE.indexOf(intent);
        return index < 0 ? PRECEDENCE.size() : index;
    }
}
