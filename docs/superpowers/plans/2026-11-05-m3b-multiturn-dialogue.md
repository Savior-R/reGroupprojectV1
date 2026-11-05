# M3B Multi-turn Dialogue (Teacher-Voice-Driven) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the existing `DialogueState` into `SessionActor`: student answers roll `question_rate × 0.5` for a follow-up, teacher ASR finals drive up to 3 rounds, and every exit emits the correct `dialogue.*` events before the classroom/session transition.

**Architecture:** Extend the pure classroom state machine and virtual-student dynamics with dialogue transitions; `SessionActor` owns the per-session `DialogueState`, answer id, and teacher-response boundary. No new infrastructure or ports; the perception channel's existing `recordSpeechFinal` is the teacher-response entry point.

**Tech Stack:** Java 21, Spring Boot 4.1.2, JUnit 5, AssertJ, Docker Compose test service.

**Spec:** `docs/superpowers/specs/2026-11-05-m3b-multiturn-dialogue-design.md`

## Global Constraints

- Java 21; run tests through Compose:
  `docker compose --profile test run --rm test gradle test --tests '*<Class>' --no-daemon --console=plain`
  Full suite: `docker compose --profile test run --rm test gradle clean test --no-daemon --console=plain`.
- Domain (`m3classroom/domain/**`, `shared/domain/**`) must not import Spring, Jackson, JPA, HTTP, `api`, `application`, or `infrastructure`.
- Application must not import `api`/`infrastructure`; API must not import `infrastructure`.
- Events are append-only; `seq` monotonic; event order is client-visible and must match the spec §5.
- `student.follow_up` payload stays exactly `{student_id, answer_id, text_id}` (do not add `dialogue_id`).
- No new dependencies; do not edit `build.gradle.kts`, `application*.yml`, `docs/design/02`, `docs/design/12`, or M3C files.
- Commit after every task with prefix `[M3B]`; **use `git commit --only <explicit paths>`** — the index contains unrelated staged deletions and a plain `git commit` would sweep them in. Never commit the plan/ledger unless explicitly listed.
- Do not change G2–G8 marks or percentages on the progress board.

## Review Focus

1. **Teacher-final boundary:** a final whose `start_ms` (or `end_ms`/current when absent) is before `dialogueAwaitingSinceMs` must not advance the dialogue; only finals beginning after the follow-up count as the teacher's response.
2. **Three-round cap:** after the third round, the next teacher final must emit `dialogue.ended(max_rounds)` and return to `waiting_hands`, never `student.follow_up` again.
3. **Termination event order:** `teacher.end_dialogue` / `end_qa` / `ask_class` / `ask_student_question` / `end_session` / timeout / `fail` must emit `dialogue.ended` → student `focused` → caller events, with no extra intermediate `waiting_hands`.
4. **Stale `dialogue_id`:** `teacher.end_dialogue` with a provided id that does not match the active dialogue must return `SESSION_STATE_INVALID` and leave state/events unchanged.
5. **Content fallback:** a failing/blank content service must still produce a follow-up with the template text, persist it, advance the round, and keep the dialogue consistent.
6. **Multi-final teacher turn:** two finals from one teacher utterance (the second starting before the new follow-up boundary) must not create two rounds.

---

## File Structure

Production (modify):

- `server/src/main/java/com/shijiangtai/m3classroom/domain/classroom/ClassroomStateMachine.java` — `endDialogue(trigger, now)` overload + trigger constants.
- `server/src/main/java/com/shijiangtai/m3classroom/domain/classroom/VirtualStudentDynamics.java` — `applyFollowUp` returns events/transitions to `dialogue`; new `applyDialogueEnded`.
- `server/src/main/java/com/shijiangtai/m3classroom/application/SessionActor.java` — dialogue fields, initial follow-up roll, teacher-final continuation, termination cleanup.
- `server/src/main/java/com/shijiangtai/m3classroom/application/SessionRuntimeRegistry.java` — test seam for the actor random factory (production behavior unchanged).

Tests (create/modify):

- `server/src/test/java/com/shijiangtai/m3classroom/domain/classroom/ClassroomStateMachineTest.java` (modify)
- `server/src/test/java/com/shijiangtai/m3classroom/domain/classroom/VirtualStudentDynamicsTest.java` (modify)
- `server/src/test/java/com/shijiangtai/m3classroom/application/SessionActorDialogueTest.java` (create)
- `server/src/test/java/com/shijiangtai/m3classroom/ws/PerceptionWebSocketIT.java` (modify)
- `server/src/test/java/com/shijiangtai/m3classroom/support/MutableRandom.java` (create)

Docs: `docs/design/05A-M3-课堂状态机与WebSocket.md`, `docs/implementation/04-M3-实现设计.md`, `docs/team/evidence/2026-11-05-m3b-multiturn-dialogue.md`, `docs/team/02-进度看板.md`.

