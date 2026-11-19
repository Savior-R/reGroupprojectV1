# M3B Classroom State Machine + WebSocket Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the M3B backend slice: task session lifecycle, classroom/interview state machines, virtual-student engine, control WebSocket with ticket/snapshot/seq/reconnect, event/text persistence, and session REST APIs.

**Architecture:** Continue the M3A hexagonal layout under `com.shijiangtai.m3classroom`. Pure state machines live in `domain/{session,classroom,interview}`; `SessionActor` and application services coordinate ports in `application`; JPA/WebSocket/ticket adapters live in `infrastructure`; controllers stay thin. One `SessionActor` per active session serializes commands and owns the in-memory `SessionContext`; the database is the durable event log.

**Tech Stack:** Java 21, Spring Boot 4.1.2, Spring WebSocket, Spring Data Redis (ticket store, optional), PostgreSQL 18/JPA/Hibernate 7.4, Jackson 3, JUnit 5, AssertJ, MockMvc, ArchUnit 1.5.1, Docker Compose test service.

**Spec:** `docs/design/05A-M3-课堂状态机与WebSocket.md`, `docs/implementation/04-M3-实现设计.md` §1–§4.4/§4.8/§5–§7, `docs/design/02-API与实时协议.md` §2.3/§4.3/§6/§8, `docs/design/12-数据库设计.md` §5.1, `docs/design/10-测试与验收.md` §6.5 (M3-T10–T14, T18; T15/T16/T19 are covered here where they do not need recording).

## Global Constraints

- Java 21; Spring Boot 4.1.2; Gradle 8.14.3; PostgreSQL 18; no host JDK/Gradle. Run builds/tests through Compose.
- Domain (`m3classroom/domain/**`, `shared/domain/**`) must not import Spring, Jackson, JPA, HTTP, `api`, `application`, or `infrastructure`.
- Application must not import `api` or `infrastructure`; API must not import `infrastructure`.
- JSON is `snake_case` (Jackson global strategy); `at_ms` is session-relative milliseconds, `Instant` is UTC.
- Every event has `event_id`, `seq` (monotonic per session, starts at 1), `session_id`, `at_ms`, `type`, `payload`.
- `command_id` is idempotent per session: repeat returns `server.ack` without re-executing.
- Illegal commands never mutate state; use `DomainException(ErrorCode.SESSION_STATE_INVALID, ...)`.
- All business errors use `DomainException`; controllers do not catch exceptions.
- Student-facing snapshots/events never expose rubric weights, reference material, answer points, virtual-student parameters, or personality text.
- **P0 simplifications (documented, not silent):** perception ASR/OCR/screen is out of scope; events/texts are persisted synchronously per command (no async batch writer); AI content calls use a dedicated bounded executor with hard timeouts and template fallback (no actor offload); automatic multi-turn dialogue (`student.follow_up`, `dialogue.*`, 3-round auto-follow-up) is deferred — after `student.answer` the actor calls `answerFinished(..., false, ...)` and returns to `waiting_hands`, and `teacher.end_dialogue` from `student_answering` finishes the current answer; retention/cleanup jobs and M6 training are out of scope. `forceFlush()` is a transaction/entity-manager flush.
- **Cross-module files (pre-approved by the user 2026-11-04 for this plan):** `server/build.gradle.kts` (Foundation), `server/src/main/java/com/shijiangtai/shared/domain/{SessionId,ErrorCode}.java` (Foundation), `server/src/main/resources/application-test.yml` / `application-docker.yml` (Foundation config fragments). M3C is covered by a separate plan with explicit Media/Training Owner authorization.
- Commit after every task with prefix `[M3B]`; never `git add -A` or `git commit -a`; stage only the task's files.
- Test command pattern: `docker compose --profile test run --rm test gradle test --tests '*<Class>' --no-daemon --console=plain`. Full suite: `docker compose --profile test run --rm test gradle clean test --no-daemon --console=plain`.

## Review Focus

1. **Reconnect/grace:** control WS disconnect keeps `in_progress` for 30 s; `session.resume` with `recording_state=active` replays only events after `last_seq` (no `time_tick`/`ping`); `lost`/timeout → `session.failed`. Tests in Task 11.
2. **Command idempotency + sequencing:** duplicate `command_id` and concurrent commands produce one effect and a strictly increasing `seq`. Tests in Task 7/11.
3. **Hand-raise invariants:** 1–3 students, at least one even when all are cooling down; 45 s cooldown; 30 s window timeout returns to lecturing. Tests in Task 5.
4. **Privileged data leakage:** `session.snapshot` and student session views contain no weights/material/answer points/parameters/personality. Tests in Task 10/8.
5. **Illegal-state safety:** every command from a wrong status/mode returns `SESSION_STATE_INVALID` and leaves the snapshot unchanged. Tests in Task 2/3/7.

---

### Task 1: Shared session type, error codes, and WebSocket/Redis dependencies

**Files:**
- Modify: `server/build.gradle.kts`
- Modify: `server/src/main/resources/application-test.yml` (`m3.ticket-store: memory`)
- Modify: `server/src/main/resources/application-docker.yml` (`m3.ticket-store: redis`)
- Create: `server/src/main/java/com/shijiangtai/shared/domain/SessionId.java`
- Modify: `server/src/main/java/com/shijiangtai/shared/domain/ErrorCode.java`
- Create: `server/src/test/java/com/shijiangtai/shared/domain/SessionIdTest.java`

**Interfaces:**
- Produces:
  - `record SessionId(UUID value)` with `static SessionId of(UUID)` and null rejection.
  - New `ErrorCode` values: `SESSION_ACTIVE_EXISTS(409)`, `SESSION_STATE_INVALID(409)`, `SESSION_FAILED(409)`, `RECORDING_INCOMPLETE(422)`, `WS_TICKET_INVALID(401)`, `WS_SESSION_FORBIDDEN(403)`, `INTERVIEW_NO_MORE_QUESTIONS(422)`.
  - Dependencies: `org.springframework.boot:spring-boot-starter-websocket`, `org.springframework.boot:spring-boot-starter-data-redis`.

- [ ] **Step 1: Write the failing test**

```java
@Test void rejectsNull() {
    assertThatThrownBy(() -> new SessionId(null)).isInstanceOf(NullPointerException.class);
}
@Test void equalityUsesUuid() {
    UUID id = UUID.randomUUID();
    assertThat(SessionId.of(id)).isEqualTo(new SessionId(id));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `docker compose --profile test run --rm test gradle test --tests '*SessionIdTest' --no-daemon --console=plain`
Expected: compilation failure because `SessionId` does not exist.

- [ ] **Step 3: Implement `SessionId` and add the error codes/dependencies**

`SessionId` mirrors the existing ID records in `shared/domain`. Add the seven enum constants at the end of `ErrorCode` before `INTERNAL_ERROR`.

- [ ] **Step 4: Run test to verify it passes**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/build.gradle.kts server/src/main/java/com/shijiangtai/shared/domain/SessionId.java \
  server/src/main/java/com/shijiangtai/shared/domain/ErrorCode.java \
  server/src/test/java/com/shijiangtai/shared/domain/SessionIdTest.java
git commit -m "[M3B] 会话 ID、错误码与 WebSocket/Redis 依赖"
```

