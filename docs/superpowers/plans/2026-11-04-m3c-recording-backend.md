# M3C Recording, Replay, and Submission Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the M3C backend slice and the M3B submission hand-off: three/two-stream recording metadata, MinIO multipart orchestration with presigned part URLs, replay presigned URLs, required-stream validation, temporary-attempt limits, and the submission transaction.

**Architecture:** Keep recording/replay in the M3A/M3B hexagonal style. `domain/recording` owns stream rules and object keys; `application` orchestrates through `RecordingObjectStorage` and repositories; `infrastructure/storage` provides MinIO and in-memory adapters; `api` exposes Recording/Replay/Submit endpoints. Existing `m3_classroom.recordings` / `recording_streams` / `submissions` tables from V4 are used unchanged.

**Tech Stack:** Java 21, Spring Boot 4.1.2, MinIO Java SDK 8.5.17, PostgreSQL 18/JPA, Jackson 3, JUnit 5/AssertJ/MockMvc, Docker Compose test service.

**Spec:** `docs/design/05B-M3-录制上传与回放.md`, `docs/implementation/04-M3-实现设计.md` §3.4/§4.5–§4.7, `docs/design/02-API与实时协议.md` §4.3/§8, `docs/design/10-测试与验收.md` §6.6 (M3-T20–T27; T20/T25 browser E2E are covered by the frontend plan/manual Chrome check).

## Global Constraints

- Same Java/module/dependency rules as `2026-11-04-m3b-classroom-backend.md`; domain has no Spring/Jackson/JPA imports.
- **M3C cross-Owner authorization:** the user explicitly authorized the current M3 Owner to implement the M3C backend on 2026-11-04; record this in the evidence file. Media/Training Owner owns `design/05B`; this plan does not modify design docs.
- No schema changes: V4 already has `recordings`, `recording_streams`, `submissions`. Do not create a new migration.
- Part size 5 MB (`5 * 1024 * 1024`); `MediaRecorder` timeslice and `fix-webm-duration` are client concerns (frontend plan).
- Object keys: task `recordings/{schoolId}/task/{taskId}/{sessionId}/{streamType}.webm`; training `recordings/{schoolId}/training/{sessionId}/{streamType}.webm` (training is not implemented here).
- Required streams: free/scenario = camera+screen+audio; interview = camera+audio.
- Presigned part URL TTL 15 minutes; replay GET URL TTL 15 minutes. Never expose bucket credentials.
- **Browser-visible presigning:** the app talks to MinIO at `http://minio:9000` inside Compose but must presign with a browser-reachable endpoint. Add `m3.minio.public-endpoint` (docker default `http://localhost:9000`) and build a second `MinioClient` for presigning; add `MINIO_API_CORS_ALLOW_ORIGIN: "*"` to the MinIO service and `M3_MINIO_PUBLIC_ENDPOINT: http://localhost:9000` to the app service. These are cross-module Compose/config edits covered by the plan approval.
- **P0 simplifications:** retention/cleanup scheduled jobs are out of scope; temporary attempts are cleaned on submit/cancel and capped when a new session is created; no resumable upload; no server transcoding; no perception streams.
- Test command pattern: `docker compose --profile test run --rm test gradle test --tests '*<Class>' --no-daemon --console=plain`.
- Commit after every task with prefix `[M3C]` (submission task may use `[M3B]` if it touches only session wiring; be consistent per task).

## Review Focus

1. **Required-stream completeness:** submission is rejected with `RECORDING_INCOMPLETE` and the missing stream list unless every required stream is `uploaded`; interview never accepts `screen`. Tests in Task 1/6.
2. **Multipart isolation:** a student cannot start/complete a stream for another student’s session or a stream type not required by the session mode. Tests in Task 4.
3. **Replay permission:** teacher sees only sessions bound to a submission; temporary recordings are student-owner only; admins always 403; cross-school 403. Tests in Task 5.
4. **Submission atomicity:** force flush, required-stream check, submission insert, session `submitted`, assignment `submitted`, and event publication happen in one transaction; a failure leaves no submission. Tests in Task 6.
5. **Other-attempt cleanup:** after submission, only the selected session’s recordings remain; DB rows and MinIO objects for other attempts are removed (objects after commit). Tests in Task 6/7.