---

### Task 0: Commit the approved design and this plan

**Files:**
- Add: `docs/superpowers/specs/2026-11-05-m3b-multiturn-dialogue-design.md`
- Add: `docs/superpowers/plans/2026-11-05-m3b-multiturn-dialogue.md`

- [ ] **Step 1: Commit only the two docs**

```bash
git commit --only -m "[M3B] 多轮对话设计与实现计划" \
  docs/superpowers/specs/2026-11-05-m3b-multiturn-dialogue-design.md \
  docs/superpowers/plans/2026-11-05-m3b-multiturn-dialogue.md
```

Expected: `git show --stat HEAD` lists exactly those two files; the unrelated staged deletions remain in the index.

---

### Task 1: ClassroomStateMachine dialogue trigger overload

**Files:**
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/domain/classroom/ClassroomStateMachine.java`
- Test: `server/src/test/java/com/shijiangtai/m3classroom/domain/classroom/ClassroomStateMachineTest.java`

**Interfaces:**
- Produces:
  - `public static final String TRIGGER_END_DIALOGUE = "end_dialogue"`
  - `public static final String TRIGGER_NO_FOLLOW_UP = "no_follow_up"`
  - `public static final String TRIGGER_MAX_ROUNDS = "max_rounds"`
  - `public List<ClassroomEvent> endDialogue(Instant now)` (delegates to `endDialogue(TRIGGER_END_DIALOGUE, now)`)
  - `public List<ClassroomEvent> endDialogue(String trigger, Instant now)` — non-blank trigger, requires `qaPhase == DIALOGUE`, returns `state_changed(QA→QA, qa_phase=waiting_hands, trigger)`.

- [ ] **Step 1: Write the failing tests**

Add to `ClassroomStateMachineTest`:
```java
@Test
void endDialogueUsesProvidedTrigger() {
    ClassroomStateMachine machine = machineInDialogue(); // helper: callOn after askClass with a student
    List<ClassroomEvent> events = machine.endDialogue(
            ClassroomStateMachine.TRIGGER_NO_FOLLOW_UP, NOW);
    assertThat(events).singleElement().satisfies(event -> {
        assertThat(event.type()).isEqualTo("classroom.state_changed");
        assertThat(event.payload()).containsEntry("qa_phase", "waiting_hands")
                .containsEntry("trigger", "no_follow_up");
    });
    assertThat(machine.qaPhase()).isEqualTo(QaPhase.WAITING_HANDS);
}

@Test
void endDialogueRejectsBlankTriggerAndWrongPhase() {
    assertThatThrownBy(() -> machineInDialogue().endDialogue(" ", NOW))
            .isInstanceOf(DomainException.class);
    ClassroomStateMachine lecturing = new ClassroomStateMachine();
    lecturing.start();
    assertThatThrownBy(() -> lecturing.endDialogue(NOW))
            .isInstanceOf(DomainException.class);
}
```

- [ ] **Step 2: Run RED**

Run: `docker compose --profile test run --rm test gradle test --tests '*ClassroomStateMachineTest' --no-daemon --console=plain`
Expected: compile failure for the missing `endDialogue(String, Instant)` overload.

- [ ] **Step 3: Implement the overload**

Keep the existing private `stateChanged(from, to, qaPhase, trigger)` helper; validate `trigger` with `Objects.requireNonNull` and `isBlank()` → `DomainException(VALIDATION_ERROR)`. The existing `endDialogue(Instant)` delegates.

- [ ] **Step 4: Run GREEN**

Run the same command. Expected: `BUILD SUCCESSFUL`, all classroom state-machine tests pass.

- [ ] **Step 5: Commit**

```bash
git commit --only -m "[M3B] 对话状态机支持 no_follow_up/max_rounds 触发器" \
  server/src/main/java/com/shijiangtai/m3classroom/domain/classroom/ClassroomStateMachine.java \
  server/src/test/java/com/shijiangtai/m3classroom/domain/classroom/ClassroomStateMachineTest.java
```

---

### Task 2: VirtualStudentDynamics dialogue transitions

**Files:**
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/domain/classroom/VirtualStudentDynamics.java`
- Test: `server/src/test/java/com/shijiangtai/m3classroom/domain/classroom/VirtualStudentDynamicsTest.java`

**Interfaces:**
- Produces:
  - `public List<ClassroomEvent> applyFollowUp(UUID studentId)` — applies `+0.05` noisy attention and transitions `answering → dialogue` (or no state event if already `dialogue`); returns the `student.state_changed` event when the behavior changes.
  - `public List<ClassroomEvent> applyDialogueEnded(UUID studentId)` — transitions `dialogue → focused` with reason `dialogue_ended`; returns the state-change event or an empty list.

- [ ] **Step 1: Write the failing tests**