---

### Task 2: Session aggregate and lifecycle

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/domain/session/{SessionType,SessionStatus,RecordingState,FailureReason,Session}.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/domain/recording/StreamType.java` (M3C reuses this enum; do not create a second one)
- Create: `server/src/test/java/com/shijiangtai/m3classroom/domain/session/SessionTest.java`

**Interfaces:**
- Produces:
  - `enum SessionType { TASK("task") }`, `String wireValue()`, `static fromWire(String)`.
  - `enum SessionStatus { PREPARING, IN_PROGRESS, ENDED, REVIEWING, SUBMITTED, CANCELLED, FAILED }` with wire values.
  - `enum StreamType { CAMERA("camera"), SCREEN("screen"), AUDIO("audio") }` with `wireValue()`/`fromWire`.
  - `enum RecordingState { ACTIVE, LOST }`, `enum FailureReason { GRACE_TIMEOUT, RECORDING_LOST, TRACK_ENDED, WS_ERROR, INTERNAL }` with wire values.
  - `final class Session` with:
    - `static Session createPreparing(SessionId id, SessionType type, TaskId taskId, CourseId courseId, AccountId student, TaskMode mode, int attemptNo, Instant now)`
    - `static Session rehydrate(...)` preserving all persisted fields
    - `start(Instant now)`, `end(Instant now)`, `markReviewing(Instant now)`, `submit(Instant now)`, `cancel(Instant now)`, `fail(FailureReason reason, Instant now)`
    - `SessionId id()`, `SessionType type()`, `TaskId taskId()`, `CourseId courseId()`, `AccountId studentAccountId()`, `TaskMode mode()`, `SessionStatus status()`, `int attemptNo()`, `Instant startedAt()`, `Instant endedAt()`, `Long durationMs()`, `Map<StreamType, Long> streamOffsets()`, `Instant createdAt()`, `Instant updatedAt()`
    - `void putStreamOffset(StreamType type, long offsetMs)`

- [ ] **Step 1: Write the failing lifecycle tests**

```java
@Test void createPreparingDefaults() { /* status PREPARING, attemptNo preserved, startedAt null */ }
@Test void startMovesPreparingToInProgress() { /* startedAt set, status IN_PROGRESS */ }
@Test void startTwiceFails() { /* SESSION_STATE_INVALID */ }
@Test void endComputesDuration() { /* endedAt - startedAt */ }
@Test void endBeforeStartFails() { /* SESSION_STATE_INVALID */ }
@Test void submitAllowsEndedOrReviewingOnly() { /* SUBMITTED */ }
@Test void cancelAllowsPreparingAndInProgress() { /* CANCELLED */ }
@Test void terminalStatusCannotFail() { /* SESSION_STATE_INVALID or SESSION_FAILED, chosen once */ }
@Test void rehydratePreservesAllFields() { /* equality of every accessor */ }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `docker compose --profile test run --rm test gradle test --tests '*SessionTest' --no-daemon --console=plain`
Expected: compilation failure.

- [ ] **Step 3: Implement the aggregate**

Transitions: `PREPARING → IN_PROGRESS → ENDED → REVIEWING → SUBMITTED`; `CANCELLED` from preparing/in_progress; `FAILED` from any non-terminal. `end` sets `durationMs = Duration.between(startedAt, now).toMillis()` and `streamOffsets` are copied defensively. Use `DomainException` with `SESSION_STATE_INVALID`. Choose and document the terminal `fail` behavior in a Javadoc line; test it.

- [ ] **Step 4: Run tests to verify they pass**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/domain/session \
  server/src/test/java/com/shijiangtai/m3classroom/domain/session/SessionTest.java
git commit -m "[M3B] Session 聚合与生命周期"
```

---

### Task 3: Classroom macro-state machine

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/domain/classroom/{ClassroomMacroState,QaPhase,ClassroomEvent,ClassroomStateMachine}.java`
- Create: `server/src/test/java/com/shijiangtai/m3classroom/domain/classroom/ClassroomStateMachineTest.java`

**Interfaces:**
- Produces:
  - `enum ClassroomMacroState { LECTURING, QA, ENDED }`, `enum QaPhase { WAITING_HANDS, STUDENT_ANSWERING, DIALOGUE, TEACHER_ANSWERING }`.
  - `record ClassroomEvent(String type, Map<String,Object> payload)`.
  - `final class ClassroomStateMachine`:
    - `void start()`
    - `List<ClassroomEvent> askClass(Instant now)` — intent `answer`, source `manual`
    - `List<ClassroomEvent> askStudentQuestion(Instant now)` — intent `student_question`, source `manual`
    - `List<ClassroomEvent> onTeacherUtterance(double confidence, Instant now)` — auto-question at `confidence >= 0.8` and cooldown elapsed ≥10 s; otherwise `classroom.question_suggested`
    - `List<ClassroomEvent> callOn(UUID studentId, Instant now)`
    - `List<ClassroomEvent> answerFinished(UUID studentId, boolean followUp, Instant now)`
    - `List<ClassroomEvent> endDialogue(Instant now)`, `List<ClassroomEvent> endQa(String reason, Instant now)`
    - `List<ClassroomEvent> handWindowTimeout(Instant now, List<UUID> handRaiseIds)`, `List<ClassroomEvent> endSession(Instant now)` — the ids are required by 05A §5.4; do not emit an empty `hand_raise_ids` in production.
    - `ClassroomMacroState macroState()`, `QaPhase qaPhase()`, `long handWindowStartedAtMs()`, `String intent()`

- [ ] **Step 1: Write the failing tests**

```java
@Test void askClassEntersWaitingHands() { /* state QA/WAITING_HANDS, question_asked{intent=answer,source=manual} */ }
@Test void askStudentQuestionUsesStudentQuestionIntent() { /* intent=student_question */ }
@Test void manualAskIsAllowedFromEveryQaPhaseAndResetsWaitingHands() { /* re-ask from each phase -> question_asked + state_changed to qa/waiting_hands */ }
@Test void callOnInLecturingIsRejected() { /* SESSION_STATE_INVALID, state unchanged */ }
@Test void callOnMovesToStudentAnsweringOrTeacherAnswering() { /* by intent */ }
@Test void answerFinishedMovesToDialogueWhenFollowUp() { /* dialogue state */ }
@Test void answerFinishedReturnsToWaitingHandsWithoutFollowUp() { /* waiting_hands */ }
@Test void endDialogueReturnsToWaitingHands() { /* ... */ }
@Test void endQaReturnsToLecturing() { /* qa_ended */ }
@Test void handWindowTimeoutReturnsToLecturing() { /* hand_window_timeout with the supplied hand_raise_ids */ }
@Test void handWindowTimeoutKeepsIdsImmutable() { /* defensive copy of hand_raise_ids */ }
@Test void voiceHighConfidenceTriggersAndLowConfidenceSuggests() { /* 0.8 threshold, 10s cooldown */ }
@Test void endSessionMovesToEnded() { /* ... */ }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `docker compose --profile test run --rm test gradle test --tests '*ClassroomStateMachineTest' --no-daemon --console=plain`
Expected: compilation failure.

- [ ] **Step 3: Implement the state machine**

Emit exactly the event types in `02` §6.4/05A §4.4: `classroom.state_changed` (`{from,to,qa_phase,trigger}`), `classroom.question_asked`, `classroom.question_suggested`, `classroom.qa_ended`, `classroom.hand_window_timeout`. No auto-timeout of QA/dialogue. Keep the 10 s voice cooldown in a field.

- [ ] **Step 4: Run tests to verify they pass**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/domain/classroom \
  server/src/test/java/com/shijiangtai/m3classroom/domain/classroom/ClassroomStateMachineTest.java
git commit -m "[M3B] 课堂宏观状态机"
```