---

### Task 1: Recording domain rules and object keys

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/domain/recording/{RecordingStatus,StreamStatus,RecordingStream,Recording,RequiredStreams,RecordingObjectKeyBuilder}.java`
- Reuse: `server/src/main/java/com/shijiangtai/m3classroom/domain/recording/StreamType.java` (created by M3B Task 2; do not create a second one)
- Create: `server/src/test/java/com/shijiangtai/m3classroom/domain/recording/{RequiredStreamsTest,RecordingObjectKeyBuilderTest,RecordingTest}.java`

**Interfaces:**
- Produces:
  - `enum StreamType { CAMERA("camera"), SCREEN("screen"), AUDIO("audio") }` with `wireValue()`/`fromWire`.
  - `enum RecordingStatus { RECORDING, UPLOADING, UPLOADED, FAILED, DELETED }`; `enum StreamStatus { UPLOADING, UPLOADED, FAILED, DELETED }`.
  - `record RecordingStream(UUID id, UUID recordingId, StreamType type, String uploadId, String objectKey, long partSize, int partsUploaded, long bytesUploaded, Long durationMs, long offsetMs, String mime, StreamStatus status)`.
  - `final class Recording` with `static Recording create(UUID id, SessionId sessionId, Instant now)`, `RecordingStream startStream(...)`, `void markStreamUploaded(...)`, `void markStreamFailed(...)`, `void abortStream(...)`, `RecordingStatus status()`, `List<RecordingStream> streams()`, `Optional<RecordingStream> stream(StreamType)`, `Recording rehydrate(...)`.
  - `final class RequiredStreams { static Set<StreamType> forMode(TaskMode mode); static List<StreamType> missing(Set<StreamType> uploaded, TaskMode mode); }`.
  - `final class RecordingObjectKeyBuilder { String taskKey(UUID schoolId, UUID taskId, UUID sessionId, StreamType type); String trainingKey(UUID schoolId, UUID sessionId, StreamType type); }`.

- [ ] **Step 1: Write the failing tests**

```java
@Test void freeRequiresCameraScreenAudio() { /* exactly those three */ }
@Test void interviewRequiresCameraAndAudio() { /* exactly those two */ }
@Test void missingListsOnlyUploadedGaps() { /* order camera,screen,audio */ }
@Test void taskKeyMatchesSpec() { /* recordings/{school}/task/{task}/{session}/screen.webm */ }
@Test void trainingKeyMatchesSpec() { /* recordings/{school}/training/{session}/audio.webm */ }
@Test void recordingStatusBecomesUploadedWhenAllRequiredStreamsUploaded() { /* ... */ }
@Test void failedStreamMakesRecordingFailed() { /* ... */ }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `docker compose --profile test run --rm test gradle test --tests '*RequiredStreamsTest' --tests '*RecordingObjectKeyBuilderTest' --tests '*RecordingTest' --no-daemon --console=plain`
Expected: compilation failure.

- [ ] **Step 3: Implement the domain**

Use `DomainException(VALIDATION_ERROR)` for invalid stream transitions and `SESSION_STATE_INVALID` when a stream is not required by the mode. `Recording` keeps streams in a `LinkedHashMap` ordered camera, screen, audio.

- [ ] **Step 4: Run tests to verify they pass**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/domain/recording \
  server/src/test/java/com/shijiangtai/m3classroom/domain/recording