Add to `VirtualStudentDynamicsTest`:
```java
@Test
void followUpTransitionsToDialogueAndAddsAttention() {
    VirtualStudentDynamics dynamics = initializedDynamics();
    UUID id = studentId(1);
    dynamics.applyAnswer(id);                       // sets answering
    double before = dynamics.find(id).attention();

    List<ClassroomEvent> events = dynamics.applyFollowUp(id);

    assertThat(dynamics.find(id).behavior())
            .isEqualTo(VirtualStudentBehavior.DIALOGUE);
    assertThat(dynamics.find(id).attention()).isGreaterThan(before);
    assertThat(events).singleElement().satisfies(event -> {
        assertThat(event.type()).isEqualTo("student.state_changed");
        assertThat(event.payload()).containsEntry("to", "dialogue")
                .containsEntry("reason", "follow_up");
    });
}

@Test
void secondFollowUpOnlyAddsAttentionAndDialogueEndReturnsToFocused() {
    VirtualStudentDynamics dynamics = initializedDynamics();
    UUID id = studentId(1);
    dynamics.applyAnswer(id);
    dynamics.applyFollowUp(id);
    double before = dynamics.find(id).attention();

    assertThat(dynamics.applyFollowUp(id)).isEmpty(); // already dialogue
    assertThat(dynamics.find(id).attention()).isGreaterThan(before);

    List<ClassroomEvent> ended = dynamics.applyDialogueEnded(id);
    assertThat(dynamics.find(id).behavior()).isEqualTo(VirtualStudentBehavior.FOCUSED);
    assertThat(ended).singleElement().satisfies(event ->
            assertThat(event.payload()).containsEntry("to", "focused")
                    .containsEntry("reason", "dialogue_ended"));
}
```

Use the existing helper names in this test class; if they differ, mirror the existing setup.

- [ ] **Step 2: Run RED**

Run: `docker compose --profile test run --rm test gradle test --tests '*VirtualStudentDynamicsTest' --no-daemon --console=plain`
Expected: compile failure for `applyFollowUp` returning `void` assigned to a list / missing `applyDialogueEnded`.

- [ ] **Step 3: Implement**

Change `applyFollowUp` to `List<ClassroomEvent>`:
```java
public List<ClassroomEvent> applyFollowUp(UUID studentId) {
    VirtualStudentRuntime student = find(studentId);
    applyNoisyAttention(student, FOLLOW_UP_ATTENTION);
    return transition(student, VirtualStudentBehavior.DIALOGUE, "follow_up");
}
```
Add:
```java
public List<ClassroomEvent> applyDialogueEnded(UUID studentId) {
    return transition(find(studentId), VirtualStudentBehavior.FOCUSED, "dialogue_ended");
}
```
Update the existing `applyFollowUp` call site (the test at line ~212 ignores the return; Java allows that) and any production callers.

- [ ] **Step 4: Run GREEN**

Run the same command, plus `*ClassroomStateMachineTest`. Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git commit --only -m "[M3B] 虚拟学生对话状态转移" \
  server/src/main/java/com/shijiangtai/m3classroom/domain/classroom/VirtualStudentDynamics.java \
  server/src/test/java/com/shijiangtai/m3classroom/domain/classroom/VirtualStudentDynamicsTest.java
```

---

### Task 3: Actor initial answer → dialogue

**Files:**
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/application/SessionActor.java`
- Test: `server/src/test/java/com/shijiangtai/m3classroom/application/SessionActorDialogueTest.java`
- Test support: `server/src/test/java/com/shijiangtai/m3classroom/support/MutableRandom.java`

**Interfaces:**
- Consumes: `DialogueState` (existing), `ClassroomStateMachine.TRIGGER_*` (Task 1), `VirtualStudentDynamics.applyFollowUp` (Task 2).
- Produces (actor fields/methods used by later tasks):
  - `private DialogueState dialogue;`
  - `private UUID dialogueAnswerId;`
  - `private long dialogueAwaitingSinceMs;`
  - `private void maybeStartDialogue(VirtualStudentRuntime student, UUID answerId, Instant now);`
  - `private List<SessionEvent> emitFollowUp(VirtualStudentRuntime student, Instant now, boolean applyAttention);`
  - `private List<SessionEvent> endActiveDialogue(String reason, Instant now);`

Behavior:
- In `emitStudentAnswer`, after `student.answer` and `students.applyAnswer`, roll:
  `random.nextDouble() < DialogueState.followUpProbability(student.config().questionRate().doubleValue())`.
  - false → existing path: `classroom.answerFinished(studentId, false, now)` + `students.applyAnswerFinished`.
  - true → `maybeStartDialogue(student, answerId, now)`.
