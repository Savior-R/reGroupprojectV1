# M3C Retention (M3-T26) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish M3-T26 by adding the scheduled retention cleanup that 05B §6 specifies: temporary task recordings expire 24 hours after `max(session end, task close)`, and submitted recordings expire one year after the course end. Cleanup deletes MinIO objects before DB rows and retries on the next run.

**Architecture:** Keep the M3C hexagonal shape. Retention candidate queries live in JPA adapters behind the existing `SessionRepository` / `SubmissionRepository` ports. `RecordingLifecycleService` owns object-first deletion; `RecordingRetentionService` orchestrates the two cleanup passes; a small infrastructure scheduler invokes it with the existing `ClockProvider`. No controller, WS, schema, or M2 writes.

**Tech Stack:** Java 21, Spring Boot 4.1.2, PostgreSQL 18/JPA, JUnit 5/AssertJ/Mockito, Docker Compose test service.

**Spec:** `docs/design/05B-M3-录制上传与回放.md` §6 (lines 147–150), `docs/design/10-测试与验收.md` §6.6 (M3-T26).

## Global Constraints

- **M3C authorization:** the user explicitly authorized the current M3 Owner to act for Media/Training Owner on these M3C retention files on 2026-11-06. Record this in the evidence file. No other module is authorized by this confirmation.
- No schema changes. Use the existing `m3_classroom.*` and `m2_course.courses` tables; do not create a migration and do not write to `ops.cleanup_jobs`.
- Do not modify `application*.yml`, `docker-compose.yml`, `ServerApplication`, the `ops` schema, M2 Java code, API/DTO/WS/event contracts, or permissions.
- Training retention (1 month) is M6 scope and must not be implemented here; the temporary query only selects `session_type = 'task'`.
- Retention math:
  - Temporary anchor = `COALESCE(ended_at, updated_at)`; task close extends it to `GREATEST(anchor, tasks.closed_at)`.
  - Temporary expiry = anchor + 24 hours, inclusive at `<= now`.
  - Submitted expiry = `courses.end_at + INTERVAL '1 year' <= now`; a null `end_at` is never cleaned.
  - Only task sessions in `ended`, `reviewing`, `failed`, or `cancelled` are temporary candidates; `submitted`, `preparing`, and `in_progress` are never candidates.
- Cleanup order: delete MinIO objects first. If any object delete throws, propagate and leave all DB rows intact so the next run retries (object deletion is idempotent).
- Submitted cleanup order: objects → submission row (cascades M4/M7 rows) → recording row → session row.
- Scheduler must not run in the `test` profile. Defaults: enabled, initial delay 1 minute, interval 1 hour. No `@EnableScheduling`.
- Test command pattern:
  `docker compose --profile test run --rm test gradle test --tests '*<Class>' --no-daemon --console=plain`
  Full suite: `docker compose --profile test run --rm test gradle test --no-daemon --console=plain`.
- Commit after every task with prefix `[M3C]`; use `git commit --only <paths>` to avoid the unrelated staged deletions in the shared workspace.

## Review Focus

1. **Boundary correctness:** 23h59 retained / 24h01 deleted; task closed 25h after end extends retention; course end + 1 calendar year inclusive; null course end never deleted.
2. **Object-first safety:** an object-store failure leaves session/recording/submission rows intact; a retry succeeds.
3. **No wrong-root deletion:** temporary cleanup never deletes a submitted session; submitted cleanup never deletes a non-submitted session.
4. **Submission cascade:** submission row is deleted before the session row so the `ON DELETE RESTRICT` FK is satisfied and M4/M7 rows cascade.
5. **Scheduler isolation:** the job is a no-op in tests and does not start threads in the `test` profile.

---

### Task 1: Retention candidate queries and repository ports

**Files:**
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/domain/session/SessionRepository.java`
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/SessionJpaRepository.java`
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/SessionRepositoryAdapter.java`
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/domain/submission/SubmissionRepository.java`
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/SubmissionJpaRepository.java`
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/SubmissionRepositoryAdapter.java`
- Modify: `server/src/test/java/com/shijiangtai/m3classroom/support/InMemorySessionRepository.java`
- Modify: `server/src/test/java/com/shijiangtai/m3classroom/support/InMemorySubmissionRepository.java`
- Create: `server/src/test/java/com/shijiangtai/m3classroom/persistence/RetentionCandidateQueryIT.java`

**Interfaces:**
- Produces:
  - `SessionRepository.findExpiredTemporary(Instant now): List<Session>` — task sessions in `ended/reviewing/failed/cancelled` whose retention expiry is `<= now`.
  - `SubmissionRepository.findExpiredForRetention(Instant now): List<Submission>` — submissions whose course `end_at + 1 year <= now`.
  - `SubmissionRepository.deleteBySession(SessionId sessionId): void` — deletes the submission row; the JPA adapter flushes so the following session delete sees the FK released.

