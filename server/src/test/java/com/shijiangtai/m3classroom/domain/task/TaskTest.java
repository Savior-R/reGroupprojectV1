package com.shijiangtai.m3classroom.domain.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.TaskId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskTest {

    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    @Test
    void createStartsPublishedWithoutSnapshot() {
        Task task = task();
        assertThat(task.status()).isEqualTo(TaskStatus.PUBLISHED);
        assertThat(task.configVersion()).isEqualTo(1);
        assertThat(task.createdAt()).isEqualTo(NOW);
        assertThat(task.updatedAt()).isEqualTo(NOW);
        assertThat(task.publishedAt()).isNull();
        assertThat(task.closedAt()).isNull();
        assertThat(task.deletedAt()).isNull();
    }

    @Test
    void modeChangeKeepsMaterialOnlyWhenCallerPassesIt() {
        Task task = taskWithMaterial();
        boolean changed = task.updateConfiguration(
                validInterviewDraft(), null, NOW.plusSeconds(60));
        assertThat(changed).isTrue();
        assertThat(task.referenceMaterial()).isNull();
        assertThat(task.draft().mode()).isEqualTo(TaskMode.INTERVIEW);
    }

    @Test
    void sameModeUpdateReturnsFalseAndKeepsMaterial() {
        Task task = taskWithMaterial();
        TaskDraft changed = new TaskDraft(
                TaskMode.FREE, "新课题", "部编版", 900, 1200,
                RubricWeights.defaults(TaskMode.FREE), null, List.of(), null);
        assertThat(task.updateConfiguration(
                changed, task.referenceMaterial(), NOW.plusSeconds(60))).isFalse();
        assertThat(task.referenceMaterial()).isNotNull();
        assertThat(task.draft().topic()).isEqualTo("新课题");
    }

    @Test
    void updateConfigurationKeepsCreatedAtAndAdvancesUpdatedAt() {
        Task task = task();
        Instant later = NOW.plusSeconds(120);
        task.updateConfiguration(validInterviewDraft(), null, later);
        assertThat(task.createdAt()).isEqualTo(NOW);
        assertThat(task.updatedAt()).isEqualTo(later);
    }

    @Test
    void attachAndRemoveMaterial() {
        Task task = task();
        task.attachMaterial(material(), NOW.plusSeconds(30));
        assertThat(task.referenceMaterial()).isNotNull();
        task.removeMaterial(NOW.plusSeconds(60));
        assertThat(task.referenceMaterial()).isNull();
    }

    @Test
    void publishInitialStoresSnapshotAndSetsVersionOne() {
        Task task = task();
        TaskSnapshot snapshot = snapshotFor(task);

        task.publishInitial(snapshot, null, NOW.plusSeconds(30));

        assertThat(task.status()).isEqualTo(TaskStatus.PUBLISHED);
        assertThat(task.configVersion()).isEqualTo(1);
        assertThat(task.publishedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(task.snapshot()).isSameAs(snapshot);
    }

    @Test
    void republishAdvancesVersion() {
        Task task = task();
        task.publishInitial(snapshotFor(task), null, NOW.plusSeconds(30));
        task.republish(snapshotFor(task), NOW.plusSeconds(40));
        assertThat(task.configVersion()).isEqualTo(2);
        assertThat(task.updatedAt()).isEqualTo(NOW.plusSeconds(40));
    }

    @Test
    void closePublishedMovesToClosed() {
        Task task = task();
        task.publishInitial(snapshotFor(task), null, NOW.plusSeconds(30));

        task.close(NOW.plusSeconds(60));

        assertThat(task.status()).isEqualTo(TaskStatus.CLOSED);
        assertThat(task.closedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(task.updatedAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void closedTaskCannotBeEdited() {
        Task task = task();
        task.close(NOW.plusSeconds(30));
        assertThatThrownBy(() -> task.updateConfiguration(
                validFreeDraft(), null, NOW.plusSeconds(60)))
                .isInstanceOfSatisfying(DomainException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.TASK_CLOSED));
    }

    @Test
    void softDeleteMarksDeletedAndPreservesSnapshot() {
        Task task = task();
        task.publishInitial(snapshotFor(task), null, NOW.plusSeconds(30));
        AccountId adminId = new AccountId(UUID.randomUUID());

        task.softDelete(adminId, NOW.plusSeconds(60));

        assertThat(task.status()).isEqualTo(TaskStatus.DELETED);
        assertThat(task.deletedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(task.deletedBy()).isEqualTo(adminId);
        assertThat(task.snapshot()).isNotNull();
        assertThatThrownBy(() -> task.softDelete(adminId, NOW.plusSeconds(70)))
                .isInstanceOfSatisfying(DomainException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.TASK_DELETED));
    }

    @Test
    void rehydratePreservesFields() {
        Task task = task();
        task.attachMaterial(material(), NOW.plusSeconds(10));
        task.publishInitial(snapshotFor(task), null, NOW.plusSeconds(20));

        Task rehydrated = Task.rehydrate(
                task.id(), task.courseId(), task.createdBy(), task.draft(), task.status(),
                task.referenceMaterial(), task.snapshot(), task.configVersion(),
                task.publishedAt(), task.closedAt(), task.deletedAt(), task.deletedBy(),
                task.createdAt(), task.updatedAt());

        assertThat(rehydrated.status()).isEqualTo(TaskStatus.PUBLISHED);
        assertThat(rehydrated.configVersion()).isEqualTo(1);
        assertThat(rehydrated.snapshot()).isSameAs(task.snapshot());
        assertThat(rehydrated.referenceMaterial()).isSameAs(task.referenceMaterial());
        assertThat(rehydrated.createdAt()).isEqualTo(task.createdAt());
    }

    private TaskSnapshot snapshotFor(Task task) {
        return new TaskSnapshot(
                1,
                task.id().value(),
                task.courseId().value(),
                UUID.randomUUID(),
                task.draft().mode().wireValue(),
                task.draft().topic(),
                task.draft().textbookVersion(),
                new TaskSnapshot.DurationSnapshot(
                        task.draft().durationRecommendedSeconds(),
                        task.draft().durationMaxSeconds()),
                Map.of("content", 30, "expression", 20, "interaction", 20, "board", 15, "time", 15),
                null,
                null,
                List.of(),
                null,
                NOW,
                task.createdBy().value());
    }

    private Task task() {
        return Task.create(
                new TaskId(UUID.randomUUID()),
                new CourseId(UUID.randomUUID()),
                new AccountId(UUID.randomUUID()),
                validFreeDraft(),
                NOW);
    }

    private Task taskWithMaterial() {
        Task task = task();
        task.attachMaterial(material(), NOW.plusSeconds(10));
        return task;
    }

    private TaskDraft validFreeDraft() {
        return new TaskDraft(
                TaskMode.FREE, "荷塘月色", "部编版", 900, 1200,
                RubricWeights.defaults(TaskMode.FREE), null, List.of(), null);
    }

    private TaskDraft validInterviewDraft() {
        return new TaskDraft(
                TaskMode.INTERVIEW, "结构化面试", null, 300, 300,
                RubricWeights.defaults(TaskMode.INTERVIEW), null, List.of(),
                new QuestionConfig(List.of(), 300));
    }

    private ReferenceMaterial material() {
        return new ReferenceMaterial(UUID.randomUUID(), "荷塘月色.txt", "key",
                "text/plain", 1024, "荷塘月色课文内容", 8);
    }
}
