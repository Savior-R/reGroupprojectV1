package com.shijiangtai.m3classroom.application;

import static com.shijiangtai.m3classroom.TestFixtures.COURSE_ID;
import static com.shijiangtai.m3classroom.TestFixtures.STUDENT;
import static com.shijiangtai.m3classroom.TestFixtures.admin;
import static com.shijiangtai.m3classroom.TestFixtures.coursePort;
import static com.shijiangtai.m3classroom.TestFixtures.otherTeacher;
import static com.shijiangtai.m3classroom.TestFixtures.student;
import static com.shijiangtai.m3classroom.TestFixtures.student2;
import static com.shijiangtai.m3classroom.TestFixtures.teacher;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.m3classroom.domain.session.SessionType;
import com.shijiangtai.m3classroom.domain.submission.Submission;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.support.FakeCourseQueryPort;
import com.shijiangtai.m3classroom.support.InMemorySubmissionRepository;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecordingAccessPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");

    private InMemorySubmissionRepository submissions;
    private RecordingAccessPolicy policy;

    @BeforeEach
    void setUp() {
        FakeCourseQueryPort courses = coursePort();
        submissions = new InMemorySubmissionRepository();
        policy = new RecordingAccessPolicy(new SessionAccessPolicy(courses, submissions));
    }

    @Test
    void ownerStudentMayReadTemporaryRecording() {
        assertThatCode(() -> policy.requireRead(student(), session(SessionStatus.PREPARING)))
                .doesNotThrowAnyException();
    }

    @Test
    void otherStudentIsForbidden() {
        assertCode(() -> policy.requireRead(student2(), session(SessionStatus.PREPARING)),
                ErrorCode.FORBIDDEN);
    }

    @Test
    void adminIsAlwaysForbidden() {
        assertCode(() -> policy.requireRead(admin(), session(SessionStatus.SUBMITTED)),
                ErrorCode.FORBIDDEN);
    }

    @Test
    void courseTeacherMayReadSubmittedSession() {
        Session session = session(SessionStatus.SUBMITTED);
        submissions.save(Submission.create(
                UUID.randomUUID(), session.taskId(), STUDENT, session.id(), null, NOW));

        assertThatCode(() -> policy.requireRead(teacher(), session)).doesNotThrowAnyException();
    }

    @Test
    void courseTeacherCannotReadSubmittedSessionWithoutSubmission() {
        assertCode(() -> policy.requireRead(teacher(), session(SessionStatus.SUBMITTED)),
                ErrorCode.FORBIDDEN);
    }

    @Test
    void teacherCannotReadTemporaryRecording() {
        assertCode(() -> policy.requireRead(teacher(), session(SessionStatus.ENDED)),
                ErrorCode.FORBIDDEN);
    }

    @Test
    void teacherOutsideCourseGroupIsForbidden() {
        Session session = session(SessionStatus.SUBMITTED);
        submissions.save(Submission.create(
                UUID.randomUUID(), session.taskId(), STUDENT, session.id(), null, NOW));

        assertCode(() -> policy.requireRead(otherTeacher(), session), ErrorCode.FORBIDDEN);
    }

    @Test
    void uploadRequiresOwnerStudent() {
        Session session = session(SessionStatus.PREPARING);
        assertThatCode(() -> policy.requireUpload(student(), session))
                .doesNotThrowAnyException();
        assertCode(() -> policy.requireUpload(student2(), session), ErrorCode.FORBIDDEN);
        assertCode(() -> policy.requireUpload(teacher(), session), ErrorCode.FORBIDDEN);
        assertCode(() -> policy.requireUpload(admin(), session), ErrorCode.FORBIDDEN);
    }

    private Session session(SessionStatus status) {
        Session session = Session.createPreparing(
                new SessionId(UUID.randomUUID()), SessionType.TASK,
                new TaskId(UUID.randomUUID()), new CourseId(COURSE_ID),
                STUDENT, TaskMode.FREE, 1, NOW);
        switch (status) {
            case PREPARING -> {
                return session;
            }
            case CANCELLED -> {
                session.cancel(NOW);
                return session;
            }
            default -> session.start(NOW);
        }
        if (status == SessionStatus.IN_PROGRESS) {
            return session;
        }
        session.end(NOW);
        if (status == SessionStatus.ENDED) {
            return session;
        }
        session.markReviewing(NOW);
        if (status == SessionStatus.REVIEWING) {
            return session;
        }
        session.submit(NOW);
        return session;
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.code())
                                .isEqualTo(expected));
    }
}
