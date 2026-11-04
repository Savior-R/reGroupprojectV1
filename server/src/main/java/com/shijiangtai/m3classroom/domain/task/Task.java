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
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Task createDraft(
            TaskId id, CourseId courseId, AccountId createdBy, TaskDraft draft, Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(now, "now");
        return new Task(id, courseId, createdBy, draft, TaskStatus.DRAFT,
                null, null, 0, null, null, now, now);
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
                configVersion, publishedAt, closedAt, createdAt, updatedAt);
    }

    public boolean updateDraft(TaskDraft newDraft, Instant now) {
        assertDraft();
        Objects.requireNonNull(newDraft, "newDraft");
        Objects.requireNonNull(now, "now");
        boolean modeChanged = draft.mode() != newDraft.mode();
        this.draft = newDraft;
        if (modeChanged) {
            this.referenceMaterial = null;
        }
        this.updatedAt = now;
        return modeChanged;
    }

    public void attachMaterial(ReferenceMaterial material, Instant now) {
        assertDraft();
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(now, "now");
        this.referenceMaterial = material;
        this.updatedAt = now;
    }

    public void removeMaterial(Instant now) {
        assertDraft();
        Objects.requireNonNull(now, "now");
        this.referenceMaterial = null;
        this.updatedAt = now;
    }

    public void close(Instant now) {
        Objects.requireNonNull(now, "now");
        if (status == TaskStatus.DRAFT) {
            throw new DomainException(ErrorCode.TASK_NOT_DRAFT, "任务尚未发布");
        }
        if (status == TaskStatus.CLOSED) {
            throw new DomainException(ErrorCode.TASK_CLOSED, "任务已关闭");
        }
        this.status = TaskStatus.CLOSED;
        this.closedAt = now;
        this.updatedAt = now;
    }

    public void publish(TaskSnapshot snapshot, Instant now) {
        assertDraft();
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(now, "now");
        this.snapshot = snapshot;
        this.status = TaskStatus.PUBLISHED;
        this.configVersion = 1;
        this.publishedAt = now;
        this.updatedAt = now;
    }

    public void assertDraft() {
        if (status != TaskStatus.DRAFT) {
            throw new DomainException(ErrorCode.TASK_NOT_DRAFT,
                    "任务不是草稿", Map.of("status", status.wireValue()));
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

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