- `maybeStartDialogue` order (spec §5.1):
  1. `appendClassroomEvents(classroom.answerFinished(studentId, true, now), now)`;
  2. `appendStudentEvents(students.applyFollowUp(studentId), now)`;
  3. `dialogue = new DialogueState(ids.nextId(), studentId); dialogueAnswerId = answerId;`
  4. append `dialogue.started` `{dialogue_id, student_id}`;
  5. `emitFollowUp(student, now, false)`.
- `emitFollowUp`: `content.questionOrFallback(contentRequest(student))`; persist `SessionText(student, studentId, "llm", "follow_up", text, ..., "final")`; if `applyAttention`, call `students.applyFollowUp` (attention only; already dialogue); append `student.follow_up {student_id, answer_id=dialogueAnswerId, text_id}`; set `dialogueAwaitingSinceMs = atMs(now)`.

- [ ] **Step 1: Write the failing test**

Create `SessionActorDialogueTest` with a fixture copied from `SessionActorTest` but using `MutableRandom`:
```java
final class MutableRandom implements RandomGenerator {
    private double value;
    MutableRandom(double value) { this.value = value; }
    void set(double value) { this.value = value; }
    @Override public long nextLong() { return 0L; }
    @Override public double nextDouble() { return value; }
}
```
Tests:
```java
@Test
void winningFollowUpRollStartsDialogueAndEmitsFollowUp() {
    Fixture f = fixture(new MutableRandom(0.0)); // p = 0.25, 0.0 wins
    f.start();
    f.actor().handle(f.callOn());

    List<SessionEvent> events = f.context().after(0);
    assertThat(events).extracting(SessionEvent::type).containsSubsequence(
            "teacher.called_on", "student.answer",
            "classroom.state_changed", "student.state_changed",
            "dialogue.started", "student.follow_up");
    SessionEvent started = events.stream()
            .filter(e -> e.type().equals("dialogue.started")).findFirst().orElseThrow();
    assertThat(started.payload().get("student_id")).isEqualTo(f.studentId());
    SessionEvent followUp = events.stream()
            .filter(e -> e.type().equals("student.follow_up")).findFirst().orElseThrow();
    assertThat(followUp.payload().get("answer_id")).isEqualTo(f.answerId(events));
    assertThat(f.classroom().qaPhase()).isEqualTo(QaPhase.DIALOGUE);
    assertThat(f.dynamics().find(f.studentId()).behavior())
            .isEqualTo(VirtualStudentBehavior.DIALOGUE);
    assertThat(f.textStore().all())
            .anySatisfy(text -> assertThat(text.kind()).isEqualTo("follow_up"));
    assertThat(f.scheduler().pendingTasks()).isZero();
}

@Test
void losingFollowUpRollKeepsLegacyAnswerFinishedPath() {
    Fixture f = fixture(new MutableRandom(0.99));
    f.start();
    f.actor().handle(f.callOn());
    assertThat(f.context().after(0)).extracting(SessionEvent::type)
            .doesNotContain("dialogue.started", "student.follow_up");
    assertThat(f.classroom().qaPhase()).isEqualTo(QaPhase.WAITING_HANDS);
    assertThat(f.dynamics().find(f.studentId()).behavior())
            .isEqualTo(VirtualStudentBehavior.FOCUSED);
}
```
The fixture must expose `studentId()`, `classroom()`, `dynamics()`, `textStore()`, `scheduler()`, and a helper to find `answer_id` from the `student.answer` event. Copy the existing `SessionActorTest` fixture setup and adapt the random parameter.

- [ ] **Step 2: Run RED**

Run: `docker compose --profile test run --rm test gradle test --tests '*SessionActorDialogueTest' --no-daemon --console=plain`
Expected: compile failure / missing dialogue events.

- [ ] **Step 3: Implement Task 3 behavior**

Keep `handleCallOn` and hand-window scheduling unchanged. Ensure the initial follow-up path does not schedule hand raises (it stays in `qa.dialogue`). `content.questionOrFallback` may block up to 2 s; the actor monitor is held, as with the existing `emitStudentAnswer` answer generation.

- [ ] **Step 4: Run GREEN**

Run: `... --tests '*SessionActorDialogueTest' --tests '*SessionActorTest' --no-daemon ...`
Expected: both pass.

- [ ] **Step 5: Commit**

```bash
git commit --only -m "[M3B] 回答后启动多轮对话" \
  server/src/main/java/com/shijiangtai/m3classroom/application/SessionActor.java \
  server/src/test/java/com/shijiangtai/m3classroom/application/SessionActorDialogueTest.java \
  server/src/test/java/com/shijiangtai/m3classroom/support/MutableRandom.java
```

---

### Task 4: Teacher-final continuation and round limit

