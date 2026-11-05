# M3B Async Persistence (M3-T16) and T19 Acceptance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `SessionActor` enqueue events and texts into a per-session ordered persistence writer, batch-write them asynchronously, force a synchronous flush before terminal events, and mark the session failed when a terminal flush cannot be persisted; complete M3-T19 and the known multi-turn dialogue test gaps.

**Architecture:** A new application port `ContextPersistenceWriter` is used by `SessionActor` for every write and for `forceFlush`. The production implementation `JpaContextPersistenceWriter` keeps one ordered queue per session, flushes on 1 s / 100 items / 64 KB, retries normal failures, and flushes synchronously for `session.ended` / `interview.ended` / `session.failed`. A `SessionBatchStore` writes events + texts in one transaction. Existing constructors keep working by delegating to `DirectContextPersistenceWriter` (synchronous), so existing unit tests and IT fixtures do not need to change.

**Tech Stack:** Java 21, Spring Boot 4.1.2, Spring Data JPA, JdbcTemplate, JUnit 5, AssertJ, PostgreSQL 18, Docker Compose test service.

**Spec:** `docs/superpowers/specs/2026-11-05-m3b-persistence-t19-design.md`

## Global Constraints

- Java 21; run tests through Compose:
  `docker compose --profile test run --rm test gradle test --tests '*<Class>' --no-daemon --console=plain`
  Full suite: `docker compose --profile test run --rm test gradle clean test --no-daemon --console=plain`.
- Domain (`m3classroom/domain/**`, `shared/domain/**`) must not import Spring, Jackson, JPA, HTTP, `api`, `application`, or `infrastructure`.
- Application must not import `api`/`infrastructure`; API must not import `infrastructure`.
- Events are append-only; `seq` monotonic; event order is client-visible and must match the spec.
- No new dependencies; do not edit `build.gradle.kts`, `application*.yml`, `docs/design/02`, `docs/design/12`, or M3C files.
- Do not change WS event payload shapes or types.
- Commit after every task with prefix `[M3B]`; **use `git commit --only <explicit paths>`** — the index contains unrelated staged deletions and a plain `git commit` would sweep them in. Never commit the plan/ledger unless explicitly listed.
- Do not change G2–G8 marks or module percentages on the progress board; only the M3B test row and §5 records.
- Every commit message ends with:
  `Co-Authored-By: Claude Code <noreply@anthropic.com>`

## Review Focus

1. **Write ordering:** events and texts enter one per-session queue in actor order; a failure requeues the whole batch at the front, preserving order.
2. **Terminal flush:** `session.ended` / `interview.ended` are flushed before they are pushed; a failed flush transitions the session to failed and the command returns `SESSION_FAILED`.
3. **Cleanup on failure:** `fail()` must still cancel timers and notify the registry when the terminal flush throws; the exception must reach the WS handler so it closes 4002.
4. **Active text context:** a teacher final that is still in the writer queue must still appear in the next content request (`recentTeacherTexts` reads `SessionContext`, not the DB).
5. **Existing behavior:** all current 512 tests keep passing; `DirectContextPersistenceWriter` preserves the old synchronous semantics for the old constructors.
6. **No transaction leak:** async writer flushes run outside the request transaction; critical flush inside the actor must not deadlock with the writer's per-queue lock.
7. **Bounded queue:** 10 000-item cap; overflow forces a synchronous flush and then throws rather than growing without bound.

---

## File Structure

Production (create):

- `server/src/main/java/com/shijiangtai/m3classroom/application/port/ContextPersistenceWriter.java`
- `server/src/main/java/com/shijiangtai/m3classroom/application/port/SessionBatchStore.java`
- `server/src/main/java/com/shijiangtai/m3classroom/application/DirectContextPersistenceWriter.java`
- `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/JpaSessionBatchStore.java`
- `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/JpaContextPersistenceWriter.java`

Production (modify):

- `server/src/main/java/com/shijiangtai/m3classroom/application/SessionActor.java`
- `server/src/main/java/com/shijiangtai/m3classroom/application/SessionRuntimeRegistry.java`
- `server/src/main/java/com/shijiangtai/m3classroom/application/SessionApplicationServiceImpl.java`
- `server/src/main/java/com/shijiangtai/m3classroom/domain/session/SessionContext.java`
- `server/src/main/java/com/shijiangtai/m3classroom/application/port/SessionEventStore.java`
- `server/src/main/java/com/shijiangtai/m3classroom/application/port/SessionTextStore.java`
- `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/SessionEventStoreAdapter.java`
- `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/SessionTextStoreAdapter.java`

Tests (create):

- `server/src/test/java/com/shijiangtai/m3classroom/application/SessionActorPersistenceTest.java`
- `server/src/test/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/JpaContextPersistenceWriterTest.java`
- `server/src/test/java/com/shijiangtai/m3classroom/persistence/ContextPersistenceWriterIT.java`
- `server/src/test/java/com/shijiangtai/m3classroom/support/BufferingContextPersistenceWriter.java`

