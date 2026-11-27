package com.shijiangtai.m6training.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m6training.domain.TrainingConfigSnapshot;
import com.shijiangtai.m6training.domain.TrainingDefaults;
import com.shijiangtai.m6training.domain.TrainingDuration;
import com.shijiangtai.m6training.domain.TrainingEffectiveConfig;
import com.shijiangtai.m6training.domain.TrainingError;
import com.shijiangtai.m6training.domain.TrainingException;
import com.shijiangtai.m6training.domain.TrainingLevel;
import com.shijiangtai.m6training.domain.TrainingOwnerRole;
import com.shijiangtai.m6training.domain.TrainingTemplate;
import com.shijiangtai.m6training.domain.TrainingVirtualStudent;
import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.SchoolId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * M6 module smoke (not the full M6-T01—11 suite): personal-template snapshot rules,
 * mock-exam duration, effective config and the role gate. Runs without Spring or PostgreSQL.
 */
class M6ModuleSmokeTest {

    private final TrainingConfigFactory factory = new TrainingConfigFactory();

    @Test
    void personalTemplateSnapshotUsesSchemaVersionTwo() {
        TrainingConfigSnapshot config = factory.snapshot(
                TrainingLevel.PROGRESSIVE,
                "荷塘月色",
                "部编版",
                900,
                1200,
                weights(),
                List.of(student("林小雨", 1)),
                null);

        assertThat(config.schemaVersion()).isEqualTo(TrainingDefaults.SCHEMA_VERSION);
        assertThat(config.trainingLevel()).isEqualTo(TrainingLevel.PROGRESSIVE);
        assertThat(config.topic()).isEqualTo("荷塘月色");
        assertThat(config.duration().recommendedSeconds()).isEqualTo(900);
        assertThat(config.duration().maxSeconds()).isEqualTo(1200);
        assertThat(config.weights().values().stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(100);
        assertThat(config.virtualStudents()).hasSize(1);
        assertThat(config.virtualStudents().get(0).name()).isEqualTo("林小雨");
    }

    @Test
    void mockExamSnapshotFixesDurationToMax() {
        TrainingConfigSnapshot config = factory.snapshot(
                TrainingLevel.MOCK_EXAM, "模拟考核", null, 900, 1200, weights(), List.of(), null);

        assertThat(config.duration().recommendedSeconds()).isEqualTo(1200);
        assertThat(config.duration().maxSeconds()).isEqualTo(1200);
        assertThat(config.virtualStudents()).hasSize(3);
    }

    @Test
    void invalidWeightsAreRejected() {
        Map<String, Integer> broken = new LinkedHashMap<>(weights());
        broken.put("content", 31);

        assertThatThrownBy(() -> factory.snapshot(
                TrainingLevel.PROGRESSIVE, "荷塘月色", null, 900, 1200,
                broken, List.of(), null))
                .isInstanceOf(TrainingException.class)
                .extracting(exception -> ((TrainingException) exception).error())
                .isEqualTo(TrainingError.TRAINING_TEMPLATE_INVALID);
    }

    @Test
    void durationMustStayWithinOneHour() {
        assertThatThrownBy(() -> new TrainingDuration(1200, 3601))
                .isInstanceOf(TrainingException.class)
                .extracting(exception -> ((TrainingException) exception).error())
                .isEqualTo(TrainingError.VALIDATION_ERROR);
    }

    @Test
    void templateSessionKeepsDurationWeightsAndReplacesStudents() {
        TrainingConfigSnapshot snapshot = factory.snapshot(
                TrainingLevel.PROGRESSIVE, "荷塘月色", "部编版", 900, 1200,
                weights(), List.of(student("林小雨", 1)), null);
        TrainingTemplate template = TrainingTemplate.createManual(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                TrainingOwnerRole.TEACHER,
                "荷塘月色 · 进阶",
                TrainingLevel.PROGRESSIVE,
                snapshot,
                Instant.now());
        TrainingVirtualStudent replacement = student("替换学生", 2);

        TrainingEffectiveConfig configured = factory.forTemplate(
                template, UUID.randomUUID(), UUID.randomUUID(), List.of(replacement));
        TrainingEffectiveConfig defaulted =
                factory.forTemplate(template, null, null, List.of());

        assertThat(configured.templateId()).isEqualTo(template.id());
        assertThat(configured.publicationId()).isNotNull();
        assertThat(configured.topic()).isEqualTo(snapshot.topic());
        assertThat(configured.duration()).isEqualTo(snapshot.duration());
        assertThat(configured.weights()).isEqualTo(snapshot.weights());
        assertThat(configured.virtualStudents()).extracting(TrainingVirtualStudent::name)
                .containsExactly("替换学生");
        assertThat(defaulted.publicationId()).isNull();
        assertThat(defaulted.virtualStudents()).extracting(TrainingVirtualStudent::name)
                .containsExactly("林小雨");
    }

    @Test
    void freeSessionAppliesLevelDefaults() {
        TrainingEffectiveConfig progressive = factory.free(
                TrainingLevel.PROGRESSIVE, null, "  ", List.of(), null);
        TrainingEffectiveConfig mockExam = factory.free(
                TrainingLevel.MOCK_EXAM, null, "模拟考核", List.of(), null);

        assertThat(progressive.templateId()).isNull();
        assertThat(progressive.publicationId()).isNull();
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
                .isEqualTo(TrainingError.TRAINING_TEMPLATE_INVALID);
    }

    @Test
    void templateNameAndSourceAreValidated() {
        TrainingConfigSnapshot snapshot = factory.snapshot(
                TrainingLevel.PROGRESSIVE, "荷塘月色", null, 900, 1200,
                weights(), List.of(), null);

        assertThatThrownBy(() -> TrainingTemplate.createManual(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                TrainingOwnerRole.TEACHER, " ", TrainingLevel.PROGRESSIVE, snapshot,
                Instant.now()))
                .isInstanceOf(TrainingException.class)
                .extracting(exception -> ((TrainingException) exception).error())
                .isEqualTo(TrainingError.TRAINING_TEMPLATE_INVALID);
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
        access.requirePersonal(student);
        access.requirePersonal(teacher);

        assertThatThrownBy(() -> access.requireStudent(teacher))
                .isInstanceOf(TrainingException.class)
                .extracting(exception -> ((TrainingException) exception).error())
                .isEqualTo(TrainingError.ROLE_NOT_ALLOWED);
        assertThatThrownBy(() -> access.requireTeacher(student))
                .isInstanceOf(TrainingException.class)
                .extracting(exception -> ((TrainingException) exception).error())
                .isEqualTo(TrainingError.ROLE_NOT_ALLOWED);
    }

    private Map<String, Integer> weights() {
        Map<String, Integer> weights = new LinkedHashMap<>();
        weights.put("content", 30);
        weights.put("expression", 20);
        weights.put("interaction", 20);
        weights.put("board", 15);
        weights.put("time", 15);
        return weights;
    }

    private TrainingVirtualStudent student(String name, int seat) {
        BigDecimal half = new BigDecimal("0.50");
        return new TrainingVirtualStudent(
                name, "avatar-02", "", half, half, half, half, half, seat, seat);
    }
}
