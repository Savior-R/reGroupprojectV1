package com.shijiangtai.m3classroom.domain.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessionTest {

    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");
    private static final UUID SESSION_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TASK_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID COURSE_UUID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID STUDENT_UUID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Test
    void createPreparingDefaults() {
        Session session = preparing();

        assertThat(session.id()).isEqualTo(new SessionId(SESSION_UUID));
        assertThat(session.type()).isEqualTo(SessionType.TASK);
        assertThat(session.taskId()).isEqualTo(new TaskId(TASK_UUID));
        assertThat(session.courseId()).isEqualTo(new CourseId(COURSE_UUID));
        assertThat(session.studentAccountId()).isEqualTo(new AccountId(STUDENT_UUID));
        assertThat(session.mode()).isEqualTo(TaskMode.FREE);
        assertThat(session.status()).isEqualTo(SessionStatus.PREPARING);
        assertThat(session.attemptNo()).isEqualTo(2);
        assertThat(session.startedAt()).isNull();
        assertThat(session.endedAt()).isNull();
        assertThat(session.durationMs()).isNull();
        assertThat(session.streamOffsets()).isEmpty();
        assertThat(session.createdAt()).isEqualTo(NOW);
        assertThat(session.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void startMovesPreparingToInProgress() {
        Session session = preparing();
        Instant startedAt = NOW.plusSeconds(15);

        session.start(startedAt);

        assertThat(session.status()).isEqualTo(SessionStatus.IN_PROGRESS);
        assertThat(session.startedAt()).isEqualTo(startedAt);
        assertThat(session.endedAt()).isNull();
        assertThat(session.durationMs()).isNull();
        assertThat(session.updatedAt()).isEqualTo(startedAt);
    }

    @Test
    void startTwiceFails() {
        Session session = inProgress();

        assertStateInvalid(() -> session.start(NOW.plusSeconds(30)));

        assertThat(session.status()).isEqualTo(SessionStatus.IN_PROGRESS);
        assertThat(session.startedAt()).isEqualTo(NOW.plusSeconds(10));
    }

    @Test
    void endComputesDuration() {
        Session session = inProgress();
        Instant endedAt = NOW.plusSeconds(130);

        session.end(endedAt);

        assertThat(session.status()).isEqualTo(SessionStatus.ENDED);
        assertThat(session.endedAt()).isEqualTo(endedAt);
        assertThat(session.durationMs()).isEqualTo(120_000L);
        assertThat(session.updatedAt()).isEqualTo(endedAt);
    }

    @Test
    void endBeforeStartFails() {
        Session session = preparing();

        assertStateInvalid(() -> session.end(NOW.plusSeconds(5)));

        assertThat(session.status()).isEqualTo(SessionStatus.PREPARING);
        assertThat(session.endedAt()).isNull();
        assertThat(session.durationMs()).isNull();
    }

    @Test
    void endBeforeStartedAtFails() {
        Session session = inProgress();

        assertStateInvalid(() -> session.end(NOW.plusSeconds(5)));

        assertThat(session.status()).isEqualTo(SessionStatus.IN_PROGRESS);
        assertThat(session.endedAt()).isNull();
        assertThat(session.durationMs()).isNull();
    }

    @Test
    void markReviewingRequiresEnded() {
        Session ended = ended();
        Instant reviewingAt = NOW.plusSeconds(200);
        ended.markReviewing(reviewingAt);
        assertThat(ended.status()).isEqualTo(SessionStatus.REVIEWING);
        assertThat(ended.updatedAt()).isEqualTo(reviewingAt);

        Session inProgress = inProgress();
        assertStateInvalid(() -> inProgress.markReviewing(NOW.plusSeconds(200)));
        assertThat(inProgress.status()).isEqualTo(SessionStatus.IN_PROGRESS);
    }

    @Test
    void submitAllowsEndedOrReviewingOnly() {
        Session ended = ended();
        ended.submit(NOW.plusSeconds(200));
        assertThat(ended.status()).isEqualTo(SessionStatus.SUBMITTED);

        Session reviewing = reviewing();
        reviewing.submit(NOW.plusSeconds(200));
        assertThat(reviewing.status()).isEqualTo(SessionStatus.SUBMITTED);

        Session preparing = preparing();
        assertStateInvalid(() -> preparing.submit(NOW.plusSeconds(200)));
        assertThat(preparing.status()).isEqualTo(SessionStatus.PREPARING);

        Session inProgress = inProgress();
        assertStateInvalid(() -> inProgress.submit(NOW.plusSeconds(200)));
        assertThat(inProgress.status()).isEqualTo(SessionStatus.IN_PROGRESS);

        Session cancelled = cancelled();
        assertStateInvalid(() -> cancelled.submit(NOW.plusSeconds(200)));
        assertThat(cancelled.status()).isEqualTo(SessionStatus.CANCELLED);
    }

    @Test
    void submitTwiceFails() {
        Session session = submitted();

        assertStateInvalid(() -> session.submit(NOW.plusSeconds(300)));

        assertThat(session.status()).isEqualTo(SessionStatus.SUBMITTED);
    }

    @Test
    void cancelAllowsPreparingAndInProgress() {
        Session preparing = preparing();
        preparing.cancel(NOW.plusSeconds(5));
        assertThat(preparing.status()).isEqualTo(SessionStatus.CANCELLED);
        assertThat(preparing.updatedAt()).isEqualTo(NOW.plusSeconds(5));

        Session inProgress = inProgress();
        inProgress.cancel(NOW.plusSeconds(30));
        assertThat(inProgress.status()).isEqualTo(SessionStatus.CANCELLED);
        assertThat(inProgress.updatedAt()).isEqualTo(NOW.plusSeconds(30));

        Session ended = ended();
        assertStateInvalid(() -> ended.cancel(NOW.plusSeconds(200)));
        assertThat(ended.status()).isEqualTo(SessionStatus.ENDED);
    }

    @Test
    void failIsAllowedFromEveryNonTerminalStatus() {
        Session preparing = preparing();
        preparing.fail(FailureReason.RECORDING_LOST, NOW.plusSeconds(5));
        assertThat(preparing.status()).isEqualTo(SessionStatus.FAILED);
        assertThat(preparing.updatedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(preparing.endedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(preparing.durationMs()).isNull();

        Session inProgress = inProgress();
        inProgress.fail(FailureReason.GRACE_TIMEOUT, NOW.plusSeconds(20));
        assertThat(inProgress.status()).isEqualTo(SessionStatus.FAILED);
        assertThat(inProgress.endedAt()).isEqualTo(NOW.plusSeconds(20));
        assertThat(inProgress.durationMs()).isEqualTo(10_000L);

        Session ended = ended();
        ended.fail(FailureReason.TRACK_ENDED, NOW.plusSeconds(200));
        assertThat(ended.status()).isEqualTo(SessionStatus.FAILED);
        assertThat(ended.endedAt()).isEqualTo(NOW.plusSeconds(130));
        assertThat(ended.durationMs()).isEqualTo(120_000L);

        Session reviewing = reviewing();
        reviewing.fail(FailureReason.WS_ERROR, NOW.plusSeconds(200));
        assertThat(reviewing.status()).isEqualTo(SessionStatus.FAILED);
        assertThat(reviewing.endedAt()).isEqualTo(NOW.plusSeconds(130));
        assertThat(reviewing.durationMs()).isEqualTo(120_000L);
    }

    @Test
    void failFromEndedPreservesEndTimeAndDuration() {
        Session ended = ended();

        ended.fail(FailureReason.TRACK_ENDED, NOW.plusSeconds(500));

        assertThat(ended.status()).isEqualTo(SessionStatus.FAILED);
        assertThat(ended.endedAt()).isEqualTo(NOW.plusSeconds(130));
        assertThat(ended.durationMs()).isEqualTo(120_000L);
        assertThat(ended.updatedAt()).isEqualTo(NOW.plusSeconds(500));
    }

    @Test
    void failBeforeStartedAtFailsLikeEnd() {
        Session session = inProgress();

        assertStateInvalid(() -> session.fail(FailureReason.INTERNAL, NOW.plusSeconds(5)));

        assertThat(session.status()).isEqualTo(SessionStatus.IN_PROGRESS);
        assertThat(session.endedAt()).isNull();
        assertThat(session.durationMs()).isNull();
    }

    @Test
    void terminalStatusCannotFail() {
        Session submitted = submitted();
        assertStateInvalid(() -> submitted.fail(FailureReason.INTERNAL, NOW.plusSeconds(500)));
        assertThat(submitted.status()).isEqualTo(SessionStatus.SUBMITTED);

        Session cancelled = cancelled();
        assertStateInvalid(() -> cancelled.fail(FailureReason.INTERNAL, NOW.plusSeconds(500)));
        assertThat(cancelled.status()).isEqualTo(SessionStatus.CANCELLED);

        Session failed = failed();
        assertStateInvalid(() -> failed.fail(FailureReason.GRACE_TIMEOUT, NOW.plusSeconds(500)));
        assertThat(failed.status()).isEqualTo(SessionStatus.FAILED);
    }

    @Test
    void putStreamOffsetStoresOffsetsAndReturnsDefensiveCopy() {
        Session session = inProgress();

        session.putStreamOffset(StreamType.CAMERA, 120L);
        session.putStreamOffset(StreamType.AUDIO, 0L);

        assertThat(session.streamOffsets()).hasSize(2);
        assertThat(session.streamOffsets()).containsEntry(StreamType.CAMERA, 120L);
        assertThat(session.streamOffsets()).containsEntry(StreamType.AUDIO, 0L);
        assertThatThrownBy(() -> session.streamOffsets().put(StreamType.SCREEN, 1L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rehydratePreservesAllFieldsAndCopiesStreamOffsets() {
        Session source = reviewing();
        source.putStreamOffset(StreamType.CAMERA, 120L);
        source.putStreamOffset(StreamType.SCREEN, 0L);
        source.putStreamOffset(StreamType.AUDIO, 350L);

        Map<StreamType, Long> mutableOffsets = new EnumMap<>(StreamType.class);
        mutableOffsets.putAll(source.streamOffsets());
        Session rehydrated = Session.rehydrate(
                source.id(), source.type(), source.taskId(), source.courseId(),
                source.studentAccountId(), source.mode(), source.status(), source.attemptNo(),
                source.startedAt(), source.endedAt(), source.durationMs(),
                mutableOffsets, source.createdAt(), source.updatedAt());

        mutableOffsets.put(StreamType.CAMERA, 999L);

        assertThat(rehydrated.id()).isEqualTo(source.id());
        assertThat(rehydrated.type()).isEqualTo(source.type());
        assertThat(rehydrated.taskId()).isEqualTo(source.taskId());
        assertThat(rehydrated.courseId()).isEqualTo(source.courseId());
        assertThat(rehydrated.studentAccountId()).isEqualTo(source.studentAccountId());
        assertThat(rehydrated.mode()).isEqualTo(source.mode());
        assertThat(rehydrated.status()).isEqualTo(SessionStatus.REVIEWING);
        assertThat(rehydrated.attemptNo()).isEqualTo(source.attemptNo());
        assertThat(rehydrated.startedAt()).isEqualTo(source.startedAt());
        assertThat(rehydrated.endedAt()).isEqualTo(source.endedAt());
        assertThat(rehydrated.durationMs()).isEqualTo(120_000L);
        assertThat(rehydrated.streamOffsets()).isEqualTo(source.streamOffsets());
        assertThat(rehydrated.createdAt()).isEqualTo(source.createdAt());
        assertThat(rehydrated.updatedAt()).isEqualTo(source.updatedAt());
    }

    @Test
    void wireValuesMatchProtocol() {
        assertThat(SessionType.TASK.wireValue()).isEqualTo("task");
        assertThat(SessionType.fromWire("task")).isEqualTo(SessionType.TASK);

        assertThat(SessionStatus.PREPARING.wireValue()).isEqualTo("preparing");
        assertThat(SessionStatus.IN_PROGRESS.wireValue()).isEqualTo("in_progress");
        assertThat(SessionStatus.ENDED.wireValue()).isEqualTo("ended");
        assertThat(SessionStatus.REVIEWING.wireValue()).isEqualTo("reviewing");
        assertThat(SessionStatus.SUBMITTED.wireValue()).isEqualTo("submitted");
        assertThat(SessionStatus.CANCELLED.wireValue()).isEqualTo("cancelled");
        assertThat(SessionStatus.FAILED.wireValue()).isEqualTo("failed");
        for (SessionStatus status : SessionStatus.values()) {
            assertThat(SessionStatus.fromWire(status.wireValue())).isEqualTo(status);
        }

        assertThat(StreamType.CAMERA.wireValue()).isEqualTo("camera");
        assertThat(StreamType.SCREEN.wireValue()).isEqualTo("screen");
        assertThat(StreamType.AUDIO.wireValue()).isEqualTo("audio");
        for (StreamType type : StreamType.values()) {
            assertThat(StreamType.fromWire(type.wireValue())).isEqualTo(type);
        }

        assertThat(RecordingState.ACTIVE.wireValue()).isEqualTo("active");
        assertThat(RecordingState.LOST.wireValue()).isEqualTo("lost");
        for (RecordingState state : RecordingState.values()) {
            assertThat(RecordingState.fromWire(state.wireValue())).isEqualTo(state);
        }

        assertThat(FailureReason.GRACE_TIMEOUT.wireValue()).isEqualTo("grace_timeout");
        assertThat(FailureReason.RECORDING_LOST.wireValue()).isEqualTo("recording_lost");
        assertThat(FailureReason.TRACK_ENDED.wireValue()).isEqualTo("track_ended");
        assertThat(FailureReason.WS_ERROR.wireValue()).isEqualTo("ws_error");
        assertThat(FailureReason.INTERNAL.wireValue()).isEqualTo("internal");
        for (FailureReason reason : FailureReason.values()) {
            assertThat(FailureReason.fromWire(reason.wireValue())).isEqualTo(reason);
        }
    }

    @Test
    void unknownWireValuesAreRejected() {
        assertThatThrownBy(() -> SessionType.fromWire("training"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> SessionStatus.fromWire("selected"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> StreamType.fromWire("desktop"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> RecordingState.fromWire("broken"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> FailureReason.fromWire("timeout"))
                .isInstanceOf(DomainException.class);
    }

    private static void assertStateInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.SESSION_STATE_INVALID));
    }

    private static Session preparing() {
        return Session.createPreparing(
                new SessionId(SESSION_UUID),
                SessionType.TASK,
                new TaskId(TASK_UUID),
                new CourseId(COURSE_UUID),
                new AccountId(STUDENT_UUID),
                TaskMode.FREE,
                2,
                NOW);
    }

    private static Session inProgress() {
        Session session = preparing();
        session.start(NOW.plusSeconds(10));
        return session;
    }

    private static Session ended() {
        Session session = inProgress();
        session.end(NOW.plusSeconds(130));
        return session;
    }

    private static Session reviewing() {
        Session session = ended();
        session.markReviewing(NOW.plusSeconds(140));
        return session;
    }

    private static Session submitted() {
        Session session = reviewing();
        session.submit(NOW.plusSeconds(150));
        return session;
    }

    private static Session cancelled() {
        Session session = preparing();
        session.cancel(NOW.plusSeconds(10));
        return session;
    }

    private static Session failed() {
        Session session = inProgress();
        session.fail(FailureReason.WS_ERROR, NOW.plusSeconds(20));
        return session;
    }
}