git commit -m "[M3C] 录制流规则、必需流与对象键"
```

---

### Task 2: Recording persistence and repositories

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/domain/recording/{RecordingRepository,RecordingStreamRepository}.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/{RecordingEntity,RecordingStreamEntity,RecordingJpaRepository,RecordingStreamJpaRepository,RecordingRepositoryAdapter,RecordingStreamRepositoryAdapter}.java`
- Create: `server/src/test/java/com/shijiangtai/m3classroom/persistence/RecordingRepositoryIT.java`

**Interfaces:**
- Produces:
  - `interface RecordingRepository { Recording save(Recording); Optional<Recording> findBySession(SessionId); Optional<Recording> findById(UUID); }`
  - `interface RecordingStreamRepository { List<RecordingStream> findByRecording(UUID recordingId); Optional<RecordingStream> find(UUID recordingId, StreamType type); void deleteByRecording(UUID recordingId); }`
  - Adapters map `m3_classroom.recordings` / `recording_streams`; the recording adapter saves the aggregate and its streams, using delete+insert or merge per stream.

- [ ] **Step 1: Write the failing repository IT**

Save a recording with three streams (uploadId/objectKey/partSize/status), reload; assert every field and the unique `(recording_id, stream_type)` behavior. Update a stream to `uploaded` with duration/size; reload; assert. Delete by recording and assert streams are gone.

- [ ] **Step 2: Run test to verify it fails**

Run: `docker compose --profile test run --rm test gradle test --tests '*RecordingRepositoryIT' --no-daemon --console=plain`
Expected: compilation failure.

- [ ] **Step 3: Implement entities and adapters**

Follow the M3A JPA adapter patterns. No JSONB columns here. `part_size` maps to `bigint`; nullable `duration_ms`/`upload_id`/`object_key` must round-trip as null.

- [ ] **Step 4: Run test to verify it passes**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/domain/recording \
  server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa \
  server/src/test/java/com/shijiangtai/m3classroom/persistence/RecordingRepositoryIT.java
git commit -m "[M3C] 录制持久化与仓储"
```

---

### Task 3: Object-storage port and MinIO multipart adapter

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/application/port/RecordingObjectStorage.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/storage/{MinioRecordingObjectStorage,InMemoryRecordingObjectStorage}.java`
- Create: `server/src/test/java/com/shijiangtai/m3classroom/infrastructure/storage/InMemoryRecordingObjectStorageTest.java`
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/config/M3Config.java` (storage beans)
- Modify: `server/src/main/resources/application-docker.yml` (`m3.minio.public-endpoint`)
- Modify: `docker-compose.yml` (app `M3_MINIO_PUBLIC_ENDPOINT`; minio `MINIO_API_CORS_ALLOW_ORIGIN: "*"`)

**Interfaces:**
- Produces:
  - `record MultipartUpload(String uploadId, String objectKey, long partSize)`.
  - `record PartEtag(int partNumber, String etag)`.
  - `record ObjectStat(long size, String contentType)`.
  - `interface RecordingObjectStorage`:
    - `MultipartUpload createMultipart(String objectKey, String contentType)`
    - `String presignPart(String objectKey, String uploadId, int partNumber, Duration ttl)`
    - `void completeMultipart(String objectKey, String uploadId, List<PartEtag> parts)`
    - `void abortMultipart(String objectKey, String uploadId)`
    - `String presignGet(String objectKey, Duration ttl)`
    - `ObjectStat stat(String objectKey)`
    - `void delete(String objectKey)`
  - `InMemoryRecordingObjectStorage` (`@ConditionalOnProperty(name="m3.storage", havingValue="memory", matchIfMissing=true)`) tracks uploads/parts/objects in maps and returns `http://memory-minio/{bucket}/{key}?partNumber=N` / `?get=1`.
  - `MinioRecordingObjectStorage` (`havingValue="minio"`) uses `MinioClient.createMultipartUpload`, `getPresignedObjectUrl` (PUT with `uploadId`/`partNumber` query params), `completeMultipartUpload`, `abortMultipartUpload`, `getPresignedObjectUrl` (GET), `statObject`, `removeObject`. It holds an internal client from `m3.minio.endpoint` and a presign client from `m3.minio.public-endpoint` (fallback to the internal endpoint when blank).