**JPA queries (exact):**

`SessionJpaRepository`:
```java
@Query(value = """
        SELECT s.* FROM m3_classroom.sessions s
        LEFT JOIN m3_classroom.tasks t ON t.id = s.task_id
        WHERE s.session_type = 'task'
          AND s.status IN ('ended', 'reviewing', 'failed', 'cancelled')
          AND GREATEST(
                COALESCE(s.ended_at, s.updated_at),
                COALESCE(t.closed_at, s.ended_at, s.updated_at)
              ) + INTERVAL '24 hours' <= :now
        ORDER BY s.id
        """, nativeQuery = true)
List<SessionEntity> findExpiredTemporary(@Param("now") Instant now);
```

`SubmissionJpaRepository`:
```java
@Query(value = """
        SELECT sub.* FROM m3_classroom.submissions sub
        JOIN m3_classroom.sessions s ON s.id = sub.session_id
        JOIN m2_course.courses c ON c.id = s.course_id
        WHERE c.end_at IS NOT NULL
          AND c.end_at + INTERVAL '1 year' <= :now
        ORDER BY sub.id
        """, nativeQuery = true)
List<SubmissionEntity> findExpiredForRetention(@Param("now") Instant now);

void deleteBySessionId(UUID sessionId);
```

**Steps:**
- [ ] **Step 1: Write the failing IT**

Create `RetentionCandidateQueryIT extends PostgresIntegrationTest`. Use `JdbcTemplate` to insert:
- tasks with `closed_at` null / recent / old;
- sessions with `ended_at` 23h59 / 24h01 before a fixed `NOW`, one cancelled with null `ended_at` and old `updated_at`;
- submissions joined to a course with `end_at` 366 days before `NOW`, one 364 days, one null.

Assertions:
```java
assertThat(sessionRepository.findExpiredTemporary(NOW))
    .extracting(s -> s.id().value())
    .containsExactlyInAnyOrder(expiredEndedId, expiredCancelledId, taskClosedExpiredId);
assertThat(submissionRepository.findExpiredForRetention(NOW))
    .extracting(Submission::id)
    .containsExactly(expiredSubmissionId);
```
The `taskCloseExtendsId` session ended 25h before `NOW` but its task closed 1h before `NOW`, so it must NOT be returned; `taskClosedExpiredId` ended 48h before `NOW` and its task closed 25h before `NOW`, so it must be returned. Keep the SQL inclusive at exactly 24h / exactly 1 year with one assertion each.

- [ ] **Step 2: Run the IT and verify it fails**

`docker compose --profile test run --rm test gradle test --tests '*RetentionCandidateQueryIT' --no-daemon --console=plain`

Expected: compilation failure / assertion failure because the port methods do not exist.

- [ ] **Step 3: Implement the queries and adapters**

Map entities to domain in both adapters. For `InMemorySessionRepository.findExpiredTemporary`, implement the same predicate over the map using `endedAt != null ? endedAt : updatedAt`, the task close extension is not available in the fake — use the anchor + 24h only and document that unit tests cover the task-close extension in `RecordingLifecycleService`. For `InMemorySubmissionRepository.findExpiredForRetention`, return an empty list by default and expose `addExpired(Submission)` only if a unit test needs it; otherwise return `List.of()`.

- [ ] **Step 4: Run the IT again**

Expected: PASS, including boundary rows.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/domain/session/SessionRepository.java \
        server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/SessionJpaRepository.java \
        server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/SessionRepositoryAdapter.java \
        server/src/main/java/com/shijiangtai/m3classroom/domain/submission/SubmissionRepository.java \
        server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/SubmissionJpaRepository.java \
        server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/SubmissionRepositoryAdapter.java \
        server/src/test/java/com/shijiangtai/m3classroom/support/InMemorySessionRepository.java \
        server/src/test/java/com/shijiangtai/m3classroom/support/InMemorySubmissionRepository.java \
        server/src/test/java/com/shijiangtai/m3classroom/persistence/RetentionCandidateQueryIT.java
