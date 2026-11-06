package com.shijiangtai.m3classroom.application;

import static com.shijiangtai.m3classroom.TestFixtures.COURSE;
import static com.shijiangtai.m3classroom.TestFixtures.STUDENT;
import static com.shijiangtai.m3classroom.TestFixtures.STUDENT_ID;
import static com.shijiangtai.m3classroom.TestFixtures.admin;
import static com.shijiangtai.m3classroom.TestFixtures.coursePort;
import static com.shijiangtai.m3classroom.TestFixtures.student;
import static com.shijiangtai.m3classroom.TestFixtures.teacher;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.application.port.RecordingObjectStorage;
import com.shijiangtai.m3classroom.application.view.ReplayStreamView;
import com.shijiangtai.m3classroom.application.view.ReplayView;
import com.shijiangtai.m3classroom.application.view.SessionEventView;
import com.shijiangtai.m3classroom.application.view.SessionTextView;
import com.shijiangtai.m3classroom.domain.recording.Recording;
import com.shijiangtai.m3classroom.domain.recording.RecordingRepository;
import com.shijiangtai.m3classroom.domain.recording.RecordingStatus;
import com.shijiangtai.m3classroom.domain.recording.RecordingStream;
import com.shijiangtai.m3classroom.domain.recording.StreamStatus;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.m3classroom.domain.session.SessionText;
import com.shijiangtai.m3classroom.domain.session.SessionType;
import com.shijiangtai.m3classroom.domain.submission.Submission;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.support.InMemorySessionEventStore;
import com.shijiangtai.m3classroom.support.InMemorySessionRepository;
import com.shijiangtai.m3classroom.support.InMemorySessionTextStore;
import com.shijiangtai.m3classroom.support.InMemorySubmissionRepository;
import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.PageResult;
import com.shijiangtai.shared.domain.SchoolId;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReplayQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");
    private static final Instant END = NOW.plusSeconds(90);

    private InMemorySessionRepository sessions;
    private InMemoryRecordingRepository recordings;
    private RecordingStorageFake storage;
    private InMemorySessionEventStore events;
    private InMemorySessionTextStore texts;
    private InMemorySubmissionRepository submissions;
    private ReplayQueryService service;

    @BeforeEach
    void setUp() {
        sessions = new InMemorySessionRepository();
        recordings = new InMemoryRecordingRepository();
        storage = new RecordingStorageFake();
        events = new InMemorySessionEventStore();
        texts = new InMemorySessionTextStore();
        submissions = new InMemorySubmissionRepository();
        service = new ReplayQueryServiceImpl(
                sessions, recordings, storage, events, texts,
                new SessionAccessPolicy(coursePort(), submissions));
    }

    @Test
    void ownerGetsPresignedStreamsWithOffsets() {
        Session session = createSession(TaskMode.FREE, SessionStatus.ENDED);
        recordingWith(session.id(),
                new StreamSpec(StreamType.CAMERA, 3_000, 8_000L, "video/webm"),
                new StreamSpec(StreamType.SCREEN, 0, 9_000L, "video/webm"),
                new StreamSpec(StreamType.AUDIO, 500, 9_000L, "audio/webm"));

        ReplayView view = service.replay(student(), session.id());

        assertThat(view.sessionId()).isEqualTo(session.id().value());
        assertThat(view.mode()).isEqualTo("free");
        assertThat(view.durationMs()).isEqualTo(Duration.ofSeconds(90).toMillis());
        assertThat(view.streams()).extracting(ReplayStreamView::streamType)
                .containsExactly("screen", "camera", "audio");
        assertThat(view.streams()).extracting(ReplayStreamView::offsetMs)
                .containsExactly(0L, 3_000L, 500L);
        assertThat(view.streams()).extracting(ReplayStreamView::durationMs)
                .containsExactly(9_000L, 8_000L, 9_000L);
        assertThat(view.streams()).extracting(ReplayStreamView::mime)
                .containsExactly("video/webm", "video/webm", "audio/webm");
        assertThat(view.streams()).extracting(ReplayStreamView::url)
                .allMatch(url -> url.startsWith("https://storage.test/recordings/"));
        assertThat(view.streams().get(0).url()).contains("/screen.webm");
        assertThat(storage.presignedTtls()).hasSize(3);
        assertThat(storage.presignedTtls().values())
                .containsOnly(Duration.ofMinutes(15));
    }

    @Test
    void interviewReplayOrdersCameraThenAudioAndNeverLeaksScreen() {
        Session session = createSession(TaskMode.INTERVIEW, SessionStatus.IN_PROGRESS);
        recordingWith(session.id(),
                new StreamSpec(StreamType.AUDIO, 0, 4_000L, "audio/webm"),
                new StreamSpec(StreamType.CAMERA, 0, 5_000L, "video/webm"),
                new StreamSpec(StreamType.SCREEN, 0, 5_000L, "video/webm"));

        ReplayView view = service.replay(student(), session.id());

        assertThat(view.mode()).isEqualTo("interview");
        assertThat(view.durationMs()).isZero();
        assertThat(view.streams()).extracting(ReplayStreamView::streamType)
                .containsExactly("camera", "audio");
    }

    @Test
    void temporaryReplayIsHiddenFromTeacher() {
        Session session = createSession(TaskMode.FREE, SessionStatus.IN_PROGRESS);
        recordingWith(session.id(),
                new StreamSpec(StreamType.CAMERA, 0, 5_000L, "video/webm"),
                new StreamSpec(StreamType.SCREEN, 0, 5_000L, "video/webm"),
                new StreamSpec(StreamType.AUDIO, 0, 5_000L, "audio/webm"));

        assertCode(() -> service.replay(teacher(), session.id()), ErrorCode.FORBIDDEN);
    }

    @Test
    void submittedReplayIsVisibleToCourseTeacher() {
        Session session = createSession(TaskMode.SCENARIO, SessionStatus.SUBMITTED);
        recordingWith(session.id(),
                new StreamSpec(StreamType.CAMERA, 0, 5_000L, "video/webm"),
                new StreamSpec(StreamType.SCREEN, 0, 5_000L, "video/webm"),
                new StreamSpec(StreamType.AUDIO, 0, 5_000L, "audio/webm"));

        ReplayView view = service.replay(teacher(), session.id());

        assertThat(view.mode()).isEqualTo("scenario");
        assertThat(view.streams()).extracting(ReplayStreamView::streamType)
                .containsExactly("screen", "camera", "audio");
    }

    @Test
    void adminAndCrossSchoolAreForbidden() {
        Session session = createSession(TaskMode.FREE, SessionStatus.SUBMITTED);
        recordingWith(session.id(),
                new StreamSpec(StreamType.CAMERA, 0, 5_000L, "video/webm"),
                new StreamSpec(StreamType.SCREEN, 0, 5_000L, "video/webm"),
                new StreamSpec(StreamType.AUDIO, 0, 5_000L, "audio/webm"));

        assertCode(() -> service.replay(admin(), session.id()), ErrorCode.FORBIDDEN);
        CurrentUser foreignStudent = new CurrentUser(
                STUDENT, new SchoolId(UUID.randomUUID()), AccountType.STUDENT);
        assertCode(() -> service.replay(foreignStudent, session.id()),
                ErrorCode.CROSS_SCHOOL_ACCESS);
        CurrentUser foreignTeacher = new CurrentUser(
                new AccountId(UUID.randomUUID()), new SchoolId(UUID.randomUUID()),
                AccountType.TEACHER);
        assertCode(() -> service.replay(foreignTeacher, session.id()),
                ErrorCode.CROSS_SCHOOL_ACCESS);
    }

    @Test
    void eventsAreFilteredAfterSeqAndLimited() {
        Session session = createSession(TaskMode.FREE, SessionStatus.PREPARING);
        for (int seq = 1; seq <= 5; seq++) {
            events.append(event(session.id(), seq));
        }

        PageResult<SessionEventView> page = service.events(student(), session.id(), 2, 2);

        assertThat(page.items()).extracting(SessionEventView::seq)
                .containsExactly(3L, 4L);
        assertThat(page.items()).extracting(SessionEventView::eventId)
                .doesNotHaveDuplicates();
        assertThat(page.items().get(0).payload()).containsEntry("index", 3);
        assertThat(page.items().get(0).type()).isEqualTo("teacher.speech");
        assertThat(page.items().get(0).atMs()).isEqualTo(300L);
        assertThat(page.total()).isEqualTo(5);
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(2);

        PageResult<SessionEventView> all = service.events(student(), session.id(), 0, 100);
        assertThat(all.items()).hasSize(5);
        assertThat(all.total()).isEqualTo(5);
    }

    @Test
    void textsFilterBySpeakerType() {
        Session session = createSession(TaskMode.SCENARIO, SessionStatus.PREPARING);
        UUID teacherRef = UUID.randomUUID();
        texts.save(text(session.id(), "teacher", teacherRef, "同学们好", 0L, 1_000L, 500L));
        texts.save(text(session.id(), "student", STUDENT_ID, "老师好", 1_000L, 2_000L,
                1_500L));

        List<SessionTextView> filtered = service.texts(student(), session.id(), "teacher");

        assertThat(filtered).hasSize(1);
        SessionTextView view = filtered.get(0);
        assertThat(view.speakerType()).isEqualTo("teacher");
        assertThat(view.speakerRef()).isEqualTo(teacherRef);
        assertThat(view.source()).isEqualTo("asr");
        assertThat(view.kind()).isEqualTo("utterance");
        assertThat(view.text()).isEqualTo("同学们好");
        assertThat(view.startMs()).isZero();
        assertThat(view.endMs()).isEqualTo(1_000L);
        assertThat(view.atMs()).isEqualTo(500L);
        assertThat(view.confidence()).isEqualByComparingTo("0.95");
        assertThat(view.status()).isEqualTo("final");

        assertThat(service.texts(student(), session.id(), null)).hasSize(2);
        assertThat(service.texts(student(), session.id(), "student")).hasSize(1);
    }

    @Test
    void missingRecordingIsIncomplete() {
        Session session = createSession(TaskMode.FREE, SessionStatus.IN_PROGRESS);

        assertIncomplete(() -> service.replay(student(), session.id()),
                "camera", "screen", "audio");
        assertThat(storage.presignedTtls()).isEmpty();
    }

    @Test
    void unuploadedRequiredStreamsAreIncomplete() {
        Session session = createSession(TaskMode.FREE, SessionStatus.IN_PROGRESS);
        recordingWith(session.id(),
                new StreamSpec(StreamType.CAMERA, 0, 5_000L, "video/webm"));

        assertIncomplete(() -> service.replay(student(), session.id()), "screen", "audio");
        assertThat(storage.presignedTtls()).isEmpty();
    }

    @Test
    void interviewMissingScreenIsNotRequired() {
        Session session = createSession(TaskMode.INTERVIEW, SessionStatus.IN_PROGRESS);
        recordingWith(session.id(),
                new StreamSpec(StreamType.CAMERA, 0, 5_000L, "video/webm"),
                new StreamSpec(StreamType.AUDIO, 0, 5_000L, "audio/webm"));

        ReplayView view = service.replay(student(), session.id());

        assertThat(view.streams()).extracting(ReplayStreamView::streamType)
                .containsExactly("camera", "audio");
    }

    @Test
    void invalidSpeakerTypeLimitAndCursorAreRejected() {
        Session session = createSession(TaskMode.FREE, SessionStatus.PREPARING);

        assertCode(() -> service.texts(student(), session.id(), "principal"),
                ErrorCode.VALIDATION_ERROR);
        assertCode(() -> service.events(student(), session.id(), 0, 0),
                ErrorCode.VALIDATION_ERROR);
        assertCode(() -> service.events(student(), session.id(), 0, 501),
                ErrorCode.VALIDATION_ERROR);
        assertCode(() -> service.events(student(), session.id(), -1, 100),
                ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void maximumLimitAndEmptyPageAreSupported() {
        Session session = createSession(TaskMode.FREE, SessionStatus.PREPARING);
        events.append(event(session.id(), 1));
        events.append(event(session.id(), 2));

        PageResult<SessionEventView> maximum = service.events(
                student(), session.id(), 0, 500);
        assertThat(maximum.items()).hasSize(2);
        assertThat(maximum.total()).isEqualTo(2);
        assertThat(maximum.size()).isEqualTo(500);

        PageResult<SessionEventView> empty = service.events(
                student(), session.id(), 2, 500);
        assertThat(empty.items()).isEmpty();
        assertThat(empty.total()).isEqualTo(2);
        assertThat(empty.size()).isEqualTo(500);
    }

    @Test
    void teacherCannotReadTemporaryEventsOrTexts() {
        Session session = createSession(TaskMode.FREE, SessionStatus.IN_PROGRESS);

        assertCode(() -> service.events(teacher(), session.id(), 0, 100),
                ErrorCode.FORBIDDEN);
        assertCode(() -> service.texts(teacher(), session.id(), null),
                ErrorCode.FORBIDDEN);
    }

    @Test
    void unknownSessionIsNotFound() {
        assertCode(() -> service.replay(student(), new SessionId(UUID.randomUUID())),
                ErrorCode.RESOURCE_NOT_FOUND);
        assertCode(() -> service.events(student(), new SessionId(UUID.randomUUID()), 0, 100),
                ErrorCode.RESOURCE_NOT_FOUND);
        assertCode(() -> service.texts(student(), new SessionId(UUID.randomUUID()), null),
                ErrorCode.RESOURCE_NOT_FOUND);
    }

    private Session createSession(TaskMode mode, SessionStatus status) {
        Session session = Session.createPreparing(
                new SessionId(UUID.randomUUID()), SessionType.TASK,
                new TaskId(UUID.randomUUID()), COURSE, STUDENT, mode, 1, NOW);
        if (status != SessionStatus.PREPARING) {
            session.start(NOW);
        }
        if (status == SessionStatus.ENDED || status == SessionStatus.SUBMITTED) {
            session.end(END);
        }
        if (status == SessionStatus.SUBMITTED) {
            session.submit(END);
        }
        sessions.save(session);
        if (status == SessionStatus.SUBMITTED) {
            submissions.save(Submission.create(
                    UUID.randomUUID(), session.taskId(), STUDENT, session.id(), null, END));
        }
        return session;
    }

    private void recordingWith(SessionId sessionId, StreamSpec... specs) {
        UUID recordingId = UUID.randomUUID();
        List<RecordingStream> streams = List.of(specs).stream()
                .map(spec -> new RecordingStream(
                        UUID.randomUUID(), recordingId, spec.type(),
                        "upload-" + spec.type().wireValue(),
                        "recordings/test/" + sessionId.value() + "/"
                                + spec.type().wireValue() + ".webm",
                        RecordingStream.DEFAULT_PART_SIZE, 1, 1_024L,
                        spec.durationMs(), spec.offsetMs(), spec.mime(),
                        StreamStatus.UPLOADED))
                .toList();
        recordings.save(Recording.rehydrate(
                recordingId, sessionId, RecordingStatus.UPLOADED, streams, NOW, NOW));
    }

    private SessionEvent event(SessionId sessionId, long seq) {
        return new SessionEvent(
                UUID.randomUUID(), sessionId, seq, "teacher.speech", seq * 100L,
                Map.of("index", (int) seq), NOW);
    }

    private SessionText text(
            SessionId sessionId, String speakerType, UUID speakerRef, String content,
            Long startMs, Long endMs, long atMs) {
        return new SessionText(
                UUID.randomUUID(), sessionId, speakerType, speakerRef, "asr", "utterance",
                content, startMs, endMs, atMs, new BigDecimal("0.95"), null, null, "final");
    }

    private static void assertIncomplete(Runnable action, String... missing) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(ErrorCode.RECORDING_INCOMPLETE);
                    assertThat(exception.details())
                            .containsEntry("missing_streams", List.of(missing));
                });
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo(expected));
    }

    private record StreamSpec(
            StreamType type, long offsetMs, Long durationMs, String mime) {
    }

    /** Captures the TTL of every presigned GET and never talks to MinIO. */
    private static final class RecordingStorageFake implements RecordingObjectStorage {
        private final Map<String, Duration> presignedTtls = new LinkedHashMap<>();

        @Override
        public String presignGet(String objectKey, Duration ttl) {
            presignedTtls.put(objectKey, ttl);
            return "https://storage.test/" + objectKey + "?signature=1";
        }

        Map<String, Duration> presignedTtls() {
            return Map.copyOf(presignedTtls);
        }

        @Override
        public MultipartUpload createMultipart(String objectKey, String contentType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String presignPart(
                String objectKey, String uploadId, int partNumber, Duration ttl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void completeMultipart(
                String objectKey, String uploadId, List<PartEtag> parts) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void abortMultipart(String objectKey, String uploadId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ObjectStat stat(String objectKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String objectKey) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class InMemoryRecordingRepository implements RecordingRepository {
        private final Map<SessionId, Recording> bySession = new LinkedHashMap<>();

        @Override
        public Recording save(Recording recording) {
            bySession.put(recording.sessionId(), recording);
            return recording;
        }

        @Override
        public Optional<Recording> findBySession(SessionId sessionId) {
            return Optional.ofNullable(bySession.get(sessionId));
        }

        @Override
        public Optional<Recording> findById(UUID id) {
            return bySession.values().stream()
                    .filter(recording -> recording.id().equals(id))
                    .findFirst();
        }

        @Override
        public void deleteBySession(SessionId sessionId) {
            bySession.remove(sessionId);
        }
    }
}