- [ ] **Step 1: Write the failing in-memory adapter test**

```java
@Test void multipartLifecycleStoresPartsAndCompletes() { /* create -> presign -> complete -> stat/delete */ }
@Test void abortRemovesUpload() { /* complete after abort throws SERVICE_UNAVAILABLE */ }
@Test void presignedUrlsContainNoCredentials() { /* strings contain object key, not minioadmin */ }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `docker compose --profile test run --rm test gradle test --tests '*InMemoryRecordingObjectStorageTest' --no-daemon --console=plain`
Expected: compilation failure.

- [ ] **Step 3: Implement the in-memory and MinIO adapters**

Map MinIO `Exception` to `DomainException(SERVICE_UNAVAILABLE, "对象存储不可用")`. For the MinIO part URL, use `GetPresignedObjectUrlArgs.builder().method(Method.PUT).bucket(bucket).object(objectKey).expiry(seconds).extraQueryParams(Map.of("uploadId", uploadId, "partNumber", String.valueOf(partNumber))).build()`. Bucket is created lazily, mirroring `MinioMaterialObjectStorage`.

- [ ] **Step 4: Run test to verify it passes**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/application/port/RecordingObjectStorage.java \
  server/src/main/java/com/shijiangtai/m3classroom/infrastructure/storage \
  server/src/main/java/com/shijiangtai/m3classroom/infrastructure/config/M3Config.java \
  server/src/test/java/com/shijiangtai/m3classroom/infrastructure/storage/InMemoryRecordingObjectStorageTest.java
git commit -m "[M3C] MinIO multipart 与内存存储适配"
```

---

### Task 4: Recording application service and REST API

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/application/{RecordingAccessPolicy,RecordingApplicationService,RecordingApplicationServiceImpl,RecordingLifecycleService}.java`
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/application/port/RecordingLifecyclePort.java` (created in Plan 1 Task 10; replace the no-op bean in `M3Config` with the real service)
- Create: `server/src/main/java/com/shijiangtai/m3classroom/application/command/CompletePartCommand.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/application/view/{MultipartUploadView,PartUrlView,RecordingStreamView}.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/api/{RecordingController,RecordingRequestMapper}.java`
- Create: `server/src/test/java/com/shijiangtai/m3classroom/application/{RecordingAccessPolicyTest,RecordingApplicationServiceTest}.java`
- Create: `server/src/test/java/com/shijiangtai/m3classroom/api/RecordingControllerIT.java`

**Interfaces:**
- Plan 2 must remove the Plan 1 no-op `RecordingLifecyclePort` bean from `M3Config` (not stack a second implementation) and, if an HTTP `ContentClient` is added, remove the fallback-only bean the same way.
- Produces:
  - `interface RecordingApplicationService { MultipartUploadView start(CurrentUser student, SessionId sessionId, StreamType type); PartUrlView partUrl(CurrentUser student, SessionId sessionId, StreamType type, int partNumber); RecordingStreamView complete(CurrentUser student, SessionId sessionId, StreamType type, CompletePartCommand command); void abort(CurrentUser student, SessionId sessionId, StreamType type); List<RecordingStreamView> list(CurrentUser user, SessionId sessionId); }`
  - `record CompletePartCommand(List<PartEtag> parts, long durationMs, long size, String mime)`.
  - `interface RecordingLifecyclePort { void abortAll(SessionId sessionId); void deleteOtherAttempts(AccountId student, TaskId task, SessionId keep); }` — implemented here and used by the session service.
  - REST:
    - `POST /api/v1/sessions/{sessionId}/recordings/{streamType}/multipart/start` → `{upload_id, object_key, part_size}`
    - `POST .../multipart/part-url` body `{part_number}` → `{url, part_number, expires_in_seconds}`
    - `POST .../multipart/complete` body `{parts:[{part_number, etag}], duration_ms, size, mime}` → `RecordingStreamView`
    - `POST .../multipart/abort` → 204
    - `GET /api/v1/sessions/{sessionId}/recordings` → `List<RecordingStreamView>`