git commit -m "[M3C] 保留期候选查询与仓储端口"
```

---

### Task 2: Object-first retention deletion in the recording lifecycle

**Files:**
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/application/port/RecordingLifecyclePort.java`
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/application/RecordingLifecycleService.java`
- Modify: `server/src/test/java/com/shijiangtai/m3classroom/application/RecordingLifecycleServiceTest.java`
- Modify: `server/src/test/java/com/shijiangtai/m3classroom/application/AttemptCleanupTest.java` (constructor wiring only)
- Modify: `server/src/test/java/com/shijiangtai/m3classroom/support/InMemorySubmissionRepository.java` (only if Task 1 did not add deletion)

**Interfaces:**
- Adds to `RecordingLifecyclePort`:
  - `boolean deleteExpiredTemporary(SessionId sessionId)` — returns true only when this call deleted the session.
  - `boolean deleteSubmittedSession(SessionId sessionId)` — returns true only when this call deleted the submission/session.
- `RecordingLifecycleService` gains `TaskRepository` and `SubmissionRepository` constructor dependencies. Update the two manual test constructors.

**Implementation rules:**
- `deleteExpiredTemporary`:
  1. Load with `sessionRepository.findByIdForUpdate`.
  2. Return false unless the status is one of `CANCELLED`, `FAILED`, `ENDED`, `REVIEWING`.
  3. Recompute expiry under the lock: `anchor = endedAt != null ? endedAt : updatedAt`; if `taskId` is non-null and `TaskRepository.findById(taskId).closedAt != null`, use `max(anchor, closedAt)`; return false when `anchor + 24h > clock.now()`.
  4. Load the recording. Delete every non-null `objectKey` through `RecordingObjectStorage.delete` synchronously; do not catch `RuntimeException` here — the transaction rolls back and no rows are removed.
  5. Delete the recording row (if present), then the session row. Return true.
- `deleteSubmittedSession`:
  1. Load with `findByIdForUpdate`; return false unless status is `SUBMITTED`.
  2. Delete every object synchronously (same failure rule).
  3. `submissionRepository.deleteBySession(sessionId)`.
  4. Delete the recording row (if present), then the session row. Return true.
- Keep the existing `deleteAttempt` / `deleteOtherAttempts` / `abortAll` behavior unchanged (after-commit object deletes).

**Steps:**
- [ ] **Step 1: Write failing unit tests**

Add to `RecordingLifecycleServiceTest`:
```java
@Test void deleteExpiredTemporaryDeletesObjectsBeforeRows() { /* recording + session gone, storage.delete called */ }
@Test void deleteExpiredTemporaryKeepsRowsWhenObjectDeleteFails() { /* throwing storage -> rows still present, exception propagated */ }
@Test void deleteExpiredTemporarySkipsTaskCloseExtension() { /* task closed 1h ago, ended 25h ago -> false, rows remain */ }
@Test void deleteExpiredTemporaryRejectsSubmitted() { /* status SUBMITTED -> false */ }
@Test void deleteSubmittedSessionDeletesSubmissionBeforeSession() { /* both gone; findings counter order */ }
@Test void deleteSubmittedSessionSkipsNonSubmitted() { /* ENDED -> false, submission remains */ }
```
Use the existing `CountingRecordingObjectStorage`; add a `failOnDelete(String objectKey)` switch. Assert `sessions.findById(sessionId)` empty/non-empty and `recordings.findBySession` empty/non-empty.

- [ ] **Step 2: Run tests and verify RED**

`docker compose --profile test run --rm test gradle test --tests '*RecordingLifecycleServiceTest' --no-daemon --console=plain`

- [ ] **Step 3: Implement the two methods and constructor dependencies**

Use `TaskRepository` for the close-extension re-check. Keep `deleteObjectsNow` private and shared by both methods. Do not call `afterCommitPort` for these two methods.

- [ ] **Step 4: Run the lifecycle tests and `AttemptCleanupTest`**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit --only \
  server/src/main/java/com/shijiangtai/m3classroom/application/port/RecordingLifecyclePort.java \
  server/src/main/java/com/shijiangtai/m3classroom/application/RecordingLifecycleService.java \
  server/src/test/java/com/shijiangtai/m3classroom/application/RecordingLifecycleServiceTest.java \
  server/src/test/java/com/shijiangtai/m3classroom/application/AttemptCleanupTest.java \
  -m "[M3C] 保留期对象优先删除与提交件清理"
```

---

