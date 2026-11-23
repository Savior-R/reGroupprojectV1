package com.shijiangtai.m4eval.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M4-T04 / M4-T05: worker callbacks are validated before persistence. */
class EvaluationResultValidatorTest {

    private static final UUID EVALUATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Map<String, Integer> FREE_WEIGHTS = Map.of(
            "content", 30, "expression", 20, "interaction", 20, "board", 15, "time", 15);

    @Test
    void acceptsAValidContentDimension() {
        assertThatCode(() -> EvaluationResultValidator.validate(
                EvaluationMode.FREE,
                FREE_WEIGHTS,
                List.of(content("82.5")),
                List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsDimensionNotApplicableToInterview() {
        assertThatThrownBy(() -> EvaluationResultValidator.validate(
                EvaluationMode.INTERVIEW,
                WeightDefaults.forMode(EvaluationMode.INTERVIEW),
                List.of(dimension("interaction", "80.0")),
                List.of()))
                .isInstanceOf(EvaluationException.class)
                .hasMessageContaining("not applicable");
    }

    @Test
    void rejectsScoreAboveHundred() {
        assertThatThrownBy(() -> EvaluationResultValidator.validate(
                EvaluationMode.FREE,
                FREE_WEIGHTS,
                List.of(content("100.5")),
                List.of()))
                .isInstanceOf(EvaluationException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    void rejectsMoreThanOneDecimal() {
        assertThatThrownBy(() -> EvaluationResultValidator.validate(
                EvaluationMode.FREE,
                FREE_WEIGHTS,
                List.of(content("82.55")),
                List.of()))
                .isInstanceOf(EvaluationException.class)
                .hasMessageContaining("one decimal");
    }

    @Test
    void acceptsMissingAccuracyWhenReferenceIsUnavailable() {
        Map<String, BigDecimal> subs = new LinkedHashMap<>(contentSubScores());
        subs.remove("accuracy");
        DimensionScore content = new DimensionScore(
                UUID.randomUUID(), EVALUATION_ID, "content", new BigDecimal("82.5"),
                subs, confidences(subs), new BigDecimal("0.75"));

        assertThatCode(() -> EvaluationResultValidator.validate(
                EvaluationMode.FREE, FREE_WEIGHTS, List.of(content), List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsADimensionWithoutAnySubItemEvidence() {
        DimensionScore empty = new DimensionScore(
                UUID.randomUUID(), EVALUATION_ID, "content", new BigDecimal("82.5"),
                Map.of(), Map.of(), new BigDecimal("0.85"));

        assertThatThrownBy(() -> EvaluationResultValidator.validate(
                EvaluationMode.FREE, FREE_WEIGHTS, List.of(empty), List.of()))
                .isInstanceOf(EvaluationException.class)
                .hasMessageContaining("at least one sub-item");
    }

    @Test
    void rejectsUnknownSubItem() {
        Map<String, BigDecimal> subs = new LinkedHashMap<>(contentSubScores());
        subs.put("made_up", new BigDecimal("80.0"));
        DimensionScore broken = new DimensionScore(
                UUID.randomUUID(), EVALUATION_ID, "content", new BigDecimal("82.5"),
                subs, confidences(contentSubScores()), new BigDecimal("0.85"));

        assertThatThrownBy(() -> EvaluationResultValidator.validate(
                EvaluationMode.FREE, FREE_WEIGHTS, List.of(broken), List.of()))
                .isInstanceOf(EvaluationException.class)
                .hasMessageContaining("unknown sub-item made_up");
    }

    @Test
    void rejectsSuggestionWithoutTimestampAnchor() {
        EvaluationIssue issue = issue(null, "medium", true);

        assertThatThrownBy(() -> EvaluationResultValidator.validate(
                EvaluationMode.FREE, FREE_WEIGHTS, List.of(content("82.5")), List.of(issue)))
                .isInstanceOf(EvaluationException.class)
                .hasMessageContaining("start_ms");
    }

    @Test
    void rejectsIssueTimeInReverseOrderAtConstruction() {
        assertThatThrownBy(() -> issue(
                        20_000L, "high", false, 10_000L, "evidence", "suggestion"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not exceed");
    }

    @Test
    void normalizeRoundsScoresAndConfidences() {
        DimensionScore rounded = EvaluationResultValidator.normalize(List.of(
                        content("82.55"))).getFirst();

        assertThatCode(() -> EvaluationResultValidator.validate(
                EvaluationMode.FREE, FREE_WEIGHTS, List.of(rounded), List.of()))
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThat(rounded.score())
                .isEqualByComparingTo("82.6");
        org.assertj.core.api.Assertions.assertThat(rounded.confidence().scale()).isEqualTo(2);
    }

    private static DimensionScore content(String score) {
        return dimension("content", score);
    }

    private static DimensionScore dimension(String name, String score) {
        Map<String, BigDecimal> subs = new LinkedHashMap<>();
        for (String sub : DimensionCatalog.subItems(name)) {
            subs.put(sub, new BigDecimal("80.0"));
        }
        return new DimensionScore(
                UUID.randomUUID(),
                EVALUATION_ID,
                name,
                new BigDecimal(score),
                subs,
                confidences(subs),
                new BigDecimal("0.85"));
    }

    private static Map<String, BigDecimal> contentSubScores() {
        Map<String, BigDecimal> subs = new LinkedHashMap<>();
        for (String sub : DimensionCatalog.subItems("content")) {
            subs.put(sub, new BigDecimal("80.0"));
        }
        return subs;
    }

    private static Map<String, BigDecimal> confidences(Map<String, BigDecimal> subs) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        for (String key : subs.keySet()) {
            values.put(key, new BigDecimal("0.80"));
        }
        return values;
    }

    private static EvaluationIssue issue(Long startMs, String severity, boolean suggestion) {
        return issue(startMs, severity, suggestion, null, "evidence", "suggestion");
    }

    private static EvaluationIssue issue(
            Long startMs,
            String severity,
            boolean suggestion,
            Long endMs,
            String evidence,
            String text) {
        return new EvaluationIssue(
                UUID.randomUUID(),
                EVALUATION_ID,
                "content",
                startMs,
                endMs,
                severity,
                evidence,
                text,
                suggestion,
                suggestion ? 1 : null,
                0);
    }
}