- [ ] **Step 1: Write the failing service tests**

```java
@Test void startCreatesMultipartForRequiredStream() { /* upload_id/object_key/part_size */ }
@Test void startRejectsScreenForInterview() { /* MATERIAL_NOT_APPLICABLE or SESSION_STATE_INVALID */ }
@Test void startRejectsOtherStudentsSession() { /* FORBIDDEN */ }
@Test void partUrlRejectsPartNumberOutsideRange() { /* VALIDATION_ERROR */ }
@Test void completeMarksStreamUploadedAndRecordingUploadedWhenAllPresent() { /* ... */ }
@Test void completeWithSizeMismatchFails() { /* RECORDING_INCOMPLETE or VALIDATION_ERROR */ }
@Test void abortMarksStreamFailedAndIsIdempotent() { /* ... */ }
@Test void listReturnsOnlySessionStreamsForOwner() { /* ... */ }
```

- [ ] **Step 2: Write the failing controller IT**

Use the test profile in-memory storage: create an in-progress session, start camera/screen/audio, assert the multipart payload; part URL; complete with a fake object size; list; a second student gets 403; admin 403. Since the in-memory storage does not actually receive bytes, `complete` must accept the declared size and `stat` returns it (the test adapter should track completed sizes).

- [ ] **Step 3: Run tests to verify they fail**

Run: `docker compose --profile test run --rm test gradle test --tests '*RecordingAccessPolicyTest' --tests '*RecordingApplicationServiceTest' --tests '*RecordingControllerIT' --no-daemon --console=plain`
Expected: compilation failure.

- [ ] **Step 4: Implement the service and controller**

`start` requires `session.status in (PREPARING, IN_PROGRESS)`, owner student, and `type in RequiredStreams.forMode(mode)`. Reuse an existing stream if `uploadId` is present; otherwise create the recording row if absent, build the object key from the task snapshot/course/task/session IDs, call `createMultipart`, and save. `complete` calls `storage.completeMultipart`, verifies `stat.size >= size` (or exact size for the in-memory adapter), updates the stream, and recomputes recording status. `abort` is idempotent. `list` checks `RecordingAccessPolicy`; temporary recordings are owner-only; teachers require a submission (implemented with `SubmissionRepository` in Task 6; until then use a `SubmissionLookupPort` with a no-op/`false` test fake).

- [ ] **Step 5: Run tests to verify they pass**

Run the Step 3 command. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/application \
  server/src/main/java/com/shijiangtai/m3classroom/api \
  server/src/test/java/com/shijiangtai/m3classroom/application \
  server/src/test/java/com/shijiangtai/m3classroom/api/RecordingControllerIT.java