Tests (modify):

- `server/src/test/java/com/shijiangtai/m3classroom/application/SessionReconnectTest.java`
- `server/src/test/java/com/shijiangtai/m3classroom/ws/ClassroomWebSocketIT.java`
- `server/src/test/java/com/shijiangtai/m3classroom/application/SessionActorDialogueTest.java`

Docs:

- `docs/design/05A-M3-课堂状态机与WebSocket.md`
- `docs/implementation/04-M3-实现设计.md`
- `docs/team/evidence/2026-11-05-m3b-persistence-t19.md`
- `docs/team/02-进度看板.md`

---

### Task 0: Commit the design and this plan

**Files:**
- Add: `docs/superpowers/specs/2026-11-05-m3b-persistence-t19-design.md`
- Add: `docs/superpowers/plans/2026-11-05-m3b-persistence-t19.md`

- [ ] **Step 1: Commit only the two docs**

```bash
git commit --only -m "[M3B] 异步持久化与 T19 验收设计、计划

Co-Authored-By: Claude Code <noreply@anthropic.com>" \
  docs/superpowers/specs/2026-11-05-m3b-persistence-t19-design.md \
  docs/superpowers/plans/2026-11-05-m3b-persistence-t19.md
```

Expected: `git show --stat HEAD` lists exactly those two files; the unrelated staged deletions remain in the index.

---

### Task 1: Writer port, direct writer, in-memory text stream, actor write-path refactor

**Files:**
- Add: `server/src/main/java/com/shijiangtai/m3classroom/application/port/ContextPersistenceWriter.java`
- Add: `server/src/main/java/com/shijiangtai/m3classroom/application/DirectContextPersistenceWriter.java`
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/domain/session/SessionContext.java`
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/application/SessionActor.java`
- Test: `server/src/test/java/com/shijiangtai/m3classroom/application/SessionActorPersistenceTest.java`

**Interfaces:**

```java
public interface ContextPersistenceWriter {
    void appendEvent(SessionEvent event);
    void saveText(SessionText text);
    /** Drains all pending writes for the session; throws PersistenceFlushException on failure. */
    void flush(SessionId sessionId);
    default void close(SessionId sessionId) { }
}
```

`DirectContextPersistenceWriter` delegates `appendEvent` to `SessionEventStore.append`,
`saveText` to `SessionTextStore.save`, and `flush` / `close` are no-ops.

- [ ] **Step 1: Write the failing actor persistence test**

Create `SessionActorPersistenceTest` with a small fixture copied from
`SessionActorTest` (same task snapshot / virtual students) but `BufferingContextPersistenceWriter`:

```java
final class BufferingContextPersistenceWriter implements ContextPersistenceWriter {
    private final SessionEventStore events;
    private final SessionTextStore texts;
    private final List<SessionEvent> pendingEvents = new ArrayList<>();
    private final List<SessionText> pendingTexts = new ArrayList<>();
    private boolean failFlush;
    private int flushCalls;

    @Override public void appendEvent(SessionEvent event) { pendingEvents.add(event); }
    @Override public void saveText(SessionText text) { pendingTexts.add(text); }

    @Override public synchronized void flush(SessionId sessionId) {
        flushCalls++;
        if (failFlush) {
            throw new PersistenceFlushException(sessionId, "flush failed");
        }
        pendingTexts.forEach(texts::save);
        pendingEvents.forEach(events::append);
        pendingTexts.clear();
        pendingEvents.clear();
    }

    @Override public void close(SessionId sessionId) { flush(sessionId); }
}
```

Add `PersistenceFlushException` to `application.port`:

```java
public final class PersistenceFlushException extends RuntimeException {
    private final SessionId sessionId;
    public PersistenceFlushException(SessionId sessionId, String message) {
        super(message);
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
    }
    public SessionId sessionId() { return sessionId; }
}
```

Test 1 (RED before the actor change):

```java
@Test
void teacherFinalStillReachesNextContentRequestBeforeFlush() {
    Fixture fixture = fixture(new BufferingContextPersistenceWriter(...));
    start(fixture);
    fixture.actor().recordSpeechFinal("teacher", null, "请解释一下这一段。",
            null, null, null);
    fixture.actor().handle(new SessionCommand.AskClass("ask-1"));
    // teacher final is still buffered (writer not flushed), but content request sees it
    assertThat(fixture.client().questionRequests().get(0).recentTeacherTexts())
            .contains("请解释一下这一段。");
}
```

Add another test asserting `recordSpeechFinal` + `AskClass` does **not** call flush:

```java
@Test
void nonTerminalCommandsDoNotForceFlush() {
    ...
    assertThat(writer.flushCalls()).isZero();
}
```