### Task 3: Retention service and scheduler

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/application/RecordingRetentionService.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/scheduling/M3RetentionProperties.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/scheduling/RecordingRetentionScheduler.java`
- Create: `server/src/test/java/com/shijiangtai/m3classroom/application/RecordingRetentionServiceTest.java`
- Create: `server/src/test/java/com/shijiangtai/m3classroom/infrastructure/scheduling/RecordingRetentionSchedulerTest.java`

**Interfaces:**
- `RecordingRetentionService.cleanup(Instant now): RetentionRun`:
  - for every `sessionRepository.findExpiredTemporary(now)`, call `deleteExpiredTemporary`; catch `RuntimeException`, log warn, count as failed, continue.
  - for every `submissionRepository.findExpiredForRetention(now)`, call `deleteSubmittedSession`; same catch/log/count.
  - `record RetentionRun(int temporaryDeleted, int submittedDeleted, int failed)`.
- `M3RetentionProperties` (`@ConfigurationProperties("m3.retention")`):
  ```java
  public record M3RetentionProperties(
          Boolean enabled, Duration initialDelay, Duration interval) {
      public M3RetentionProperties {
          enabled = enabled == null ? Boolean.TRUE : enabled;
          initialDelay = initialDelay == null ? Duration.ofMinutes(1) : initialDelay;
          interval = interval == null ? Duration.ofHours(1) : interval;
      }
  }
  ```
- `RecordingRetentionScheduler`:
  - `@Component`, `@Profile("!test")`, `AutoCloseable`.
  - Constructor: `RecordingRetentionService`, `ClockProvider`, `M3RetentionProperties`.
  - `@PostConstruct start()` when `enabled`: single daemon thread, `scheduleWithFixedDelay(this::runSafely, initialDelay, interval, MILLISECONDS)`.
  - `void runSafely()` catches `RuntimeException`, logs error; calls `service.cleanup(clock.now())` and logs counts at info.
  - `@PreDestroy close()` calls `shutdownNow()`.

**Steps:**
- [ ] **Step 1: Write failing service tests**

Use `InMemorySessionRepository`, `InMemorySubmissionRepository`, and a small `RecordingLifecyclePort` fake that records calls and can throw for a given id. Assert:
- expired sessions/submissions are passed to the lifecycle and counted;
- a failing candidate does not stop later candidates;
- `RetentionRun` counts are exact.

- [ ] **Step 2: Write failing scheduler tests**

Use Mockito:
- `enabled=false` → `start()` does not schedule and `close()` is safe;
- `runSafely()` calls `service.cleanup(fixedNow)` and swallows a thrown exception;
- optional: `enabled=true` with `initialDelay=10ms`, `interval=1h`, await one call then close (use `Awaitility` if present; otherwise call `runSafely()` directly and keep the test deterministic).

- [ ] **Step 3: Run tests and verify RED**

`docker compose --profile test run --rm test gradle test --tests '*RecordingRetention*' --no-daemon --console=plain`

- [ ] **Step 4: Implement the service, properties, and scheduler**

No `@EnableScheduling`, no `application*.yml` edit. The scheduler is excluded from the `test` profile, so full-suite tests are unaffected; scheduler tests instantiate it directly.

- [ ] **Step 5: Run tests**

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git commit --only \
  server/src/main/java/com/shijiangtai/m3classroom/application/RecordingRetentionService.java \
  server/src/main/java/com/shijiangtai/m3classroom/infrastructure/scheduling/M3RetentionProperties.java \
  server/src/main/java/com/shijiangtai/m3classroom/infrastructure/scheduling/RecordingRetentionScheduler.java \
  server/src/test/java/com/shijiangtai/m3classroom/application/RecordingRetentionServiceTest.java \
  server/src/test/java/com/shijiangtai/m3classroom/infrastructure/scheduling/RecordingRetentionSchedulerTest.java \
  -m "[M3C] 保留期清理服务与调度器"
```

---

### Task 4: Full suite, evidence, and board

**Files:**
- Modify: `docs/team/evidence/2026-11-04-m3c-recording-replay.md`
- Modify: `docs/team/02-进度看板.md` (M3C row + §5 record only)

**Steps:**
- [ ] **Step 1: Run the full backend suite**

`docker compose --profile test run --rm test gradle test --no-daemon --console=plain`

Expected: 0 failures / 0 errors / 0 skipped. Record the exact class/test counts.

- [ ] **Step 2: Update evidence**

Add a §10 "保留期（M3-T26）" section with:
- authorization line (user confirmed 2026-11-06, current M3 Owner acting for Media/Training Owner, file scope);
- exact semantics (24h / max close / course end +1y / object-first / retry);
- test classes and counts;
- remaining gap: training 1-month retention is M6; no live MinIO retention smoke (object-first path is unit-tested; InMemory storage used by IT).

- [ ] **Step 3: Update the board**

In `docs/team/02-进度看板.md`:
- change only the M3C blocker text to mention retention is implemented and training retention remains M6;
- append one §5 row dated `2026-11-06` with the test counts and evidence link.
Do not change gates G3–G8 or completion percentages.

- [ ] **Step 4: Commit**

```bash
git commit --only \
  docs/team/evidence/2026-11-04-m3c-recording-replay.md \
  docs/team/02-进度看板.md \
  -m "[M3C] 更新保留期证据与进度看板"
```