git commit -m "[M3C] 录制应用服务与 multipart REST 接口"
```

---

### Task 5: Replay and context query APIs

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/application/{ReplayQueryService,ReplayQueryServiceImpl}.java`
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/application/SessionAccessPolicy.java` (created in Plan 1 Task 10; add the submitted-session teacher rule)
- Create: `server/src/main/java/com/shijiangtai/m3classroom/application/view/{ReplayView,ReplayStreamView,SessionEventView,SessionTextView}.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/api/ReplayController.java`
- Create: `server/src/test/java/com/shijiangtai/m3classroom/application/ReplayQueryServiceTest.java`
- Create: `server/src/test/java/com/shijiangtai/m3classroom/api/ReplayControllerIT.java`

**Interfaces:**
- Produces:
  - `interface ReplayQueryService { ReplayView replay(CurrentUser user, SessionId sessionId); PageResult<SessionEventView> events(CurrentUser user, SessionId sessionId, long afterSeq, int limit); List<SessionTextView> texts(CurrentUser user, SessionId sessionId, String speakerType); }`
  - `record ReplayView(UUID sessionId, String mode, long durationMs, List<ReplayStreamView> streams)`.
  - `record ReplayStreamView(String streamType, String url, long offsetMs, long durationMs, String mime)`.
  - REST:
    - `GET /api/v1/sessions/{sessionId}/replay`
    - `GET /api/v1/sessions/{sessionId}/events?after_seq=0&limit=100`
    - `GET /api/v1/sessions/{sessionId}/texts?speaker_type=teacher`
  - `SessionAccessPolicy`: owner student for any non-draft session; teacher group for submitted sessions only; admin 403; cross-school 403.

- [ ] **Step 1: Write the failing replay tests**

```java
@Test void ownerGetsPresignedStreamsWithOffsets() { /* screen,camera,audio; 15 min TTL */ }
@Test void temporaryReplayIsHiddenFromTeacher() { /* FORBIDDEN */ }
@Test void submittedReplayIsVisibleToCourseTeacher() { /* 200 */ }
@Test void adminAndCrossSchoolAreForbidden() { /* 403 */ }
@Test void eventsAreFilteredAfterSeqAndLimited() { /* ... */ }
@Test void textsFilterBySpeakerType() { /* ... */ }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `docker compose --profile test run --rm test gradle test --tests '*ReplayQueryServiceTest' --tests '*ReplayControllerIT' --no-daemon --console=plain`
Expected: compilation failure.

- [ ] **Step 3: Implement replay**

Read the recording/streams; if none or not `uploaded`, return `RESOURCE_NOT_FOUND` / `RECORDING_INCOMPLETE`. Presign each uploaded stream with 15 minutes. Sort streams `screen, camera, audio` for free/scenario and `camera, audio` for interview. Events/texts come from the persisted repositories; default limit 100, max 500. Teacher access requires `SubmissionRepository.findBySession(sessionId)`; admin always 403.

- [ ] **Step 4: Run tests to verify they pass**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/application server/src/main/java/com/shijiangtai/m3classroom/api \
  server/src/test/java/com/shijiangtai/m3classroom/application \
  server/src/test/java/com/shijiangtai/m3classroom/api/ReplayControllerIT.java
git commit -m "[M3C] 回放、事件与文本查询接口"
```

---

### Task 6: Submission transaction

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/domain/submission/{Submission,SubmissionRepository}.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/{SubmissionEntity,SubmissionJpaRepository,SubmissionRepositoryAdapter}.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/application/{SubmissionService,SubmissionServiceImpl}.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/application/view/SubmissionView.java`
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/application/SessionApplicationService.java` (add `SubmissionView submit(CurrentUser student, SessionId sessionId)`)
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/api/SessionController.java` (add `POST /sessions/{id}/submit`)
- Create: `server/src/test/java/com/shijiangtai/m3classroom/application/SubmissionServiceTest.java`
- Create: `server/src/test/java/com/shijiangtai/m3classroom/persistence/SubmissionPersistenceIT.java`
- Create: `server/src/test/java/com/shijiangtai/m3classroom/api/SubmissionControllerIT.java`

**Interfaces:**
- Produces:
  - `final class Submission { static Submission create(UUID id, TaskId taskId, AccountId student, SessionId sessionId, ClassId classIdSnapshot, Instant now); ... }`
  - `interface SubmissionRepository { Submission save(Submission); Optional<Submission> findByTaskAndStudent(TaskId, AccountId); Optional<Submission> findBySession(SessionId); boolean existsBySession(SessionId); }`
  - `final class SubmissionService`:
    - `SubmissionView submit(CurrentUser student, SessionId sessionId)`
    - `void deleteOtherAttempts(AccountId student, TaskId task, SessionId keep)`
  - `record SubmissionView(UUID submissionId, UUID taskId, UUID sessionId, UUID studentAccountId, String status, String submittedAt)`.