- [ ] **Step 2: Run the test and verify it fails**

```bash
docker compose --profile test run --rm test \
  gradle test --tests '*SessionActorPersistenceTest' --no-daemon --console=plain
```

Expected: compile error (no writer-aware constructor) or assertion failure because
`recentTeacherTexts` reads the empty DB store.

- [ ] **Step 3: Implement the port, direct writer, and context text stream**

`SessionContext`:

```java
private final List<SessionText> texts = new ArrayList<>();

public void addText(SessionText text) {
    texts.add(Objects.requireNonNull(text, "text"));
}

public List<SessionText> texts(String speakerType) {
    return texts.stream()
            .filter(text -> speakerType == null || speakerType.equals(text.speakerType()))
            .toList();
}
```

`SessionActor`:
- Add `private final ContextPersistenceWriter persistence;`
- New constructor:

```java
public SessionActor(
        Session session, TaskSnapshot taskSnapshot, SessionContext context,
        ClassroomStateMachine classroom, VirtualStudentDynamics students,
        InterviewStateMachine interview, ContentGenerationService content,
        ContextPersistenceWriter persistence, IdGenerator ids, ClockProvider clock,
        SchedulerPort scheduler, RandomGenerator random) { ... }
```

- Keep the old constructor:

```java
public SessionActor(
        Session session, TaskSnapshot taskSnapshot, SessionContext context,
        ClassroomStateMachine classroom, VirtualStudentDynamics students,
        InterviewStateMachine interview, ContentGenerationService content,
        SessionEventStore eventStore, SessionTextStore textStore, IdGenerator ids,
        ClockProvider clock, SchedulerPort scheduler, RandomGenerator random) {
    this(session, taskSnapshot, context, classroom, students, interview, content,
            new DirectContextPersistenceWriter(eventStore, textStore), ids, clock,
            scheduler, random);
}
```

- Replace `eventStore.append(event)` with `persistence.appendEvent(event)`.
- Add:

```java
private void persistText(SessionText text) {
    context.addText(text);
    persistence.saveText(text);
}
```

- Replace all five `textStore.save(new SessionText(...))` calls with
  `persistText(new SessionText(...))`.
- Replace `recentTeacherTexts` body:

```java
List<SessionText> teacherTexts = context.texts("teacher");
```

- Replace `forceFlush` body:

```java
public synchronized void forceFlush() {
    persistence.flush(session.id());
}
```

- Add:

```java
public synchronized long lastSeq() {
    return context.lastSeq();
}
```

- Remove the `eventStore` / `textStore` fields (the old constructor no longer assigns them).

- [ ] **Step 4: Run the new test and the existing actor tests**

```bash
docker compose --profile test run --rm test \
  gradle test --tests '*SessionActorPersistenceTest' \
  --tests '*SessionActorTest' --tests '*SessionActorDialogueTest' \
  --tests '*SessionActorPerceptionTest' --no-daemon --console=plain
```

Expected: all pass; `SessionActorTest` etc. still use the old constructor and remain green.

- [ ] **Step 5: Commit**

```bash
git commit --only -m "[M3B] 引入持久化 writer 端口与内存文本流

Co-Authored-By: Claude Code <noreply@anthropic.com>" \
  server/src/main/java/com/shijiangtai/m3classroom/application/port/ContextPersistenceWriter.java \
  server/src/main/java/com/shijiangtai/m3classroom/application/port/PersistenceFlushException.java \
  server/src/main/java/com/shijiangtai/m3classroom/application/DirectContextPersistenceWriter.java \
  server/src/main/java/com/shijiangtai/m3classroom/domain/session/SessionContext.java \
  server/src/main/java/com/shijiangtai/m3classroom/application/SessionActor.java \
  server/src/test/java/com/shijiangtai/m3classroom/application/SessionActorPersistenceTest.java \
  server/src/test/java/com/shijiangtai/m3classroom/support/BufferingContextPersistenceWriter.java
```

---

### Task 2: Terminal critical flush, failure-to-failed, cleanup semantics

**Files:**
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/application/SessionActor.java`
- Test: `server/src/test/java/com/shijiangtai/m3classroom/application/SessionActorPersistenceTest.java`

- [ ] **Step 1: Write failing tests**

Add to `SessionActorPersistenceTest`:

```java
@Test
void endSessionFlushesBeforePushingEndedAndReturnsOk() {
    // start, then teacher.end_session
    assertThat(writer.flushCalls()).isGreaterThanOrEqualTo(1);
    assertThat(fixture.session().status()).isEqualTo(SessionStatus.ENDED);
    assertThat(eventStore.all()).anyMatch(e -> e.type().equals("session.ended"));
    assertThat(pushed).anyMatch(e -> e.type().equals("session.ended"));
}

