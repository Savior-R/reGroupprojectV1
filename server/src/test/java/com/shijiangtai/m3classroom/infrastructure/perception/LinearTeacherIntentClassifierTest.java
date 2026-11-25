package com.shijiangtai.m3classroom.infrastructure.perception;

import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.m3classroom.domain.classroom.TeacherIntent;
import com.shijiangtai.m3classroom.domain.classroom.TeacherUtteranceContext;
import com.shijiangtai.m3classroom.domain.classroom.TeacherUtteranceIntent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class LinearTeacherIntentClassifierTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsModelAndPredictsAmbiguousMultiLabelScores() throws Exception {
        Path model = tempDir.resolve("model.json");
        Files.writeString(model, """
                {
                  "format": "linear-char-ngram-v1",
                  "version": "test-linear-v1",
                  "labels": ["end_dialogue", "comment_answer", "other"],
                  "ngram_min": 1,
                  "ngram_max": 3,
                  "vocabulary": ["请", "坐", "好", "请坐"],
                  "idf": [1, 1, 1, 1],
                  "weights": [
                    [0, 0, 0, 10],
                    [0, 0, 5, 0],
                    [0, 0, 0, 0]
                  ],
                  "intercepts": [-5, -5, -5]
                }
                """);

        LinearTeacherIntentClassifier classifier =
                new LinearTeacherIntentClassifier(model, new ObjectMapper());
        TeacherUtteranceIntent intent = classifier.classify(context("请坐", List.of()));

        assertThat(intent.has(TeacherIntent.END_DIALOGUE)).isTrue();
        assertThat(intent.confidenceOf(TeacherIntent.END_DIALOGUE)).isGreaterThan(0.5);
        assertThat(intent.primary()).isEqualTo(TeacherIntent.END_DIALOGUE);
        assertThat(intent.modelVersion()).isEqualTo("test-linear-v1");
        assertThat(intent.studentName()).isNull();
    }

    @Test
    void masksStudentNamesBeforeVectorization() throws Exception {
        Path model = tempDir.resolve("model.json");
        Files.writeString(model, """
                {
                  "format": "linear-char-ngram-v1",
                  "version": "test-mask-v1",
                  "labels": ["call_on", "other"],
                  "ngram_min": 1,
                  "ngram_max": 3,
                  "vocabulary": ["某", "同", "学", "某同学", "你", "来", "说"],
                  "idf": [1, 1, 1, 1, 1, 1, 1],
                  "weights": [
                    [0, 0, 0, 30, 0, 0, 0],
                    [0, 0, 0, 0, 0, 0, 0]
                  ],
                  "intercepts": [-5, -5]
                }
                """);

        LinearTeacherIntentClassifier classifier =
                new LinearTeacherIntentClassifier(model, new ObjectMapper());
        TeacherUtteranceContext context = new TeacherUtteranceContext(
                "张三你来说",
                "qa",
                "waiting_hands",
                null,
                null,
                List.of(new TeacherUtteranceContext.CandidateStudent(
                        UUID.randomUUID(), "张三")));
        TeacherUtteranceIntent intent = classifier.classify(context);

        assertThat(intent.has(TeacherIntent.CALL_ON)).isTrue();
        assertThat(intent.confidenceOf(TeacherIntent.CALL_ON)).isGreaterThan(0.5);
        assertThat(intent.primary()).isEqualTo(TeacherIntent.CALL_ON);
    }

    @Test
    void lowScoresFallBackToOtherInsteadOfInventingLabels() throws Exception {
        Path model = tempDir.resolve("model.json");
        Files.writeString(model, """
                {
                  "format": "linear-char-ngram-v1",
                  "version": "test-low-v1",
                  "labels": ["call_on", "other"],
                  "ngram_min": 1,
                  "ngram_max": 1,
                  "vocabulary": ["请"],
                  "idf": [1],
                  "weights": [[0], [0]],
                  "intercepts": [-10, -10]
                }
                """);

        LinearTeacherIntentClassifier classifier =
                new LinearTeacherIntentClassifier(model, new ObjectMapper());
        TeacherUtteranceIntent intent = classifier.classify(context("请坐", List.of()));

        assertThat(intent.primary()).isEqualTo(TeacherIntent.OTHER);
        assertThat(intent.has(TeacherIntent.CALL_ON)).isFalse();
        assertThat(intent.hits()).hasSize(1);
    }

    private static TeacherUtteranceContext context(
            String text, List<TeacherUtteranceContext.CandidateStudent> candidates) {
        return new TeacherUtteranceContext(
                text, "lecturing", null, null, null, candidates);
    }
}