---

### Task 4: Virtual-student attention, behavior, and discipline aggregation

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/domain/classroom/{VirtualStudentBehavior,VirtualStudentRuntime,DisciplineLevel,DisciplineSnapshot,VirtualStudentDynamics,DisciplineAggregator,Correctness}.java`
- Create: `server/src/test/java/com/shijiangtai/m3classroom/domain/classroom/{VirtualStudentDynamicsTest,DisciplineAggregatorTest}.java`

**Interfaces:**
- Produces:
  - `enum VirtualStudentBehavior { FOCUSED, DISTRACTED, WHISPERING, HAND_RAISED, CALLED_ON, ANSWERING, DIALOGUE }`.
  - `enum Correctness { CORRECT, PARTIAL, WRONG }` with wire values `correct/partial/wrong` (Task 5 reuses it).
  - `final class VirtualStudentRuntime` initialized from `VirtualStudentConfig`: mutable `attention`, `handRaiseEnergy`, `cooldownUntil`, `behavior`, `whisperContagionBonus`, getters; `tick(long elapsedSeconds, RandomGenerator random)`; `applyAttentionDelta(double delta)`; `void markHandRaised(Instant now, double energyDelta)`; `boolean isCoolingDown(Instant now)`; `double whisperContagionBonus()`; contagion decay happens inside `tick`, not via a separate public method.
  - `final class VirtualStudentDynamics`:
    - `void initialize(List<VirtualStudentConfig>)`
    - `List<ClassroomEvent> tick(long elapsedSeconds, Instant now, RandomGenerator random)`
    - `void applyQuestionAsked()`, `void applyCalledOn(UUID studentId)`, `void applyMarked(UUID studentId, Correctness correctness)`, `List<ClassroomEvent> applyManaged(UUID studentId)` (reminder returns the student to `FOCUSED` and emits `student.state_changed` when it changed), `void applyHandRaised(UUID studentId, Instant now, RandomGenerator random)` (computes the noisy `-0.20` energy delta and applies the 45 s cooldown through the runtime), `void applyStudentQuestion(UUID studentId)`, `void applyAnswer(UUID studentId)`, `void applyFollowUp(UUID studentId)`, `void applyStateChanged(UUID studentId, VirtualStudentBehavior behavior)`, `void applyDisciplineEvent(UUID studentId)`, `void applyQaStarted()`, `void applyHandWindowTimeout()`
    - `List<VirtualStudentRuntime> students()`, `VirtualStudentRuntime find(UUID id)`
    - `List<ClassroomEvent> disciplineLevelChangedIfNeeded(Instant now)` (record-only)
  - Discipline escalation state: track the raw aggregate level and the time the **raw** level last changed; a raw level 2 lasting >20 s yields effective level 3 without resetting the raw-duration clock (so it does not flap back to 2).
  - Neighbour whisper contagion: when a student enters `WHISPERING`, each seat-adjacent neighbour (`seatNo ± 1`) gains `+0.02` `whisperContagionBonus` (clamped `[0, 0.2]`), decays by `0.005` per tick, and is added to `p_discipline`. This freezes the underspecified "小幅上升" for P0.
  - `record DisciplineSnapshot(int level, int distractedCount, int whisperingCount, double noisyRatio, double avgAttention)`.
  - `final class DisciplineAggregator { DisciplineSnapshot aggregate(List<VirtualStudentRuntime>, long levelDurationMs); }`

- [ ] **Step 1: Write the failing dynamics tests**

```java
@Test void attentionDecaysByBaselineFormula() { /* 0.002 + 0.004*(1-baseline) per tick */ }
@Test void attentionClampsToFloorAndCeiling() { /* [0.05, 1] */ }
@Test void handRaiseEnergyRecoversAndClamps() { /* +0.005 per tick */ }
@Test void lowAttentionCanTriggerDistractedOrWhispering() { /* seeded random, behavior changes */ }
@Test void manageStudentRaisesAttentionBy025() { /* +0.25, clamped */ }
@Test void eventMatrixAppliesExpectedDeltas() { /* question asked, called on, marked, hand raised, ... */ }
@Test void tickEmitsStudentStateChangedOnlyOnTransition() { /* ... */ }
```

- [ ] **Step 2: Write the failing discipline tests**

```java
@Test void levelZeroWhenQuiet() { /* 0 */ }
@Test void levelUsesMaxOfCountAndRatio() { /* 1..3 table-driven */ }
@Test void levelTwoPersistingOver20sBecomesLevelThreeWithoutFlapping() { /* +21s and +22s; no 3->2 event */ }
@Test void repeatedAggregationAtSameInstantIsIdempotent() { /* second call emits no event */ }
@Test void neighbourWhisperContagionRaisesDisciplineProbability() { /* seatNo ±1 bonus + decay */ }
@Test void reminderReturnsDistractedOrWhisperingStudentToFocused() { /* applyManaged emits state_changed */ }
@Test void disciplineEventsAreRecordOnly() { /* aggregator does not mutate students */ }
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `docker compose --profile test run --rm test gradle test --tests '*VirtualStudentDynamicsTest' --tests '*DisciplineAggregatorTest' --no-daemon --console=plain`
Expected: compilation failure.

- [ ] **Step 4: Implement dynamics and aggregation**

Use the exact formulas/values in 05A §5.1/§5.3. Inject `RandomGenerator`; never use `Math.random()`. Apply per-event noise ±20% with a minimum ±0.005. Emit `student.state_changed` and `classroom.discipline_level_changed` payloads per 02 §6.4. The aggregator is pure and does not feed back into probabilities.

- [ ] **Step 5: Run tests to verify they pass**

Run the Step 3 command. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/domain/classroom \
  server/src/test/java/com/shijiangtai/m3classroom/domain/classroom
git commit -m "[M3B] 虚拟学生注意力、行为与纪律聚合"
```

---

### Task 5: Hand-raise, answer quality, and dialogue policies

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/domain/classroom/{HandRaisePolicy,AnswerQualityPolicy,DialogueState}.java`
- Reuse: `server/src/main/java/com/shijiangtai/m3classroom/domain/classroom/Correctness.java` (created by Task 4; do not create a second one)
- Create: `server/src/test/java/com/shijiangtai/m3classroom/domain/classroom/{HandRaisePolicyTest,AnswerQualityPolicyTest,DialogueStateTest}.java`