@Test
void endSessionFlushFailureMarksFailedAndDoesNotPushEnded() {
    writer.failFlush = true;
    CommandResult result = fixture.actor().handle(
            new SessionCommand.EndSession("end-1"));
    assertThat(result.ok()).isFalse();
    assertThat(result.errorCode()).isEqualTo(ErrorCode.SESSION_FAILED);
    assertThat(fixture.session().status()).isEqualTo(SessionStatus.FAILED);
    assertThat(eventsOfType(fixture, "session.failed")).hasSize(1);
    assertThat(pushed).noneMatch(e -> e.type().equals("session.ended"));
}

@Test
void failFlushFailureStillCancelsTimersAndNotifiesRegistry() {
    writer.failFlush = true;
    assertThatThrownBy(() -> fixture.actor().fail(FailureReason.INTERNAL))
            .isInstanceOf(PersistenceFlushException.class);
    assertThat(fixture.session().status()).isEqualTo(SessionStatus.FAILED);
    assertThat(fixture.scheduler().pendingTasks()).isZero();
}
```

For the push assertions, attach a sink that records events:
`fixture.actor().attachEventSink(pushed::add)`.

- [ ] **Step 2: Run and verify RED**

```bash
docker compose --profile test run --rm test \
  gradle test --tests '*SessionActorPersistenceTest' --no-daemon --console=plain
```

Expected: end-session cases fail because `session.ended` is appended with the normal
`append` (pushed before flush), and flush failure does not mark failed.

- [ ] **Step 3: Implement**

Add:

```java
private SessionEvent appendCritical(String type, Map<String, Object> payload, Instant now) {
    SessionEvent event = context.append(SessionEventDraft.of(type, payload), atMs(now));
    persistence.appendEvent(event);
    persistence.flush(session.id());
    push(event);
    return event;
}
```

`endClassroomSession`:

```java
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
```

`endInterview` mirrors this. `handleEndSession` / `handleEndInterview`:

```java
List<SessionEvent> events = endClassroomSession(clock.now(), END_REASON_MANUAL);
if (session.status() == SessionStatus.FAILED) {
    return CommandResult.error(ErrorCode.SESSION_FAILED, "会话持久化失败");
}
return CommandResult.ok(events);
```

`persistenceFailed`:

```java
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
```

`fail` restructure:

```java
RuntimeException flushFailure = null;
try {
    endActiveDialogue("session_ended", now);
    emitHandLowers("session_failed", now);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("reason", reason.wireValue());
    append(SESSION_FAILED, payload, now);
} finally {
    try {
        forceFlush();
    } catch (RuntimeException exception) {
        flushFailure = exception;
        LOG.error("Failed to flush session.failed for {}", session.id().value(), exception);
    }
    cancelAllTasks();
    notifyStatusChanged();
}
if (flushFailure != null) {
    throw flushFailure;
}
```

`cancel`:

```java
try {
    forceFlush();
} catch (RuntimeException exception) {
    LOG.warn("Failed to flush cancelled session {}", session.id().value(), exception);
}
```

- [ ] **Step 4: Run tests**

Run `SessionActorPersistenceTest`, `SessionActorTest`, `SessionReconnectTest`.
Expected green. `failCleanupRunsWhenSessionFailedPersistThrows` must still pass.

- [ ] **Step 5: Commit**

```bash
git commit --only -m "[M3B] 终态事件强制 flush 与持久化失败转 failed

Co-Authored-By: Claude Code <noreply@anthropic.com>" \
  server/src/main/java/com/shijiangtai/m3classroom/application/SessionActor.java \
  server/src/test/java/com/shijiangtai/m3classroom/application/SessionActorPersistenceTest.java
```

---

### Task 3: Async writer, batch store, adapters, wiring

**Files:**
- Add: `server/src/main/java/com/shijiangtai/m3classroom/application/port/SessionBatchStore.java`
- Add: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/JpaSessionBatchStore.java`
- Add: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/JpaContextPersistenceWriter.java`
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/application/port/SessionEventStore.java`
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/application/port/SessionTextStore.java`
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/SessionEventStoreAdapter.java`
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/SessionTextStoreAdapter.java`
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/application/SessionRuntimeRegistry.java`
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/application/SessionApplicationServiceImpl.java`
- Test: `server/src/test/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/JpaContextPersistenceWriterTest.java`
- Test: `server/src/test/java/com/shijiangtai/m3classroom/persistence/ContextPersistenceWriterIT.java`

**Ports:**

```java
public interface SessionBatchStore {
    void write(List<SessionEvent> events, List<SessionText> texts);
}

