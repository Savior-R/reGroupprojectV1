package com.shijiangtai.m3classroom.persistence;

import static com.shijiangtai.m3classroom.TestFixtures.COURSE_ID;
import static com.shijiangtai.m3classroom.TestFixtures.SCHOOL_ID;
import static com.shijiangtai.m3classroom.TestFixtures.STUDENT_ID;
import static com.shijiangtai.m3classroom.TestFixtures.TEACHER_ID;
import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.submission.Submission;
import com.shijiangtai.m3classroom.domain.submission.SubmissionRepository;
import com.shijiangtai.shared.domain.SessionId;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class RetentionCandidateQueryIT extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void findsExpiredTemporarySessionsAndSubmissions() {
        UUID openTaskId = seedTask(COURSE_ID, null);
        UUID recentCloseTaskId = seedTask(COURSE_ID, NOW.minus(Duration.ofHours(1)));
        UUID oldCloseTaskId = seedTask(COURSE_ID, NOW.minus(Duration.ofHours(25)));

        // 23h59 after the anchor is still inside the 24h retention window.
        UUID retainedEndedId = seedSession(openTaskId, COURSE_ID, "ended",
                NOW.minus(Duration.ofHours(23).plusMinutes(59)),
                NOW.minus(Duration.ofHours(23).plusMinutes(59)));
        // 24h01 after the anchor is expired.
        UUID expiredEndedId = seedSession(openTaskId, COURSE_ID, "ended",
                NOW.minus(Duration.ofHours(24).plusMinutes(1)),
                NOW.minus(Duration.ofHours(24).plusMinutes(1)));
        // Cancelled sessions use updated_at when ended_at is null.
        UUID expiredCancelledId = seedSession(openTaskId, COURSE_ID, "cancelled",
                null, NOW.minus(Duration.ofHours(25)));
        // Ending 25h ago is extended by a task closed only 1h ago.
        UUID taskCloseExtendsId = seedSession(recentCloseTaskId, COURSE_ID, "ended",
                NOW.minus(Duration.ofHours(25)), NOW.minus(Duration.ofHours(25)));
        // Ending 48h ago plus a task closed 25h ago is still expired.
        UUID taskClosedExpiredId = seedSession(oldCloseTaskId, COURSE_ID, "ended",
                NOW.minus(Duration.ofHours(48)), NOW.minus(Duration.ofHours(48)));
        // Reviewing and failed are terminal candidates too.
        UUID expiredReviewingId = seedSession(openTaskId, COURSE_ID, "reviewing",
                NOW.minus(Duration.ofHours(25)), NOW.minus(Duration.ofHours(25)));
        UUID expiredFailedId = seedSession(openTaskId, COURSE_ID, "failed",
                NOW.minus(Duration.ofHours(25)), NOW.minus(Duration.ofHours(25)));
        // ended_at wins over a later updated_at: 25h-old end is expired even though updated 1h ago.
        UUID anchorPrecedenceId = seedSession(openTaskId, COURSE_ID, "ended",
                NOW.minus(Duration.ofHours(25)), NOW.minus(Duration.ofHours(1)));
        // Submitted sessions are never temporary candidates.
        seedSession(openTaskId, COURSE_ID, "submitted",
                NOW.minus(Duration.ofHours(48)), NOW.minus(Duration.ofHours(48)));

        UUID expiredCourseId = seedCourse(NOW.minus(Duration.ofDays(366)), "expired");
        UUID expiredSubmissionId = seedSubmission(expiredCourseId);
        // Training submissions are M6 retention; the M3C query must ignore them.
        UUID trainingTaskId = seedTask(expiredCourseId, null);
        UUID trainingSessionId = seedSession("training", null, expiredCourseId, "selected",
                NOW.minus(Duration.ofDays(400)), NOW.minus(Duration.ofDays(400)));
        seedSubmission(trainingTaskId, trainingSessionId);

        UUID retainedCourseId = seedCourse(NOW.minus(Duration.ofDays(364)), "retained");
        seedSubmission(retainedCourseId);

        UUID openEndedCourseId = seedCourse(null, "open-ended");
        seedSubmission(openEndedCourseId);

        assertThat(sessionRepository.findExpiredTemporary(NOW))
                .extracting(session -> session.id().value())
                .containsExactlyInAnyOrder(
                        expiredEndedId, expiredCancelledId, taskClosedExpiredId,
                        expiredReviewingId, expiredFailedId, anchorPrecedenceId);
        assertThat(sessionRepository.findExpiredTemporary(NOW))
                .extracting(session -> session.id().value())
                .doesNotContain(retainedEndedId, taskCloseExtendsId);
        assertThat(submissionRepository.findExpiredForRetention(NOW))
                .extracting(Submission::id)
                .containsExactly(expiredSubmissionId);
    }

    @Test
    void includesExpiryBoundaries() {
        UUID openTaskId = seedTask(COURSE_ID, null);
        // Exactly 24h after the anchor is expired (inclusive).
        UUID boundarySessionId = seedSession(openTaskId, COURSE_ID, "ended",
                NOW.minus(Duration.ofHours(24)), NOW.minus(Duration.ofHours(24)));

        Instant exactlyOneYearBefore = NOW.atOffset(ZoneOffset.UTC).minusYears(1).toInstant();
        UUID boundaryCourseId = seedCourse(exactlyOneYearBefore, "boundary");
        UUID boundarySubmissionId = seedSubmission(boundaryCourseId);

        assertThat(sessionRepository.findExpiredTemporary(NOW))
                .extracting(session -> session.id().value())
                .contains(boundarySessionId);
        assertThat(submissionRepository.findExpiredForRetention(NOW))
                .extracting(Submission::id)
                .contains(boundarySubmissionId);
    }

    @Test
    void deleteBySessionReleasesRestrictForeignKeyBeforeSessionDelete() {
        UUID courseId = seedCourse(NOW.minus(Duration.ofDays(366)), "delete-contract");
        UUID taskId = seedTask(courseId, null);
        UUID sessionId = seedSession(taskId, courseId, "submitted",
                NOW.minus(Duration.ofDays(400)), NOW.minus(Duration.ofDays(400)));
        UUID submissionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO m3_classroom.submissions
                    (id, task_id, student_account_id, session_id, status)
                VALUES (?, ?, ?, ?, 'submitted')
                """, submissionId, taskId, STUDENT_ID, sessionId);

        submissionRepository.deleteBySession(new SessionId(sessionId));

        assertThat(jdbc.queryForObject(
                "select count(*) from m3_classroom.submissions where id = ?",
                Long.class, submissionId)).isZero();
        // This would fail on fk_submissions_session (ON DELETE RESTRICT) if the submission
        // delete had not been flushed.
        sessionRepository.deleteById(new SessionId(sessionId));
        assertThat(jdbc.queryForObject(
                "select count(*) from m3_classroom.sessions where id = ?",
                Long.class, sessionId)).isZero();
    }

    private UUID seedCourse(Instant endAt, String label) {
        UUID courseId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO m2_course.courses
                    (id, school_id, name, created_by, status, end_at)
                VALUES (?, ?, ?, ?, 'active', ?)
                """, courseId, SCHOOL_ID, "retention-" + label + "-" + courseId,
                TEACHER_ID, timestamp(endAt));
        return courseId;
    }

    private UUID seedTask(UUID courseId, Instant closedAt) {
        UUID taskId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO m3_classroom.tasks
                    (id, course_id, mode, topic, duration_recommended_seconds,
                     duration_max_seconds, created_by, status, closed_at)
                VALUES (?, ?, 'free', '保留期候选', 900, 1200, ?, 'published', ?)
                """, taskId, courseId, TEACHER_ID, timestamp(closedAt));
        return taskId;
    }

    private UUID seedSession(
            UUID taskId, UUID courseId, String status, Instant endedAt, Instant updatedAt) {
        return seedSession("task", taskId, courseId, status, endedAt, updatedAt);
    }

    private UUID seedSession(
            String sessionType, UUID taskId, UUID courseId, String status,
            Instant endedAt, Instant updatedAt) {
        UUID sessionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO m3_classroom.sessions
                    (id, session_type, task_id, course_id, student_account_id, mode, status,
                     attempt_no, ended_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'free', ?, 1, ?, ?)
                """, sessionId, sessionType, taskId, courseId, STUDENT_ID, status,
                timestamp(endedAt), timestamp(updatedAt));
        return sessionId;
    }

    private UUID seedSubmission(UUID courseId) {
        UUID taskId = seedTask(courseId, null);
        UUID sessionId = seedSession(taskId, courseId, "submitted",
                NOW.minus(Duration.ofDays(400)), NOW.minus(Duration.ofDays(400)));
        return seedSubmission(taskId, sessionId);
    }

    private UUID seedSubmission(UUID taskId, UUID sessionId) {
        UUID submissionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO m3_classroom.submissions
                    (id, task_id, student_account_id, session_id, status)
                VALUES (?, ?, ?, ?, 'submitted')
                """, submissionId, taskId, STUDENT_ID, sessionId);
        return submissionId;
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
