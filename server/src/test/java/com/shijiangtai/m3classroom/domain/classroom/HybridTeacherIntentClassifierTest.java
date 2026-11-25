package com.shijiangtai.m3classroom.domain.classroom;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HybridTeacherIntentClassifierTest {

    @Test
    void keepsHigherScorePerLabelAndRuleSlots() {
        TeacherIntentClassifier model = context -> new TeacherUtteranceIntent(
                List.of(new TeacherIntentHit(TeacherIntent.CALL_ON, 0.90),
                        new TeacherIntentHit(TeacherIntent.ASK_CLASS, 0.40)),
                TeacherIntent.CALL_ON,
                0.90,
                null,
                null,
                "model-v1");
        TeacherIntentClassifier rules = context -> new TeacherUtteranceIntent(
                List.of(new TeacherIntentHit(TeacherIntent.CALL_ON, 0.60),
                        new TeacherIntentHit(TeacherIntent.END_DIALOGUE, 0.95)),
                TeacherIntent.END_DIALOGUE,
                0.95,
                "张三",
                "positive",
                "rules-v1");

        HybridTeacherIntentClassifier hybrid =
                new HybridTeacherIntentClassifier(model, rules);
        TeacherUtteranceIntent result = hybrid.classify(context("张三请坐"));

        assertThat(result.primary()).isEqualTo(TeacherIntent.END_DIALOGUE);
        assertThat(result.confidenceOf(TeacherIntent.END_DIALOGUE)).isEqualTo(0.95);
        assertThat(result.confidenceOf(TeacherIntent.CALL_ON)).isEqualTo(0.90);
        assertThat(result.confidenceOf(TeacherIntent.ASK_CLASS)).isEqualTo(0.40);
        assertThat(result.studentName()).isEqualTo("张三");
        assertThat(result.polarity()).isEqualTo("positive");
        assertThat(result.modelVersion()).isEqualTo("model-v1+rules");
    }

    @Test
    void modelAllowlistKeepsHighRiskLabelsOnRules() {
        TeacherIntentClassifier model = context -> new TeacherUtteranceIntent(
                List.of(new TeacherIntentHit(TeacherIntent.CALL_ON, 0.99),
                        new TeacherIntentHit(TeacherIntent.ASK_CLASS, 0.80)),
                TeacherIntent.CALL_ON,
                0.99,
                null,
                null,
                "model-v1");
        TeacherIntentClassifier rules = context -> new TeacherUtteranceIntent(
                List.of(new TeacherIntentHit(TeacherIntent.CALL_ON, 0.60),
                        new TeacherIntentHit(TeacherIntent.END_DIALOGUE, 0.95)),
                TeacherIntent.END_DIALOGUE,
                0.95,
                "张三",
                null,
                "rules-v1");

        TeacherUtteranceIntent result = new HybridTeacherIntentClassifier(
                model, rules, Set.of(TeacherIntent.ASK_CLASS))
                .classify(context("张三请坐"));

        assertThat(result.confidenceOf(TeacherIntent.CALL_ON)).isEqualTo(0.60);
        assertThat(result.confidenceOf(TeacherIntent.ASK_CLASS)).isEqualTo(0.80);
        assertThat(result.confidenceOf(TeacherIntent.END_DIALOGUE)).isEqualTo(0.95);
        assertThat(result.primary()).isEqualTo(TeacherIntent.END_DIALOGUE);
        assertThat(result.studentName()).isEqualTo("张三");
    }

    @Test
    void usesRulesWhenModelHasNoUsefulHit() {
        TeacherIntentClassifier model = context -> new TeacherUtteranceIntent(
                List.of(new TeacherIntentHit(TeacherIntent.OTHER, 0.10)),
                TeacherIntent.OTHER,
                0.10,
                null,
                null,
                "model-v1");
        TeacherIntentClassifier rules = context -> new TeacherUtteranceIntent(
                List.of(new TeacherIntentHit(TeacherIntent.END_QA, 0.92)),
                TeacherIntent.END_QA,
                0.92,
                null,
                null,
                "rules-v1");

        TeacherUtteranceIntent result =
                new HybridTeacherIntentClassifier(model, rules).classify(context("下课"));

        assertThat(result.primary()).isEqualTo(TeacherIntent.END_QA);
        assertThat(result.confidenceOf(TeacherIntent.END_QA)).isEqualTo(0.92);
    }

    private static TeacherUtteranceContext context(String text) {
        return new TeacherUtteranceContext(text, "qa", "dialogue", "teacher", null, List.of());
    }
}