default void appendAll(List<SessionEvent> events) { events.forEach(this::append); } // SessionEventStore
default void saveAll(List<SessionText> texts) { texts.forEach(this::save); }        // SessionTextStore
```

- [ ] **Step 1: Write the failing writer unit test**

Create `JpaContextPersistenceWriterTest`:

```java
class JpaContextPersistenceWriterTest {
    private final RecordingBatchStore store = new RecordingBatchStore();
    private final JpaContextPersistenceWriter writer =
            new JpaContextPersistenceWriter(store, Runnable::run);

    @Test
    void flushWritesBufferedEventsAndTexts() {
        SessionId id = new SessionId(UUID.randomUUID());
        writer.appendEvent(event(id, 1));
        writer.saveText(text(id));
        assertThat(store.writes()).isEmpty();

        writer.flush(id);

        assertThat(store.writes()).hasSize(1);
        assertThat(store.writes().get(0).events()).extracting(SessionEvent::seq)
                .containsExactly(1L);
        assertThat(store.writes().get(0).texts()).hasSize(1);
    }

    @Test
    void flushFailureRetriesThenThrowsAndKeepsPending() {
        SessionId id = ...;
        store.failNext(3);
        writer.appendEvent(event(id, 1));

        assertThatThrownBy(() -> writer.flush(id))
                .isInstanceOf(PersistenceFlushException.class);

        store.resetFailure();
        writer.flush(id);
        assertThat(store.allEvents()).extracting(SessionEvent::seq).containsExactly(1L);
    }

    @Test
    void hundredItemsTriggerAsyncFlush() {
        ...
        for (int i = 1; i <= 100; i++) writer.appendEvent(event(id, i));
        assertThat(store.writes()).isNotEmpty();
    }

    @Test
    void asyncFailureKeepsBatchForNextFlush() {
        store.failNext(3);
        writer.appendEvent(event(id, 1));
        // inline executor ran the size-triggered flush and failed
        assertThat(store.allEvents()).isEmpty();
        store.resetFailure();
        writer.flush(id);
        assertThat(store.allEvents()).hasSize(1);
    }

    @Test
    void closeFlushesAndForgetsQueue() {
        writer.appendEvent(event(id, 1));
        writer.close(id);
        assertThat(store.allEvents()).hasSize(1);
    }
}
```

Use a package-private constructor `JpaContextPersistenceWriter(SessionBatchStore, Executor)`
that does not start a scheduler. The 100-item test uses `Runnable::run` so the threshold
flush is synchronous.

- [ ] **Step 2: Run and verify RED**

```bash
docker compose --profile test run --rm test \
  gradle test --tests '*JpaContextPersistenceWriterTest' --no-daemon --console=plain
```

Expected: compile error / missing class.

- [ ] **Step 3: Implement the batch adapters and writer**

`SessionEventStoreAdapter.appendAll` (refactor `append` to call it):

```java
@Override
public void appendAll(List<SessionEvent> events) {
    if (events.isEmpty()) return;
    List<String> payloads = events.stream()
            .map(event -> jsonCodec.writeEventPayload(event.payload()))
            .toList();
    jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
        @Override public void setValues(PreparedStatement statement, int index) throws SQLException {
            SessionEvent event = events.get(index);
            statement.setObject(1, event.eventId());
            statement.setObject(2, event.sessionId().value());
            statement.setLong(3, event.seq());
            statement.setString(4, event.type());
            statement.setLong(5, event.atMs());
            statement.setString(6, payloads.get(index));
            statement.setObject(7, OffsetDateTime.ofInstant(event.createdAt(), ZoneOffset.UTC));
        }
        @Override public int getBatchSize() { return events.size(); }
    });
}
```

`SessionTextStoreAdapter.saveAll`:

```java
@Override
public void saveAll(List<SessionText> texts) {
    if (texts.isEmpty()) return;
    repository.saveAll(texts.stream().map(this::toEntity).toList());
}
```

`JpaSessionBatchStore`:

```java
@Repository
public class JpaSessionBatchStore implements SessionBatchStore {
    private final SessionEventStore events;
    private final SessionTextStore texts;

