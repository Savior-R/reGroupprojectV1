package com.shijiangtai.m2course.domain;

import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SchoolId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 课程聚合。
 *
 * <p>持有课程名称、所属学校、专业范围、老师组与归档状态；{@code end_at} 是教学结束，
 * 归档是管理动作，两者都不物理删除课程。
 */
public final class Course {
    private final CourseId id;
    private final SchoolId schoolId;
    private final AccountId createdBy;
    private final Instant createdAt;
    private final Set<MajorId> majorIds;
    private final List<CourseTeacher> teachers;
    private String name;
    private CourseStatus status;
    private Instant endAt;
    private Instant archivedAt;
    private Instant updatedAt;

    private Course(
            CourseId id,
            SchoolId schoolId,
            AccountId createdBy,
            String name,
            CourseStatus status,
            Instant endAt,
            Instant archivedAt,
            Instant createdAt,
            Instant updatedAt,
            Collection<MajorId> majorIds,
            Collection<CourseTeacher> teachers) {
        this.id = Objects.requireNonNull(id, "id");
        this.schoolId = Objects.requireNonNull(schoolId, "schoolId");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.name = Objects.requireNonNull(name, "name");
        this.status = Objects.requireNonNull(status, "status");
        this.endAt = endAt;
        this.archivedAt = archivedAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.majorIds = new LinkedHashSet<>(majorIds);
        this.teachers = new ArrayList<>(teachers);
    }

    /** 新建课程：状态启用，创建者自动进入老师组。 */
    public static Course create(
            CourseId id,
            SchoolId schoolId,
            AccountId creator,
            String name,
            Collection<MajorId> majorIds,
            Instant endAt,
            Instant now) {
        Course course = new Course(
                id,
                schoolId,
                creator,
                CoursePolicy.normalizeName(name),
                CourseStatus.ACTIVE,
                endAt,
                null,
                now,
                now,
                majorIds,
                List.of(CourseTeacher.active(creator, now)));
        CoursePolicy.requireMajorSelection(course.majorIds);
        return course;
    }

    /** 从持久化状态还原聚合。 */
    public static Course restore(
            CourseId id,
            SchoolId schoolId,
            AccountId createdBy,
            String name,
            CourseStatus status,
            Instant endAt,
            Instant archivedAt,
            Instant createdAt,
            Instant updatedAt,
            Collection<MajorId> majorIds,
            Collection<CourseTeacher> teachers) {
        return new Course(
                id, schoolId, createdBy, name, status, endAt, archivedAt,
                createdAt, updatedAt, majorIds, teachers);
    }

    public void rename(String newName, Instant now) {
        CoursePolicy.requireEditable(this, now);
        this.name = CoursePolicy.normalizeName(newName);
        this.updatedAt = now;
    }

    public void changeEndAt(Instant newEndAt, Instant now) {
        CoursePolicy.requireEditable(this, now);
        CoursePolicy.requireValidEndAt(newEndAt, now);
        this.endAt = newEndAt;
        this.updatedAt = now;
    }

    public void archive(Instant now) {
        if (isArchived()) {
            return;
        }
        this.status = CourseStatus.ARCHIVED;
        this.archivedAt = now;
        this.updatedAt = now;
    }

    public void restoreToActive(Instant now) {
        if (!isArchived()) {
            return;
        }
        this.status = CourseStatus.ACTIVE;
        this.archivedAt = null;
        this.updatedAt = now;
    }

    /** 加入老师组；已在组内返回 409。 */
    public CourseTeacher addTeacher(AccountId accountId, Instant now) {
        CoursePolicy.requireNotArchived(this);
        CourseTeacher existing = teachers.stream()
                .filter(item -> item.accountId().equals(accountId))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            if (existing.isActive()) {
                throw new DomainException(ErrorCode.DUPLICATE_RESOURCE, "该教师已在课程老师组");
            }
            existing.rejoin(now);
            this.updatedAt = now;
            return existing;
        }
        CourseTeacher created = CourseTeacher.active(accountId, now);
        teachers.add(created);
        this.updatedAt = now;
        return created;
    }

    /** 移出老师组；至少保留一名有效教师。 */
    public CourseTeacher removeTeacher(AccountId accountId, Instant now) {
        CoursePolicy.requireNotArchived(this);
        CourseTeacher teacher = teachers.stream()
                .filter(item -> item.isActive() && item.accountId().equals(accountId))
                .findFirst()
                .orElseThrow(() -> new DomainException(
                        ErrorCode.RESOURCE_NOT_FOUND, "该教师不在课程老师组"));
        if (activeTeacherCount() <= 1) {
            throw new DomainException(ErrorCode.LAST_TEACHER, "老师组至少保留一名有效教师");
        }
        teacher.markRemoved(now);
        this.updatedAt = now;
        return teacher;
    }

    public boolean isArchived() {
        return status == CourseStatus.ARCHIVED;
    }

    /** 已到 end_at 即视为教学结束；未设置 end_at 时只有归档触发只读。 */
    public boolean isEnded(Instant now) {
        return endAt != null && !now.isBefore(endAt);
    }

    public boolean hasActiveTeacher(AccountId accountId) {
        return teachers.stream()
                .anyMatch(teacher -> teacher.isActive() && teacher.accountId().equals(accountId));
    }

    public long activeTeacherCount() {
        return teachers.stream().filter(CourseTeacher::isActive).count();
    }

    public List<CourseTeacher> activeTeachers() {
        return teachers.stream().filter(CourseTeacher::isActive).toList();
    }

    /** 学生可见性：启用 + 专业在课程范围内；已结束仍可见，已归档不可见。 */
    public boolean isVisibleToStudent(MajorId studentMajor) {
        return !isArchived() && studentMajor != null && majorIds.contains(studentMajor);
    }

    public boolean coversMajor(MajorId majorId) {
        return majorIds.contains(majorId);
    }

    public boolean coversAnyMajor(Collection<MajorId> candidate) {
        return candidate.stream().anyMatch(majorIds::contains);
    }

    public CourseId id() {
        return id;
    }

    public SchoolId schoolId() {
        return schoolId;
    }

    public AccountId createdBy() {
        return createdBy;
    }

    public String name() {
        return name;
    }

    public CourseStatus status() {
        return status;
    }

    public Instant endAt() {
        return endAt;
    }

    public Instant archivedAt() {
        return archivedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Set<MajorId> majorIds() {
        return Set.copyOf(majorIds);
    }

    public List<CourseTeacher> teachers() {
        return List.copyOf(teachers);
    }
}