**Files:**
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/application/SessionActor.java`
- Test: `server/src/test/java/com/shijiangtai/m3classroom/application/SessionActorDialogueTest.java`

**Interfaces:**
- Consumes: Task 3 fields; `ClassroomStateMachine.endDialogue(trigger, now)` (Task 1).
- Produces:
  - `recordSpeechFinal` side effect: after persisting a teacher final, call
    `continueDialogueAfterTeacherFinal(text, startMs, endMs, now)`.
  - `private void continueDialogueAfterTeacherFinal(String text, Long startMs, Long endMs, Instant now);`

Behavior (spec §5.2):
- Return immediately if `dialogue == null` or speaker is not teacher (call only for `"teacher"`).
- `finalStart = startMs != null ? startMs : endMs != null ? endMs : currentAtMs()`.
- If `finalStart < dialogueAwaitingSinceMs` → return.
- `student = students.find(dialogue.studentId())`; if absent → return.
- If `!dialogue.canFollowUp()`:
  `endActiveDialogue("max_rounds", now)`; `appendClassroomEvents(classroom.endDialogue(TRIGGER_MAX_ROUNDS, now), now)`.
- Else if `!dialogue.shouldFollowUp(student.config().questionRate().doubleValue(), random)`:
  `endActiveDialogue("no_follow_up", now)`; `appendClassroomEvents(classroom.endDialogue(TRIGGER_NO_FOLLOW_UP, now), now)`.
- Else: `dialogue.nextRound(); emitFollowUp(student, now, true)`.
- `endActiveDialogue` writes `dialogue.ended {dialogue_id, student_id, reason}`, appends `students.applyDialogueEnded`, clears `dialogue` / `dialogueAnswerId` / `dialogueAwaitingSinceMs`, and returns the appended events. It does not touch `classroom`.

- [ ] **Step 1: Write the failing tests**

Add:
```java
@Test
void teacherFinalAfterFollowUpContinuesToSecondRound() {
    MutableRandom random = new MutableRandom(0.0);
    Fixture f = fixture(random);
    f.start(); f.actor().handle(f.callOn());
    long boundary = f.lastFollowUpAtMs(); // read student.follow_up event at_ms

    f.actor().recordSpeechFinal("teacher", null, "对，你能再具体说说吗？",
            boundary + 1000, boundary + 2000, BigDecimal.valueOf(0.9));

    assertThat(f.context().after(0)).extracting(SessionEvent::type)
            .contains("dialogue.started", "student.follow_up");
    assertThat(f.dialogueRound()).isEqualTo(2); // fixture reads the private state via snapshot/events
}

@Test
void finalStartedBeforeBoundaryDoesNotAdvanceDialogue() {
    MutableRandom random = new MutableRandom(0.0);
    Fixture f = fixture(random);
    f.start(); f.actor().handle(f.callOn());
    long boundary = f.lastFollowUpAtMs();
    int before = f.countEvents("student.follow_up");

    f.actor().recordSpeechFinal("teacher", null, "上一句话",
            boundary - 1000, boundary - 500, BigDecimal.valueOf(0.9));

    assertThat(f.countEvents("student.follow_up")).isEqualTo(before);
}

@Test
void teacherFinalWithoutFollowUpEndsDialogueAndReturnsToWaitingHands() {
    MutableRandom random = new MutableRandom(0.0);
    Fixture f = fixture(random);
    f.start(); f.actor().handle(f.callOn());
    long boundary = f.lastFollowUpAtMs();
    random.set(0.99); // next follow-up roll loses

    f.actor().recordSpeechFinal("teacher", null, "嗯，请坐。",
            boundary + 1000, boundary + 2000, BigDecimal.valueOf(0.9));

    assertThat(f.context().after(0)).extracting(SessionEvent::type)
            .containsSubsequence("dialogue.ended", "student.state_changed",
                    "classroom.state_changed");
    SessionEvent ended = f.lastEvent("dialogue.ended");
    assertThat(ended.payload().get("reason")).isEqualTo("no_follow_up");
    assertThat(f.classroom().qaPhase()).isEqualTo(QaPhase.WAITING_HANDS);
    assertThat(f.dynamics().find(f.studentId()).behavior())
            .isEqualTo(VirtualStudentBehavior.FOCUSED);
}

@Test
void classificationDuringDialogueIsIgnored() {
    Fixture f = dialogueFixture(); // enters qa.dialogue with a fixed 0.0 random
    int before = f.countEvents("classroom.question_suggested");
    f.actor().onTeacherSpeechClassified(0.9);
    assertThat(f.countEvents("classroom.question_suggested")).isEqualTo(before);
    assertThat(f.context().after(0)).extracting(SessionEvent::type)
            .doesNotContain("classroom.question_asked");
}

