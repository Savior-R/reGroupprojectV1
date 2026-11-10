package com.shijiangtai.m2course.domain;

import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SchoolId;
import java.time.Instant;
import java.util.Collection;

/**
 * 课程领域规则：角色 / 学校 / 老师组权限，{@code end_at} 与归档状态矩阵，名称与专业范围校验。
 *
 * <p>状态矩阵见 docs/design/03 §4.1：{@code end_at} 是教学结束，归档是管理结束；
 * 两者都不物理删除课程。
 */
public final class CoursePolicy {
    public static final int MAX_NAME_LENGTH = 150;

    private CoursePolicy() {
    }

    public static String normalizeName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "课程名称不能为空");
        }
        String name = raw.trim();
        if (name.length() > MAX_NAME_LENGTH) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "课程名称不能超过 150 个字符");
        }
        return name;
    }

    public static void requireMajorSelection(Collection<MajorId> majorIds) {
        if (majorIds == null || majorIds.isEmpty()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "至少选择一个专业");
        }
    }

    public static void requireValidEndAt(Instant endAt, Instant now) {
        if (endAt != null && !endAt.isAfter(now)) {
            throw new DomainException(ErrorCode.INVALID_END_AT, "课程结束时间必须晚于当前时间");
        }
    }

    /** 只有教师可以创建课程；管理员与学生的创建请求一律 403。 */
    public static void requireCanCreate(CurrentUser user) {
        if (user.type() != AccountType.TEACHER) {
            throw new DomainException(ErrorCode.ROLE_NOT_ALLOWED, "只有教师可以创建课程");
        }
    }

    public static void requireTeacher(CurrentUser user) {
        if (user.type() != AccountType.TEACHER) {
            throw new DomainException(ErrorCode.ROLE_NOT_ALLOWED, "该操作仅限教师");
        }
    }

    public static void requireStudent(CurrentUser user) {
        if (user.type() != AccountType.STUDENT) {
            throw new DomainException(ErrorCode.ROLE_NOT_ALLOWED, "该操作仅限学生");
        }
    }

    public static void requireAdmin(CurrentUser user) {
        if (user.type() != AccountType.ADMIN) {
            throw new DomainException(ErrorCode.FORBIDDEN, "该操作仅限管理员");
        }
    }

    public static void requireSameSchool(CurrentUser user, SchoolId schoolId) {
        if (!user.schoolId().equals(schoolId)) {
            throw new DomainException(ErrorCode.CROSS_SCHOOL_ACCESS, "不能访问其他学校的课程");
        }
    }

    /** 老师组 / 学生名单的读权限：本组教师或本校管理员；学生不可读。 */
    public static void requireMembersReadRight(CurrentUser user, Course course) {
        requireSameSchool(user, course.schoolId());
        if (user.type() == AccountType.ADMIN) {
            return;
        }
        if (user.type() == AccountType.TEACHER && course.hasActiveTeacher(user.accountId())) {
            return;
        }
        if (user.type() == AccountType.TEACHER) {
            throw new DomainException(ErrorCode.TEACHER_NOT_IN_GROUP, "不在课程老师组，不能查看成员");
        }
        throw new DomainException(ErrorCode.FORBIDDEN, "学生不能查看课程成员名单");
    }

    /** 老师组 / 学生名单的写权限：本组教师或本校管理员。 */
    public static void requireManageRight(CurrentUser user, Course course) {
        requireSameSchool(user, course.schoolId());
        if (user.type() == AccountType.ADMIN) {
            return;
        }
        if (user.type() == AccountType.TEACHER && course.hasActiveTeacher(user.accountId())) {
            return;
        }
        if (user.type() == AccountType.TEACHER) {
            throw new DomainException(ErrorCode.TEACHER_NOT_IN_GROUP, "不在课程老师组，不能管理课程");
        }
        throw new DomainException(ErrorCode.FORBIDDEN, "无权管理该课程");
    }

    public static void requireNotArchived(Course course) {
        if (course.isArchived()) {
            throw new DomainException(ErrorCode.COURSE_ARCHIVED, "课程已归档");
        }
    }

    /** 编辑课程名称 / end_at：归档后只读，已结束同样只读。 */
    public static void requireEditable(Course course, Instant now) {
        requireNotArchived(course);
        if (course.isEnded(now)) {
            throw new DomainException(ErrorCode.COURSE_ENDED, "课程已结束，不能修改");
        }
    }

    /** 加入 / 加人 / 老师组变更：归档或已结束都不允许。 */
    public static void requireOpenForMembership(Course course, Instant now) {
        requireNotArchived(course);
        if (course.isEnded(now)) {
            throw new DomainException(ErrorCode.COURSE_ENDED, "课程已结束，不能变更成员");
        }
    }
}
