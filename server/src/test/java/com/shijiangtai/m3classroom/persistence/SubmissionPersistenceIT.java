package com.shijiangtai.m3classroom.persistence;

import static com.shijiangtai.m3classroom.TestFixtures.CLASS_ID;
import static com.shijiangtai.m3classroom.TestFixtures.COURSE_ID;
import static com.shijiangtai.m3classroom.TestFixtures.STUDENT2_ID;
import static com.shijiangtai.m3classroom.TestFixtures.STUDENT_ID;
import static com.shijiangtai.m3classroom.TestFixtures.TEACHER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.domain.submission.Submission;
import com.shijiangtai.m3classroom.domain.submission.SubmissionRepository;
import com.shijiangtai.m3classroom.infrastructure.persistence.jpa.SubmissionRepositoryAdapter;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.ClassId;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class SubmissionPersistenceIT extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void realJpaAdapterIsWired() {
        assertThat(submissionRepository).isInstanceOf(SubmissionRepositoryAdapter.class);
    }

    @Test
    void savesAndReloadsEveryField() {
        TaskId taskId = seedTask();
        SessionId sessionId = seedSession(taskId, STUDENT_ID);
        Submission submission = Submission.create(
                UUID.randomUUID(), taskId, new AccountId(STUDENT_ID), sessionId,
                new ClassId(CLASS_ID), NOW);

        submissionRepository.save(submission);

        Submission bySession = submissionRepository.findBySession(sessionId).orElseThrow();
        assertThat(bySession).isEqualTo(submission);
        Submission byTaskAndStudent = submissionRepository
                .findByTaskAndStudent(taskId, new AccountId(STUDENT_ID)).orElseThrow();
        assertThat(byTaskAndStudent).isEqualTo(submission);
        assertThat(submissionRepository.existsBySession(sessionId)).isTrue();
        assertThat(submissionRepository.existsBySession(new SessionId(UUID.randomUUID())))
                .isFalse();
        assertThat(jdbc.queryForObject(
                "select class_id_snapshot::text from m3_classroom.submissions where id = ?",
                String.class, submission.id())).isEqualTo(CLASS_ID.toString());
    }

    @Test
    void roundTripsNullableClassIdSnapshot() {
        TaskId taskId = seedTask();
        SessionId sessionId = seedSession(taskId, STUDENT_ID);
        Submission submission = Submission.create(
                UUID.randomUUID(), taskId, new AccountId(STUDENT_ID), sessionId, null, NOW);

        submissionRepository.save(submission);

        Submission loaded = submissionRepository.findBySession(sessionId).orElseThrow();
        assertThat(loaded.classIdSnapshot()).isNull();
        assertThat(loaded).isEqualTo(submission);
    }

    @Test
    void rejectsDuplicateTaskAndStudent() {
        TaskId taskId = seedTask();
        SessionId firstSession = seedSession(taskId, STUDENT_ID);
        SessionId secondSession = seedSession(taskId, STUDENT_ID);
        submissionRepository.save(Submission.create(
                UUID.randomUUID(), taskId, new AccountId(STUDENT_ID), firstSession,
                new ClassId(CLASS_ID), NOW));

        assertThatThrownBy(() -> submissionRepository.save(Submission.create(
                UUID.randomUUID(), taskId, new AccountId(STUDENT_ID), secondSession,
                new ClassId(CLASS_ID), NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateSession() {
        TaskId taskId = seedTask();
        SessionId sessionId = seedSession(taskId, STUDENT_ID);
        submissionRepository.save(Submission.create(
                UUID.randomUUID(), taskId, new AccountId(STUDENT_ID), sessionId,
                new ClassId(CLASS_ID), NOW));

        assertThatThrownBy(() -> submissionRepository.save(Submission.create(
                UUID.randomUUID(), taskId, new AccountId(STUDENT2_ID), sessionId,
                new ClassId(CLASS_ID), NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private TaskId seedTask() {
        TaskId taskId = new TaskId(UUID.randomUUID());
        jdbc.update("""
                INSERT INTO m3_classroom.tasks
                    (id, course_id, mode, topic, duration_recommended_seconds,
                     duration_max_seconds, created_by, status)
                VALUES (?, ?, 'free', '提交持久化测试', 900, 1200, ?, 'published')
                """, taskId.value(), COURSE_ID, TEACHER_ID);
        return taskId;
    }

    private SessionId seedSession(TaskId taskId, UUID studentAccountId) {
        SessionId sessionId = new SessionId(UUID.randomUUID());
        jdbc.update("""
                INSERT INTO m3_classroom.sessions
                    (id, session_type, task_id, course_id, student_account_id, mode, status)
                VALUES (?, 'task', ?, ?, ?, 'free', 'ended')
                """, sessionId.value(), taskId.value(), COURSE_ID, studentAccountId);
        return sessionId;
    }
}