**Interfaces:**
- Produces:
  - `record HandRaise(UUID handRaiseId, UUID studentId, String questionPreview)`.
  - `final class HandRaisePolicy { List<HandRaise> select(List<VirtualStudentRuntime> candidates, Instant now, RandomGenerator random); }` — selection only; the caller emits `student.hand_raised` then calls `VirtualStudentDynamics.applyHandRaised(studentId, now, random)` exactly once (energy/cooldown/noise live there).
  - `record AnswerQuality(Correctness correctness, double score)` (reuses the Task 4 `Correctness` enum).
  - `final class AnswerQualityPolicy { AnswerQuality evaluate(double ability, double questionClarity, RandomGenerator random); }`
  - `final class DialogueState` with `UUID dialogueId`, `UUID studentId`, `int round`, `boolean canFollowUp()`, `void nextRound()`; max 3 rounds.

- [ ] **Step 1: Write the failing hand-raise tests**

```java
@Test void selectsOneToThreeStudents() { /* size between 1 and 3 */ }
@Test void guaranteesAtLeastOneWhenAllBelowThreshold() { /* highest score */ }
@Test void ignoresCooldownIfAllCoolingDown() { /* forced highest, cooldown bypassed */ }
@Test void respectsFocusedOrDistractedAndNotInDialogue() { /* ... */ }
@Test void handRaiseEnergyAndCooldownLiveInDynamicsNotSelection() { /* policy selects only; dynamics applies noisy -0.2 and +45s once */ }
```

- [ ] **Step 2: Write the failing answer/dialogue tests**

```java
@Test void qualityBandsAt07And04() { /* correct / partial / wrong */ }
@Test void qualityIsClampedToZeroOne() { /* ... */ }
@Test void followUpProbabilityIsQuestionRateTimesHalf() { /* seeded */ }
@Test void dialogueStopsAtThreeRounds() { /* canFollowUp false after 3 */ }
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `docker compose --profile test run --rm test gradle test --tests '*HandRaisePolicyTest' --tests '*AnswerQualityPolicyTest' --tests '*DialogueStateTest' --no-daemon --console=plain`
Expected: compilation failure.

- [ ] **Step 4: Implement the policies**

Use 05A §5.4/§5.5 formulas exactly: score `activity*0.3 + question_rate*0.2 + attention*0.2 + hand_raise_energy*0.2 + random*0.1`; quality `clamp(ability*0.7 + clarity*0.3 + random(-0.15,0.15), 0, 1)`; follow-up probability `question_rate*0.5`; max 3 rounds.

- [ ] **Step 5: Run tests to verify they pass**

Run the Step 3 command. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/domain/classroom \
  server/src/test/java/com/shijiangtai/m3classroom/domain/classroom
git commit -m "[M3B] 举手、回答质量与多轮对话策略"
```

---

### Task 6: Interview state machine

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/domain/interview/{InterviewStateMachine,InterviewState,InterviewQuestionState}.java`
- Create: `server/src/test/java/com/shijiangtai/m3classroom/domain/interview/InterviewStateMachineTest.java`

**Interfaces:**
- Produces:
  - `record InterviewQuestionState(UUID questionId, int order, int total, String text)`.
  - `record InterviewSnapshot(InterviewQuestionState current, UUID answerId, long remainingMs, boolean ended)`.
  - `final class InterviewStateMachine`:
    - `void start(List<QuestionSnapshot> questions, Instant startedAt, int totalDurationSeconds)`
    - `List<ClassroomEvent> nextQuestion(Instant now)`
    - `List<ClassroomEvent> end(Instant now, String reason)`
    - `List<ClassroomEvent> onTimeout(Instant now)`
    - `InterviewSnapshot snapshot(Instant now)`

- [ ] **Step 1: Write the failing tests**

```java
@Test void startShowsFirstQuestionAndStartsAnswer() { /* question_shown + answer_started */ }
@Test void nextQuestionEndsPreviousThenShowsNext() { /* answer_ended, next_question, question_shown, answer_started */ }
@Test void nextOnLastQuestionFails() { /* INTERVIEW_NO_MORE_QUESTIONS */ }
@Test void endEmitsAnswerEndedAndInterviewEnded() { /* ... */ }
@Test void timeoutEmitsSameOrderWithReasonTimeout() { /* ... */ }
@Test void snapshotExposesCurrentQuestionAndRemainingTime() { /* ... */ }
@Test void emptyQuestionListFailsAtStart() { /* SESSION_STATE_INVALID */ }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `docker compose --profile test run --rm test gradle test --tests '*InterviewStateMachineTest' --no-daemon --console=plain`
Expected: compilation failure.

- [ ] **Step 3: Implement the interview state machine**

Event order per 05A §5.6 and `implementation/04` §4.4. Generate `answerId` via the injected `IdGenerator`? Keep the machine pure: accept `UUID answerId` per answer start or use `UUID.randomUUID()` (domain can use JDK). Prefer an injected `Supplier<UUID>` in the constructor for deterministic tests.

- [ ] **Step 4: Run tests to verify they pass**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/domain/interview \
  server/src/test/java/com/shijiangtai/m3classroom/domain/interview/InterviewStateMachineTest.java
