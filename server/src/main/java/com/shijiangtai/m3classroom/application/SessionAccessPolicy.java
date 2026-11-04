package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.port.CourseQueryPort;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.m3classroom.domain.task.CourseInfo;
import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Authorization rules for a task session (02 §4.3).
 *
 * <p>A student may only access their own session. A teacher in the session's course group may
 * read a submitted session only; teachers cannot read preparing / in-progress / ended sessions
 * and never mutate one. Administrators are always forbidden. A user whose school differs from
 * the session course's school gets {@link ErrorCode#CROSS_SCHOOL_ACCESS}.
 */
@Component
public class SessionAccessPolicy {

    private final CourseQueryPort courseQueryPort;

    public SessionAccessPolicy(CourseQueryPort courseQueryPort) {
        this.courseQueryPort = Objects.requireNonNull(courseQueryPort, "courseQueryPort");
    }

    /** Read access: owner, or a course teacher when the session is submitted. */
    public void requireRead(CurrentUser user, Session session) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(session, "session");
        if (user.type() == AccountType.ADMIN) {
            throw forbidden("无权限访问会话");
        }
        CourseInfo course = courseFor(session);
        requireSameSchool(user, course);
        if (user.type() == AccountType.STUDENT) {
            if (!session.studentAccountId().equals(user.accountId())) {
                throw forbidden("会话不属于当前学生");
            }
            return;
        }
        if (user.type() != AccountType.TEACHER) {
            throw forbidden("无权限访问会话");
        }
        if (session.status() != SessionStatus.SUBMITTED) {
            throw forbidden("仅可查看已提交的会话");
        }
        if (course == null || !courseQueryPort.isTeacherInGroup(
                session.courseId(), user.accountId())) {
            throw forbidden("无权限访问会话");
        }
    }

    /** Lifecycle access: only the student who owns the session. */
    public void requireOwner(CurrentUser user, Session session) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(session, "session");
        if (user.type() != AccountType.STUDENT) {
            throw forbidden("无权限操作会话");
        }
        CourseInfo course = courseFor(session);
        requireSameSchool(user, course);
        if (!session.studentAccountId().equals(user.accountId())) {
            throw forbidden("会话不属于当前学生");
        }
    }

    private CourseInfo courseFor(Session session) {
        if (session.courseId() == null) {
            return null;
        }
        return courseQueryPort.findCourse(session.courseId()).orElse(null);
    }

    private void requireSameSchool(CurrentUser user, CourseInfo course) {
        if (course != null && !course.schoolId().equals(user.schoolId().value())) {
            throw new DomainException(ErrorCode.CROSS_SCHOOL_ACCESS, "跨校访问",
                    Map.of("course_id", course.id().toString()));
        }
    }

    private static DomainException forbidden(String message) {
        return new DomainException(ErrorCode.FORBIDDEN, message);
    }
}