@Test
void thirdRoundTeacherFinalEndsWithMaxRounds() {
    MutableRandom random = new MutableRandom(0.0);
    Fixture f = fixture(random);
    f.start(); f.actor().handle(f.callOn());
    long boundary = f.lastFollowUpAtMs();
    f.actor().recordSpeechFinal("teacher", null, "继续", boundary + 1000, boundary + 2000,
            BigDecimal.valueOf(0.9)); // round 2 follow-up
    long boundary2 = f.lastFollowUpAtMs();
    f.actor().recordSpeechFinal("teacher", null, "再继续", boundary2 + 1000, boundary2 + 2000,
            BigDecimal.valueOf(0.9)); // round 3 follow-up
    long boundary3 = f.lastFollowUpAtMs();
    f.actor().recordSpeechFinal("teacher", null, "最后", boundary3 + 1000, boundary3 + 2000,
            BigDecimal.valueOf(0.9)); // max rounds

    SessionEvent ended = f.lastEvent("dialogue.ended");
    assertThat(ended.payload().get("reason")).isEqualTo("max_rounds");
    assertThat(f.classroom().qaPhase()).isEqualTo(QaPhase.WAITING_HANDS);
}
```

Fixture helpers: `lastFollowUpAtMs()` reads the last `student.follow_up` event's `at_ms`; `dialogueRound()` may be derived by counting `student.follow_up` events + 1; `countEvents(type)`; `lastEvent(type)`.

- [ ] **Step 2: Run RED**

Run: `... --tests '*SessionActorDialogueTest' --no-daemon ...`
Expected: new tests fail (no continuation).

- [ ] **Step 3: Implement Task 4 behavior**

Call the continuation from `recordSpeechFinal` only when `"teacher".equals(speakerType)`. Do not trigger for candidate/interview finals. Keep the text persistence before the dialogue events.

Also add the classification guard at the top of `onTeacherSpeechClassified`:
```java
if (classroom.macroState() != ClassroomMacroState.LECTURING) {
    return;
}
```
after the existing status/interview checks, so a teacher final during `qa.dialogue`/`qa.waiting_hands`/`qa.student_answering` does not emit `classroom.question_suggested`.

- [ ] **Step 4: Run GREEN**

Run: `... --tests '*SessionActorDialogueTest' --tests '*SessionActorTest' --tests '*SessionActorPerceptionTest' --no-daemon ...`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git commit --only -m "[M3B] 教师语音回应驱动多轮对话" \
  server/src/main/java/com/shijiangtai/m3classroom/application/SessionActor.java \
  server/src/test/java/com/shijiangtai/m3classroom/application/SessionActorDialogueTest.java
```

---

### Task 5: Termination paths and dialogue-id validation

**Files:**
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/application/SessionActor.java`
- Test: `server/src/test/java/com/shijiangtai/m3classroom/application/SessionActorDialogueTest.java`

**Interfaces:**
- Consumes: `endActiveDialogue` (Task 3/4), `ClassroomStateMachine.endDialogue` (Task 1).
- Produces: no new public API; behavior changes in `handleEndDialogue`, `handleEndQa`, `handleAsk`, `endClassroomSession`, `fail`, `cancel`.

Behavior (spec §5.3):
- Change `dispatch` to call `handleEndDialogue(endDialogue)` and `handleEndDialogue(SessionCommand.EndDialogue command)`:
  - If `command.dialogueId() != null` and (`dialogue == null` or id mismatch) → `DomainException(SESSION_STATE_INVALID, "对话不存在或已结束")`; no state change.
  - If `qaPhase == DIALOGUE`: `events = new ArrayList<>(endActiveDialogue("teacher", now))`; then `events.addAll(appendClassroomEvents(classroom.endDialogue(TRIGGER_END_DIALOGUE, now), now))`.
  - If `qaPhase == STUDENT_ANSWERING`: keep existing behavior (finish answer, no dialogue events).
  - Otherwise: `classroom.endDialogue(...)` throws `SESSION_STATE_INVALID`.
- `handleEndQa`: if dialogue active, `events = new ArrayList<>(endActiveDialogue("qa_ended", now))` before `classroom.endQa`; prepend to the existing event list.
- `handleAsk` (both manual/voice): if dialogue active, prepend `endActiveDialogue("qa_restarted", now)` before `emitHandLowers`/`classroom.askClass`.
- `endClassroomSession`: prepend `endActiveDialogue("session_ended", now)` before `classroom.endSession`.
- `fail`: call `endActiveDialogue("session_ended", now)` after `session.fail(...)` and before `append(SESSION_FAILED, ...)`; the student-focused event uses the dynamics even though the session is failed.
- `cancel`: clear `dialogue`, `dialogueAnswerId`, `dialogueAwaitingSinceMs` without events (REST cancel does not emit events).

- [ ] **Step 1: Write the failing tests**

Add:
```java
@Test
void endDialogueWithMatchingIdEmitsTeacherReasonThenStateChange() {
    Fixture f = dialogueFixture();
    UUID dialogueId = f.activeDialogueId(); // parse from dialogue.started
    f.actor().handle(new SessionCommand.EndDialogue("end-1", dialogueId));

    assertThat(f.context().after(0)).extracting(SessionEvent::type)
            .containsSubsequence("dialogue.ended", "student.state_changed",
                    "classroom.state_changed");
    assertThat(f.lastEvent("dialogue.ended").payload().get("reason")).isEqualTo("teacher");
    assertThat(f.classroom().qaPhase()).isEqualTo(QaPhase.WAITING_HANDS);
}