git commit -m "[M3B] 结构化面试状态机"
```

---

### Task 7: Session context, commands, and SessionActor

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/domain/session/{SessionEvent,SessionCommand,CommandResult,SessionSnapshot,SessionContext,SessionText}.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/application/port/{ContentClient,SessionEventStore,SessionTextStore,SchedulerPort}.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/application/{ContentGenerationService,SessionActor}.java`
- Create: `server/src/test/java/com/shijiangtai/m3classroom/application/SessionActorTest.java`
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/time/UuidIdGenerator.java` (UUIDv7, per 05C §6.2)
- Create: `server/src/test/java/com/shijiangtai/m3classroom/infrastructure/time/UuidIdGeneratorTest.java`
- Test support: `server/src/test/java/com/shijiangtai/m3classroom/support/{FakeContentClient,FakeScheduler,InMemorySessionEventStore,InMemorySessionTextStore}.java`

**Interfaces:**
- Produces:
  - `record SessionEvent(UUID eventId, SessionId sessionId, long seq, String type, long atMs, Map<String,Object> payload, Instant createdAt)`.
  - `sealed interface SessionCommand permits ...` with the records listed in 05A §6.1; every record has `String commandId()`.
  - `record CommandResult(boolean ok, List<SessionEvent> events, ErrorCode errorCode, String message)`.
  - `record SessionSnapshot(SessionStatus status, String mode, Map<String,Object> publicConfig, List<Map<String,Object>> virtualStudents, Map<String,Object> interview, Map<String,Object> recording, long lastSeq, long elapsedMs)`.
  - `final class SessionContext { long nextSeq(); SessionEvent append(SessionEventDraft draft, long atMs); List<SessionEvent> after(long seq); long lastSeq(); }`.
  - `record ContentRequest(String taskId, String sessionId, String mode, String topic, List<String> recentTexts, String screenText, String studentName, String personality, double ability, String question)`.
  - `record ContentResponse(String text, double confidence, String modelVersion, String promptVersion)`.
  - `interface ContentClient { ContentResponse question(ContentRequest request); ContentResponse answer(ContentRequest request); }`.
  - `final class ContentGenerationService { ContentResponse questionOrFallback(...); ContentResponse answerOrFallback(...); }` with 2 s / 3 s timeouts and template fallback.
  - `record SessionText(UUID id, SessionId sessionId, String speakerType, UUID speakerRef, String source, String kind, String text, Long startMs, Long endMs, long atMs, BigDecimal confidence, String modelVersion, String promptVersion, String status)`.
  - `interface SessionEventStore { void append(SessionEvent event); List<SessionEvent> after(SessionId id, long seq, int limit); long maxSeq(SessionId id); }`.
  - `interface SessionTextStore { void save(SessionText text); List<SessionText> bySession(SessionId id, String speakerType); }`.
  - `interface SchedulerPort { Cancellable schedule(Runnable task, Duration delay); }`.
  - `final class SessionActor`:
    - `SessionActor(Session session, TaskSnapshot snapshot, SessionContext context, ClassroomStateMachine classroom, VirtualStudentDynamics students, InterviewStateMachine interview, ContentGenerationService content, SessionEventStore eventStore, SessionTextStore textStore, IdGenerator ids, ClockProvider clock, SchedulerPort scheduler, RandomGenerator random)`
    - `CommandResult handle(SessionCommand command)` — `synchronized`, serializes commands; records `command_id` in a bounded LRU (e.g., 200) and returns the previous ack on duplicates.
    - `void onTick(Instant now)`, `SessionSnapshot snapshot()`, `List<SessionEvent> replayAfter(long seq)`, `void forceFlush()`, `void fail(FailureReason reason)`, `void onWsDisconnected(Instant now)`, `CommandResult resume(long lastSeq, RecordingState state)`.
    - `void attachEventSink(Consumer<SessionEvent> sink)` (application port for WS push, avoids actor depending on infrastructure).

- [ ] **Step 1: Write the failing actor tests**

```java
@Test void duplicateCommandIdExecutesOnce() { /* one event, ack ok */ }
@Test void seqIsMonotonicAcrossCommands() { /* 1..N */ }
@Test void illegalCommandLeavesSnapshotUnchanged() { /* SESSION_STATE_INVALID */ }
@Test void startRequiresRecordingReady() { /* SESSION_STATE_INVALID before ready_ack ok */ }
@Test void recordingReadyAckListsRequiredStreams() { /* free: camera,screen,audio; interview: camera,audio */ }
@Test void snapshotHidesPrivilegedConfig() { /* traverse record fields/maps, not just top-level maps; no weights/material/answer_points/personality */ }
@Test void handLoweredIsEmittedForEveryOpenHand() { /* timeout: teacher_timeout; call_on: called_on; re-ask/end_qa/end_session/fail */ }
@Test void illegalEndDialogueKeepsHandWindowTimeoutPending() { /* no timer cancellation before validation */ }
@Test void examinerTextUsesQuestionIdAsSpeakerRef() { /* persisted session_text.speaker_ref == question_id */ }
@Test void answerFinishesAndReturnsToWaitingHands() { /* answerFinished(false); end_dialogue from student_answering works */ }
@Test void contentTimeoutFallsBackWithoutBlockingCommonPool() { /* dedicated executor + cancel */ }
@Test void eventSinkReceivesOnlyNewEvents() { /* ... */ }
@Test void fiftyConcurrentCommandsHaveUniqueSeq() { /* executor + distinct seq */ }
@Test void idGeneratorProducesUuidVersion7() { /* UUID.version() == 7 */ }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `docker compose --profile test run --rm test gradle test --tests '*SessionActorTest' --no-daemon --console=plain`
Expected: compilation failure.

- [ ] **Step 3: Implement the context, commands, content fallback, and actor**

`UuidIdGenerator` must return UUID version 7 (48-bit Unix-ms timestamp, version/variant bits, random tail); keep the `IdGenerator` interface. `SessionActor` is `synchronized` (P0 serialization); `handle` validates status/mode before mutating. `recording.ready` records stream readiness and returns `recording.ready_ack`; `session.start` requires all required streams ready. On `teacher.ask_class` / `teacher.ask_student_question`, schedule the hand-raise events through `SchedulerPort` after a deterministic 1–3 s delay (tests inject `FakeScheduler` and advance it), so commands do not block. After emitting each `student.hand_raised`, call `students.applyHandRaised(studentId, now, random)` exactly once. On `teacher.manage_student`, call `students.applyManaged(studentId)` and emit the returned `student.state_changed` events. On `student.question`, call `students.applyStudentQuestion(studentId)`. Manual ask is allowed from `LECTURING` or any `QA` phase and resets to `QA/WAITING_HANDS`; the 30 s timeout starts **when the first hand-raise event is emitted**, not when the window opens. When it fires, call `handWindowTimeout(now, handRaiseIds)` so no empty ids are emitted. Emit events through `SessionContext`, persist them through `SessionEventStore` immediately, and push to `eventSink` only after appending. Fallback templates: question `"老师，这里为什么要这样理解？"`, answer `"老师，我认为这一段的重点是……"`; `ContentGenerationService` catches client exceptions/timeouts and returns the fallback with confidence 0.0.

- [ ] **Step 4: Run tests to verify they pass**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/domain/session \
  server/src/main/java/com/shijiangtai/m3classroom/application \
  server/src/main/java/com/shijiangtai/m3classroom/application/port \
  server/src/test/java/com/shijiangtai/m3classroom/application/SessionActorTest.java \
  server/src/test/java/com/shijiangtai/m3classroom/support
