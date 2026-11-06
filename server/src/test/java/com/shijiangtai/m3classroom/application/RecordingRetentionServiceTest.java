package com.shijiangtai.m3classroom.application;

import static com.shijiangtai.m3classroom.TestFixtures.CLASS;
import static com.shijiangtai.m3classroom.TestFixtures.COURSE;
import static com.shijiangtai.m3classroom.TestFixtures.STUDENT;
import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.m3classroom.application.RecordingRetentionService.RetentionRun;
import com.shijiangtai.m3classroom.application.port.RecordingLifecyclePort;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.m3classroom.domain.session.SessionType;
import com.shijiangtai.m3classroom.domain.submission.Submission;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.support.InMemorySessionRepository;
import com.shijiangtai.m3classroom.support.InMemorySubmissionRepository;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecordingRetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-06T00:00:00Z");

    private InMemorySessionRepository sessions;
    private InMemorySubmissionRepository submissions;
    private RecordingLifecycleFake lifecycle;
    private RecordingRetentionService service;

    @BeforeEach
    void setUp() {
        sessions = new InMemorySessionRepository();
        submissions = new InMemorySubmissionRepository();
        lifecycle = new RecordingLifecycleFake();
        service = new RecordingRetentionService(sessions, submissions, lifecycle);
    }

    @Test
    void cleanupDeletesExpiredSessionsAndSubmissionsAndReturnsExactCounts() {
        SessionId first = expiredSession(NOW.minus(Duration.ofHours(25)), SessionStatus.ENDED);
        SessionId second = expiredSession(NOW.minus(Duration.ofHours(30)), SessionStatus.FAILED);
        SessionId retained =
                expiredSession(NOW.minus(Duration.ofHours(23)), SessionStatus.REVIEWING);
        expiredSubmission(first);
        expiredSubmission(second);

        RetentionRun run = service.cleanup(NOW);

        assertThat(run).isEqualTo(new RetentionRun(2, 2, 0));
        assertThat(lifecycle.temporaryRequests()).containsExactlyInAnyOrder(first, second);
        assertThat(lifecycle.temporaryRequests()).doesNotContain(retained);
        assertThat(lifecycle.submittedSessions()).containsExactlyInAnyOrder(first, second);
    }

    @Test
    void cleanupContinuesAfterFailuresAndCountsFailedCandidates() {
        SessionId failingSession =
                expiredSession(NOW.minus(Duration.ofHours(25)), SessionStatus.ENDED);
        SessionId laterSession =
                expiredSession(NOW.minus(Duration.ofHours(26)), SessionStatus.CANCELLED);
        SessionId lastSession =
                expiredSession(NOW.minus(Duration.ofHours(27)), SessionStatus.FAILED);
        SessionId failingSubmissionSession = SessionId.of(UUID.randomUUID());
        SessionId laterSubmissionSession = SessionId.of(UUID.randomUUID());
        expiredSubmission(failingSubmissionSession);
        expiredSubmission(laterSubmissionSession);
        lifecycle.failTemporary(failingSession);
        lifecycle.failSubmitted(failingSubmissionSession);

        RetentionRun run = service.cleanup(NOW);

        assertThat(run).isEqualTo(new RetentionRun(2, 1, 2));
        assertThat(lifecycle.temporaryRequests())
                .containsExactlyInAnyOrder(failingSession, laterSession, lastSession);
        assertThat(lifecycle.submittedSessions())
                .containsExactlyInAnyOrder(failingSubmissionSession, laterSubmissionSession);
    }

    @Test
    void cleanupDoesNotCountCandidatesSkippedByTheLifecycle() {
        SessionId skippedSession =
                expiredSession(NOW.minus(Duration.ofHours(48)), SessionStatus.ENDED);
        SessionId skippedSubmissionSession = SessionId.of(UUID.randomUUID());
        expiredSubmission(skippedSubmissionSession);
        lifecycle.skip(skippedSession);
        lifecycle.skip(skippedSubmissionSession);

        assertThat(service.cleanup(NOW)).isEqualTo(new RetentionRun(0, 0, 0));
    }

    private SessionId expiredSession(Instant endedAt, SessionStatus status) {
        Session session = Session.rehydrate(
                new SessionId(UUID.randomUUID()),
                SessionType.TASK,
                new TaskId(UUID.randomUUID()),
                COURSE,
                STUDENT,
                TaskMode.FREE,
                status,
                1,
                endedAt.minus(Duration.ofMinutes(10)),
                endedAt,
                Duration.ofMinutes(10).toMillis(),
                Map.of(),
                endedAt,
                endedAt);
        sessions.save(session);
        return session.id();
    }

    private void expiredSubmission(SessionId sessionId) {
        Submission submission = Submission.create(
                UUID.randomUUID(),
                new TaskId(UUID.randomUUID()),
                STUDENT,
                sessionId,
                CLASS,
                NOW.minus(Duration.ofDays(400)));
        submissions.addExpired(submission);
    }

    /**
     * Records retention calls and fails or skips chosen session ids so the service's per-candidate
     * isolation and count semantics can be asserted.
     */
    private static final class RecordingLifecycleFake implements RecordingLifecyclePort {

        private final List<SessionId> temporaryRequests = new ArrayList<>();
        private final List<SessionId> submittedSessions = new ArrayList<>();
        private final Set<SessionId> failingTemporary = new HashSet<>();
        private final Set<SessionId> failingSubmitted = new HashSet<>();
        private final Set<SessionId> skipped = new HashSet<>();

        @Override
        public void abortAll(SessionId sessionId) {
            throw new AssertionError("retention cleanup must not call abortAll");
        }

        @Override
        public void deleteAttempt(SessionId sessionId) {
            throw new AssertionError("retention cleanup must not call deleteAttempt");
        }

        @Override
        public void deleteOtherAttempts(AccountId student, TaskId task, SessionId keep) {
            throw new AssertionError("retention cleanup must not call deleteOtherAttempts");
        }

        @Override
        public boolean deleteExpiredTemporary(SessionId sessionId) {
            temporaryRequests.add(sessionId);
            if (failingTemporary.contains(sessionId)) {
                throw new IllegalStateException("temporary cleanup failed: " + sessionId.value());
            }
            return !skipped.contains(sessionId);
        }

        @Override
        public boolean deleteSubmittedSession(SessionId sessionId) {
            submittedSessions.add(sessionId);
            if (failingSubmitted.contains(sessionId)) {
                throw new IllegalStateException("submitted cleanup failed: " + sessionId.value());
            }
            return !skipped.contains(sessionId);
        }

        RecordingLifecycleFake failTemporary(SessionId sessionId) {
            failingTemporary.add(sessionId);
            return this;
        }

        RecordingLifecycleFake failSubmitted(SessionId sessionId) {
            failingSubmitted.add(sessionId);
            return this;
        }

        RecordingLifecycleFake skip(SessionId sessionId) {
            skipped.add(sessionId);
            return this;
        }

        List<SessionId> temporaryRequests() {
            return temporaryRequests;
        }

        List<SessionId> submittedSessions() {
            return submittedSessions;
        }
    }
}
