package com.shijiangtai.m3classroom.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.shijiangtai.m3classroom.domain.classroom.HybridTeacherIntentClassifier;
import com.shijiangtai.m3classroom.domain.classroom.RuleBasedTeacherIntentClassifier;
import com.shijiangtai.m3classroom.domain.classroom.TeacherIntent;
import com.shijiangtai.m3classroom.domain.classroom.TeacherUtteranceContext;
import com.shijiangtai.m3classroom.infrastructure.perception.ShadowTeacherIntentClassifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class M3ConfigTeacherIntentTest {

    private static final Path MODEL = Path.of("../ai/models/teacher_intent/v1/model.json");

    @Test
    void blankPathUsesRules() {
        assertThat(new M3Config().teacherIntentClassifier(
                "", "active", "", new ObjectMapper()))
                .isInstanceOf(RuleBasedTeacherIntentClassifier.class);
    }

    @Test
    void offModeKeepsRulesEvenWithModelPath() {
        assumeTrue(Files.exists(MODEL), "baseline model artifact not generated");

        assertThat(new M3Config().teacherIntentClassifier(
                MODEL.toString(), "off", "", new ObjectMapper()))
                .isInstanceOf(RuleBasedTeacherIntentClassifier.class);
    }

    @Test
    void activeModeUsesHybridClassifierAndSafeLabels() {
        assumeTrue(Files.exists(MODEL), "baseline model artifact not generated");

        var classifier = new M3Config().teacherIntentClassifier(
                MODEL.toString(), "active",
                "ask_class,ask_student,comment_answer", new ObjectMapper());
        assertThat(classifier).isInstanceOf(HybridTeacherIntentClassifier.class);

        var result = classifier.classify(new TeacherUtteranceContext(
                "很好，请坐", "qa", "dialogue", "teacher", null, List.of()));
        // end_dialogue is not in the model allowlist, but the rules still detect it.
        assertThat(result.has(TeacherIntent.END_DIALOGUE)).isTrue();
        assertThat(result.has(TeacherIntent.COMMENT_ANSWER)).isTrue();
    }

    @Test
    void shadowModeUsesShadowClassifier() {
        assumeTrue(Files.exists(MODEL), "baseline model artifact not generated");

        assertThat(new M3Config().teacherIntentClassifier(
                MODEL.toString(), "shadow", "", new ObjectMapper()))
                .isInstanceOf(ShadowTeacherIntentClassifier.class);
    }

    @Test
    void unknownModeFallsBackToRules() {
        assumeTrue(Files.exists(MODEL), "baseline model artifact not generated");

        assertThat(new M3Config().teacherIntentClassifier(
                MODEL.toString(), "surprise", "", new ObjectMapper()))
                .isInstanceOf(RuleBasedTeacherIntentClassifier.class);
    }

    @Test
    void brokenModelPathFallsBackToRules() {
        assertThat(new M3Config().teacherIntentClassifier(
                "/tmp/does-not-exist-teacher-intent.json", "active", "",
                new ObjectMapper()))
                .isInstanceOf(RuleBasedTeacherIntentClassifier.class);
    }
}