git commit -m "[M3B] SessionActor、命令串行与内容兜底"
```

---

### Task 8: WebSocket ticket store and ticket API

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/application/port/WsTicketStore.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/application/{WsTicketService,SessionRuntimeRegistry}.java`
- Modify: `server/src/main/resources/application-docker.yml` (`spring.data.redis.host: ${SPRING_DATA_REDIS_HOST:redis}`)
- Modify: `docker-compose.yml` (app `SPRING_DATA_REDIS_HOST: redis`)
- Create: `server/src/main/java/com/shijiangtai/m3classroom/domain/session/SessionRepository.java` (interface only; Task 9 implements the JPA adapter)
- Create: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/ticket/{InMemoryWsTicketStore,RedisWsTicketStore}.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/api/WsTicketController.java`
- Modify: `server/src/test/resources/db/test-clean.sql` (truncate `m3_classroom.sessions` too, before tasks)
- Create: `server/src/test/java/com/shijiangtai/m3classroom/application/WsTicketServiceTest.java`
- Create: `server/src/test/java/com/shijiangtai/m3classroom/support/InMemorySessionRepository.java` (test fake for the ticket service/controller tests)
- Create: `server/src/test/java/com/shijiangtai/m3classroom/api/WsTicketControllerIT.java`

**Interfaces:**
- Produces:
  - `record WsTicketClaims(SessionId sessionId, AccountId accountId)`.
  - `interface WsTicketStore { String issue(WsTicketClaims claims, Duration ttl); Optional<WsTicketClaims> consume(String ticket); }`.
  - `final class WsTicketService { String issue(CurrentUser student, SessionId id); WsTicketClaims consume(String ticket); }` — verifies the session belongs to the student before issuing; 30 s TTL; `WS_TICKET_INVALID` / `WS_SESSION_FORBIDDEN`.
  - `interface SessionRepository { Session save(Session); Optional<Session> findById(SessionId); Optional<Session> findActiveByStudentAndTask(AccountId, TaskId); List<Session> findByStudentAndTask(AccountId, TaskId); long countByStudentAndTask(AccountId, TaskId); }` (Task 9 implements the adapter).
  - `final class SessionRuntimeRegistry` in this task: `void register(SessionActor actor); Optional<SessionActor> find(SessionId id); void remove(SessionId id);` Task 10 adds `getOrCreate`.
  - `WsTicketService.issue` verifies the session exists and belongs to the student through `SessionRepository`; missing session -> `RESOURCE_NOT_FOUND`, other student -> `WS_SESSION_FORBIDDEN`.
  - REST: `POST /api/v1/sessions/{sessionId}/ws-ticket` → `{ticket, expires_at}` (ISO-8601 UTC).
  - `InMemoryWsTicketStore` is the test/default bean (`m3.ticket-store=memory`); `RedisWsTicketStore` is conditional on `m3.ticket-store=redis` and uses `SETEX`/`GETDEL` semantics.

- [ ] **Step 1: Write the failing service test**

```java
@Test void issuesOneTimeTicketForOwner() { /* consume once returns claims, second consume empty */ }
@Test void rejectsExpiredTicket() { /* injectable clock/TTL; empty */ }
@Test void rejectsOtherStudentsSession() { /* WS_SESSION_FORBIDDEN */ }
@Test void rejectsMissingSession() { /* RESOURCE_NOT_FOUND */ }
```

- [ ] **Step 2: Write the failing controller test**

Use `PostgresIntegrationTest` + MockMvc. Task 10 is not implemented yet, so seed one `m3_classroom.sessions` row with `JdbcTemplate` (teacher/course/student IDs from `TestFixtures`; status `preparing`, mode `free`, type `task`, non-null `task_id` referencing a seeded task). Assert 200 with `ticket` and parseable `expires_at` (≈30 s), 403 for another student, 401 for missing headers.

- [ ] **Step 3: Run tests to verify they fail**

Run: `docker compose --profile test run --rm test gradle test --tests '*WsTicketServiceTest' --tests '*WsTicketControllerIT' --no-daemon --console=plain`
Expected: compilation failure.

- [ ] **Step 4: Implement the ticket store/service/controller**

Ticket value is opaque (`UUID.randomUUID().toString()`); store claims keyed by ticket. Redis adapter uses `StringRedisTemplate.opsForValue().set(key, json, ttl)` and `getAndDelete(key)`; JSON via the injected Jackson 3 `ObjectMapper`. Controller delegates to the service; no business logic.

- [ ] **Step 5: Run tests to verify they pass**

Run the Step 3 command. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/application/WsTicketService.java \
  server/src/main/java/com/shijiangtai/m3classroom/application/SessionRuntimeRegistry.java \
  server/src/main/java/com/shijiangtai/m3classroom/application/port/WsTicketStore.java \
  server/src/main/java/com/shijiangtai/m3classroom/infrastructure/ticket \
  server/src/main/java/com/shijiangtai/m3classroom/api/WsTicketController.java \
  server/src/test/java/com/shijiangtai/m3classroom/application/WsTicketServiceTest.java \
  server/src/test/java/com/shijiangtai/m3classroom/api/WsTicketControllerIT.java
git commit -m "[M3B] WS ticket 存储与接口"
```

---

### Task 9: Session/event/text persistence

**Files:**
- Reuse: `server/src/main/java/com/shijiangtai/m3classroom/domain/session/SessionRepository.java` (created by Task 8; implement the adapter, do not duplicate the interface)
- Create: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/{SessionEntity,SessionEventEntity,SessionTextEntity,SessionJpaRepository,SessionEventJpaRepository,SessionTextJpaRepository,SessionRepositoryAdapter,SessionEventStoreAdapter,SessionTextStoreAdapter,SessionJsonCodec}.java`
- Create: `server/src/test/java/com/shijiangtai/m3classroom/persistence/{SessionRepositoryIT,SessionEventPersistenceIT}.java`
- Modify: `server/src/test/resources/db/test-clean.sql` (add `events`, `session_texts`, `submissions`, `recordings`, `recording_streams`, `sessions` in FK-safe order)
- Modify: `server/src/test/resources/db/test-upstream.sql` only if a missing FK row is required (should not be).

**Interfaces:**
- Produces:
  - `interface SessionRepository { Session save(Session); Optional<Session> findById(SessionId); Optional<Session> findActiveByStudentAndTask(AccountId, TaskId); List<Session> findByStudentAndTask(AccountId, TaskId); long countByStudentAndTask(AccountId, TaskId); }`
  - `SessionEventStoreAdapter implements SessionEventStore` (Task 7) using the JPA repository; `append` is an idempotent `ON CONFLICT (session_id, seq) DO NOTHING` insert, `after` orders by `seq`, `maxSeq` returns `coalesce(max(seq),0)`.
  - **Acceptance criterion:** Task 9 removes the temporary `Optional<SessionRepository>` bridge from `WsTicketService`; inject `SessionRepository` directly and add a context/IT assertion so a missing adapter fails fast.
  - `SessionTextStoreAdapter implements SessionTextStore` (Task 7).
  - JPA adapters map the existing V4 tables; `sessions.stream_offsets` and `events.payload` are `String` columns with `columnDefinition = "jsonb"`; `SessionJsonCodec` writes/reads snake_case JSON.

- [ ] **Step 1: Write the failing repository IT**

Seed a `Session` (preparing, task/course/student IDs from `TestFixtures`/`DevDataConfig`), save, reload; assert every field including `stream_offsets` JSONB via `select stream_offsets::text`. Save two events with seq 1/2, re-save seq 1, assert no duplicate and `maxSeq=2`.

- [ ] **Step 2: Run test to verify it fails**

Run: `docker compose --profile test run --rm test gradle test --tests '*SessionRepositoryIT' --tests '*SessionEventPersistenceIT' --no-daemon --console=plain`
Expected: compilation failure.

- [ ] **Step 3: Implement entities, adapters, and codec**

Follow `TaskRepositoryAdapter`/`TaskEntity` patterns. Use `@Column(columnDefinition = "jsonb")`; never change V4. `SessionTextEntity` maps `session_texts`. Add `sessions` + `events` + `session_texts` + `submissions` + `recordings`/`recording_streams` to `test-clean.sql` in FK-safe order (children first).

- [ ] **Step 4: Run test to verify it passes**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/domain/session \
  server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa \
  server/src/test/java/com/shijiangtai/m3classroom/persistence \
  server/src/test/resources/db/test-clean.sql
git commit -m "[M3B] 会话、事件与文本持久化"
```

---

