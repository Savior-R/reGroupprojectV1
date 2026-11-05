package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.TestFixtures;
import com.shijiangtai.m3classroom.application.port.ContextPersistenceWriter;
import com.shijiangtai.m3classroom.application.port.PersistenceFlushException;
import com.shijiangtai.m3classroom.application.port.SessionBatchStore;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.session.SessionType;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.persistence.PostgresIntegrationTest;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Regression test for {@link JpaSessionBatchStore}'s {@code REQUIRES_NEW} propagation.
 *
 * <p>On the REST end path the terminal flush runs inside the caller's transaction. If a failing
 * batch write participated in that transaction (default {@code REQUIRED}), Spring would mark the
 * caller rollback-only; {@code noRollbackFor = DomainException.class} could then not commit the
 * derived {@code failed} session row and the actor would already be evicted. With
 * {@code REQUIRES_NEW} the batch rolls back alone and the outer transaction still commits.
 *
 * <p>A scheduler-free writer over the real {@code JpaSessionBatchStore} bean keeps the failure on
 * the test thread and deterministic.
 */
class JpaSessionBatchStoreTransactionIT extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    @TestConfiguration
    static class SchedulerFreeWriterConfig {

        @Bean
        @Primary
        ContextPersistenceWriter schedulerFreeContextPersistenceWriter(SessionBatchStore store) {
            // Package-private test constructor: inline executor, zero backoff, no 1 s scheduler.
            return new JpaContextPersistenceWriter(store, Runnable::run);
        }
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ContextPersistenceWriter writer;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void failedBatchWriteDoesNotPoisonOuterTransaction() {
        TaskId taskId = seedTask();
        // No sessions row for this id, so every event insert inside the batch transaction fails
        // its FK and the writer exhausts its retries.
        SessionId orphan = new SessionId(UUID.randomUUID());
        writer.appendEvent(new SessionEvent(
                UUID.randomUUID(), orphan, 1, "test.event", 0, Map.of(), NOW));
        Session marker = Session.createPreparing(
                new SessionId(UUID.randomUUID()),
                SessionType.TASK,
                taskId,
                new CourseId(TestFixtures.COURSE_ID),
                new AccountId(TestFixtures.STUDENT_ID),
                TaskMode.FREE,
                1,
                NOW);
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        try {
            outer.executeWithoutResult(status -> {
                assertThatThrownBy(() -> writer.flush(orphan))
                        .isInstanceOf(PersistenceFlushException.class);
                // Same outer transaction as the failed flush: it must still commit.
                sessionRepository.save(marker);
            });

            // Commit would have thrown UnexpectedRollbackException if the failed batch had
            // marked the outer transaction rollback-only; the marker proves the commit landed.
            assertThat(sessionRepository.findById(marker.id())).isPresent();
        } finally {
            // Drop the queued failing event; the writer has no scheduler, so this is the only
            // remaining retry and it stays on the test thread.
            writer.close(orphan);
        }
    }

    private TaskId seedTask() {
        TaskId taskId = new TaskId(UUID.randomUUID());
        jdbc.update("""
                INSERT INTO m3_classroom.tasks
                    (id, course_id, mode, topic, duration_recommended_seconds,
                     duration_max_seconds, created_by)
                VALUES (?, ?, 'free', '事务回归测试', 60, 120, ?)
                """, taskId.value(), TestFixtures.COURSE_ID, TestFixtures.TEACHER_ID);
        return taskId;
    }
}