- [ ] **Step 1: Write the failing submission tests**

```java
@Test void submitRequiresEndedOrReviewingSession() { /* SESSION_STATE_INVALID */ }
@Test void submitRequiresAllRequiredStreamsUploaded() { /* RECORDING_INCOMPLETE with missing streams */ }
@Test void submitCreatesSubmissionAndMarksSessionAndAssignment() { /* status submitted; assignment submitted; event emitted */ }
@Test void submitTwiceFails() { /* SESSION_STATE_INVALID / duplicate resource */ }
@Test void submitDeletesOtherAttempts() { /* DB rows and objects removed; selected kept */ }
@Test void submitFlushFailureLeavesNoSubmission() { /* fake event store throws -> no submission */ }
```

- [ ] **Step 2: Write the failing persistence/controller IT**

`SubmissionPersistenceIT`: unique `(task_id, student_account_id)` and `session_id`; round-trip. `SubmissionControllerIT`: create an in-progress session, upload/complete required streams via the in-memory storage, end the session, submit, assert 200 + `submission_id`; a missing stream yields 422 `RECORDING_INCOMPLETE`; another student 403.

- [ ] **Step 3: Run tests to verify they fail**

Run: `docker compose --profile test run --rm test gradle test --tests '*SubmissionServiceTest' --tests '*SubmissionPersistenceIT' --tests '*SubmissionControllerIT' --no-daemon --console=plain`
Expected: compilation failure.

- [ ] **Step 4: Implement submission**

`submit` is `@Transactional`: load session, owner check, status `ENDED`/`REVIEWING`, `actor.forceFlush()` (if an actor exists), `RequiredStreams.missing(uploaded streams, mode)` → `RECORDING_INCOMPLETE` with `missing_streams`; load task/assignment, build `Submission`, save, `session.submit(now)`, `assignment.markSubmitted()`, save, append `submission.created` event, publish `SubmissionCreatedEvent` after commit. Then `deleteOtherAttempts` after the transaction/after commit (objects first, then DB) — use `AfterCommitPort` for object deletes.

- [ ] **Step 5: Run tests to verify they pass**

Run the Step 3 command. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/domain/submission \
  server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa \
  server/src/main/java/com/shijiangtai/m3classroom/application server/src/main/java/com/shijiangtai/m3classroom/api \
  server/src/test/java/com/shijiangtai/m3classroom
git commit -m "[M3C] 提交事务、必需流校验与清理"
```

---

### Task 7: Attempt limit and cancellation cleanup

**Files:**
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/application/SessionApplicationServiceImpl.java`
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/application/RecordingLifecyclePort.java` implementation
- Create: `server/src/test/java/com/shijiangtai/m3classroom/application/AttemptCleanupTest.java`

**Interfaces:**
- Produces:
  - On `createSession`, if the student already has ≥5 non-submitted attempts for the task, delete the oldest attempt (session + recordings/streams + objects) before creating the new one. Submitted attempts are never deleted.
  - On `cancel`, abort all uploading multipart uploads, delete temporary objects, and mark the recording/streams deleted.
  - `RecordingLifecyclePort.abortAll(SessionId)` and `deleteOtherAttempts(...)` are implemented by `RecordingLifecycleService` using the storage adapter and repositories; object deletes happen after commit.

- [ ] **Step 1: Write the failing tests**

```java
@Test void sixthAttemptDeletesOldestTemporarySession() { /* count stays 5; oldest object delete scheduled */ }
@Test void submittedAttemptIsNeverDeleted() { /* ... */ }
@Test void cancelAbortsUploadingMultipartAndDeletesObjects() { /* storage.abort called; rows deleted */ }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `docker compose --profile test run --rm test gradle test --tests '*AttemptCleanupTest' --no-daemon --console=plain`
Expected: compilation failure or assertion failure.