### Task 10: Session application service and REST API

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/application/{SessionAccessPolicy,SessionApplicationService,SessionApplicationServiceImpl}.java`
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/application/SessionRuntimeRegistry.java` (build actors from the task snapshot and runtime ports)
- Create: `server/src/main/java/com/shijiangtai/m3classroom/application/port/RecordingLifecyclePort.java`
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/config/M3Config.java` (no-op `RecordingLifecyclePort` bean; Plan 2 replaces it)
- Create: `server/src/main/java/com/shijiangtai/m3classroom/application/view/{SessionView,SessionIdView}.java` (or records in the service file if smaller)
- Create: `server/src/main/java/com/shijiangtai/m3classroom/api/{SessionController,SessionRequestMapper}.java`
- Create: `server/src/test/java/com/shijiangtai/m3classroom/application/{SessionAccessPolicyTest,SessionApplicationServiceTest}.java`
- Create: `server/src/test/java/com/shijiangtai/m3classroom/api/SessionControllerIT.java`

**Interfaces:**
- Produces:
  - `interface SessionApplicationService { SessionView createSession(CurrentUser student, TaskId taskId); SessionView get(CurrentUser user, SessionId sessionId); SessionView start(CurrentUser student, SessionId sessionId); SessionView end(CurrentUser student, SessionId sessionId); void cancel(CurrentUser student, SessionId sessionId); }`
  - `interface RecordingLifecyclePort { void abortAll(SessionId sessionId); void deleteOtherAttempts(AccountId student, TaskId task, SessionId keep); }` — Plan 1 registers a no-op `@Bean @ConditionalOnMissingBean`; Plan 2 provides the real implementation.
  - `record SessionView(UUID sessionId, UUID taskId, UUID courseId, String sessionType, String mode, String status, int attemptNo, String startedAt, String endedAt, Long durationMs, Map<String,Long> streamOffsets, long lastSeq)` (Jackson snake_case).
  - REST:
    - `POST /api/v1/tasks/{taskId}/sessions` → 200 `SessionView`
    - `GET /api/v1/sessions/{sessionId}` → 200 `SessionView`
    - `POST /api/v1/sessions/{sessionId}/start` → 200 (idempotent fallback; requires `recording.ready`)
    - `POST /api/v1/sessions/{sessionId}/end` → 200
    - `POST /api/v1/sessions/{sessionId}/cancel` → 204
  - `SessionAccessPolicy`: student owner; teachers may read a submitted session only (return `FORBIDDEN` otherwise); admins always `FORBIDDEN`; cross-school `CROSS_SCHOOL_ACCESS`.

- [ ] **Step 1: Write the failing service tests**

```java
@Test void assignedStudentCreatesPreparingSession() { /* attemptNo=1, status preparing */ }
@Test void unassignedStudentIsForbidden() { /* FORBIDDEN */ }
@Test void closedTaskRejectsNewSession() { /* TASK_CLOSED */ }
@Test void archivedOrEndedCourseRejectsNewSession() { /* COURSE_ARCHIVED / COURSE_ENDED */ }
@Test void activeSessionBlocksSecondCreate() { /* SESSION_ACTIVE_EXISTS */ }
@Test void secondAttemptGetsAttemptNoTwo() { /* after a failed/ended attempt */ }
@Test void startRequiresRecordingReady() { /* SESSION_STATE_INVALID */ }
@Test void startEndAndCancelFollowStateMachine() { /* status transitions */ }
@Test void teacherCannotReadUnsubmittedSession() { /* FORBIDDEN */ }
@Test void restEndEndsAnInterviewSession() { /* EndInterview path, status ended */ }
@Test void cancelUpdatesTheLiveActorAndStopsScheduledEvents() { /* FakeScheduler pending task does not append after cancel */ }
@Test void concurrentCreateReturnsSessionActiveExists() { /* two threads -> one success, one 409 */ }
```

- [ ] **Step 2: Write the failing controller IT**

`SessionControllerIT extends PostgresIntegrationTest` + `@AutoConfigureMockMvc`. Seed a task/assignment (M3A helper or `JdbcTemplate`), create a session as the assigned student, assert snake_case fields; start before `recording.ready` → 409 `SESSION_STATE_INVALID`; cancel → 204; another student → 403; admin → 403; missing headers → 401.

- [ ] **Step 3: Run tests to verify they fail**

Run: `docker compose --profile test run --rm test gradle test --tests '*SessionAccessPolicyTest' --tests '*SessionApplicationServiceTest' --tests '*SessionControllerIT' --no-daemon --console=plain`
Expected: compilation failure.

- [ ] **Step 4: Implement the service and controller**

`createSession`: load task via `TaskRepository`, require published and not closed, require assignment via `TaskAssignmentRepository.find(taskId, student)`, verify course active/endAt via `CourseQueryPort`, reject an existing preparing/in_progress session, save `Session.createPreparing(...)` with `attemptNo = count+1`. `start` loads/creates the actor via `SessionRuntimeRegistry` and calls `handle(new StartSession(...))`; if the actor reports `SESSION_STATE_INVALID`, rethrow. `end` dispatches `EndInterview` when the session mode is `INTERVIEW`, otherwise `EndSession` (05C §7.3); `cancel` cancels through the live `SessionActor` when one is registered (so scheduled callbacks cannot keep emitting events), persists that same instance, removes the actor, and calls `RecordingLifecyclePort.abortAll` (Plan 1 no-op; Plan 2 real). Return `SessionView` without privileged data; `lastSeq` comes from `SessionEventStore.maxSeq`.

- [ ] **Step 5: Run tests to verify they pass**

Run the Step 3 command. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/application server/src/main/java/com/shijiangtai/m3classroom/api \
  server/src/test/java/com/shijiangtai/m3classroom/application \
  server/src/test/java/com/shijiangtai/m3classroom/api/SessionControllerIT.java
git commit -m "[M3B] 会话应用服务与 REST 接口"
```

---

### Task 11: Tick, 30-second grace, resume, and failure

**Files:**
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/application/SessionActor.java`
- Modify/reuse: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/time/SystemScheduler.java` (created by Task 10)
- Create: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/ws/{SessionEventSinkAdapter,WsConnectionRegistry}.java` (or fold into the handler in Task 12)
- Create: `server/src/test/java/com/shijiangtai/m3classroom/application/SessionReconnectTest.java`

**Interfaces:**
- Produces:
  - `SessionActor.onTick(Instant now)` emits `session.time_tick` and updates dynamics; tick is a no-op outside `IN_PROGRESS`. `SessionRuntimeRegistry` schedules a 1 s tick through `SchedulerPort` when an actor enters `IN_PROGRESS` and cancels it on terminal states.
  - `SessionActor.onWsDisconnected(Instant now)` schedules `fail(GRACE_TIMEOUT)` after 30 s via `SchedulerPort`; `resume(lastSeq, state)` cancels it.
  - `resume` with `RecordingState.ACTIVE` → `session.resumed` + `replayAfter(lastSeq)`; with `LOST` → `fail(RECORDING_LOST)`; repeated `session.resume` on a failed session → `SESSION_FAILED`.
  - Time-tick events are pushed but never replayed by `replayAfter`.

- [ ] **Step 1: Write the failing reconnect tests**

```java
@Test void disconnectWithinGraceDoesNotFail() { /* fake scheduler not advanced */ }
@Test void graceTimeoutFailsSession() { /* advance fake scheduler 30s -> FAILED + session.failed */ }
@Test void resumeActiveReplaysEventsAfterLastSeq() { /* excludes earlier seq and time_tick */ }
@Test void resumeLostFailsImmediately() { /* RECORDING_LOST */ }
@Test void resumeAfterFailureReturnsSessionFailed() { /* SESSION_FAILED */ }
@Test void tickOutsideInProgressIsNoOp() { /* no events */ }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `docker compose --profile test run --rm test gradle test --tests '*SessionReconnectTest' --no-daemon --console=plain`
Expected: compilation failure.

- [ ] **Step 3: Implement grace/tick/resume**