    @Override
    @Transactional
    public void write(List<SessionEvent> events, List<SessionText> texts) {
        if (!texts.isEmpty()) this.texts.saveAll(texts);
        if (!events.isEmpty()) this.events.appendAll(events);
    }
}
```

`JpaContextPersistenceWriter`:
- package `infrastructure.persistence.jpa`, `@Repository`.
- Fields: `SessionBatchStore store`, `Executor flushExecutor`, `ScheduledExecutorService
  scheduler`, `ConcurrentHashMap<SessionId, SessionQueue> queues`.
- Production `@Autowired` constructor creates a fixed daemon pool and a single daemon
  scheduler; `@PostConstruct` schedules `flushAll` every 1 s; `@PreDestroy` flushes all
  best-effort and shuts down.
- Package-private test constructor `(SessionBatchStore, Executor)` uses no scheduler.
- `SessionQueue`: `ArrayDeque<Item> pending`, `ReentrantLock flushLock`, `int bytes`.
  `Item` is a sealed interface or a small record with `event`/`text`.
- `appendEvent` / `saveText`: enqueue under `synchronized (queue)`; if
  `pending.size() >= 100 || bytes >= 64 * 1024` → `flushAsync(id)`.
  If `pending.size() >= 10_000` → `flush(id)` synchronously; if still full →
  `throw new PersistenceFlushException(id, "持久化队列已满")`.
- `flush(id)`:
  ```java
  SessionQueue queue = queues.get(id);
  if (queue == null) return;
  queue.flushLock.lock();
  try {
      boolean first = true;
      while (true) {
          List<Item> batch = drain(queue);
          if (batch.isEmpty()) return;
          writeWithRetry(id, batch, true);
      }
  } finally {
      queue.flushLock.unlock();
  }
  ```
  `writeWithRetry` for force flush: 3 attempts (50/100/200 ms sleep); on final failure
  re-add the batch at the front and throw.
- `flushAsync(id)`: submit to `flushExecutor` a task that calls `flush(id)` but catches
  `PersistenceFlushException`, logs, and leaves the batch requeued.
  Important: `flush(id)` re-adds on failure, so async retry works.
- `flushAll()`: for each session id with pending items, `flushAsync(id)`.
- `close(id)`: best-effort `flush(id)` (catch/log), then `queues.remove(id)`.
- `drain` must cap a batch at, say, 500 items to keep transactions bounded; the loop
  continues until empty.

Wire the registry:
- Production `@Autowired` constructor takes `ContextPersistenceWriter persistence` and
  passes it to `buildActor` → new actor constructor.
- Existing 8-arg and 9-arg constructors delegate with
  `new DirectContextPersistenceWriter(eventStore, textStore)`.
- New full constructor:
  ```java
  public SessionRuntimeRegistry(..., ContentClient contentClient,
          ContextPersistenceWriter persistence, Supplier<RandomGenerator> randomFactory)
  ```

`SessionApplicationServiceImpl.toView`:

```java
long lastSeq = runtimeRegistry.find(session.id())
        .map(SessionActor::lastSeq)
        .orElseGet(() -> eventStore.maxSeq(session.id()));
```

- [ ] **Step 4: Write the Postgres IT**

`ContextPersistenceWriterIT extends PostgresIntegrationTest`:
- Autowire `JdbcTemplate`, `ContextPersistenceWriter`, `SessionEventStore`,
  `SessionTextStore`.
- Insert a minimal task + in-progress session using `TestFixtures`:
  ```sql
  INSERT INTO m3_classroom.tasks
    (id, course_id, mode, topic, duration_recommended_seconds,
     duration_max_seconds, created_by)
  VALUES (?, ?, 'free', '持久化测试', 60, 120, ?);
  INSERT INTO m3_classroom.sessions
    (id, session_type, task_id, course_id, student_account_id, mode, status)
  VALUES (?, 'task', ?, ?, ?, 'free', 'in_progress');
  ```
- `manualFlushPersistsEventAndText`: append event seq 1 + text; `writer.flush(id)`;
  assert `eventStore.after(id, 0, 10)` and `textStore.bySession(id, "teacher")`.
- `scheduledFlushPersistsWithoutManualCall`: append event seq 2; poll up to 5 s until
  `eventStore.after` contains it.

- [ ] **Step 5: Run tests**

```bash
docker compose --profile test run --rm test \
  gradle test --tests '*JpaContextPersistenceWriterTest' \
  --tests '*ContextPersistenceWriterIT' --tests '*SessionActorTest' \
  --tests '*ClassroomWebSocketIT' --no-daemon --console=plain
```

Expected green. If the async writer makes an existing IT fail because a DB read happens
before the 1 s flush, update that IT to poll (do not add a manual flush to production
code just for tests).

- [ ] **Step 6: Commit**

```bash
git commit --only -m "[M3B] 异步批量持久化 writer 与注册表接线

Co-Authored-By: Claude Code <noreply@anthropic.com>" \
  server/src/main/java/com/shijiangtai/m3classroom/application/port/SessionBatchStore.java \
  server/src/main/java/com/shijiangtai/m3classroom/application/port/SessionEventStore.java \
  server/src/main/java/com/shijiangtai/m3classroom/application/port/SessionTextStore.java \
  server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/JpaSessionBatchStore.java \
  server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/JpaContextPersistenceWriter.java \
  server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/SessionEventStoreAdapter.java \
  server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/SessionTextStoreAdapter.java \
  server/src/main/java/com/shijiangtai/m3classroom/application/SessionRuntimeRegistry.java \
  server/src/main/java/com/shijiangtai/m3classroom/application/SessionApplicationServiceImpl.java \
  server/src/test/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/JpaContextPersistenceWriterTest.java \
  server/src/test/java/com/shijiangtai/m3classroom/persistence/ContextPersistenceWriterIT.java
