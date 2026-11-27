package com.shijiangtai.m3classroom.infrastructure.perception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.shijiangtai.m3classroom.domain.classroom.HybridTeacherIntentClassifier;
import com.shijiangtai.m3classroom.domain.classroom.RuleBasedTeacherIntentClassifier;
import com.shijiangtai.m3classroom.domain.classroom.TeacherIntent;
import com.shijiangtai.m3classroom.domain.classroom.TeacherUtteranceContext;
import com.shijiangtai.m3classroom.domain.classroom.TeacherUtteranceIntent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Smoke test for the committed training artifact; skipped when it is absent. */
class LinearTeacherIntentModelArtifactTest {

    private static final Path MODEL = Path.of("../ai/models/teacher_intent/v1/model.json");

    @Test
    void artifactLoadsAndRecognizesRepresentativeUtterances() {
        assumeTrue(Files.exists(MODEL), "baseline model artifact not generated");
        LinearTeacherIntentClassifier model =
                new LinearTeacherIntentClassifier(MODEL, new ObjectMapper());

        TeacherUtteranceIntent sitDown = model.classify(context("很好，请坐", List.of()));
        assertThat(sitDown.has(TeacherIntent.END_DIALOGUE)).isTrue();
        assertThat(sitDown.has(TeacherIntent.COMMENT_ANSWER)).isTrue();
        // Cross-language parity with training/teacher_intent/infer.py.
        assertThat(sitDown.confidenceOf(TeacherIntent.END_DIALOGUE))
                .isCloseTo(0.950176, within(1e-4));
        assertThat(sitDown.confidenceOf(TeacherIntent.COMMENT_ANSWER))
                .isCloseTo(0.947447, within(1e-4));

        TeacherUtteranceIntent ask = model.classify(context(
                "张三，你来说说光合作用需要什么条件？",
                List.of(new TeacherUtteranceContext.CandidateStudent(
                        UUID.randomUUID(), "张三"))));
        assertThat(ask.has(TeacherIntent.ASK_STUDENT)).isTrue();
        assertThat(ask.has(TeacherIntent.CALL_ON)).isTrue();

        TeacherUtteranceIntent endQa = model.classify(context("我们继续讲下一部分", List.of()));
        assertThat(endQa.has(TeacherIntent.END_QA)).isTrue();
    }

    @Test
    void hybridKeepsRuleEndingAndModelRecall() {
        assumeTrue(Files.exists(MODEL), "baseline model artifact not generated");
        HybridTeacherIntentClassifier hybrid = new HybridTeacherIntentClassifier(
                new LinearTeacherIntentClassifier(MODEL, new ObjectMapper()),
                new RuleBasedTeacherIntentClassifier());

        TeacherUtteranceIntent intent = hybrid.classify(context("很好，请坐", List.of()));

        assertThat(intent.has(TeacherIntent.END_DIALOGUE)).isTrue();
        assertThat(intent.confidenceOf(TeacherIntent.END_DIALOGUE)).isGreaterThanOrEqualTo(0.9);
        assertThat(intent.has(TeacherIntent.COMMENT_ANSWER)).isTrue();
    }

    private static TeacherUtteranceContext context(
            String text, List<TeacherUtteranceContext.CandidateStudent> candidates) {
        return new TeacherUtteranceContext(
                text, "qa", "dialogue", "teacher", null, candidates);
    }
}
