package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.port.RecordingObjectStorage;
import com.shijiangtai.m3classroom.application.port.SessionEventStore;
import com.shijiangtai.m3classroom.application.port.SessionTextStore;
import com.shijiangtai.m3classroom.application.view.ReplayStreamView;
import com.shijiangtai.m3classroom.application.view.ReplayView;
import com.shijiangtai.m3classroom.application.view.SessionEventView;
import com.shijiangtai.m3classroom.application.view.SessionTextView;
import com.shijiangtai.m3classroom.domain.recording.Recording;
import com.shijiangtai.m3classroom.domain.recording.RecordingRepository;
import com.shijiangtai.m3classroom.domain.recording.RecordingStream;
import com.shijiangtai.m3classroom.domain.recording.RequiredStreams;
import com.shijiangtai.m3classroom.domain.recording.StreamStatus;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.session.SessionText;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.PageResult;
import com.shijiangtai.shared.domain.SessionId;
import java.time.Duration;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only replay and context queries (05B §5).
 *
 * <p>Access is the shared session rule: the owner student, or a course teacher once the session is
 * submitted; administrators and cross-school users are forbidden.
 */
@Service
@Transactional(readOnly = true)
public class ReplayQueryServiceImpl implements ReplayQueryService {

    static final Duration REPLAY_URL_TTL = Duration.ofMinutes(15);
    static final int MAX_LIMIT = 500;

    private static final Set<String> SPEAKER_TYPES =
            Set.of("teacher", "student", "examiner", "candidate");
    private static final List<StreamType> FREE_STREAM_ORDER =
            List.of(StreamType.SCREEN, StreamType.CAMERA, StreamType.AUDIO);
    private static final List<StreamType> INTERVIEW_STREAM_ORDER =
            List.of(StreamType.CAMERA, StreamType.AUDIO);

    private final SessionRepository sessionRepository;
    private final RecordingRepository recordingRepository;
    private final RecordingObjectStorage storage;
    private final SessionEventStore eventStore;
    private final SessionTextStore textStore;
    private final SessionAccessPolicy accessPolicy;

    public ReplayQueryServiceImpl(
            SessionRepository sessionRepository,
            RecordingRepository recordingRepository,
            RecordingObjectStorage storage,
            SessionEventStore eventStore,
            SessionTextStore textStore,
            SessionAccessPolicy accessPolicy) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.recordingRepository = Objects.requireNonNull(
                recordingRepository, "recordingRepository");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.textStore = Objects.requireNonNull(textStore, "textStore");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    }

    @Override
    public ReplayView replay(CurrentUser user, SessionId sessionId) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(sessionId, "sessionId");
        Session session = findSession(sessionId);
        accessPolicy.requireRead(user, session);

        Recording recording = recordingRepository.findBySession(sessionId)
                .orElseThrow(() -> incomplete(RequiredStreams.missing(
                        EnumSet.noneOf(StreamType.class), session.mode())));
        Set<StreamType> uploaded = EnumSet.noneOf(StreamType.class);
        for (RecordingStream stream : recording.streams()) {
            if (stream.status() == StreamStatus.UPLOADED) {
                uploaded.add(stream.type());
            }
        }
        List<StreamType> missing = RequiredStreams.missing(uploaded, session.mode());
        if (!missing.isEmpty()) {
            throw incomplete(missing);
        }

        List<ReplayStreamView> streams = recording.streams().stream()
                .filter(stream -> stream.status() == StreamStatus.UPLOADED)
                .sorted(Comparator.comparingInt(
                        (RecordingStream stream) -> streamRank(session.mode(), stream.type())))
                .map(this::toView)
                .toList();
        long durationMs = session.durationMs() == null ? 0L : session.durationMs();
        return new ReplayView(session.id().value(), session.mode().wireValue(),
                durationMs, streams);
    }

    @Override
    public PageResult<SessionEventView> events(
            CurrentUser user, SessionId sessionId, long afterSeq, int limit) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(sessionId, "sessionId");
        Session session = findSession(sessionId);
        accessPolicy.requireRead(user, session);
        requireValidLimit(limit);

        List<SessionEventView> views = eventStore.after(sessionId, afterSeq, limit).stream()
                .map(event -> new SessionEventView(
                        event.eventId(), event.seq(), event.type(), event.atMs(),
                        event.payload()))
                .toList();
        return new PageResult<>(views, eventStore.maxSeq(sessionId), 0, limit);
    }

    @Override
    public List<SessionTextView> texts(
            CurrentUser user, SessionId sessionId, String speakerType) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(sessionId, "sessionId");
        Session session = findSession(sessionId);
        accessPolicy.requireRead(user, session);
        requireValidSpeakerType(speakerType);

        return textStore.bySession(sessionId, speakerType).stream()
                .map(ReplayQueryServiceImpl::toView)
                .toList();
    }

    private Session findSession(SessionId sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND,
                        "会话不存在", Map.of("session_id", sessionId.value())));
    }

    private ReplayStreamView toView(RecordingStream stream) {
        String url = storage.presignGet(stream.objectKey(), REPLAY_URL_TTL);
        long durationMs = stream.durationMs() == null ? 0L : stream.durationMs();
        return new ReplayStreamView(stream.type().wireValue(), url, stream.offsetMs(),
                durationMs, stream.mime());
    }

    private static int streamRank(TaskMode mode, StreamType type) {
        List<StreamType> order = mode == TaskMode.INTERVIEW
                ? INTERVIEW_STREAM_ORDER : FREE_STREAM_ORDER;
        int index = order.indexOf(type);
        return index < 0 ? order.size() : index;
    }

    private static void requireValidLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "分页数量超出范围",
                    Map.of("field", "limit", "value", limit));
        }
    }

    private static void requireValidSpeakerType(String speakerType) {
        if (speakerType != null && !SPEAKER_TYPES.contains(speakerType)) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "未知说话人类型",
                    Map.of("field", "speaker_type", "value", speakerType));
        }
    }

    private static DomainException incomplete(List<StreamType> missing) {
        return new DomainException(ErrorCode.RECORDING_INCOMPLETE, "录制不完整",
                Map.of("missing_streams",
                        missing.stream().map(StreamType::wireValue).toList()));
    }

    private static SessionTextView toView(SessionText text) {
        return new SessionTextView(
                text.id(),
                text.speakerType(),
                text.speakerRef(),
                text.source(),
                text.kind(),
                text.text(),
                text.startMs(),
                text.endMs(),
                text.atMs(),
                text.confidence(),
                text.status());
    }
}