```

---

### Task 4: M3-T19 acceptance tests

**Files:**
- Modify: `server/src/test/java/com/shijiangtai/m3classroom/application/SessionReconnectTest.java`
- Modify: `server/src/test/java/com/shijiangtai/m3classroom/ws/ClassroomWebSocketIT.java`

- [ ] **Step 1: Add unit tests**

In `SessionReconnectTest`:

```java
@Test
void normalEndedSessionIsNotFailed() {
    ReconnectFixture fixture = fixture();
    start(fixture);

    CommandResult result = fixture.actor().handle(new SessionCommand.EndSession("end-1"));

    assertThat(result.ok()).isTrue();
    assertThat(fixture.session().status()).isEqualTo(SessionStatus.ENDED);
    assertThat(eventsOfType(fixture, "session.failed")).isEmpty();
    assertThat(eventsOfType(fixture, "session.ended")).hasSize(1);
    assertThat(fixture.scheduler().pendingTasks()).isZero();
}

@Test
void pageRefreshWithoutResumeFailsAfterGrace() {
    // equivalent to a refreshed page that never sends session.resume
    ReconnectFixture fixture = fixture();
    start(fixture);
    fixture.actor().onWsDisconnected(NOW);
    fixture.scheduler().advance(Duration.ofSeconds(30));

    assertThat(fixture.session().status()).isEqualTo(SessionStatus.FAILED);
    assertThat(eventsOfType(fixture, "session.failed")).hasSize(1);
    assertThat(eventsOfType(fixture, "session.failed").get(0).payload().get("reason"))
            .isEqualTo("grace_timeout");
}
```

- [ ] **Step 2: Add WS integration tests**

In `ClassroomWebSocketIT`:

```java
@Test
void resumeWithLostRecordingFailsSession() throws Exception {
    SessionId id = createSession();
    Connection connection = connect(id);
    connection.nextOfType("session.snapshot");
    startSession(connection, "start-lost");

    send(connection, "session.resume", "resume-lost",
            Map.of("last_seq", 0, "recording_state", "lost"));

    JsonNode failed = connection.nextOfType("session.failed");
    assertServerEnvelope(failed, id);
    assertThat(failed.get("payload").get("reason").asString())
            .isEqualTo("recording_lost");
    JsonNode error = connection.nextOfType("error");
    assertThat(error.get("payload").get("code").asString())
            .isEqualTo("SESSION_FAILED");
    assertThat(sessionApplicationService.get(TestFixtures.student(), id).status())
            .isEqualTo("failed");
}

@Test
void normalEndSessionDoesNotFail() throws Exception {
    SessionId id = createSession();
    Connection connection = connect(id);
    connection.nextOfType("session.snapshot");
    startSession(connection, "start-ok");

    send(connection, "teacher.end_session", "end-ok", Map.of());

    connection.nextOfType("session.ended");
    connection.nextOfType("server.ack");
    assertThat(connection.handler.countOfType("session.failed")).isZero();
    assertThat(sessionApplicationService.get(TestFixtures.student(), id).status())
            .isEqualTo("ended");
}
```

If the `session.failed` push and the error arrive in the opposite order, use
`collectUntilAck` / `nextOfType` with the actual order discovered by a failing run;
do not weaken the assertions.

- [ ] **Step 3: Run tests**

```bash
docker compose --profile test run --rm test \
  gradle test --tests '*SessionReconnectTest' \
  --tests '*ClassroomWebSocketIT' --no-daemon --console=plain
```

- [ ] **Step 4: Commit**

```bash
git commit --only -m "[M3B] 补齐 M3-T19 录制丢失、grace 超时与正常结束验收

Co-Authored-By: Claude Code <noreply@anthropic.com>" \
  server/src/test/java/com/shijiangtai/m3classroom/application/SessionReconnectTest.java \
  server/src/test/java/com/shijiangtai/m3classroom/ws/ClassroomWebSocketIT.java
```

---

### Task 5: Multi-turn dialogue test-gap closures

**Files:**
- Modify: `server/src/test/java/com/shijiangtai/m3classroom/application/SessionActorDialogueTest.java`

- [ ] **Step 1: Add tests**

```java
@Test
void nullDialogueIdOnEndDialogueIsRejectedWhileDialogueActive() {
    // start a dialogue, then send EndDialogue(commandId, null)
    // expect SESSION_STATE_INVALID; dialogue.ended still absent; qa phase still dialogue
}

@Test
void duplicateEndDialogueIsRejectedWithoutSecondEndedEvent() {
    // first EndDialogue(id) succeeds; second EndDialogue(id) errors;
    // exactly one dialogue.ended, exactly one waiting_hands transition
}

