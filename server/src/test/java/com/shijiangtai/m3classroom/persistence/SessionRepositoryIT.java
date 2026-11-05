package com.shijiangtai.m3classroom.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.m3classroom.domain.session.SessionType;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.infrastructure.config.DevDataConfig;
import com.shijiangtai.m3classroom.infrastructure.persistence.jpa.SessionJsonCodec;
import com.shijiangtai.m3classroom.infrastructure.persistence.jpa.SessionRepositoryAdapter;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class SessionRepositoryIT extends PostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    @Autowired
    private SessionRepository repository;

    @Autowired
    private SessionJsonCodec jsonCodec;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void realJpaAdapterIsWired() {
        assertThat(repository).isInstanceOf(SessionRepositoryAdapter.class);
    }

    @Test
    void savesAndReloadsEveryFieldIncludingJsonbOffsets() {
        TaskId taskId = seedTask();
        SessionId sessionId = new SessionId(UUID.randomUUID());
        Session session = preparingSession(sessionId, taskId, 1);
        session.putStreamOffset(StreamType.CAMERA, 1500L);
        session.putStreamOffset(StreamType.AUDIO, 0L);

        repository.save(session);

        Session loaded = repository.findById(sessionId).orElseThrow();
        assertThat(loaded.id()).isEqualTo(sessionId);
        assertThat(loaded.type()).isEqualTo(SessionType.TASK);
        assertThat(loaded.taskId()).isEqualTo(taskId);
        assertThat(loaded.courseId()).isEqualTo(new CourseId(DevDataConfig.COURSE_ID));
        assertThat(loaded.studentAccountId()).isEqualTo(new AccountId(DevDataConfig.STUDENT_ID));
        assertThat(loaded.mode()).isEqualTo(TaskMode.FREE);
        assertThat(loaded.status()).isEqualTo(SessionStatus.PREPARING);
        assertThat(loaded.attemptNo()).isEqualTo(1);
        assertThat(loaded.startedAt()).isNull();
        assertThat(loaded.endedAt()).isNull();
        assertThat(loaded.durationMs()).isNull();
        assertThat(loaded.streamOffsets()).containsExactlyInAnyOrderEntriesOf(
                Map.of(StreamType.CAMERA, 1500L, StreamType.AUDIO, 0L));
        assertThat(loaded.createdAt()).isEqualTo(NOW);
        assertThat(loaded.updatedAt()).isEqualTo(NOW);

        String offsetsJson = jdbc.queryForObject(
                "select stream_offsets::text from m3_classroom.sessions where id = ?",
                String.class, sessionId.value());
        assertThat(offsetsJson).contains("\"camera\"").contains("\"audio\"");
        assertThat(jsonCodec.readStreamOffsets(offsetsJson)).containsExactlyInAnyOrderEntriesOf(
                Map.of(StreamType.CAMERA, 1500L, StreamType.AUDIO, 0L));
    }

    @Test
    void persistsLifecycleFieldUpdates() {
        TaskId taskId = seedTask();
        SessionId sessionId = new SessionId(UUID.randomUUID());
        Session session = preparingSession(sessionId, taskId, 1);
        repository.save(session);

        session.start(NOW.plusSeconds(5));
        session.end(NOW.plusSeconds(65));
        repository.save(session);

        Session loaded = repository.findById(sessionId).orElseThrow();
        assertThat(loaded.status()).isEqualTo(SessionStatus.ENDED);
        assertThat(loaded.startedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(loaded.endedAt()).isEqualTo(NOW.plusSeconds(65));
        assertThat(loaded.durationMs()).isEqualTo(60_000L);
        assertThat(loaded.updatedAt()).isEqualTo(NOW.plusSeconds(65));
    }

    @Test
    void findsActiveAttemptsAndCountsPerStudentAndTask() {
        TaskId taskId = seedTask();
        AccountId student = new AccountId(DevDataConfig.STUDENT_ID);
        SessionId firstId = new SessionId(UUID.randomUUID());
        Session first = preparingSession(firstId, taskId, 1);
        repository.save(first);

        assertThat(repository.findActiveByStudentAndTask(student, taskId).orElseThrow().id())
                .isEqualTo(firstId);
        assertThat(repository.countByStudentAndTask(student, taskId)).isEqualTo(1);

        first.cancel(NOW.plusSeconds(10));
        repository.save(first);
        assertThat(repository.findActiveByStudentAndTask(student, taskId)).isEmpty();

        SessionId secondId = new SessionId(UUID.randomUUID());
        Session second = preparingSession(secondId, taskId, 2);
        repository.save(second);

        assertThat(repository.findByStudentAndTask(student, taskId))
                .extracting(Session::attemptNo)
                .containsExactly(1, 2);
        assertThat(repository.countByStudentAndTask(student, taskId)).isEqualTo(2);
        assertThat(repository.findActiveByStudentAndTask(student, taskId).orElseThrow().id())
                .isEqualTo(secondId);
        assertThat(repository.countByStudentAndTask(
                new AccountId(DevDataConfig.STUDENT2_ID), taskId)).isZero();
    }

    @Test
    void findsSessionsByStatusForRestartReconciliation() {
        TaskId taskId = seedTask();
        TaskId otherTaskId = seedTask();
        // preparing + in_progress are both active; the partial unique index allows only one
        // active task session per student/task, so the in_progress session needs its own task.
        Session preparing = preparingSession(new SessionId(UUID.randomUUID()), taskId, 1);
        Session inProgress = preparingSession(
                new SessionId(UUID.randomUUID()), otherTaskId, 1);
        inProgress.start(NOW);
        Session ended = preparingSession(new SessionId(UUID.randomUUID()), taskId, 3);
        ended.start(NOW);
        ended.end(NOW.plusSeconds(30));
        repository.save(preparing);
        repository.save(inProgress);
        repository.save(ended);

        assertThat(repository.findByStatusIn(List.of(
                SessionStatus.PREPARING, SessionStatus.IN_PROGRESS)))
                .extracting(Session::id)
                .containsExactlyInAnyOrder(preparing.id(), inProgress.id());
        assertThat(repository.findByStatusIn(List.of(SessionStatus.FAILED))).isEmpty();
    }

    @Test
    void reloadsSessionWithNullCourseId() {
        TaskId taskId = seedTask();
        SessionId sessionId = new SessionId(UUID.randomUUID());
        Session session = Session.rehydrate(
                sessionId, SessionType.TASK, taskId, null,
                new AccountId(DevDataConfig.STUDENT_ID), TaskMode.SCENARIO,
                SessionStatus.IN_PROGRESS, 2, NOW, null, null,
                Map.of(StreamType.SCREEN, 4200L), NOW, NOW);

        repository.save(session);

        Session loaded = repository.findById(sessionId).orElseThrow();
        assertThat(loaded.courseId()).isNull();
        assertThat(loaded.mode()).isEqualTo(TaskMode.SCENARIO);
        assertThat(loaded.status()).isEqualTo(SessionStatus.IN_PROGRESS);
        assertThat(loaded.streamOffsets()).containsExactlyEntriesOf(
                Map.of(StreamType.SCREEN, 4200L));
    }

    private Session preparingSession(SessionId sessionId, TaskId taskId, int attemptNo) {
        return Session.createPreparing(
                sessionId, SessionType.TASK, taskId,
                new CourseId(DevDataConfig.COURSE_ID),
                new AccountId(DevDataConfig.STUDENT_ID), TaskMode.FREE, attemptNo, NOW);
    }

    private TaskId seedTask() {
        TaskId taskId = new TaskId(UUID.randomUUID());
        jdbc.update("""
                INSERT INTO m3_classroom.tasks
                    (id, course_id, mode, topic, duration_recommended_seconds,
                     duration_max_seconds, created_by, status)
                VALUES (?, ?, 'free', '荷塘月色', 900, 1200, ?, 'published')
                """, taskId.value(), DevDataConfig.COURSE_ID, DevDataConfig.TEACHER_ID);
        return taskId;
    }
}
