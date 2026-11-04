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
    void createDraftStartsInDraftWithVersionZero() {
        Task task = draftTask();
        assertThat(task.status()).isEqualTo(TaskStatus.DRAFT);
        assertThat(task.configVersion()).isZero();
        assertThat(task.createdAt()).isEqualTo(NOW);
        assertThat(task.updatedAt()).isEqualTo(NOW);
        assertThat(task.publishedAt()).isNull();
        assertThat(task.closedAt()).isNull();
    }

    @Test
    void modeChangeReturnsTrueAndClearsMaterial() {
        Task task = draftWithMaterial();
        boolean changed = task.updateDraft(validInterviewDraft(), NOW.plusSeconds(60));
        assertThat(changed).isTrue();
        assertThat(task.referenceMaterial()).isNull();
        assertThat(task.draft().mode()).isEqualTo(TaskMode.INTERVIEW);
    }

    @Test
    void sameModeUpdateReturnsFalseAndKeepsMaterial() {
        Task task = draftWithMaterial();
        TaskDraft changed = new TaskDraft(
                TaskMode.FREE, "新课题", "部编版", 900, 1200,
                RubricWeights.defaults(TaskMode.FREE), null, List.of(), null);
        assertThat(task.updateDraft(changed, NOW.plusSeconds(60))).isFalse();
        assertThat(task.referenceMaterial()).isNotNull();
        assertThat(task.draft().topic()).isEqualTo("新课题");
    }

    @Test
    void closeDraftIsRejected() {
        assertThatThrownBy(() -> draftTask().close(NOW))
                .isInstanceOfSatisfying(DomainException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.TASK_NOT_DRAFT));
    }

    @Test
    void updateDraftKeepsCreatedAtAndAdvancesUpdatedAt() {
        Task task = draftTask();
        Instant later = NOW.plusSeconds(120);
        task.updateDraft(validInterviewDraft(), later);
        assertThat(task.createdAt()).isEqualTo(NOW);
        assertThat(task.updatedAt()).isEqualTo(later);
    }

    @Test
    void attachAndRemoveMaterial() {
        Task task = draftTask();
        task.attachMaterial(material(), NOW.plusSeconds(30));
        assertThat(task.referenceMaterial()).isNotNull();
        task.removeMaterial(NOW.plusSeconds(60));
        assertThat(task.referenceMaterial()).isNull();
    }

    @Test
    void assertDraftPassesForDraft() {
        draftTask().assertDraft();
    }

    @Test
    void publishStoresSnapshotAndSetsVersionOne() {
        Task task = draftTask();
        TaskSnapshot snapshot = snapshotFor(task);

        task.publish(snapshot, NOW.plusSeconds(30));

        assertThat(task.status()).isEqualTo(TaskStatus.PUBLISHED);
        assertThat(task.configVersion()).isEqualTo(1);
        assertThat(task.publishedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(task.snapshot()).isSameAs(snapshot);
    }

    @Test
    void publishedTaskCannotBeEdited() {
        Task task = draftTask();
        task.publish(snapshotFor(task), NOW.plusSeconds(30));

        assertThatThrownBy(() -> task.updateDraft(validFreeDraft(), NOW.plusSeconds(60)))
                .isInstanceOfSatisfying(DomainException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.TASK_NOT_DRAFT));
    }

    @Test
    void closePublishedMovesToClosed() {
        Task task = draftTask();
        task.publish(snapshotFor(task), NOW.plusSeconds(30));

        task.close(NOW.plusSeconds(60));

        assertThat(task.status()).isEqualTo(TaskStatus.CLOSED);
        assertThat(task.closedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(task.updatedAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void rehydratePreservesFields() {
        Task task = draftTask();
        task.attachMaterial(material(), NOW.plusSeconds(10));
        task.publish(snapshotFor(task), NOW.plusSeconds(20));

        Task rehydrated = Task.rehydrate(
                task.id(), task.courseId(), task.createdBy(), task.draft(), task.status(),
                task.referenceMaterial(), task.snapshot(), task.configVersion(),
                task.publishedAt(), task.closedAt(), task.createdAt(), task.updatedAt());

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

    private Task draftTask() {
        return Task.createDraft(
                new TaskId(UUID.randomUUID()),
                new CourseId(UUID.randomUUID()),
                new AccountId(UUID.randomUUID()),
                validFreeDraft(),
                NOW);
    }

    private Task draftWithMaterial() {
        Task task = draftTask();
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
        return new ReferenceMaterial(
                UUID.randomUUID(), "荷塘月色.txt", "materials/key.txt", "text/plain",
                1024, "荷塘月色课文内容", 8);
    }
}
