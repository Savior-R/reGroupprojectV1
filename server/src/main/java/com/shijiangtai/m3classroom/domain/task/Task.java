package com.shijiangtai.m3classroom.domain.task;

import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.TaskId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public final class Task {
    private final TaskId id;
    private final CourseId courseId;
    private final AccountId createdBy;
    private final Instant createdAt;
    private TaskDraft draft;
    private TaskStatus status;
    private ReferenceMaterial referenceMaterial;
    private TaskSnapshot snapshot;
    private int configVersion;
    private Instant publishedAt;
    private Instant closedAt;
    private Instant deletedAt;
    private AccountId deletedBy;
    private Instant updatedAt;

    private Task(
            TaskId id,
            CourseId courseId,
            AccountId createdBy,
            TaskDraft draft,
            TaskStatus status,
            ReferenceMaterial referenceMaterial,
            TaskSnapshot snapshot,
            int configVersion,
            Instant publishedAt,
            Instant closedAt,
            Instant deletedAt,
            AccountId deletedBy,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.courseId = courseId;
        this.createdBy = createdBy;
        this.draft = draft;
        this.status = status;
        this.referenceMaterial = referenceMaterial;
        this.snapshot = snapshot;
        this.configVersion = configVersion;
        this.publishedAt = publishedAt;
        this.closedAt = closedAt;
        this.deletedAt = deletedAt;
        this.deletedBy = deletedBy;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Creates a task that will be published in the same transaction. The instance is not
     * persisted until {@link #publishInitial(TaskSnapshot, ReferenceMaterial, Instant)} succeeds.
     */
    public static Task create(
            TaskId id, CourseId courseId, AccountId createdBy, TaskDraft draft, Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(now, "now");
        return new Task(id, courseId, createdBy, draft, TaskStatus.PUBLISHED,
                null, null, 1, null, null, null, null, now, now);
    }

    public static Task rehydrate(
            TaskId id,
            CourseId courseId,
            AccountId createdBy,
            TaskDraft draft,
            TaskStatus status,
            ReferenceMaterial referenceMaterial,
            TaskSnapshot snapshot,
            int configVersion,
            Instant publishedAt,
            Instant closedAt,
            Instant deletedAt,
            AccountId deletedBy,
            Instant createdAt,
            Instant updatedAt) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        return new Task(id, courseId, createdBy, draft, status, referenceMaterial, snapshot,
                configVersion, publishedAt, closedAt, deletedAt, deletedBy, createdAt, updatedAt);
    }

    /**
     * Rehydrates a row written before the soft-delete columns existed. New code should
     * pass deletedAt/deletedBy explicitly.
     */
    public static Task rehydrate(
            TaskId id,
            CourseId courseId,
            AccountId createdBy,
            TaskDraft draft,
            TaskStatus status,
            ReferenceMaterial referenceMaterial,
            TaskSnapshot snapshot,
            int configVersion,
            Instant publishedAt,
            Instant closedAt,
            Instant createdAt,
            Instant updatedAt) {
        return rehydrate(id, courseId, createdBy, draft, status, referenceMaterial, snapshot,
                configVersion, publishedAt, closedAt, null, null, createdAt, updatedAt);
    }

    /**
     * Applies an edit. Callers must ensure there are no sessions or submissions first.
     */
    public boolean updateConfiguration(
            TaskDraft newDraft, ReferenceMaterial newMaterial, Instant now) {
        assertEditable();
        Objects.requireNonNull(newDraft, "newDraft");
        Objects.requireNonNull(now, "now");
        boolean modeChanged = draft.mode() != newDraft.mode();
        this.draft = newDraft;
        this.referenceMaterial = newMaterial;
        this.updatedAt = now;
        return modeChanged;
    }

    public void attachMaterial(ReferenceMaterial material, Instant now) {
        assertEditable();
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(now, "now");
        this.referenceMaterial = material;
        this.updatedAt = now;
    }

    public void removeMaterial(Instant now) {
        assertEditable();
        Objects.requireNonNull(now, "now");
        this.referenceMaterial = null;
        this.updatedAt = now;
    }

    public void publishInitial(
            TaskSnapshot snapshot, ReferenceMaterial material, Instant now) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(now, "now");
        this.snapshot = snapshot;
        this.referenceMaterial = material;
        this.status = TaskStatus.PUBLISHED;
        this.configVersion = 1;
        this.publishedAt = now;
        this.updatedAt = now;
    }

    public void republish(TaskSnapshot snapshot, Instant now) {
        assertEditable();
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(now, "now");
        this.snapshot = snapshot;
        this.configVersion += 1;
        this.updatedAt = now;
    }

    public void close(Instant now) {
        Objects.requireNonNull(now, "now");
        if (status == TaskStatus.DELETED) {
            throw new DomainException(ErrorCode.TASK_DELETED, "任务已删除");
        }
        if (status == TaskStatus.CLOSED) {
            throw new DomainException(ErrorCode.TASK_CLOSED, "任务已关闭");
        }
        this.status = TaskStatus.CLOSED;
        this.closedAt = now;
        this.updatedAt = now;
    }

    public void softDelete(AccountId deletedBy, Instant now) {
        Objects.requireNonNull(deletedBy, "deletedBy");
        Objects.requireNonNull(now, "now");
        if (status == TaskStatus.DELETED) {
            throw new DomainException(ErrorCode.TASK_DELETED, "任务已删除");
        }
        this.status = TaskStatus.DELETED;
        this.deletedAt = now;
        this.deletedBy = deletedBy;
        this.updatedAt = now;
    }

    public void assertEditable() {
        if (status == TaskStatus.DELETED) {
            throw new DomainException(ErrorCode.TASK_DELETED,
                    "任务已删除", Map.of("status", status.wireValue()));
        }
        if (status == TaskStatus.CLOSED) {
            throw new DomainException(ErrorCode.TASK_CLOSED,
                    "任务已关闭", Map.of("status", status.wireValue()));
        }
    }

    public TaskId id() {
        return id;
    }

    public CourseId courseId() {
        return courseId;
    }

    public AccountId createdBy() {
        return createdBy;
    }

    public TaskDraft draft() {
        return draft;
    }

    public TaskStatus status() {
        return status;
    }

    public ReferenceMaterial referenceMaterial() {
        return referenceMaterial;
    }

    public TaskSnapshot snapshot() {
        return snapshot;
    }

    public int configVersion() {
        return configVersion;
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    public Instant closedAt() {
        return closedAt;
    }

    public Instant deletedAt() {
        return deletedAt;
    }

    public AccountId deletedBy() {
        return deletedBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