@Test
void askStudentQuestionDuringDialogueEndsDialogueBeforeQuestion() {
    // from qa.dialogue, handle AskStudentQuestion
    // event order: dialogue.ended(qa_restarted) -> student focused ->
    // classroom.question_asked; no classroom.state_changed to waiting_hands between
}

@Test
void tickTimeoutDuringDialogueEndsDialogueBeforeSessionEnded() {
    // advance the clock to max duration, call onTick
    // dialogue.ended(session_ended) precedes session.ended; one session.ended
}
```

Use the existing `lastEvent`, `eventsOfType`, `indexOfType`, and `dialogueId()`
helpers. Assert payload `reason` / `trigger` values, not just event types.

- [ ] **Step 2: Run and verify**

```bash
docker compose --profile test run --rm test \
  gradle test --tests '*SessionActorDialogueTest' --no-daemon --console=plain
```

If a test reveals a real bug (for example `ask_student_question` skips
`endActiveDialogue`), fix the production code in `SessionActor` and include the fix in
this task. Otherwise no production change.

- [ ] **Step 3: Commit**

```bash
git commit --only -m "[M3B] 补齐多轮对话终止路径测试缺口

Co-Authored-By: Claude Code <noreply@anthropic.com>" \
  server/src/test/java/com/shijiangtai/m3classroom/application/SessionActorDialogueTest.java
```

If production code changed, add `server/src/main/java/com/shijiangtai/m3classroom/application/SessionActor.java`
to the commit.

---

### Task 6: Documentation, evidence, progress board, full suite

**Files:**
- Modify: `docs/design/05A-M3-课堂状态机与WebSocket.md`
- Modify: `docs/implementation/04-M3-实现设计.md`
- Add: `docs/team/evidence/2026-11-05-m3b-persistence-t19.md`
- Modify: `docs/team/02-进度看板.md`

- [ ] **Step 1: Update 05A**

In §9.2 / §9.3:
- Mark `ContextPersistenceWriter` as implemented: per-session queue, 1 s / 100 / 64 KB,
  critical flush for `session.ended` / `interview.ended` / `session.failed`.
- State that frames remain synchronous in `PerceptionApplicationService` (read-after-write
  for OCR updates); this is a deviation from the original “events + texts + frames in one
  batch” wording.
- Add the failure rule: terminal flush failure → `session.failed`, command `SESSION_FAILED`.
- Note the in-memory teacher text stream is used for LLM context.

- [ ] **Step 2: Update implementation/04**

- §3.1: `ContextPersistenceWriter` is now implemented, not just planned.
- §5: add the batch transaction rule (texts then events) and the terminal flush rule.
- §7: add T16/T19 test points.

- [ ] **Step 3: Write the evidence file**

`docs/team/evidence/2026-11-05-m3b-persistence-t19.md` with:
- scope / authorization
- implementation summary
- RED / GREEN per task
- test counts (full suite)
- T16 / T19 mapping
- known deviations (frames synchronous, submission flush deferred)
- changed files.

- [ ] **Step 4: Update the progress board**

Only the M3B test row and a new §5 record:
- M3B test completion 80% (UT / IT / contract done; E2E and performance/security missing).
- M3B blocker: “P0 后端完成（含异步持久化）；前端、M3C、E2E/NFR 待续”.
- Do not change G2–G8 or the 10% module completion.

- [ ] **Step 5: Run the full suite**

```bash
docker compose --profile test run --rm test \
  gradle clean test --no-daemon --console=plain
```

Parse the XML counts (classes / tests / failures / errors / skipped). Update the evidence
file with the exact numbers. If the count differs from the test matrix rows, explain the
M3B-commit scope vs same-worktree M3C tests.

- [ ] **Step 6: Commit**

```bash
git commit --only -m "[M3B] 异步持久化与 T19 文档、证据、进度

Co-Authored-By: Claude Code <noreply@anthropic.com>" \
  docs/design/05A-M3-课堂状态机与WebSocket.md \
  docs/implementation/04-M3-实现设计.md \
  docs/team/evidence/2026-11-05-m3b-persistence-t19.md \
  docs/team/02-进度看板.md
```

---

## Self-Review

- **T16 spec coverage:** queue per session, 1 s / 100 / 64 KB trigger, critical flush,
  retry, failure → failed, batch transaction, bounded queue, in-memory text context,
  live `last_seq`. Frames explicitly deferred with a documented reason.
- **T19 coverage:** recording lost, grace timeout / page refresh, normal ended.
- **Existing behavior:** old constructors delegate to direct writer; no existing test
  fixture needs to change.
- **Transaction safety:** batch writes happen in `JpaSessionBatchStore`; terminal flush
  runs on the actor thread and does not hold the writer queue lock while waiting for
  another actor lock.
- **No contract changes:** WS event types / payloads and REST DTOs are unchanged.