Use a single `ScheduledExecutorService` in `SystemScheduler`; `FakeScheduler` in tests stores tasks and `advance(Duration)` runs them. `SessionActor` keeps `graceTask`; `onWsDisconnected` only applies when `IN_PROGRESS`. On `fail`, set the session status, emit/persist `session.failed`, force flush, and remove the actor from the registry in the service layer.

- [ ] **Step 4: Run tests to verify they pass**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/application/SessionActor.java \
  server/src/main/java/com/shijiangtai/m3classroom/infrastructure/time/SystemScheduler.java \
  server/src/main/java/com/shijiangtai/m3classroom/infrastructure/ws \
  server/src/test/java/com/shijiangtai/m3classroom/application/SessionReconnectTest.java
git commit -m "[M3B] tick、30 秒 grace 与重连"
```

---

### Task 12: WebSocket handler, envelopes, contract tests, and smoke evidence

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/ws/{WebSocketConfig,ClassroomWebSocketHandler,WsHandshakeInterceptor,ClientCommandEnvelope,ServerEventEnvelope,WsCommandParser,WsMessageSender}.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/api/dto/WsDtos.java` if needed
- Create: `server/src/test/java/com/shijiangtai/m3classroom/ws/{WsEnvelopeTest,ClassroomWebSocketIT}.java`
- Create: `docs/team/evidence/2026-11-04-m3b-classroom-ws.md`
- Modify: `docs/team/02-进度看板.md` (M3B row blocker + §5 update row only)
- Modify: `server/README.md` (WS smoke command)

**Interfaces:**
- Produces:
  - Raw WebSocket endpoint `/ws/v1/sessions/{sessionId}?ticket=...`.
  - Envelope `{v:1, type, event_id, seq, at_ms, session_id, payload}` for server events; `{v:1, type, command_id, payload}` for commands.
  - Invalid/missing/expired ticket: accept the upgrade and close with 4001 from the handler (interceptor carries an auth-error attribute); do not rely on an HTTP handshake error.
  - On connect: consume ticket, load actor, send `session.snapshot`, attach sink, and cancel any pending PREPARING/IN_PROGRESS grace timer. `session.start` also cancels a pending grace timer. For `ENDED`/`REVIEWING`/terminal sessions, send the snapshot, close normally, and evict the transient actor only when the persisted row is terminal (never bypass the registry's save-failure shield); `PREPARING` disconnects follow the 30 s grace/cancel-or-fail path.
  - `time.sync` responds with a `server.ack` payload containing `at_ms` and `server_time_ms` (02 §6.5) so the client can compute the offset. On command: parse → `actor.handle` → send `server.ack {command_id, ok}`; **do not send `CommandResult.events()`** — the actor's event sink is the single live push path, so command events and scheduled/tick events are sent exactly once. On error send `error {code, message, command_id}` without state change. `session.resume` pushes `session.resumed` followed by the missing replay tail through the sink; clients process the replay tail even though its `seq` values are lower than `session.resumed`.
  - Heartbeat: 15 s `ping`, 30 s timeout → close 4004; message size 64 KB → close 4005 (configure the container text buffer above 64 KB, e.g. 256 KB, so the handler performs the protocol check; map container too-big errors where possible); ticket failure → 4001; failed session → 4002; invalid state → 4003.
  - `ClassroomWebSocketIT` uses Spring `StandardWebSocketClient`/`WebSocketClient` against `@SpringBootTest(webEnvironment=RANDOM_PORT)`, obtains a ticket over REST, sends `recording.ready`/`session.start`, receives snapshot/ack/events, disconnects and resumes. A second connection for the same session replaces the first: close the old socket without starting grace failure (05A §8). `session.time_tick` and `ping` are never replayed.

- [ ] **Step 1: Write the failing envelope contract test**

```java
@Test void serverEnvelopeUsesSnakeCaseAndRequiredFields() { /* v,type,event_id,seq,at_ms,session_id,payload */ }
@Test void clientEnvelopeParsesCommandIdAndPayload() { /* ... */ }
@Test void rejectsMissingCommandIdAndUnsupportedVersion() { /* VALIDATION_ERROR */ }
```

- [ ] **Step 2: Write the failing WS integration test**

```java
@Test void ticketConnectSnapshotAndStart() { /* 101, snapshot.last_seq, recording.ready_ack, session.started */ }
@Test void duplicateCommandIdAcksOnce() { /* one session.started */ }
@Test void invalidCommandDoesNotChangeState() { /* error SESSION_STATE_INVALID */ }
@Test void reconnectWithinGraceReplaysMissingEvents() { /* session.resumed then missing seq */ }
@Test void reconnectOutsideGraceCloses4002() { /* failed session */ }
@Test void invalidTicketUpgradeCloses4001() { /* accepted upgrade then close 4001 */ }
@Test void oversizedFrameCloses4005() { /* >64KB ASCII frame */ }
@Test void timeSyncReturnsServerTime() { /* ack payload at_ms + server_time_ms */ }
@Test void terminalSessionConnectDoesNotCacheActor() { /* snapshot, close, registry empty */ }
@Test void preparingReconnectThenStartCancelsGrace() { /* advance 30s after reconnect/start -> still in_progress */ }
@Test void terminalEvictionDoesNotBypassSaveFailureShield() { /* failed terminal save keeps actor registered */ }
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `docker compose --profile test run --rm test gradle test --tests '*WsEnvelopeTest' --tests '*ClassroomWebSocketIT' --no-daemon --console=plain`
Expected: compilation failure.

- [ ] **Step 4: Implement the handler and config**

Register `ClassroomWebSocketHandler` via `WebSocketConfigurer`; set allowed origins to `*` for the dev slice (document as dev-only). The handshake interceptor reads `ticket` and puts `SessionId`/`AccountId` in attributes; reject with 4001. The handler delegates to `WsTicketService`, `SessionRuntimeRegistry`, and `SessionActor`. Keep Spring imports out of `domain`/`application` (the sink is a `Consumer<SessionEvent>`).

- [ ] **Step 5: Run tests to verify they pass**

Run the Step 3 command. Expected: PASS.

- [ ] **Step 6: Run the full suite and Docker smoke**

```bash
docker compose --profile test run --rm test gradle clean test --no-daemon --console=plain
docker compose up -d --build
curl -fsS http://localhost:8080/actuator/health
# create an M3A free task, publish, create a session, request a ticket, connect with a WS client if available
```

Record the test count, WS transcript, and any deferred items in the evidence file. Update the M3B row's blocker to `课堂/WS 后端 P0 已实现；感知通道与前端待续` and add one §5 row with the evidence link. Do not mark G3–G8.

- [ ] **Step 7: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/infrastructure/ws \
  server/src/test/java/com/shijiangtai/m3classroom/ws \
  docs/team/evidence/2026-11-04-m3b-classroom-ws.md docs/team/02-进度看板.md server/README.md
git commit -m "[M3B] 课堂 WebSocket、契约测试与进度证据"
```

---

## Out of Scope / Follow-up Plans

- M3C recording multipart, replay, and submission: `2026-11-04-m3c-recording-backend.md`.
- Frontend classroom/interview/review/replay: `2026-11-04-m3-frontend-classroom-replay.md`.
- Perception ASR/OCR/screen WS, async batch context writer, retention/cleanup jobs, M6 training, E2E, performance/security NFR.
