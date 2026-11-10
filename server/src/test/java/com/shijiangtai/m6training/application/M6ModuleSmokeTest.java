package com.shijiangtai.m6training.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m6training.domain.TrainingConfigSnapshot;
import com.shijiangtai.m6training.domain.TrainingDefaults;
import com.shijiangtai.m6training.domain.TrainingEffectiveConfig;
import com.shijiangtai.m6training.domain.TrainingError;
import com.shijiangtai.m6training.domain.TrainingException;
import com.shijiangtai.m6training.domain.TrainingLevel;
import com.shijiangtai.m6training.domain.TrainingTemplate;
import com.shijiangtai.m6training.domain.TrainingVirtualStudent;
import com.shijiangtai.m6training.domain.port.SourceTaskPort.SourceTask;
import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.SchoolId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * M6 module smoke (not the full M6-T01—11 suite): M3 snapshot cloning, mock-exam duration,
 * template/free effective config and the role gate. Runs without Spring or PostgreSQL.
 */
class M6ModuleSmokeTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();
    private final TrainingConfigFactory factory = new TrainingConfigFactory(mapper);

    @Test
    void sourceTaskSnapshotIsClonedForProgressiveTraining() {
        UUID taskId = UUID.randomUUID();
        UUID targetCourseId = UUID.randomUUID();
        SourceTask task = task(taskId, 900, 1200);

        TrainingConfigSnapshot config =
                factory.fromSourceTask(task, targetCourseId, TrainingLevel.PROGRESSIVE);

        assertThat(config.sourceTaskId()).isEqualTo(taskId);
        assertThat(config.sourceTaskConfigVersion()).isEqualTo(3);
        assertThat(config.courseId()).isEqualTo(targetCourseId);
        assertThat(config.trainingLevel()).isEqualTo(TrainingLevel.PROGRESSIVE);
        assertThat(config.topic()).isEqualTo("荷塘月色");
        assertThat(config.textbookVersion()).isEqualTo("部编版");
        assertThat(config.duration().recommendedSeconds()).isEqualTo(900);
        assertThat(config.duration().maxSeconds()).isEqualTo(1200);
        assertThat(config.weights().values().stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(100);
        assertThat(config.virtualStudents()).hasSize(1);
        assertThat(config.virtualStudents().get(0).name()).isEqualTo("林小雨");
    }

    @Test
    void mockExamSnapshotFixesDurationToMax() {
        TrainingConfigSnapshot config = factory.fromSourceTask(
                task(UUID.randomUUID(), 900, 1200), UUID.randomUUID(), TrainingLevel.MOCK_EXAM);

        assertThat(config.duration().recommendedSeconds()).isEqualTo(1200);
        assertThat(config.duration().maxSeconds()).isEqualTo(1200);
    }

    @Test
    void invalidSourceSnapshotIsRejected() {
        SourceTask broken = new SourceTask(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "free", "published", 1, "{\"topic\":\"x\"}");

        assertThatThrownBy(() -> factory.fromSourceTask(
                broken, UUID.randomUUID(), TrainingLevel.PROGRESSIVE))
                .isInstanceOf(TrainingException.class)
                .extracting(exception -> ((TrainingException) exception).error())
                .isEqualTo(TrainingError.TRAINING_SOURCE_INVALID);
    }

    @Test
    void templateSessionKeepsDurationWeightsAndReplacesStudents() {
        TrainingConfigSnapshot snapshot = factory.fromSourceTask(
                task(UUID.randomUUID(), 900, 1200), UUID.randomUUID(), TrainingLevel.PROGRESSIVE);
        TrainingTemplate template = TrainingTemplate.create(
                UUID.randomUUID(), snapshot.sourceTaskId(), snapshot.sourceTaskConfigVersion(),
                snapshot.courseId(), TrainingLevel.PROGRESSIVE, snapshot,
                UUID.randomUUID(), Instant.now());
        TrainingVirtualStudent replacement = student("替换学生", 2);

        TrainingEffectiveConfig configured =
                factory.fromTemplate(template, List.of(replacement));
        TrainingEffectiveConfig defaulted = factory.fromTemplate(template, List.of());

        assertThat(configured.templateId()).isEqualTo(template.id());
        assertThat(configured.topic()).isEqualTo(snapshot.topic());
        assertThat(configured.duration()).isEqualTo(snapshot.duration());
        assertThat(configured.weights()).isEqualTo(snapshot.weights());
        assertThat(configured.virtualStudents()).extracting(TrainingVirtualStudent::name)
                .containsExactly("替换学生");
        assertThat(defaulted.virtualStudents()).extracting(TrainingVirtualStudent::name)
                .containsExactly("林小雨");
    }

    @Test
    void freeSessionAppliesLevelDefaults() {
        TrainingEffectiveConfig progressive = factory.free(
                TrainingLevel.PROGRESSIVE, null, "  ", List.of(), null);
        TrainingEffectiveConfig mockExam = factory.free(
                TrainingLevel.MOCK_EXAM, null, "模拟考核", List.of(), null);

        assertThat(progressive.topic()).isEqualTo(TrainingDefaults.DEFAULT_TOPIC);
        assertThat(progressive.duration().recommendedSeconds()).isEqualTo(900);
        assertThat(progressive.duration().maxSeconds()).isEqualTo(1200);
        assertThat(progressive.virtualStudents()).hasSize(3);
        assertThat(progressive.weights().values().stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(100);
        assertThat(mockExam.duration().recommendedSeconds()).isEqualTo(1200);
        assertThat(mockExam.duration().maxSeconds()).isEqualTo(1200);
    }

    @Test
    void freeSessionRejectsTopicLongerThanLimit() {
        String tooLong = "长".repeat(TrainingDefaults.MAX_TOPIC_LENGTH + 1);

        assertThatThrownBy(() -> factory.free(
                TrainingLevel.PROGRESSIVE, null, tooLong, List.of(), null))
                .isInstanceOf(TrainingException.class)
                .extracting(exception -> ((TrainingException) exception).error())
                .isEqualTo(TrainingError.VALIDATION_ERROR);
    }

    @Test
    void onlyStudentsTrainAndOnlyTeachersManageTemplates() {
        M6Access access = new M6Access();
        CurrentUser student = new CurrentUser(new AccountId(UUID.randomUUID()),
                new SchoolId(UUID.randomUUID()), AccountType.STUDENT);
        CurrentUser teacher = new CurrentUser(new AccountId(UUID.randomUUID()),
                new SchoolId(UUID.randomUUID()), AccountType.TEACHER);

        access.requireStudent(student);
        access.requireTeacher(teacher);

        assertThatThrownBy(() -> access.requireStudent(teacher))
                .isInstanceOf(TrainingException.class)
                .extracting(exception -> ((TrainingException) exception).error())
                .isEqualTo(TrainingError.ROLE_NOT_ALLOWED);
        assertThatThrownBy(() -> access.requireTeacher(student))
                .isInstanceOf(TrainingException.class)
                .extracting(exception -> ((TrainingException) exception).error())
                .isEqualTo(TrainingError.ROLE_NOT_ALLOWED);
    }

    private SourceTask task(UUID taskId, int recommendedSeconds, int maxSeconds) {
        String json = """
                {
                  "schema_version": 1,
                  "task_id": "%s",
                  "course_id": "%s",
                  "school_id": "%s",
                  "mode": "free",
                  "topic": "荷塘月色",
                  "textbook_version": "部编版",
                  "duration": {"recommended_seconds": %d, "max_seconds": %d},
                  "weights": {"content": 30, "expression": 20, "interaction": 20,
                              "board": 15, "time": 15},
                  "reference_material": null,
                  "virtual_students": [
                    {"virtual_student_id": "%s", "name": "林小雨", "avatar": "avatar-01",
                     "personality_text": "外向", "activity": 0.60, "question_rate": 0.50,
                     "ability": 0.70, "attention_baseline": 0.50,
                     "discipline_probability": 0.40, "seat_no": 1, "sort_order": 1}
                  ]
                }
                """.formatted(taskId, UUID.randomUUID(), UUID.randomUUID(),
                recommendedSeconds, maxSeconds, UUID.randomUUID());
        return new SourceTask(taskId, UUID.randomUUID(), UUID.randomUUID(),
                "free", "published", 3, json);
    }

    private TrainingVirtualStudent student(String name, int seat) {
        BigDecimal half = new BigDecimal("0.50");
        return new TrainingVirtualStudent(
                name, "avatar-02", "", half, half, half, half, half, seat, seat);
    }
}