@Test
void endDialogueWithStaleIdIsRejectedWithoutMutation() {
    Fixture f = dialogueFixture();
    int before = f.context().after(0).size();
    CommandResult result = f.actor().handle(
            new SessionCommand.EndDialogue("end-2", UUID.randomUUID()));
    assertThat(result.ok()).isFalse();
    assertThat(result.errorCode()).isEqualTo(ErrorCode.SESSION_STATE_INVALID);
    assertThat(f.context().after(0)).hasSize(before);
    assertThat(f.classroom().qaPhase()).isEqualTo(QaPhase.DIALOGUE);
}

@Test
void endQaWhileInDialogueEmitsDialogueEndedFirst() {
    Fixture f = dialogueFixture();
    f.actor().handle(new SessionCommand.EndQa("qa-1"));
    assertThat(f.context().after(0)).extracting(SessionEvent::type)
            .containsSubsequence("dialogue.ended", "student.state_changed",
                    "classroom.qa_ended", "classroom.state_changed");
}

@Test
void askClassWhileInDialogueRestartsAfterDialogueEnded() {
    Fixture f = dialogueFixture();
    f.actor().handle(new SessionCommand.AskClass("ask-1"));
    assertThat(f.context().after(0)).extracting(SessionEvent::type)
            .containsSubsequence("dialogue.ended", "student.state_changed",
                    "classroom.question_asked", "classroom.state_changed");
}

@Test
void endSessionWhileInDialogueEmitsDialogueEndedBeforeSessionEnded() {
    Fixture f = dialogueFixture();
    f.actor().handle(new SessionCommand.EndSession("end-3"));
    assertThat(f.context().after(0)).extracting(SessionEvent::type)
            .containsSubsequence("dialogue.ended", "student.state_changed",
                    "session.ended");
}

@Test
void failWhileInDialogueEmitsDialogueEndedBeforeSessionFailed() {
    Fixture f = dialogueFixture();
    f.actor().fail(FailureReason.GRACE_TIMEOUT);
    assertThat(f.context().after(0)).extracting(SessionEvent::type)
            .containsSubsequence("dialogue.ended", "student.state_changed",
                    "session.failed");
}
```
`dialogueFixture()` = start + call_on with `MutableRandom(0.0)`; `activeDialogueId()` parses `dialogue.started`.

- [ ] **Step 2: Run RED**

Run: `... --tests '*SessionActorDialogueTest' --no-daemon ...`
Expected: new tests fail (no cleanup/stale-id rejection).

- [ ] **Step 3: Implement Task 5 behavior**

Keep event ordering exactly as specified. The `endActiveDialogue` events are already appended/pushed; add them to the returned `CommandResult.events()` so existing tests/handlers see the same list. Do not append twice.

- [ ] **Step 4: Run GREEN**

Run: `... --tests '*SessionActorDialogueTest' --tests '*SessionActorTest' --tests '*ClassroomStateMachineTest' --no-daemon ...`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git commit --only -m "[M3B] 对话终止路径与 dialogue_id 校验" \
  server/src/main/java/com/shijiangtai/m3classroom/application/SessionActor.java \
  server/src/test/java/com/shijiangtai/m3classroom/application/SessionActorDialogueTest.java
```

---

### Task 6: Perception-driven integration, docs, evidence, full verification

