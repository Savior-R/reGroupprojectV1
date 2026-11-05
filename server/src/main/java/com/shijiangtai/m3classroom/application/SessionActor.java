package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.port.ContentRequest;
import com.shijiangtai.m3classroom.application.port.ContentResponse;
import com.shijiangtai.m3classroom.application.port.ContextPersistenceWriter;
import com.shijiangtai.m3classroom.application.port.SchedulerPort;
import com.shijiangtai.m3classroom.application.port.SessionEventStore;
import com.shijiangtai.m3classroom.application.port.SessionTextStore;
import com.shijiangtai.m3classroom.domain.classroom.AnswerQuality;
import com.shijiangtai.m3classroom.domain.classroom.AnswerQualityPolicy;
import com.shijiangtai.m3classroom.domain.classroom.ClassroomEvent;
import com.shijiangtai.m3classroom.domain.classroom.ClassroomMacroState;
import com.shijiangtai.m3classroom.domain.classroom.ClassroomStateMachine;
import com.shijiangtai.m3classroom.domain.classroom.DialogueState;
import com.shijiangtai.m3classroom.domain.classroom.HandRaise;
import com.shijiangtai.m3classroom.domain.classroom.HandRaisePolicy;
import com.shijiangtai.m3classroom.domain.classroom.QaPhase;
import com.shijiangtai.m3classroom.domain.classroom.VirtualStudentDynamics;
import com.shijiangtai.m3classroom.domain.classroom.VirtualStudentRuntime;
import com.shijiangtai.m3classroom.domain.interview.InterviewSnapshot;
import com.shijiangtai.m3classroom.domain.interview.InterviewState;
import com.shijiangtai.m3classroom.domain.interview.InterviewStateMachine;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.m3classroom.domain.session.CommandResult;
import com.shijiangtai.m3classroom.domain.session.FailureReason;
import com.shijiangtai.m3classroom.domain.session.RecordingState;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionCommand;
import com.shijiangtai.m3classroom.domain.session.SessionContext;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.m3classroom.domain.session.SessionEventDraft;
import com.shijiangtai.m3classroom.domain.session.SessionSnapshot;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.m3classroom.domain.session.SessionText;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshot;
import com.shijiangtai.m3classroom.domain.task.VirtualStudentConfig;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.IdGenerator;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One actor per active session (05A §6.1, implementation/04 §3.3).
 *
 * <p>P0 semantics: every command is serialized by {@link #synchronized}; a duplicate
 * {@code command_id} returns the previous ack without re-executing; validation happens before
 * any state mutation; events are appended to {@link SessionContext}, persisted through
 * {@link ContextPersistenceWriter} and only then pushed to the optional event sink.
 *
 * <p>Hand raises and the 30 s hand-window timeout run through {@link SchedulerPort}, so a
 * command never waits for the simulated classroom. The timeout starts when the first
 * {@code student.hand_raised} event is emitted and carries the raised ids into
 * {@link ClassroomStateMachine#handWindowTimeout(Instant, List)}.
 *
 * <p>A finished answer rolls {@code question_rate * 0.5}: a losing roll keeps the P0 path
 * ({@link ClassroomStateMachine#answerFinished(UUID, boolean, Instant)} with {@code false} and
 * back to {@code waiting_hands}); a winning roll starts a dialogue ({@code dialogue.started} +
 * {@code student.follow_up}) and leaves the classroom in {@code qa.dialogue}. Teacher-response
 * driven continuation rounds end the dialogue through the same {@code dialogue.ended} →
 * student {@code focused} → caller transition order used by every termination path (05A §5.3).
 */
public final class SessionActor {

    private static final Logger LOG = LoggerFactory.getLogger(SessionActor.class);

    private static final int COMMAND_HISTORY_LIMIT = 200;
    private static final long HAND_RAISE_MIN_DELAY_MS = 1_000L;
    private static final long HAND_RAISE_MAX_EXTRA_DELAY_MS = 2_000L;
    private static final Duration HAND_WINDOW_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration WS_GRACE = Duration.ofSeconds(30);

    private static final String RECORDING_READY_ACK = "recording.ready_ack";
    private static final String SESSION_STARTED = "session.started";
    private static final String SESSION_ENDED = "session.ended";
    private static final String SESSION_FAILED = "session.failed";
    private static final String SESSION_RESUMED = "session.resumed";
    private static final String STUDENT_HAND_RAISED = "student.hand_raised";
    private static final String STUDENT_HAND_LOWERED = "student.hand_lowered";
    private static final String STUDENT_QUESTION = "student.question";
    private static final String STUDENT_ANSWER = "student.answer";
    private static final String STUDENT_FOLLOW_UP = "student.follow_up";
    private static final String DIALOGUE_STARTED = "dialogue.started";
    private static final String DIALOGUE_ENDED = "dialogue.ended";
    private static final String TEACHER_CALLED_ON = "teacher.called_on";
    private static final String TEACHER_MARKED = "teacher.marked";
    private static final String TEACHER_MANAGEMENT_ACTION = "teacher.management_action";
    private static final String QUESTION_ASKED = "classroom.question_asked";
    private static final String STATE_CHANGED = "classroom.state_changed";
    private static final String HAND_WINDOW_TIMEOUT_EVENT = "classroom.hand_window_timeout";
    private static final String INTERVIEW_QUESTION_SHOWN = "interview.question_shown";
    private static final String PERCEPTION_SCREEN_CHANGED = "perception.screen_changed";
    private static final String TIME_TICK = "session.time_tick";
    private static final String END_REASON_MANUAL = "manual";
    private static final String END_REASON_TIMEOUT = "timeout";

    private static final Set<String> NON_REPLAYABLE =
            Set.of(TIME_TICK, "ping", "pong", "time.sync");

    private final Session session;
    private final TaskSnapshot taskSnapshot;
    private final SessionContext context;
    private final ClassroomStateMachine classroom;
    private final VirtualStudentDynamics students;
    private final InterviewStateMachine interview;
    private final ContentGenerationService content;
    private final ContextPersistenceWriter persistence;
    private final IdGenerator ids;
    private final ClockProvider clock;
    private final SchedulerPort scheduler;
    private final RandomGenerator random;

    private final HandRaisePolicy handRaisePolicy = new HandRaisePolicy();
    private final AnswerQualityPolicy answerQualityPolicy = new AnswerQualityPolicy();
    private final Map<String, CommandResult> commandHistory =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CommandResult> eldest) {
                    return size() > COMMAND_HISTORY_LIMIT;
                }
            };
    private final Map<UUID, UUID> openHandRaises = new LinkedHashMap<>();
    private final Map<UUID, UUID> answerStudents = new HashMap<>();
    private final EnumMap<StreamType, String> streamStatuses = new EnumMap<>(StreamType.class);

    private Consumer<SessionEvent> eventSink;
    private Consumer<SessionStatus> statusListener = status -> { };
    private final List<Consumer<SessionStatus>> statusListeners = new CopyOnWriteArrayList<>();
    private SchedulerPort.Cancellable handRaiseTask;
    private SchedulerPort.Cancellable handWindowTask;
    private SchedulerPort.Cancellable graceTask;
    private long windowGeneration;
    private UUID currentStudentId;

    private DialogueState dialogue;
    private UUID dialogueAnswerId;
    private long dialogueAwaitingSinceMs;

    public SessionActor(
            Session session,
            TaskSnapshot taskSnapshot,
            SessionContext context,
            ClassroomStateMachine classroom,
            VirtualStudentDynamics students,
            InterviewStateMachine interview,
            ContentGenerationService content,
            ContextPersistenceWriter persistence,
            IdGenerator ids,
            ClockProvider clock,
            SchedulerPort scheduler,
            RandomGenerator random) {
        this.session = Objects.requireNonNull(session, "session");
        this.taskSnapshot = Objects.requireNonNull(taskSnapshot, "taskSnapshot");
        this.context = Objects.requireNonNull(context, "context");
        this.classroom = Objects.requireNonNull(classroom, "classroom");
        this.students = Objects.requireNonNull(students, "students");
        this.interview = Objects.requireNonNull(interview, "interview");
        this.content = Objects.requireNonNull(content, "content");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.random = Objects.requireNonNull(random, "random");
        initializeStudentsIfNeeded();
    }

    /** Backwards-compatible constructor: synchronous persistence through the two stores. */
    public SessionActor(
            Session session,
            TaskSnapshot taskSnapshot,
            SessionContext context,
            ClassroomStateMachine classroom,
            VirtualStudentDynamics students,
            InterviewStateMachine interview,
            ContentGenerationService content,
            SessionEventStore eventStore,
            SessionTextStore textStore,
            IdGenerator ids,
            ClockProvider clock,
            SchedulerPort scheduler,
            RandomGenerator random) {
        this(session, taskSnapshot, context, classroom, students, interview, content,
                new DirectContextPersistenceWriter(eventStore, textStore), ids, clock,
                scheduler, random);
    }

    // ------------------------------------------------------------------
    // Command entry point
    // ------------------------------------------------------------------

    /**
     * Cancels the live session from the REST fallback: moves the aggregate to {@code CANCELLED},
     * invalidates in-flight timer generations, clears open hands and cancels every scheduled
     * task. A callback already running either finished before this lock or observes the
     * terminal status and becomes a no-op.
     *
     * <p>The best-effort flush runs <em>before</em> {@link #notifyStatusChanged()}: the registry
     * reacts to the terminal status by closing (and thus releasing) the persistence queue, so
     * flushing afterwards would find no queue and silently skip the retry.
     */
    public synchronized void cancel(Instant now) {
        Objects.requireNonNull(now, "now");
        session.cancel(now);
        windowGeneration++;
        openHandRaises.clear();
        dialogue = null;
        dialogueAnswerId = null;
        dialogueAwaitingSinceMs = 0L;
        cancelAllTasks();
        try {
            forceFlush();
        } catch (RuntimeException exception) {
            LOG.warn("Failed to flush cancelled session {}", session.id().value(), exception);
        }
        notifyStatusChanged();
    }

    public synchronized CommandResult handle(SessionCommand command) {
        Objects.requireNonNull(command, "command");
        CommandResult duplicate = commandHistory.get(command.commandId());
        if (duplicate != null) {
            return duplicate;
        }
        CommandResult result;
        try {
            result = dispatch(command);
        } catch (DomainException exception) {
            result = CommandResult.error(exception.code(), exception.getMessage());
        } catch (RuntimeException exception) {
            LOG.error("Unexpected failure handling session command: session_id={}, "
                            + "command={}, command_id={}",
                    session.id().value(), command.getClass().getSimpleName(),
                    command.commandId(), exception);
            result = CommandResult.error(ErrorCode.INTERNAL_ERROR, "命令处理失败");
        }
        commandHistory.put(command.commandId(), result);
        return result;
    }

    private CommandResult dispatch(SessionCommand command) {
        if (command instanceof SessionCommand.RecordingReady ready) {
            return handleRecordingReady(ready);
        }
        if (command instanceof SessionCommand.StartSession start) {
            return handleStart(start);
        }
        if (command instanceof SessionCommand.ResumeSession resume) {
            return resume(resume.lastSeq(), resume.recordingState());
        }
        if (command instanceof SessionCommand.AskClass ask) {
            return handleAsk(false);
        }
        if (command instanceof SessionCommand.AskStudentQuestion ask) {
            return handleAsk(true);
        }
        if (command instanceof SessionCommand.CallOn callOn) {
            return handleCallOn(callOn);
        }
        if (command instanceof SessionCommand.MarkAnswer markAnswer) {
            return handleMarkAnswer(markAnswer);
        }
        if (command instanceof SessionCommand.ManageStudent manage) {
            return handleManageStudent(manage);
        }
        if (command instanceof SessionCommand.EndDialogue endDialogue) {
            return handleEndDialogue(endDialogue);
        }
        if (command instanceof SessionCommand.EndQa endQa) {
            return handleEndQa();
        }
        if (command instanceof SessionCommand.EndSession endSession) {
            return handleEndSession();
        }
        if (command instanceof SessionCommand.NextQuestion nextQuestion) {
            return handleNextQuestion();
        }
        if (command instanceof SessionCommand.EndInterview endInterview) {
            return handleEndInterview();
        }
        if (command instanceof SessionCommand.TimeSync timeSync) {
            return CommandResult.ok(List.of());
        }
        if (command instanceof SessionCommand.Pong pong) {
            return CommandResult.ok(List.of());
        }
        throw new DomainException(ErrorCode.INTERNAL_ERROR, "未知命令类型");
    }

    // ------------------------------------------------------------------
    // Lifecycle commands
    // ------------------------------------------------------------------

    private CommandResult handleRecordingReady(SessionCommand.RecordingReady command) {
        requireAnyStatus("recording.ready", SessionStatus.PREPARING, SessionStatus.IN_PROGRESS);
        Instant now = clock.now();
        for (SessionCommand.RecordingReady.StreamStatus status : command.streams()) {
            streamStatuses.put(status.streamType(), status.status());
        }
        List<String> required = requiredStreams().stream().map(StreamType::wireValue).toList();
        boolean ok = requiredStreams().stream().allMatch(this::isStreamReady);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("required_streams", required);
        payload.put("ok", ok);
        return CommandResult.ok(List.of(append(RECORDING_READY_ACK, payload, now)));
    }

    private CommandResult handleStart(SessionCommand.StartSession command) {
        requireStatus("session.start", SessionStatus.PREPARING);
        requireAllStreamsReady();
        TaskSnapshot.InterviewSnapshot interviewConfig = taskSnapshot.interview();
        if (isInterviewMode()) {
            if (interviewConfig == null || interviewConfig.questions().isEmpty()) {
                throw stateInvalid("session.start", "面试题目为空");
            }
            if (interviewConfig.totalDurationSeconds() <= 0) {
                throw stateInvalid("session.start", "面试总时长非法");
            }
        }

        Instant now = clock.now();
        // A start from a reconnected client must not be killed by a stale PREPARING grace
        // timer; the connection may not have gone through onWsConnected (e.g. REST fallback).
        cancelGrace();
        session.start(now);
        List<SessionEvent> events = new ArrayList<>();
        events.add(append(SESSION_STARTED, Map.of("started_at_ms", 0L), now));
        if (isInterviewMode()) {
            events.addAll(appendClassroomEvents(interview.start(
                    interviewConfig.questions(), now,
                    interviewConfig.totalDurationSeconds()), now));
        } else {
            classroom.start();
        }
        notifyStatusChanged();
        return CommandResult.ok(events);
    }

    /**
     * Grace reconnect (05A §8). The event sink is the single live push path: the actor pushes
     * {@code session.resumed} first and then the replayable tail after {@code lastSeq}
     * ({@code session.time_tick}/{@code ping} excluded) in append order, even though the replayed
     * {@code seq} values are lower than the resume event. The returned events mirror that push
     * order; the Task 12 handler only sends {@code server.ack}/{@code error} itself and must not
     * send {@link CommandResult#events()} again.
     */
    public synchronized CommandResult resume(long lastSeq, RecordingState state) {
        Objects.requireNonNull(state, "state");
        if (session.status() == SessionStatus.FAILED) {
            return CommandResult.error(ErrorCode.SESSION_FAILED, "会话已失败");
        }
        if (state == RecordingState.LOST) {
            fail(FailureReason.RECORDING_LOST);
            return CommandResult.error(ErrorCode.SESSION_FAILED, "录制已丢失");
        }
        requireStatus("session.resume", SessionStatus.IN_PROGRESS);
        cancelGrace();
        Instant now = clock.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("last_seq", lastSeq);
        payload.put("server_time_ms", now.toEpochMilli());
        SessionEvent resumed = append(SESSION_RESUMED, payload, now);
        List<SessionEvent> events = new ArrayList<>();
        events.add(resumed);
        for (SessionEvent event : replayAfter(lastSeq)) {
            if (event.seq() != resumed.seq()) {
                events.add(event);
                push(event);
            }
        }
        return CommandResult.ok(events);
    }

    // ------------------------------------------------------------------
    // Classroom commands
    // ------------------------------------------------------------------

    private CommandResult handleAsk(boolean studentQuestion) {
        String action = studentQuestion ? "teacher.ask_student_question" : "teacher.ask_class";
        requireInProgress(action);
        requireClassroomMode(action);

        Instant now = clock.now();
        List<ClassroomEvent> classroomEvents = studentQuestion
                ? classroom.askStudentQuestion(now)
                : classroom.askClass(now);

        List<SessionEvent> events = new ArrayList<>(endActiveDialogue("qa_restarted", now));
        events.addAll(emitHandLowers("qa_ended", now));
        cancel(handRaiseTask);
        handRaiseTask = null;
        cancel(handWindowTask);
        handWindowTask = null;
        events.addAll(appendClassroomEvents(classroomEvents, now));

        scheduleHandWindow(studentQuestion
                ? ClassroomStateMachine.INTENT_STUDENT_QUESTION
                : ClassroomStateMachine.INTENT_ANSWER);
        return CommandResult.ok(events);
    }

    /**
     * Invalidates the previous window generation and schedules the first hand raise (1–3 s) plus
     * the 30 s hand-window timeout. Shared by manual asks and voice-triggered questions.
     *
     * <p>The timeout is scheduled even before the first hand is raised: a class with no eligible
     * student must still leave {@code qa.waiting_hands} after 30 s. When a hand is raised the
     * timer is moved to 30 s from that first raise (05A §5.4).
     */
    private long scheduleHandWindow(String expectedIntent) {
        long generation = ++windowGeneration;
        long delayMs = HAND_RAISE_MIN_DELAY_MS
                + random.nextLong(HAND_RAISE_MAX_EXTRA_DELAY_MS + 1L);
        handRaiseTask = scheduler.schedule(
                () -> emitHandRaises(expectedIntent, generation), Duration.ofMillis(delayMs));
        handWindowTask = scheduler.schedule(
                () -> emitHandWindowTimeout(generation), HAND_WINDOW_TIMEOUT);
        return generation;
    }

    private synchronized void emitHandRaises(String expectedIntent, long generation) {
        handRaiseTask = null;
        if (generation != windowGeneration
                || session.status() != SessionStatus.IN_PROGRESS
                || classroom.macroState() != ClassroomMacroState.QA
                || classroom.qaPhase() != QaPhase.WAITING_HANDS
                || !expectedIntent.equals(classroom.intent())) {
            return;
        }
        Instant now = clock.now();
        List<HandRaise> handRaises = handRaisePolicy.select(students.students(), now, random);
        if (handRaises.isEmpty()) {
            // Keep the timeout scheduled by handleAsk; the class may simply have no eligible
            // student (all whispering / in dialogue / ineligible), and the window must still end.
            return;
        }
        for (HandRaise handRaise : handRaises) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("student_id", handRaise.studentId());
            payload.put("hand_raise_id", handRaise.handRaiseId());
            append(STUDENT_HAND_RAISED, payload, now);
            appendStudentEvents(students.applyHandRaised(
                    handRaise.studentId(), now, random), now);
            openHandRaises.put(handRaise.handRaiseId(), handRaise.studentId());
        }
        // Move the window timeout to 30 s after the first hand raise; the ask-scheduled timer
        // only covers the "nobody raises" path.
        cancel(handWindowTask);
        handWindowTask = scheduler.schedule(
                () -> emitHandWindowTimeout(generation), HAND_WINDOW_TIMEOUT);
    }

    private synchronized void emitHandWindowTimeout(long generation) {
        handWindowTask = null;
        if (generation != windowGeneration
                || session.status() != SessionStatus.IN_PROGRESS
                || classroom.macroState() != ClassroomMacroState.QA
                || classroom.qaPhase() != QaPhase.WAITING_HANDS) {
            return;
        }
        // P0 fallback: an empty window still times out. A class with no eligible student must
        // not stay in qa.waiting_hands forever; the event carries an empty id list.
        List<UUID> handRaiseIds = List.copyOf(openHandRaises.keySet());
        Instant now = clock.now();
        emitHandLowers("teacher_timeout", now);
        appendClassroomEvents(classroom.handWindowTimeout(now, handRaiseIds), now);
    }

    private CommandResult handleCallOn(SessionCommand.CallOn command) {
        requireInProgress("teacher.call_on");
        requireClassroomMode("teacher.call_on");
        VirtualStudentRuntime student = requireStudent(command.studentId());
        if (command.handRaiseId() != null) {
            UUID raisedStudent = openHandRaises.get(command.handRaiseId());
            if (raisedStudent != null && !raisedStudent.equals(command.studentId())) {
                throw new DomainException(ErrorCode.VALIDATION_ERROR,
                        "举手学生与点名学生不一致",
                        Map.of("hand_raise_id", command.handRaiseId().toString()));
            }
        }

        Instant now = clock.now();
        List<ClassroomEvent> callOnEvents = classroom.callOn(command.studentId(), now);
        windowGeneration++;
        cancel(handRaiseTask);
        handRaiseTask = null;
        cancel(handWindowTask);
        handWindowTask = null;
        currentStudentId = command.studentId();

        if (command.handRaiseId() != null) {
            openHandRaises.remove(command.handRaiseId());
        }
        List<SessionEvent> events = new ArrayList<>(emitHandLowers("called_on", now));
        events.addAll(appendClassroomEvents(callOnEvents, now));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("student_id", command.studentId());
        if (command.handRaiseId() != null) {
            payload.put("hand_raise_id", command.handRaiseId());
        }
        events.add(append(TEACHER_CALLED_ON, payload, now));
        events.addAll(appendStudentEvents(students.applyCalledOn(command.studentId()), now));

        if (ClassroomStateMachine.INTENT_STUDENT_QUESTION.equals(classroom.intent())) {
            events.addAll(emitStudentQuestion(student, now));
        } else {
            events.addAll(emitStudentAnswer(student, now));
        }
        return CommandResult.ok(events);
    }

    private CommandResult handleMarkAnswer(SessionCommand.MarkAnswer command) {
        requireInProgress("teacher.mark_answer");
        requireClassroomMode("teacher.mark_answer");
        Instant now = clock.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("answer_id", command.answerId());
        payload.put("correctness", command.correctness().wireValue());
        SessionEvent event = append(TEACHER_MARKED, payload, now);
        UUID studentId = answerStudents.get(command.answerId());
        if (studentId != null) {
            students.applyMarked(studentId, command.correctness());
        }
        return CommandResult.ok(List.of(event));
    }

    private CommandResult handleManageStudent(SessionCommand.ManageStudent command) {
        requireInProgress("teacher.manage_student");
        requireClassroomMode("teacher.manage_student");
        VirtualStudentRuntime student = requireStudent(command.studentId());
        Instant now = clock.now();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("student_id", student.id());
        payload.put("action", command.action());
        List<SessionEvent> events = new ArrayList<>();
        events.add(append(TEACHER_MANAGEMENT_ACTION, payload, now));
        events.addAll(appendClassroomEvents(students.applyManaged(student.id()), now));
        return CommandResult.ok(events);
    }

    private CommandResult handleEndDialogue(SessionCommand.EndDialogue command) {
        requireInProgress("teacher.end_dialogue");
        requireClassroomMode("teacher.end_dialogue");
        requireMatchingDialogueId(command);
        Instant now = clock.now();

        List<SessionEvent> events;
        boolean answerFinished = false;
        if (classroom.macroState() == ClassroomMacroState.QA
                && classroom.qaPhase() == QaPhase.STUDENT_ANSWERING) {
            // P0: an explicit end_dialogue while the student is answering finishes the answer.
            // No dialogue is active in this phase, so any supplied dialogue_id was already
            // rejected as stale by requireMatchingDialogueId.
            if (currentStudentId == null) {
                throw stateInvalid("teacher.end_dialogue", "当前没有可结束的回答");
            }
            List<ClassroomEvent> domainEvents =
                    classroom.answerFinished(currentStudentId, false, now);
            events = new ArrayList<>(appendClassroomEvents(domainEvents, now));
            answerFinished = true;
        } else {
            if (classroom.macroState() == ClassroomMacroState.QA
                    && classroom.qaPhase() == QaPhase.DIALOGUE) {
                events = new ArrayList<>(endActiveDialogue("teacher", now));
                events.addAll(appendClassroomEvents(classroom.endDialogue(
                        ClassroomStateMachine.TRIGGER_END_DIALOGUE, now), now));
            } else {
                // Any other phase is rejected by the state machine without mutation.
                events = new ArrayList<>(appendClassroomEvents(
                        classroom.endDialogue(now), now));
            }
        }

        windowGeneration++;
        cancel(handWindowTask);
        handWindowTask = null;
        openHandRaises.clear();
        if (answerFinished) {
            events.addAll(appendStudentEvents(
                    students.applyAnswerFinished(currentStudentId), now));
        }
        return CommandResult.ok(events);
    }

    /**
     * Rejects {@code teacher.end_dialogue} carrying a stale {@code dialogue_id}: when the command
     * supplies an id it must match the active dialogue. Nothing is mutated on rejection.
     */
    private void requireMatchingDialogueId(SessionCommand.EndDialogue command) {
        if (command.dialogueId() != null
                && (dialogue == null
                || !dialogue.dialogueId().equals(command.dialogueId()))) {
            throw stateInvalid("teacher.end_dialogue", "对话不存在或已结束");
        }
    }

    private CommandResult handleEndQa() {
        requireInProgress("teacher.end_qa");
        requireClassroomMode("teacher.end_qa");
        Instant now = clock.now();
        List<ClassroomEvent> domainEvents = classroom.endQa("manual", now);

        windowGeneration++;
        List<SessionEvent> events = new ArrayList<>(endActiveDialogue("qa_ended", now));
        events.addAll(emitHandLowers("qa_ended", now));
        cancel(handRaiseTask);
        handRaiseTask = null;
        cancel(handWindowTask);
        handWindowTask = null;
        events.addAll(appendClassroomEvents(domainEvents, now));
        return CommandResult.ok(events);
    }

    private CommandResult handleEndSession() {
        requireInProgress("teacher.end_session");
        requireClassroomMode("teacher.end_session");
        List<SessionEvent> events = endClassroomSession(clock.now(), END_REASON_MANUAL);
        if (session.status() == SessionStatus.FAILED) {
            return CommandResult.error(ErrorCode.SESSION_FAILED, "会话持久化失败");
        }
        return CommandResult.ok(events);
    }

    /**
     * Ends a free/scenario classroom session, either manually ({@code manual}) or when
     * {@code duration.max_seconds} is reached ({@code timeout}, 05C §6.3).
     */
    private List<SessionEvent> endClassroomSession(Instant now, String reason) {
        List<ClassroomEvent> domainEvents = classroom.endSession(now);

        windowGeneration++;
        List<SessionEvent> events = new ArrayList<>(
                endActiveDialogue("session_ended", now));
        events.addAll(emitHandLowers("session_ended", now));
        events.addAll(appendClassroomEvents(domainEvents, now));
        session.end(now);
        try {
            events.add(appendCritical(SESSION_ENDED, sessionEndedPayload(reason), now));
        } catch (RuntimeException exception) {
            persistenceFailed(now, exception);
            return events;
        }
        cancelAllTasks();
        notifyStatusChanged();
        return events;
    }

    // ------------------------------------------------------------------
    // Interview commands
    // ------------------------------------------------------------------

    private CommandResult handleNextQuestion() {
        requireInProgress("interview.next_question");
        requireInterviewMode("interview.next_question");
        Instant now = clock.now();
        return CommandResult.ok(appendClassroomEvents(interview.nextQuestion(now), now));
    }

    private CommandResult handleEndInterview() {
        requireInProgress("interview.end");
        requireInterviewMode("interview.end");
        Instant now = clock.now();
        List<SessionEvent> events = endInterview(now, InterviewStateMachine.REASON_MANUAL);
        if (session.status() == SessionStatus.FAILED) {
            return CommandResult.error(ErrorCode.SESSION_FAILED, "会话持久化失败");
        }
        return CommandResult.ok(events);
    }

    private List<SessionEvent> endInterview(Instant now, String reason) {
        List<SessionEvent> events = new ArrayList<>(
                appendClassroomEvents(interview.end(now, reason), now));
        session.end(now);
        try {
            events.add(appendCritical(SESSION_ENDED, sessionEndedPayload(reason), now));
        } catch (RuntimeException exception) {
            persistenceFailed(now, exception);
            return events;
        }
        cancelAllTasks();
        notifyStatusChanged();
        return events;
    }

    // ------------------------------------------------------------------
    // Tick, failure, replay, snapshot
    // ------------------------------------------------------------------

    /**
     * One 1 s tick (05A §6.2): interview timeout check, virtual-student dynamics and the
     * {@code session.time_tick} push. Outside {@code in_progress} this is a no-op, so a tick
     * callback that races a terminal transition cannot mutate the session.
     */
    public synchronized void onTick(Instant now) {
        Objects.requireNonNull(now, "now");
        if (session.status() != SessionStatus.IN_PROGRESS) {
            return;
        }
        if (isInterviewMode()) {
            if (interview.state() == InterviewState.PRESENTING
                    && interview.snapshot(now).remainingMs() <= 0L) {
                endInterview(now, InterviewStateMachine.REASON_TIMEOUT);
                return;
            }
        } else if (remainingMs(now) <= 0L) {
            endClassroomSession(now, END_REASON_TIMEOUT);
            return;
        }

        // tick() already applied the state transitions to the runtime students; only the
        // resulting events need to be appended, so they are not fed through the command-side
        // dynamics again.
        for (ClassroomEvent event : students.tick(1L, now, random)) {
            append(event.type(), event.payload(), now);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("elapsed_ms", atMs(now));
        payload.put("remaining_ms", remainingMs(now));
        payload.put("discipline_level", students.disciplineLevel());
        payload.put("states", studentStates());
        append(TIME_TICK, payload, now);
    }

    /**
     * Terminal failure (05A §4.1): the aggregate moves to {@code failed}, open hands are lowered,
     * {@code session.failed} is persisted and pushed, every scheduled callback is cancelled and
     * the registry is notified so the actor is dropped from the live map.
     */
    public synchronized void fail(FailureReason reason) {
        Objects.requireNonNull(reason, "reason");
        if (session.status() == SessionStatus.SUBMITTED
                || session.status() == SessionStatus.CANCELLED
                || session.status() == SessionStatus.FAILED) {
            return;
        }
        Instant now = clock.now();
        session.fail(reason, now);
        windowGeneration++;
        RuntimeException flushFailure = null;
        try {
            endActiveDialogue("session_ended", now);
            emitHandLowers("session_failed", now);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("reason", reason.wireValue());
            append(SESSION_FAILED, payload, now);
        } finally {
            // The aggregate is already failed: timers and the registry entry must be released
            // even if the terminal flush (or a hand-lower/session.failed append) throws. A
            // flush failure is rethrown after cleanup; a try-block exception takes precedence.
            try {
                forceFlush();
            } catch (RuntimeException exception) {
                flushFailure = exception;
                LOG.error("Failed to flush session.failed for {}", session.id().value(),
                        exception);
            }
            cancelAllTasks();
            notifyStatusChanged();
        }
        if (flushFailure != null) {
            throw flushFailure;
        }
    }

    /**
     * Converts a failed terminal flush into a failed session: the aggregate moves to
     * {@code failed} (from {@code ENDED} too), {@code session.failed} is appended and flushed
     * best-effort, timers are cancelled and listeners are notified.
     */
    private void persistenceFailed(Instant now, RuntimeException cause) {
        LOG.error("Session {} persistence flush failed; failing session",
                session.id().value(), cause);
        if (session.status() != SessionStatus.FAILED) {
            session.fail(FailureReason.INTERNAL, now);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", FailureReason.INTERNAL.wireValue());
        try {
            append(SESSION_FAILED, payload, now);
        } catch (RuntimeException appendFailure) {
            LOG.error("Failed to append session.failed for {}", session.id().value(),
                    appendFailure);
        }
        try {
            forceFlush();
        } catch (RuntimeException flushFailure) {
            LOG.error("Failed to flush session.failed for {}", session.id().value(),
                    flushFailure);
        }
        cancelAllTasks();
        notifyStatusChanged();
    }

    /**
     * Starts the 30 s reconnect grace after a control-WebSocket disconnect. Both
     * {@code preparing} and {@code in_progress} sessions are recoverable; a preparing session
     * that is not resumed is failed with {@code grace_timeout} just like a live classroom
     * (02 §6.5). Terminal states are ignored.
     */
    public synchronized void onWsDisconnected(Instant now) {
        Objects.requireNonNull(now, "now");
        SessionStatus status = session.status();
        if (status != SessionStatus.PREPARING && status != SessionStatus.IN_PROGRESS) {
            return;
        }
        cancelGrace();
        graceTask = scheduler.schedule(
                () -> fail(FailureReason.GRACE_TIMEOUT), WS_GRACE);
    }

    /**
     * Cancels the reconnect grace when a control connection is (re)established. A PREPARING
     * session may then be started over the new socket; without this cancellation the stale
     * timer would fail an in-progress, connected session 30 s after the original disconnect.
     */
    public synchronized void onWsConnected() {
        cancelGrace();
    }

    // ------------------------------------------------------------------
    // Perception hooks (05A §6.2)
    // ------------------------------------------------------------------

    /**
     * Persists one final ASR segment as a {@link SessionText}. Perception callbacks can race a
     * session end; outside {@code in_progress} (or before the session started) the text is
     * dropped so no speech is persisted after the terminal event.
     */
    public synchronized Optional<UUID> recordSpeechFinal(
            String speakerType,
            UUID speakerRef,
            String text,
            Long startMs,
            Long endMs,
            BigDecimal confidence) {
        if (session.status() != SessionStatus.IN_PROGRESS
                || session.startedAt() == null
                || text == null
                || text.isBlank()) {
            return Optional.empty();
        }
        UUID textId = ids.nextId();
        Instant now = clock.now();
        long atMs = endMs != null ? Math.max(0L, endMs) : this.atMs(now);
        persistText(new SessionText(
                textId, session.id(), speakerType, speakerRef, "asr",
                "candidate".equals(speakerType) ? "answer" : "utterance",
                text, startMs, endMs, atMs, confidence, null, null, "final"));
        if ("teacher".equals(speakerType)) {
            continueDialogueAfterTeacherFinal(text, startMs, endMs, now);
        }
        return Optional.of(textId);
    }

    /**
     * Advances the active dialogue once a teacher ASR final starts at or after the current
     * follow-up boundary (05A §5.5). Finals that began before the boundary belong to the
     * teacher's previous utterance and only get persisted. At the round limit or on a losing
     * follow-up roll the dialogue ends and the classroom returns to {@code waiting_hands};
     * otherwise the student follows up again in the next round.
     */
    private void continueDialogueAfterTeacherFinal(
            String text, Long startMs, Long endMs, Instant now) {
        if (dialogue == null) {
            return;
        }
        long finalStart = startMs != null ? startMs
                : endMs != null ? endMs : currentAtMs();
        if (finalStart < dialogueAwaitingSinceMs) {
            return;
        }
        VirtualStudentRuntime student = students.find(dialogue.studentId());
        if (!dialogue.canFollowUp()) {
            endActiveDialogue("max_rounds", now);
            appendClassroomEvents(classroom.endDialogue(
                    ClassroomStateMachine.TRIGGER_MAX_ROUNDS, now), now);
            return;
        }
        if (!dialogue.shouldFollowUp(
                student.config().questionRate().doubleValue(), random)) {
            endActiveDialogue("no_follow_up", now);
            appendClassroomEvents(classroom.endDialogue(
                    ClassroomStateMachine.TRIGGER_NO_FOLLOW_UP, now), now);
            return;
        }
        dialogue.nextRound();
        emitFollowUp(student, now, true);
    }

    /**
     * Applies an off-actor teacher-utterance classification. A high-confidence question starts a
     * voice QA window with the same hand scheduling as a manual ask; every other classification
     * only appends the suggested-question event. Terminal and interview sessions are ignored, so
     * a late classification cannot mutate the session.
     */
    public synchronized void onTeacherSpeechClassified(double confidence) {
        if (session.status() != SessionStatus.IN_PROGRESS || isInterviewMode()) {
            return;
        }
        // A teacher final during QA (answering / dialogue / waiting hands) is the teacher's
        // response to the ongoing interaction, not a new class-level question.
        if (classroom.macroState() != ClassroomMacroState.LECTURING) {
            return;
        }
        Instant now = clock.now();
        List<ClassroomEvent> classroomEvents = classroom.onTeacherUtterance(confidence, now);
        boolean voiceQuestion = classroomEvents.stream().anyMatch(event ->
                QUESTION_ASKED.equals(event.type())
                        && ClassroomStateMachine.SOURCE_VOICE.equals(
                                event.payload().get("source")));
        if (!voiceQuestion) {
            appendClassroomEvents(classroomEvents, now);
            return;
        }
        emitHandLowers("qa_ended", now);
        cancel(handRaiseTask);
        handRaiseTask = null;
        cancel(handWindowTask);
        handWindowTask = null;
        appendClassroomEvents(classroomEvents, now);
        scheduleHandWindow(ClassroomStateMachine.INTENT_ANSWER);
    }

    /** Emits a processed-screen perception event; late OCR results after the end are dropped. */
    public synchronized void onScreenProcessed(UUID frameId, long frameAtMs, String frameType) {
        if (session.status() != SessionStatus.IN_PROGRESS) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("frame_id", frameId);
        payload.put("at_ms", frameAtMs);
        payload.put("frame_type", frameType);
        append(PERCEPTION_SCREEN_CHANGED, payload, clock.now());
    }

    /** Session clock shared with perception message timestamps. */
    public synchronized long currentAtMs() {
        return atMs(clock.now());
    }

    /** Interview answer id shared by all ASR segments of the current question, else null. */
    public synchronized UUID currentAnswerId() {
        if (!isInterviewMode() || session.status() != SessionStatus.IN_PROGRESS) {
            return null;
        }
        return interview.snapshot(clock.now()).answerId();
    }

    public synchronized List<SessionEvent> replayAfter(long seq) {
        return context.after(seq).stream()
                .filter(event -> !NON_REPLAYABLE.contains(event.type()))
                .toList();
    }

    public synchronized SessionSnapshot snapshot() {
        Instant now = clock.now();
        long elapsedMs = atMs(now);

        Map<String, Object> publicConfig = new LinkedHashMap<>();
        publicConfig.put("topic", taskSnapshot.topic());
        publicConfig.put("textbook_version", taskSnapshot.textbookVersion());
        if (taskSnapshot.duration() != null) {
            publicConfig.put("recommended_seconds",
                    taskSnapshot.duration().recommendedSeconds());
            publicConfig.put("max_seconds", taskSnapshot.duration().maxSeconds());
        }
        if (taskSnapshot.scenario() != null) {
            Map<String, Object> scenario = new LinkedHashMap<>();
            scenario.put("role", taskSnapshot.scenario().role());
            scenario.put("situation", taskSnapshot.scenario().situation());
            publicConfig.put("scenario", scenario);
        }

        List<Map<String, Object>> virtualStudents = students.students().stream()
                .map(this::virtualStudentView)
                .toList();
        return new SessionSnapshot(
                session.status(),
                session.mode().wireValue(),
                publicConfig,
                virtualStudents,
                interviewView(now),
                recordingView(),
                context.lastSeq(),
                elapsedMs);
    }

    /** Drains every pending event/text write for this session through the writer. */
    public synchronized void forceFlush() {
        persistence.flush(session.id());
    }

    /** Last sequence number allocated by the in-memory context. */
    public synchronized long lastSeq() {
        return context.lastSeq();
    }

    public void attachEventSink(Consumer<SessionEvent> sink) {
        this.eventSink = Objects.requireNonNull(sink, "sink");
    }

    /**
     * Application-layer hook used by {@link SessionRuntimeRegistry} to start the 1 s tick on
     * {@code in_progress} and to stop it plus drop the actor on terminal states. The callback
     * runs while this actor's monitor is held, so listeners must not call back into blocking
     * actor commands.
     */
    public void attachStatusListener(Consumer<SessionStatus> listener) {
        this.statusListener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Registers an additional status listener without replacing the primary one; the returned
     * action removes it again. Used by the perception WebSocket connection registry, which must
     * not disturb the runtime registry's tick listener.
     */
    public Runnable addStatusListener(Consumer<SessionStatus> listener) {
        Objects.requireNonNull(listener, "listener");
        statusListeners.add(listener);
        return () -> statusListeners.remove(listener);
    }

    public synchronized boolean isInProgress() {
        return session.status() == SessionStatus.IN_PROGRESS;
    }

    /** The live aggregate; the service layer persists it after command execution. */
    public Session session() {
        return session;
    }

    // ------------------------------------------------------------------
    // Event emission and virtual-student reactions
    // ------------------------------------------------------------------

    private SessionEvent append(String type, Map<String, Object> payload, Instant now) {
        SessionEvent event = context.append(SessionEventDraft.of(type, payload), atMs(now));
        persistence.appendEvent(event);
        push(event);
        return event;
    }

    /**
     * Appends a terminal event and flushes it synchronously before pushing: a failed flush must
     * never advertise a terminal event to clients whose durable state still lags behind.
     */
    private SessionEvent appendCritical(String type, Map<String, Object> payload, Instant now) {
        SessionEvent event = context.append(SessionEventDraft.of(type, payload), atMs(now));
        persistence.appendEvent(event);
        persistence.flush(session.id());
        push(event);
        return event;
    }

    /**
     * Records one text in the in-memory stream (for subsequent content requests) and hands it to
     * the persistence writer.
     */
    private void persistText(SessionText text) {
        context.addText(text);
        persistence.saveText(text);
    }

    /** Single live push path to the attached WS event sink; no-op before the handler attaches. */
    private void push(SessionEvent event) {
        Consumer<SessionEvent> sink = eventSink;
        if (sink != null) {
            sink.accept(event);
        }
    }

    /**
     * Appends already-applied virtual-student transitions (attention deltas and behaviour state
     * were applied by {@link VirtualStudentDynamics}; re-feeding them through the command-side
     * dynamics would double-apply the deltas).
     */
    private List<SessionEvent> appendStudentEvents(
            List<ClassroomEvent> studentEvents, Instant now) {
        List<SessionEvent> appended = new ArrayList<>(studentEvents.size());
        for (ClassroomEvent event : studentEvents) {
            appended.add(append(event.type(), event.payload(), now));
        }
        return appended;
    }

    private List<SessionEvent> appendClassroomEvents(
            List<ClassroomEvent> classroomEvents, Instant now) {
        List<SessionEvent> expanded = new ArrayList<>(classroomEvents.size());
        for (ClassroomEvent classroomEvent : classroomEvents) {
            SessionEvent event = append(classroomEvent.type(), classroomEvent.payload(), now);
            expanded.add(event);
            applyClassroomDynamics(classroomEvent);
            if (INTERVIEW_QUESTION_SHOWN.equals(classroomEvent.type())) {
                persistExaminerText(classroomEvent, now);
            }
        }
        return expanded;
    }

    private void applyClassroomDynamics(ClassroomEvent event) {
        switch (event.type()) {
            case QUESTION_ASKED -> students.applyQuestionAsked();
            case STATE_CHANGED -> {
                Object from = event.payload().get("from");
                Object to = event.payload().get("to");
                if (ClassroomMacroState.QA.wireValue().equals(to)
                        && !ClassroomMacroState.QA.wireValue().equals(from)) {
                    students.applyQaStarted();
                }
            }
            case HAND_WINDOW_TIMEOUT_EVENT -> students.applyHandWindowTimeout();
            default -> {
                // no attention/energy delta for the remaining classroom events
            }
        }
    }

    /**
     * Emits {@code student.hand_lowered} for every open hand and clears the open-hand map.
     * Every caller must have completed its domain transition first, so an illegal command
     * leaves both the scheduled timer and the open hands untouched.
     */
    private List<SessionEvent> emitHandLowers(String reason, Instant now) {
        List<SessionEvent> events = new ArrayList<>(openHandRaises.size());
        for (Map.Entry<UUID, UUID> entry : openHandRaises.entrySet()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("student_id", entry.getValue());
            payload.put("hand_raise_id", entry.getKey());
            payload.put("reason", reason);
            events.add(append(STUDENT_HAND_LOWERED, payload, now));
            events.addAll(appendStudentEvents(
                    students.applyHandLowered(entry.getValue(), reason), now));
        }
        openHandRaises.clear();
        return events;
    }

    private void persistExaminerText(ClassroomEvent event, Instant now) {
        Object text = event.payload().get("text");
        if (!(text instanceof String value) || value.isBlank()) {
            return;
        }
        Object questionId = event.payload().get("question_id");
        UUID speakerRef = questionId instanceof UUID id ? id : null;
        persistText(new SessionText(
                ids.nextId(), session.id(), "examiner", speakerRef, "manual", "question",
                value, null, null, atMs(now), null, null, null, "final"));
    }

    private List<SessionEvent> emitStudentQuestion(VirtualStudentRuntime student, Instant now) {
        ContentResponse response = content.questionOrFallback(contentRequest(student));
        UUID textId = ids.nextId();
        persistText(new SessionText(
                textId, session.id(), "student", student.id(), "llm", "question",
                response.text(), null, null, atMs(now),
                BigDecimal.valueOf(response.confidence()),
                response.modelVersion(), response.promptVersion(), "final"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("student_id", student.id());
        payload.put("text_id", textId);
        payload.put("question_id", ids.nextId());
        SessionEvent event = append(STUDENT_QUESTION, payload, now);
        students.applyStudentQuestion(student.id());
        return List.of(event);
    }

    private List<SessionEvent> emitStudentAnswer(VirtualStudentRuntime student, Instant now) {
        ContentResponse response = content.answerOrFallback(contentRequest(student));
        AnswerQuality quality = answerQualityPolicy.evaluate(
                student.config().ability().doubleValue(), 0.5, random);
        UUID textId = ids.nextId();
        UUID answerId = ids.nextId();
        persistText(new SessionText(
                textId, session.id(), "student", student.id(), "llm", "answer",
                response.text(), null, null, atMs(now),
                BigDecimal.valueOf(response.confidence()),
                response.modelVersion(), response.promptVersion(), "final"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("student_id", student.id());
        payload.put("answer_id", answerId);
        payload.put("text_id", textId);
        payload.put("quality", quality.score());
        payload.put("correctness", quality.correctness().wireValue());
        SessionEvent event = append(STUDENT_ANSWER, payload, now);
        answerStudents.put(answerId, student.id());

        // Behaviour order mirrors the protocol: fact, answer_started, then either the legacy
        // answer-finished path or the first follow-up round when the student decides to keep
        // the dialogue going (05A §5.1).
        List<SessionEvent> events = new ArrayList<>();
        events.add(event);
        events.addAll(appendStudentEvents(students.applyAnswer(student.id()), now));
        boolean followUp = random.nextDouble() < DialogueState.followUpProbability(
                student.config().questionRate().doubleValue());
        if (followUp) {
            events.addAll(maybeStartDialogue(student, answerId, now));
        } else {
            events.addAll(appendClassroomEvents(
                    classroom.answerFinished(student.id(), false, now), now));
            events.addAll(appendStudentEvents(
                    students.applyAnswerFinished(student.id()), now));
        }
        return events;
    }

    /**
     * Starts a dialogue after the initial answer (05A §5.1). Event order is fixed:
     * {@code classroom.state_changed} (follow-up), the student's {@code answering -> dialogue}
     * transition, {@code dialogue.started}, then the first {@code student.follow_up}. The
     * initial round does not apply a second attention delta, because
     * {@link VirtualStudentDynamics#applyFollowUp} already did so above.
     *
     * @return every event appended by this method, in append order, for the command ack
     */
    private List<SessionEvent> maybeStartDialogue(
            VirtualStudentRuntime student, UUID answerId, Instant now) {
        List<SessionEvent> events = new ArrayList<>();
        events.addAll(appendClassroomEvents(
                classroom.answerFinished(student.id(), true, now), now));
        events.addAll(appendStudentEvents(students.applyFollowUp(student.id()), now));

        dialogue = new DialogueState(ids.nextId(), student.id());
        dialogueAnswerId = answerId;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dialogue_id", dialogue.dialogueId());
        payload.put("student_id", student.id());
        events.add(append(DIALOGUE_STARTED, payload, now));

        events.addAll(emitFollowUp(student, now, false));
        return events;
    }

    /**
     * Generates and persists one follow-up question as a {@code session_texts} row
     * ({@code kind=follow_up}) and appends {@code student.follow_up}. Subsequent rounds
     * ({@code applyAttention=true}) add the +0.05 attention delta without another behaviour
     * transition, because the student is already in {@code dialogue}.
     */
    private List<SessionEvent> emitFollowUp(
            VirtualStudentRuntime student, Instant now, boolean applyAttention) {
        ContentResponse response = content.questionOrFallback(contentRequest(student));
        UUID textId = ids.nextId();
        persistText(new SessionText(
                textId, session.id(), "student", student.id(), "llm", "follow_up",
                response.text(), null, null, atMs(now),
                BigDecimal.valueOf(response.confidence()),
                response.modelVersion(), response.promptVersion(), "final"));
        if (applyAttention) {
            students.applyFollowUp(student.id());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("student_id", student.id());
        payload.put("answer_id", dialogueAnswerId);
        payload.put("text_id", textId);
        SessionEvent event = append(STUDENT_FOLLOW_UP, payload, now);
        dialogueAwaitingSinceMs = atMs(now);
        return List.of(event);
    }

    /**
     * Closes the active dialogue: {@code dialogue.ended} then the student's
     * {@code dialogue -> focused} transition. The classroom state machine is intentionally left
     * untouched; the caller appends its own terminal transition ({@code end_dialogue},
     * {@code qa_ended}, restart, session end) so no intermediate {@code waiting_hands} event is
     * emitted. Returns an empty list when no dialogue is active.
     */
    private List<SessionEvent> endActiveDialogue(String reason, Instant now) {
        if (dialogue == null) {
            return List.of();
        }
        DialogueState active = dialogue;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dialogue_id", active.dialogueId());
        payload.put("student_id", active.studentId());
        payload.put("reason", reason);

        List<SessionEvent> events = new ArrayList<>();
        events.add(append(DIALOGUE_ENDED, payload, now));
        events.addAll(appendStudentEvents(
                students.applyDialogueEnded(active.studentId()), now));

        dialogue = null;
        dialogueAnswerId = null;
        dialogueAwaitingSinceMs = 0L;
        return events;
    }

    private ContentRequest contentRequest(VirtualStudentRuntime student) {
        return new ContentRequest(
                taskSnapshot.taskId().toString(),
                session.id().value().toString(),
                session.mode().wireValue(),
                taskSnapshot.topic(),
                recentTeacherTexts(),
                null,
                student.config().name(),
                student.config().personalityText(),
                student.config().ability().doubleValue(),
                null);
    }

    private List<String> recentTeacherTexts() {
        List<SessionText> teacherTexts = context.texts("teacher");
        int from = Math.max(0, teacherTexts.size() - 20);
        return teacherTexts.subList(from, teacherTexts.size()).stream()
                .map(SessionText::text)
                .toList();
    }

    // ------------------------------------------------------------------
    // Snapshot views
    // ------------------------------------------------------------------

    private Map<String, Object> virtualStudentView(VirtualStudentRuntime student) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("virtual_student_id", student.id());
        view.put("name", student.config().name());
        view.put("avatar", student.config().avatar());
        view.put("seat_no", student.config().seatNo());
        view.put("sort_order", student.config().sortOrder());
        view.put("state", student.behavior().wireValue());
        view.put("attention", student.attention());
        view.put("hand_raise_energy", student.handRaiseEnergy());
        return view;
    }

    private Map<String, Object> interviewView(Instant now) {
        if (!isInterviewMode()) {
            return Map.of();
        }
        InterviewSnapshot state = interview.snapshot(now);
        Map<String, Object> view = new LinkedHashMap<>();
        if (state.current() == null) {
            view.put("current", null);
        } else {
            Map<String, Object> current = new LinkedHashMap<>();
            current.put("question_id", state.current().questionId());
            current.put("order", state.current().order());
            current.put("total", state.current().total());
            current.put("text", state.current().text());
            view.put("current", current);
        }
        view.put("answer_id", state.answerId());
        view.put("remaining_ms", state.remainingMs());
        view.put("ended", state.ended());
        return view;
    }

    private Map<String, Object> recordingView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("required_streams",
                requiredStreams().stream().map(StreamType::wireValue).toList());
        Map<String, Object> streams = new LinkedHashMap<>();
        streamStatuses.forEach((type, status) -> streams.put(type.wireValue(), status));
        view.put("streams", streams);
        view.put("ready", allRequiredStreamsReady());
        return view;
    }

    /** {@code session.time_tick.states}: current behaviour and attention per virtual student. */
    private List<Map<String, Object>> studentStates() {
        List<VirtualStudentRuntime> current = students.students();
        List<Map<String, Object>> states = new ArrayList<>(current.size());
        for (VirtualStudentRuntime student : current) {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("virtual_student_id", student.id());
            state.put("state", student.behavior().wireValue());
            state.put("attention", student.attention());
            state.put("hand_raise_energy", student.handRaiseEnergy());
            states.add(state);
        }
        return states;
    }

    /**
     * Remaining time for {@code session.time_tick}. Interviews count down the total duration;
     * free/scenario sessions count down {@code duration.max_seconds} (05C §6.3).
     */
    private long remainingMs(Instant now) {
        if (isInterviewMode()) {
            return interview.snapshot(now).remainingMs();
        }
        if (taskSnapshot.duration() == null) {
            return 0L;
        }
        long totalMs = taskSnapshot.duration().maxSeconds() * 1_000L;
        return Math.max(0L, totalMs - atMs(now));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void initializeStudentsIfNeeded() {
        if (!students.students().isEmpty()
                || taskSnapshot.virtualStudents() == null
                || taskSnapshot.virtualStudents().isEmpty()) {
            return;
        }
        students.initialize(taskSnapshot.virtualStudents().stream()
                .map(SessionActor::toConfig)
                .toList());
    }

    private static VirtualStudentConfig toConfig(TaskSnapshot.VirtualStudentSnapshot student) {
        return new VirtualStudentConfig(
                student.virtualStudentId(),
                student.name(),
                student.avatar(),
                student.personalityText(),
                student.activity(),
                student.questionRate(),
                student.ability(),
                student.attentionBaseline(),
                student.disciplineProbability(),
                student.seatNo(),
                student.sortOrder());
    }

    private VirtualStudentRuntime requireStudent(UUID studentId) {
        try {
            return students.find(studentId);
        } catch (DomainException exception) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "虚拟学生不存在",
                    Map.of("student_id", studentId.toString()));
        }
    }

    private boolean isInterviewMode() {
        return session.mode() == TaskMode.INTERVIEW;
    }

    private List<StreamType> requiredStreams() {
        return isInterviewMode()
                ? List.of(StreamType.CAMERA, StreamType.AUDIO)
                : List.of(StreamType.CAMERA, StreamType.SCREEN, StreamType.AUDIO);
    }

    private boolean isStreamReady(StreamType type) {
        return "ready".equalsIgnoreCase(streamStatuses.get(type));
    }

    private boolean allRequiredStreamsReady() {
        return requiredStreams().stream().allMatch(this::isStreamReady);
    }

    private void requireAllStreamsReady() {
        List<StreamType> missing = requiredStreams().stream()
                .filter(type -> !isStreamReady(type))
                .toList();
        if (!missing.isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("action", "session.start");
            details.put("missing_streams",
                    missing.stream().map(StreamType::wireValue).toList());
            throw new DomainException(ErrorCode.SESSION_STATE_INVALID,
                    "录制流未全部就绪", details);
        }
    }

    private void requireStatus(String action, SessionStatus expected) {
        if (session.status() != expected) {
            throw stateInvalid(action, "当前状态不允许该操作");
        }
    }

    private void requireAnyStatus(String action, SessionStatus... allowed) {
        for (SessionStatus status : allowed) {
            if (session.status() == status) {
                return;
            }
        }
        throw stateInvalid(action, "当前状态不允许该操作");
    }

    private void requireInProgress(String action) {
        requireStatus(action, SessionStatus.IN_PROGRESS);
    }

    private void requireClassroomMode(String action) {
        if (isInterviewMode()) {
            throw stateInvalid(action, "结构化面试不支持课堂命令");
        }
    }

    private void requireInterviewMode(String action) {
        if (!isInterviewMode()) {
            throw stateInvalid(action, "仅结构化面试支持该命令");
        }
    }

    private DomainException stateInvalid(String action, String message) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("action", action);
        details.put("status", session.status().wireValue());
        details.put("mode", session.mode().wireValue());
        return new DomainException(ErrorCode.SESSION_STATE_INVALID, message, details);
    }

    private long atMs(Instant now) {
        Instant startedAt = session.startedAt();
        if (startedAt == null) {
            return 0L;
        }
        // Terminal/reviewing connections must not observe an ever-growing session clock.
        Instant endedAt = session.endedAt();
        Instant effectiveNow = endedAt != null && now.isAfter(endedAt) ? endedAt : now;
        return Math.max(0L, Duration.between(startedAt, effectiveNow).toMillis());
    }

    private Map<String, Object> sessionEndedPayload(String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", reason);
        payload.put("duration_ms", session.durationMs() == null ? 0L : session.durationMs());
        return payload;
    }

    private void notifyStatusChanged() {
        SessionStatus status = session.status();
        statusListener.accept(status);
        for (Consumer<SessionStatus> listener : statusListeners) {
            listener.accept(status);
        }
    }

    private void cancelAllTasks() {
        cancel(handRaiseTask);
        handRaiseTask = null;
        cancel(handWindowTask);
        handWindowTask = null;
        cancelGrace();
    }

    private void cancelGrace() {
        cancel(graceTask);
        graceTask = null;
    }

    private static void cancel(SchedulerPort.Cancellable task) {
        if (task != null) {
            task.cancel();
        }
    }
}