- [ ] **Step 3: Implement cleanup**

Add `SessionRepository.findTemporaryByStudentAndTaskOrderedByCreatedAt` and `deleteById`. Prefer deleting the oldest `CANCELLED`/`FAILED`, then the oldest `ENDED`/`REVIEWING`; if all five are `PREPARING`/`IN_PROGRESS`, reject with `SESSION_ACTIVE_EXISTS` (do not delete an active attempt). Use `RecordingLifecyclePort` for object cleanup.

- [ ] **Step 4: Run tests to verify they pass**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/application \
  server/src/test/java/com/shijiangtai/m3classroom/application/AttemptCleanupTest.java
git commit -m "[M3C] 临时 attempt 上限与取消清理"
```

---

### Task 8: Contract tests, full suite, Docker smoke, and progress evidence

**Files:**
- Create: `server/src/test/java/com/shijiangtai/m3classroom/contract/{RecordingContractTest,ReplayContractTest}.java`
- Create: `docs/team/evidence/2026-11-04-m3c-recording-replay.md`
- Modify: `docs/team/02-进度看板.md` (M3C row blocker + §5 update row only)
- Modify: `server/README.md` (recording/replay smoke commands)
- Modify: `docs/team/01-模块归属与修改规则.md` only if a new M3C path needs an owner note (prefer no change)

**Interfaces:**
- Produces: verified DTO/WS-independent contract fixtures for multipart start/part-url/complete/replay/submission; full-suite evidence; Compose smoke.

- [ ] **Step 1: Write the failing contract tests**

Freeze JSON fixtures for:
- `multipart/start` response `{upload_id, object_key, part_size}`.
- `multipart/part-url` response `{url, part_number, expires_in_seconds}`.
- `multipart/complete` request/response.
- `replay` response with `session_id`, `mode`, `duration_ms`, `streams[]` (`stream_type`, `url`, `offset_ms`, `duration_ms`, `mime`).
- `RECORDING_INCOMPLETE` error body with `details.missing_streams`.

Assert snake_case keys and exact field names with Jackson tree reads (do not depend on whitespace).

- [ ] **Step 2: Run contract tests to verify they fail**

Run: `docker compose --profile test run --rm test gradle test --tests '*RecordingContractTest' --tests '*ReplayContractTest' --no-daemon --console=plain`
Expected: compilation failure / missing DTO fields.

- [ ] **Step 3: Adjust DTOs if the contract tests expose mismatches**

Do not change the design; make the DTOs match `02` §4.3.

- [ ] **Step 4: Run the full suite and smoke**

```bash
docker compose --profile test run --rm test gradle clean test --no-daemon --console=plain
docker compose up -d --build
curl -fsS http://localhost:8080/actuator/health
# teacher creates/publishes a free task; student creates a session; start three multipart uploads;
# PUT small parts to the presigned MinIO URLs; complete; end; replay; submit.
```

Record the test count, the presigned URL checks (no credentials), the MinIO object keys, and the submission row in the evidence file. Update the M3C row blocker to `录制/回放后端 P0 已实现；浏览器录制与 E2E 待续` and add one §5 row. Do not mark G3–G8.

- [ ] **Step 5: Commit**

```bash
git add server/src/test/java/com/shijiangtai/m3classroom/contract \
  docs/team/evidence/2026-11-04-m3c-recording-replay.md docs/team/02-进度看板.md server/README.md
git commit -m "[M3C] 契约测试、全量验证与进度证据"
```

---

## Out of Scope / Follow-up

- Browser MediaRecorder/uploader/replay UI: `2026-11-04-m3-frontend-classroom-replay.md`.
- Perception ASR/OCR/screen, retention scheduled jobs, server transcoding, resumable uploads, training records (M6).