**Files:**
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/application/SessionRuntimeRegistry.java` — add a `Supplier<RandomGenerator>` test seam; production constructor delegates to `Random::new`.
- Modify: `server/src/test/java/com/shijiangtai/m3classroom/ws/PerceptionWebSocketIT.java` — add the dialogue flow IT.
- Create: `server/src/test/java/com/shijiangtai/m3classroom/support/MutableRandom.java` (if not already in Task 3).
- Modify: `docs/design/05A-M3-课堂状态机与WebSocket.md` — add the multi-turn event order, boundary rule, max rounds, termination reasons, and the `02` stale-description note.
- Modify: `docs/implementation/04-M3-实现设计.md` — add the actor/dialogue methods and `ClassroomStateMachine.endDialogue(trigger)`.
- Create: `docs/team/evidence/2026-11-05-m3b-multiturn-dialogue.md`.
- Modify: `docs/team/02-进度看板.md` — only M3B row blocker + one §5 row; no G2–G8 changes.

Behavior to verify in the IT:
- `@TestConfiguration` provides a `@Primary SessionRuntimeRegistry` built with a shared `MutableRandom` (0.0 initially) so the follow-up roll is deterministic.
- Create/publish a free task, create and start a session; call `teacher.call_on` through the actor (or control WS) to enter `qa.dialogue`.
- Request a perception ticket, connect, send `audio.start`; fake ASR emits a teacher final whose `begin_ms` is after the follow-up boundary; assert a second `student.follow_up` (round 2) and `dialogue` phase.
- Set the mutable random to 0.99; emit another teacher final after the new boundary; assert `dialogue.ended(no_follow_up)` and `qa.waiting_hands`.
- Assert the session stays `in_progress` and the control WS stays connected.

Docs evidence: scope, TDD RED/GREEN, test counts, IT transcript, deferred items, file list.

- [ ] **Step 1: Add the registry random seam**

Add a public constructor overload:
`SessionRuntimeRegistry(..., Supplier<RandomGenerator> randomFactory)`; the existing Spring constructor delegates with `Random::new`. Use `randomFactory.get()` in `buildActor`. Annotate the existing production constructor with `@Autowired` because the class then has two public constructors. No behavior change in production.

- [ ] **Step 2: Write the failing IT**

Add `teacherVoiceResponseDrivesFollowUpRoundsAndEndsWithoutFollowUp` to `PerceptionWebSocketIT` with the test registry. Run:
`docker compose --profile test run --rm test gradle test --tests '*PerceptionWebSocketIT' --no-daemon --console=plain`
Expected: fail (no second follow-up / no dialogue.ended) until the wiring is complete.

- [ ] **Step 3: Finish the IT wiring and run GREEN**

Run the same command. Expected: all IT tests pass.

- [ ] **Step 4: Update docs and evidence**

05A: add the §5.3 sequences, the `dialogueAwaitingSinceMs` rule, the `no_follow_up`/`max_rounds` triggers, and the note that 02's `teacher.end_dialogue` description is stale (Foundation-owned).
Implementation 04: add the actor methods and the `endDialogue(String, Instant)` overload.
Evidence file: fill in the RED/GREEN evidence and counts.
Progress board: update only the M3B row blocker and add one §5 row with the evidence link; leave completion at 10%.

- [ ] **Step 5: Run the full suite**

Run: `docker compose --profile test run --rm test gradle clean test --no-daemon --console=plain`
Expected: `BUILD SUCCESSFUL`; record the exact class/test count (previous baseline 66/490; this slice adds tests).

- [ ] **Step 6: Commit**

```bash
git commit --only -m "[M3B] 多轮对话集成、契约与证据" \
  server/src/main/java/com/shijiangtai/m3classroom/application/SessionRuntimeRegistry.java \
  server/src/test/java/com/shijiangtai/m3classroom/ws/PerceptionWebSocketIT.java \
  server/src/test/java/com/shijiangtai/m3classroom/support/MutableRandom.java \
  docs/design/05A-M3-课堂状态机与WebSocket.md \
  docs/implementation/04-M3-实现设计.md \
  docs/team/evidence/2026-11-05-m3b-multiturn-dialogue.md \
  docs/team/02-进度看板.md
```

---

## Self-Review

- **Spec coverage:** §2.1 initial roll → Task 3; §2.2 teacher-final rounds → Task 4; §2.3/§5.3 termination order → Task 5; §2.4 `dialogue_id` validation → Task 5; §2.5 content fallback → Task 3/4 via `questionOrFallback`; §2.6 classification guard → Task 4/5 (`onTeacherSpeechClassified` only lecturing — add the guard in Task 4 if not already); §8 tests → Tasks 1–6.
- **Type consistency:** `applyFollowUp` returns `List<ClassroomEvent>` in Task 2 and is consumed with that type in Task 3; `endDialogue(String, Instant)` defined in Task 1 and used in Tasks 4/5; `dialogueAwaitingSinceMs` is a `long` throughout; `dialogueAnswerId` is `UUID`.
- **Review Focus:** boundary → Task 4 tests; cap → Task 4; termination order → Task 5; stale id → Task 5; content fallback → Task 3/4 (template path exercised by `FakeContentClient.failing()` in the dialogue test); multi-final → Task 4 boundary test.
- **Proportion:** test snippets carry assertions; production bodies are signatures/ordering rules, not full transcripts.

### Note on the classification guard

`onTeacherSpeechClassified` currently calls `classroom.onTeacherUtterance` for any in-progress classroom final. Task 4 must add:
```java
if (classroom.macroState() != ClassroomMacroState.LECTURING) {
    return;
}
```
before calling the domain method, so dialogues (and QA) do not emit `classroom.question_suggested`. Add a test: after entering dialogue, call `onTeacherSpeechClassified(0.9)` and assert no `question_asked`/`question_suggested`.
