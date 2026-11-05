# M3B 感知通道（ASR / OCR）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the M3B perception channel: perception WebSocket audio → Python ASR → teacher/candidate text + voice-triggered QA, and screen screenshot → async OCR → `m3.frames` + `perception.screen_changed`, with failures never affecting the session.

**Architecture:** A new raw WebSocket handler beside the control channel forwards PCM to a `AsrStreamClient` port, persists final text through new `SessionActor` methods, and classifies teacher utterances off-actor. A thin controller accepts JPEG screenshots, persists a pending `SessionFrame` through a new store port, and OCRs asynchronously on a bounded executor. Domain stays framework-free; Python/HTTP/WS adapters live in `infrastructure/perception`; tests use fake ports.

**Tech Stack:** Java 21, Spring Boot 4.1.2, Spring WebSocket, JDK `HttpClient` + `java.net.http.WebSocket`, Jackson 3, JPA/PostgreSQL 18, JUnit 5, AssertJ, MockMvc, Docker Compose test service. **No new dependencies.**

**Spec:** `docs/superpowers/specs/2026-11-05-m3b-perception-design.md`

## Global Constraints

- Java 21; Spring Boot 4.1.2; Gradle 8.14.3; PostgreSQL 18; no host JDK/Gradle. Run builds/tests through Compose:
  `docker compose --profile test run --rm test gradle test --tests '*<Class>' --no-daemon --console=plain`
  Full suite: `docker compose --profile test run --rm test gradle clean test --no-daemon --console=plain`.
- Domain (`m3classroom/domain/**`, `shared/domain/**`) must not import Spring, Jackson, JPA, HTTP, `api`, `application`, or `infrastructure`.
- Application must not import `api` or `infrastructure`; API must not import `infrastructure`.
- JSON is `snake_case` (Jackson global strategy); `at_ms` is session-relative milliseconds; `Instant` is UTC.
- All business errors use `DomainException`; controllers do not catch exceptions. Illegal commands never mutate state.
- Student-facing data never exposes weights/material/answer points/virtual-student parameters/personality text.
- **P0 boundaries (binding):** no acoustic events (`perception.acoustic`), no vision model frame types (`final`/`chart`), no new control-WS events, no `perception.failed` event, no frontend, no M4 partial, no async batch writer.
- **AI failures never change session status, never stop recording, never enter the control WS grace path.**
- Commit after every task with prefix `[M3B]`; never `git add -A` or `git commit -a`; stage only the task's files. The working tree contains unrelated staged deletions and untracked M3C files — leave them untouched.
- **Cross-module files:** none. Do not edit `server/build.gradle.kts`, `server/src/main/resources/application*.yml`, `docs/design/02`, `docs/design/12`, or M3C paths. This plan edits only M3B-owned paths + `docs/design/05A`, `docs/implementation/04`, `docs/team/02`, and the M3B evidence file.

## Review Focus

1. **Illegal perception messages:** wrong `format`/`sample_rate`/`channels`, binary before `audio.start`, double start, or `audio.stop` before start must return `error VALIDATION_ERROR` and close 4003 without touching the session.
2. **AI outage:** ASR connect failure or mid-stream drop, OCR failure, and classifier failure must leave the session `in_progress` and the control channel untouched (M3-T17).
3. **Null OCR confidence:** Python OCR usually returns no confidence; a null must not turn every frame into `low_confidence` — `change_score` decides `slide_change` vs `normal`.
4. **Late finals / terminal race:** a final arriving after `session.end`, and a session ending while a perception socket is open, must not persist text after the terminal event or leave the socket open (close 4002).
5. **Duplicate perception connections:** a second connect must replace the first (1000), and the stale connection must not keep forwarding audio or emit duplicate `session_texts` / QA events.
6. **Voice trigger ordering:** a high-confidence question must emit `classroom.question_asked(source=voice)` before `classroom.state_changed`, and must schedule hand raises + the 30 s window exactly like a manual ask.

---

## File Structure

Production (new):

- `server/src/main/java/com/shijiangtai/m3classroom/domain/session/{FrameType,FrameStatus,OcrBox,SessionFrame,FrameTypeClassifier}.java` — frame model + pure type rule.
- `server/src/main/java/com/shijiangtai/m3classroom/application/port/{AsrStreamClient,AsrSession,AsrRequest,AsrChunk,OcrClient,OcrResult,TeacherSpeechClassifier,ClassifyResult,SessionFrameStore}.java` — ports.
- `server/src/main/java/com/shijiangtai/m3classroom/application/{PerceptionApplicationService,view/PerceptionFrameView}.java` — screenshot ingest + async OCR.
- `server/src/main/java/com/shijiangtai/m3classroom/api/{PerceptionScreenController,dto/PerceptionScreenResponse}.java` — HTTP adapter.
- `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/perception/{PerceptionAiProperties,PythonAsrStreamClient,PythonPerceptionHttpClient}.java` — Python adapters.
- `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/{SessionFrameEntity,SessionFrameJpaRepository,SessionFrameStoreAdapter}.java` — `m3.frames`.
- `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/ws/{PerceptionProtocolParser,PerceptionConnectionRegistry,PerceptionWebSocketHandler}.java` — perception WS.

Production (modify):

- `server/src/main/java/com/shijiangtai/m3classroom/application/SessionActor.java` — perception hooks + multi status listener.
- `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/config/M3Config.java` — fallback AI beans.
- `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/ws/WebSocketConfig.java` — register perception path.

Tests (new): `FrameTypeClassifierTest`, `SessionActorPerceptionTest`, `PythonPerceptionHttpClientTest`, `PythonAsrStreamClientIT`, `SessionFramePersistenceIT`, `PerceptionApplicationServiceTest`, `PerceptionScreenIT`, `PerceptionProtocolParserTest`, `PerceptionWebSocketIT`, plus `support/{FakeAsrStreamClient,FakeOcrClient,FakeTeacherSpeechClassifier,InMemorySessionFrameStore}.java`.

Docs: `docs/design/05A-M3-课堂状态机与WebSocket.md`, `docs/implementation/04-M3-实现设计.md`, `docs/team/02-进度看板.md`, `docs/team/evidence/2026-11-05-m3b-perception.md`.

---

### Task 0: Commit the approved design and this plan

**Files:**
- Add: `docs/superpowers/specs/2026-11-05-m3b-perception-design.md`
- Add: `docs/superpowers/plans/2026-11-05-m3b-perception.md`

- [ ] **Step 1: Commit only the two docs**

```bash
git add docs/superpowers/specs/2026-11-05-m3b-perception-design.md \
  docs/superpowers/plans/2026-11-05-m3b-perception.md
git commit -m "[M3B] 感知通道设计与实现计划"
```

Expected: `git status --short` still shows the unrelated staged deletions and M3C untracked files; they are not in the commit.

---

### Task 1: Frame domain model and type classifier

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/domain/session/FrameType.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/domain/session/FrameStatus.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/domain/session/OcrBox.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/domain/session/SessionFrame.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/domain/session/FrameTypeClassifier.java`
- Test: `server/src/test/java/com/shijiangtai/m3classroom/domain/session/FrameTypeClassifierTest.java`

**Interfaces:**
- Produces:
  - `enum FrameType { NORMAL, SLIDE_CHANGE, LOW_CONFIDENCE, FINAL, CHART }` with `String wireValue()` (`normal`, `slide_change`, `low_confidence`, `final`, `chart`).
  - `enum FrameStatus { PENDING, PROCESSED, FAILED }` with `String wireValue()`.
  - `record OcrBox(String text, double x1, double y1, double x2, double y2, BigDecimal confidence)`.
  - `record SessionFrame(UUID id, SessionId sessionId, long timestampMs, String text, List<OcrBox> boundingBoxes, BigDecimal confidence, BigDecimal changeScore, FrameType frameType, String objectKey, String contentHash, FrameStatus status, String errorMessage)` with `static SessionFrame pending(UUID id, SessionId sessionId, long timestampMs, BigDecimal changeScore, String objectKey, String contentHash)`.
  - `final class FrameTypeClassifier` with `BigDecimal LOW_CONFIDENCE_THRESHOLD = new BigDecimal("0.60")`, `BigDecimal SLIDE_CHANGE_THRESHOLD = new BigDecimal("0.125")`, `FrameType classify(BigDecimal confidence, BigDecimal changeScore)`.

- [ ] **Step 1: Write the failing test**

```java
class FrameTypeClassifierTest {
    private final FrameTypeClassifier classifier = new FrameTypeClassifier();

    @Test
    void nullConfidenceUsesChangeScore() {
        assertThat(classifier.classify(null, new BigDecimal("0.5")))
                .isEqualTo(FrameType.SLIDE_CHANGE);
        assertThat(classifier.classify(null, new BigDecimal("0.1249")))
                .isEqualTo(FrameType.NORMAL);
        assertThat(classifier.classify(null, null)).isEqualTo(FrameType.NORMAL);
    }

    @Test
    void lowConfidenceWinsOverSlideChange() {
        assertThat(classifier.classify(new BigDecimal("0.59"), new BigDecimal("0.9")))
                .isEqualTo(FrameType.LOW_CONFIDENCE);
    }

    @Test
    void thresholdBoundaries() {
        assertThat(classifier.classify(new BigDecimal("0.60"), new BigDecimal("0.125")))
                .isEqualTo(FrameType.SLIDE_CHANGE);
        assertThat(classifier.classify(new BigDecimal("0.60"), new BigDecimal("0.1249")))
                .isEqualTo(FrameType.NORMAL);
    }

    @Test
    void pendingFrameStartsWithEmptyPayload() {
        SessionId sessionId = new SessionId(UUID.randomUUID());
        SessionFrame frame = SessionFrame.pending(
                UUID.randomUUID(), sessionId, 1200, new BigDecimal("0.3"), "k", "hash");
        assertThat(frame.status()).isEqualTo(FrameStatus.PENDING);
        assertThat(frame.frameType()).isEqualTo(FrameType.NORMAL);
        assertThat(frame.boundingBoxes()).isEmpty();
        assertThat(frame.text()).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `docker compose --profile test run --rm test gradle test --tests '*FrameTypeClassifierTest' --no-daemon --console=plain`
Expected: FAIL at `compileTestJava` with `cannot find symbol: class FrameTypeClassifier` (and the frame types).

- [ ] **Step 3: Implement the five domain files**

Rules: `classify` returns `LOW_CONFIDENCE` only when `confidence != null && confidence.compareTo(LOW_CONFIDENCE_THRESHOLD) < 0`; else `SLIDE_CHANGE` when `changeScore != null && changeScore.compareTo(SLIDE_CHANGE_THRESHOLD) >= 0`; else `NORMAL`. `SessionFrame.pending` sets `text=null`, `boundingBoxes=List.of()`, `confidence=null`, `frameType=NORMAL`, `status=PENDING`, `errorMessage=null`; the compact constructor requires non-null `id`, `sessionId`, `boundingBoxes` (copy with `List.copyOf`), `frameType`, `status`, and rejects `timestampMs < 0`.

- [ ] **Step 4: Run test to verify it passes**

Run: same command.
Expected: `BUILD SUCCESSFUL`, `FrameTypeClassifierTest` 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/domain/session/FrameType.java \
  server/src/main/java/com/shijiangtai/m3classroom/domain/session/FrameStatus.java \
  server/src/main/java/com/shijiangtai/m3classroom/domain/session/OcrBox.java \
  server/src/main/java/com/shijiangtai/m3classroom/domain/session/SessionFrame.java \
  server/src/main/java/com/shijiangtai/m3classroom/domain/session/FrameTypeClassifier.java \
  server/src/test/java/com/shijiangtai/m3classroom/domain/session/FrameTypeClassifierTest.java
git commit -m "[M3B] 感知帧领域模型与类型规则"
```

---

### Task 2: SessionActor perception hooks

**Files:**
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/application/SessionActor.java`
- Test: `server/src/test/java/com/shijiangtai/m3classroom/application/SessionActorPerceptionTest.java`

**Interfaces:**
- Consumes: `OcrBox`, `SessionFrame`, `FrameType` (Task 1); existing `SessionText`, `SessionTextStore`, `FakeScheduler`, `InMemorySessionEventStore`, `InMemorySessionTextStore`.
- Produces (exact signatures):
  - `public synchronized Optional<UUID> recordSpeechFinal(String speakerType, UUID speakerRef, String text, Long startMs, Long endMs, BigDecimal confidence)`
  - `public synchronized void onTeacherSpeechClassified(double confidence)`
  - `public synchronized void onScreenProcessed(UUID frameId, long frameAtMs, String frameType)`
  - `public synchronized long currentAtMs()`
  - `public synchronized UUID currentAnswerId()`
  - `public Runnable addStatusListener(Consumer<SessionStatus> listener)`

Behavior to implement:
- `recordSpeechFinal`: return `Optional.empty()` when the session is not `IN_PROGRESS` or the text is blank; otherwise persist a `SessionText` with `source="asr"`, `status="final"`, `kind = "candidate".equals(speakerType) ? "answer" : "utterance"`, `atMs = endMs != null ? Math.max(0, endMs) : currentAtMs()`, model/prompt null; return the new id.
- `onTeacherSpeechClassified`: no-op unless `IN_PROGRESS` and classroom mode. Call `classroom.onTeacherUtterance(confidence, now)`. If any returned event is `classroom.question_asked` with payload `source=voice`, then lower open hands with reason `qa_ended`, increment `windowGeneration`, cancel old hand timers, append the classroom events, and schedule the first hand raise (`1–3 s`) and 30 s window timeout exactly as `handleAsk` does. Otherwise append the events only.
- `onScreenProcessed`: when `IN_PROGRESS`, append `perception.screen_changed` with `{frame_id, at_ms, frame_type}`; otherwise drop.
- `currentAtMs`: return the existing private `atMs(clock.now())`.
- `currentAnswerId`: return `interview.snapshot(clock.now()).answerId()` when interview mode and `IN_PROGRESS`, else null.
- `addStatusListener`: append to a new list; `notifyStatusChanged` calls the existing primary `statusListener` plus every added listener. `attachStatusListener` keeps its replace semantics. Return a `Runnable` that removes the listener.
- Factor the scheduling block currently inside `handleAsk` into `private long scheduleHandWindow(String expectedIntent)` and reuse it from both `handleAsk` and `onTeacherSpeechClassified`.

- [ ] **Step 1: Write the failing test**

Use the same fixture style as `SessionActorTest` (`fixture(TaskMode.FREE, ...)`) and `FakeScheduler`. Assertions:

```java
@Test
void highConfidenceClassifiedQuestionStartsVoiceQaAndSchedulesHands() {
    fixture.actor().handle(startCommand()); // existing helper: recording.ready + session.start
    fixture.actor().onTeacherSpeechClassified(0.9);

    List<SessionEvent> events = fixture.context().after(0);
    assertThat(events).extracting(SessionEvent::type)
            .containsSubsequence("classroom.question_asked", "classroom.state_changed");
    SessionEvent asked = events.stream()
            .filter(e -> e.type().equals("classroom.question_asked")).findFirst().orElseThrow();
    assertThat(asked.payload().get("source")).isEqualTo("voice");
    assertThat(asked.payload().get("intent")).isEqualTo("answer");
    assertThat(fixture.scheduler().pendingTasks()).isEqualTo(2); // hand raise + window timeout
}

@Test
void lowConfidenceClassifiedQuestionOnlySuggests() {
    fixture.actor().handle(startCommand());
    fixture.actor().onTeacherSpeechClassified(0.4);
    assertThat(fixture.context().after(0)).extracting(SessionEvent::type)
            .contains("classroom.question_suggested")
            .doesNotContain("classroom.question_asked");
    assertThat(fixture.classroom().macroState()).isEqualTo(ClassroomMacroState.LECTURING);
}

@Test
void classifiedQuestionIsIgnoredInInterviewModeAndAfterTerminal() { /* interview fixture + end, then call; assert no new events */ }

@Test
void recordSpeechFinalUsesKindAndAnswerIdForInterview() {
    // interview fixture, start; currentAnswerId() non-null
    UUID textId = fixture.actor().recordSpeechFinal("candidate", fixture.actor().currentAnswerId(),
            "我的回答", 0L, 1200L, new BigDecimal("0.91")).orElseThrow();
    SessionText text = fixture.textStore().all().stream()
            .filter(t -> t.id().equals(textId)).findFirst().orElseThrow();
    assertThat(text.kind()).isEqualTo("answer");
    assertThat(text.speakerType()).isEqualTo("candidate");
    assertThat(text.speakerRef()).isEqualTo(fixture.actor().currentAnswerId());
    assertThat(text.startMs()).isZero();
    assertThat(text.endMs()).isEqualTo(1200L);
}

@Test
void screenProcessedEmitsPerceptionEventOnlyWhileInProgress() {
    fixture.actor().handle(startCommand());
    UUID frameId = UUID.randomUUID();
    fixture.actor().onScreenProcessed(frameId, 1500, "slide_change");
    SessionEvent event = fixture.context().after(0).stream()
            .filter(e -> e.type().equals("perception.screen_changed")).findFirst().orElseThrow();
    assertThat(event.payload()).containsEntry("frame_id", frameId)
            .containsEntry("at_ms", 1500L).containsEntry("frame_type", "slide_change");
}
```

Also assert `addStatusListener` receives `IN_PROGRESS` and terminal statuses without replacing the primary listener (call `attachStatusListener` first, then add).

- [ ] **Step 2: Run test to verify it fails**

Run: `docker compose --profile test run --rm test gradle test --tests '*SessionActorPerceptionTest' --no-daemon --console=plain`
Expected: FAIL at `compileTestJava` with `cannot find symbol: method onTeacherSpeechClassified` / `recordSpeechFinal` / `onScreenProcessed` / `addStatusListener`.

- [ ] **Step 3: Implement the actor changes**

Keep all state mutations inside `synchronized` methods. Do not call the classifier or any blocking IO from the actor. `onTeacherSpeechClassified` must not throw when the session is terminal (return quietly). `recordSpeechFinal` must not persist when `session.startedAt() == null`.

- [ ] **Step 4: Run test to verify it passes**

Run: same command, then the existing actor tests:
`docker compose --profile test run --rm test gradle test --tests '*SessionActorTest' --tests '*SessionActorPerceptionTest' --no-daemon --console=plain`
Expected: both classes pass; no existing behavior changed.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/application/SessionActor.java \
  server/src/test/java/com/shijiangtai/m3classroom/application/SessionActorPerceptionTest.java
git commit -m "[M3B] SessionActor 感知钩子与语音问答触发"
```

---

### Task 3: Ports, Python HTTP adapter, and fallbacks

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/application/port/{OcrClient,OcrResult,TeacherSpeechClassifier,ClassifyResult}.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/perception/PerceptionAiProperties.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/perception/PythonPerceptionHttpClient.java`
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/config/M3Config.java` — add `@ConditionalOnMissingBean` fallbacks for `OcrClient` and `TeacherSpeechClassifier` that throw `DomainException(ErrorCode.SERVICE_UNAVAILABLE, ...)`.
- Test: `server/src/test/java/com/shijiangtai/m3classroom/infrastructure/perception/PythonPerceptionHttpClientTest.java`

**Interfaces:**
- Produces:
  - `interface OcrClient { OcrResult recognize(byte[] jpeg); }`
  - `record OcrResult(String text, List<OcrBox> boxes, BigDecimal confidence, String modelVersion)`
  - `interface TeacherSpeechClassifier { ClassifyResult classify(String text, List<String> recentTexts, String mode); }`
  - `record ClassifyResult(String label, BigDecimal confidence, String modelVersion, String promptVersion)`
  - `@ConfigurationProperties(prefix="m3.ai") record PerceptionAiProperties(String baseUrl, String serviceToken, Duration ocrTimeout, Duration classifyTimeout, Duration asrConnectTimeout)`
  - `@Component @ConditionalOnProperty(name="m3.ai.base-url") class PythonPerceptionHttpClient implements OcrClient, TeacherSpeechClassifier`, constructor `(PerceptionAiProperties, tools.jackson.databind.ObjectMapper)`.
- Python contracts (already deployed):
  - `POST {base}/internal/v1/ocr` body `{"image_base64": "..."}` → `{"text","boxes":[{"text","x1","y1","x2","y2","confidence"}],"confidence","model_version","latency_ms"}`.
  - `POST {base}/internal/v1/classroom/classify` body `{"text","recent_texts","mode"}` → `{"label":"question|not_question","confidence","model_version","prompt_version"}`.
  - Header `X-Service-Token` when `serviceToken` is non-blank.
  - Defaults when properties are null: OCR 15 s, classify 3 s, connect 3 s.

- [ ] **Step 1: Write the failing test**

Use `com.sun.net.httpserver.HttpServer` on port 0. Tests:
1. `ocrPostsBase64AndMapsBoxes`: server records method/path/header/body; returns a fixture JSON with two boxes; assert `OcrResult.text`, `boxes` coordinates, `confidence`, `modelVersion`, and that the request JSON has `image_base64` (not `imageBase64`) and the service token header.
2. `classifyPostsSnakeCaseAndMapsLabel`: assert body `{"text":"...","recent_texts":["a"],"mode":"free"}` and result.
3. `non200ThrowsRuntimeException`: server returns 503; assert `RuntimeException` with message containing `OCR 服务不可用` / `教师话语分类服务不可用`.
4. `timeoutThrowsRuntimeException`: server sleeps 300 ms with a 50 ms client timeout; assert RuntimeException.

- [ ] **Step 2: Run test to verify it fails**

Run: `docker compose --profile test run --rm test gradle test --tests '*PythonPerceptionHttpClientTest' --no-daemon --console=plain`
Expected: FAIL at `compileTestJava` (missing classes).

- [ ] **Step 3: Implement the ports and HTTP client**

Use `HttpClient.newBuilder().connectTimeout(...)`; `HttpRequest` with `timeout(...)`, header `Content-Type: application/json`, optional `X-Service-Token`. Parse with Jackson 3 `ObjectMapper.readTree`. On `IOException`, `InterruptedException` (restore interrupt), non-2xx, or missing fields, throw `new RuntimeException("OCR 服务不可用", cause)`. Do not catch in the adapter caller (Task 6 handles).

- [ ] **Step 4: Run test to verify it passes**

Run: same command.
Expected: `BUILD SUCCESSFUL`, 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/application/port/OcrClient.java \
  server/src/main/java/com/shijiangtai/m3classroom/application/port/OcrResult.java \
  server/src/main/java/com/shijiangtai/m3classroom/application/port/TeacherSpeechClassifier.java \
  server/src/main/java/com/shijiangtai/m3classroom/application/port/ClassifyResult.java \
  server/src/main/java/com/shijiangtai/m3classroom/infrastructure/perception/PerceptionAiProperties.java \
  server/src/main/java/com/shijiangtai/m3classroom/infrastructure/perception/PythonPerceptionHttpClient.java \
  server/src/main/java/com/shijiangtai/m3classroom/infrastructure/config/M3Config.java \
  server/src/test/java/com/shijiangtai/m3classroom/infrastructure/perception/PythonPerceptionHttpClientTest.java
git commit -m "[M3B] Python OCR 与教师话语分类适配器"
```

---

### Task 4: Python ASR WebSocket adapter

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/application/port/{AsrStreamClient,AsrSession,AsrRequest,AsrChunk}.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/perception/PythonAsrStreamClient.java`
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/config/M3Config.java` — add `@ConditionalOnMissingBean AsrStreamClient` that throws `DomainException(SERVICE_UNAVAILABLE, "ASR 服务不可用")` from `open`.
- Test: `server/src/test/java/com/shijiangtai/m3classroom/infrastructure/perception/PythonAsrStreamClientIT.java`

**Interfaces:**
- Produces:
  - `interface AsrStreamClient { AsrSession open(AsrRequest request); }`
  - `interface AsrSession extends AutoCloseable { void send(byte[] pcm); void finish(); void onChunk(Consumer<AsrChunk> listener); void onClosed(Runnable listener); void onError(Consumer<RuntimeException> listener); void close(); }`
  - `record AsrRequest(SessionId sessionId, int sampleRate, String format, String language)`
  - `record AsrChunk(String text, boolean finalChunk, Long beginMs, Long endMs, BigDecimal confidence)`
  - `@Component @ConditionalOnProperty(name="m3.ai.base-url") class PythonAsrStreamClient implements AsrStreamClient`, constructor `(PerceptionAiProperties, tools.jackson.databind.ObjectMapper)`.
- Wire contract: URI `ws(s)://{base}/internal/v1/asr/stream?session_id=...&sample_rate=16000&format=pcm&language=zh`; header `X-Service-Token`; client sends binary PCM frames; `finish()` sends text `{"type":"finish"}` after queued audio; server sends `{"type":"chunk","text","is_final","begin_ms","end_ms","confidence"}`, `{"type":"done"}`, `{"type":"error","code","message"}`.

Implementation decisions:
- Convert `http`→`ws`, `https`→`wss`; strip trailing `/`.
- `open` blocks up to `asrConnectTimeout` (default 3 s) for the JDK WebSocket handshake; failure throws `RuntimeException("ASR 服务不可用", cause)`.
- One daemon sender thread per session with an `ArrayBlockingQueue<byte[]>` capacity 64; `send` offers, and on a full queue invokes the error listener and closes. A zero-length array is the finish sentinel; the sender sends text `{"type":"finish"}` after draining.
- Listener callbacks parse JSON; `done` invokes closed listeners; `error` invokes error listeners with a `RuntimeException(message)`.
- `close()` closes the WebSocket and interrupts the sender; callbacks after close are ignored.

- [ ] **Step 1: Write the failing test**

`@SpringBootTest(webEnvironment = RANDOM_PORT)` extending `PostgresIntegrationTest`, with a nested `@TestConfiguration` implementing `WebSocketConfigurer` that registers a test `TextWebSocketHandler` at `/internal/v1/asr/stream`. The handler:
- records `X-Service-Token`, query params;
- for each binary frame replies with a partial chunk JSON;
- on text `{"type":"finish"}` replies with `{"type":"chunk","text":"这是最后一句","is_final":true,"begin_ms":0,"end_ms":1500,"confidence":0.9}` then `{"type":"done"}` and closes.

Test constructs `new PythonAsrStreamClient(props, objectMapper)` with `baseUrl = "http://localhost:" + port` (no `m3.ai.base-url` property needed), opens a session, sends two chunks, calls `finish()`, and waits on a `LinkedBlockingQueue`:
- assert two partial chunks and one final chunk;
- assert closed listener fires;
- assert the test server saw `sample_rate=16000`, `format=pcm`, `language=zh`, and the service token header.
Also test `open` against a closed port throws `RuntimeException` within the connect timeout.

- [ ] **Step 2: Run test to verify it fails**

Run: `docker compose --profile test run --rm test gradle test --tests '*PythonAsrStreamClientIT' --no-daemon --console=plain`
Expected: FAIL at `compileTestJava` (missing classes).

- [ ] **Step 3: Implement the adapter**

Use `HttpClient.newWebSocketBuilder().header(...).buildAsync(uri, listener).get(timeout, MILLISECONDS)`. Keep the sender queue bounded; never block the caller. Ensure `finish()` is idempotent. Ensure `close()` is safe before the handshake completes.

- [ ] **Step 4: Run test to verify it passes**

Run: same command.
Expected: `BUILD SUCCESSFUL`, all adapter IT cases pass.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/application/port/AsrStreamClient.java \
  server/src/main/java/com/shijiangtai/m3classroom/application/port/AsrSession.java \
  server/src/main/java/com/shijiangtai/m3classroom/application/port/AsrRequest.java \
  server/src/main/java/com/shijiangtai/m3classroom/application/port/AsrChunk.java \
  server/src/main/java/com/shijiangtai/m3classroom/infrastructure/perception/PythonAsrStreamClient.java \
  server/src/main/java/com/shijiangtai/m3classroom/infrastructure/config/M3Config.java \
  server/src/test/java/com/shijiangtai/m3classroom/infrastructure/perception/PythonAsrStreamClientIT.java
git commit -m "[M3B] Python 流式 ASR WebSocket 适配器"
```

---

### Task 5: Frame persistence

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/application/port/SessionFrameStore.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/SessionFrameEntity.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/SessionFrameJpaRepository.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/SessionFrameStoreAdapter.java`
- Test: `server/src/test/java/com/shijiangtai/m3classroom/persistence/SessionFramePersistenceIT.java`

**Interfaces:**
- Produces: `interface SessionFrameStore { void save(SessionFrame frame); Optional<SessionFrame> find(UUID frameId); void markProcessed(UUID frameId, String text, List<OcrBox> boxes, BigDecimal confidence, FrameType frameType, String objectKey); void markFailed(UUID frameId, String errorMessage); }`
- Table `m3_classroom.frames` already exists (V4). Entity maps: `id`, `session_id`, `timestamp_ms`, `text`, `bounding_boxes` (jsonb string), `confidence` numeric(3,2), `change_score` numeric(5,4), `frame_type`, `object_key`, `content_hash`, `status`, `error_message`, `created_at` (insertable=false), `updated_at`.
- Adapter serializes `List<OcrBox>` to JSON with the injected Jackson 3 `ObjectMapper` (snake_case keys) and deserializes on read; `updated_at` is set to `Instant.now()` on insert/update.

- [ ] **Step 1: Write the failing IT**

```java
class SessionFramePersistenceIT extends PostgresIntegrationTest {
    @Autowired SessionFrameStore store;
    @Autowired SessionRepository sessions;

    @Test
    void roundTripsPendingAndProcessedFrame() {
        SessionId id = savePreparingSession();
        UUID frameId = UUID.randomUUID();
        store.save(SessionFrame.pending(frameId, id, 1200, new BigDecimal("0.20"),
                "m3/frames/" + id.value() + "/" + frameId + ".jpg", "hash"));
        SessionFrame pending = store.find(frameId).orElseThrow();
        assertThat(pending.status()).isEqualTo(FrameStatus.PENDING);
        assertThat(pending.changeScore()).isEqualByComparingTo("0.20");

        List<OcrBox> boxes = List.of(new OcrBox("标题", 1.0, 2.0, 3.0, 4.0, new BigDecimal("0.88")));
        store.markProcessed(frameId, "识别文本", boxes, new BigDecimal("0.88"),
                FrameType.LOW_CONFIDENCE, pending.objectKey());

        SessionFrame processed = store.find(frameId).orElseThrow();
        assertThat(processed.status()).isEqualTo(FrameStatus.PROCESSED);
        assertThat(processed.text()).isEqualTo("识别文本");
        assertThat(processed.boundingBoxes()).containsExactlyElementsOf(boxes);
        assertThat(processed.frameType()).isEqualTo(FrameType.LOW_CONFIDENCE);
    }

    @Test
    void markFailedStoresTruncatedError() { /* save, markFailed with 600 chars, assert length 500 */ }

    @Test
    void unknownFrameUpdatesAreNoOps() { /* markProcessed/markFailed random id do not throw */ }
}
```

`savePreparingSession()` uses `Session.createPreparing(...)` and `sessions.save(...)` with `TestFixtures` values (same pattern as `SessionRepositoryIT`).

- [ ] **Step 2: Run test to verify it fails**

Run: `docker compose --profile test run --rm test gradle test --tests '*SessionFramePersistenceIT' --no-daemon --console=plain`
Expected: FAIL at `compileTestJava` (missing store/entity).

- [ ] **Step 3: Implement the entity, repository, and adapter**

Use `@Entity @Table(name="frames", schema="m3_classroom")`; `@Column(columnDefinition="jsonb")` for `bounding_boxes`; `@Transactional` on mutating adapter methods. `markFailed` truncates the message to 500 characters. Unknown ids: find by id, return without saving when absent.

- [ ] **Step 4: Run test to verify it passes**

Run: same command.
Expected: `BUILD SUCCESSFUL`, 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/application/port/SessionFrameStore.java \
  server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/SessionFrameEntity.java \
  server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/SessionFrameJpaRepository.java \
  server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/SessionFrameStoreAdapter.java \
  server/src/test/java/com/shijiangtai/m3classroom/persistence/SessionFramePersistenceIT.java
git commit -m "[M3B] 感知帧 m3.frames 持久化"
```

---

### Task 6: Perception application service and screenshot endpoint

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/application/PerceptionApplicationService.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/application/view/PerceptionFrameView.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/api/PerceptionScreenController.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/api/dto/PerceptionScreenResponse.java`
- Test: `server/src/test/java/com/shijiangtai/m3classroom/application/PerceptionApplicationServiceTest.java`
- Test: `server/src/test/java/com/shijiangtai/m3classroom/api/PerceptionScreenIT.java`
- Test support: `server/src/test/java/com/shijiangtai/m3classroom/support/{FakeOcrClient,InMemorySessionFrameStore}.java`

**Interfaces:**
- Consumes: `SessionFrameStore` (Task 5), `OcrClient`/`OcrResult` (Task 3), `FrameTypeClassifier`/`FrameType` (Task 1), `SessionActor.onScreenProcessed` (Task 2), `SessionRepository`, `SessionAccessPolicy`, `SessionRuntimeRegistry`, `MaterialObjectStorage`, `IdGenerator`, `ClockProvider`.
- Produces:
  - `record PerceptionFrameView(UUID frameId, String status)`
  - `@Service class PerceptionApplicationService` with:
    - `public PerceptionFrameView ingestScreen(CurrentUser user, SessionId sessionId, byte[] jpeg, String contentType, long atMs, BigDecimal changeScore)`
    - test seam constructor accepting an `ExecutorService` owned by the caller.
  - `@RestController @RequestMapping("/api/v1/sessions/{sessionId}/perception")` with `POST /screen` consuming `multipart/form-data`, fields `file`, `at_ms`, optional `change_score`, returning `202 Accepted` and `PerceptionScreenResponse(UUID frameId, String status)` (snake_case JSON).

Behavior:
- Validate: non-empty JPEG, `image/jpeg`, ≤ 5 MB, `at_ms >= 0`, `change_score` null or `[0,1]` → `DomainException(VALIDATION_ERROR, ...)`.
- Load session, `requireOwner`, status must be `IN_PROGRESS` else `SESSION_STATE_INVALID`.
- Generate frame id; object key `m3/frames/{sessionId}/{frameId}.jpg`; `storage.put` failure logs WARN and leaves `objectKey=null` but continues.
- Save `SessionFrame.pending(...)` synchronously; return view `(frameId, "pending")`.
- Submit OCR to a bounded single-thread daemon executor (queue 32). On success: `FrameTypeClassifier.classify(result.confidence(), changeScore)`, `frameStore.markProcessed(...)`, then `runtimeRegistry.find(sessionId).ifPresent(a -> a.onScreenProcessed(frameId, atMs, type.wireValue()))`. On any `RuntimeException`: `frameStore.markFailed(frameId, message)` and log WARN with session/frame id. On `RejectedExecutionException`: mark failed with `"OCR 队列已满"`.
- Never throw from the async path into the request thread.

- [ ] **Step 1: Write the failing unit test**

`PerceptionApplicationServiceTest` with fakes:
1. `ingestSavesPendingFrameAndReturns202View`: assert `frameStore.find(frameId).status == PENDING`, object key present, view status `pending`.
2. `ocrSuccessMarksProcessedAndEmitsEvent`: fake OCR returns text/confidence 0.9 and the request carries `change_score=0.3`; after awaiting the executor, assert frame `PROCESSED`, `FrameType.SLIDE_CHANGE`, and actor event `perception.screen_changed` (use a Mockito mock of `SessionActor` returned from a mocked `SessionRuntimeRegistry`, then verify `onScreenProcessed`).
3. `ocrFailureMarksFailedAndDoesNotThrow`: fake throws; assert `FAILED`, error message, no event.
4. `storageFailureStillOcrProcesses`: storage throws; assert objectKey null and frame processed.
5. `nonOwnerIsForbidden`: `requireOwner` throws `FORBIDDEN`.
6. `nonInProgressSessionRejected`: status `PREPARING` → `SESSION_STATE_INVALID`.
7. `invalidParamsRejected`: wrong content type, >5 MB array, negative atMs, changeScore 1.1 → `VALIDATION_ERROR`.

Use a deterministic executor (single-thread, await termination or a polling helper) so assertions do not race.

- [ ] **Step 2: Run test to verify it fails**

Run: `docker compose --profile test run --rm test gradle test --tests '*PerceptionApplicationServiceTest' --no-daemon --console=plain`
Expected: FAIL at `compileTestJava` (missing service).

- [ ] **Step 3: Implement the service**

Use the existing `SessionAccessPolicy.requireOwner`, `SessionRepository.findById`, `IdGenerator.nextId`, `MaterialObjectStorage.put`. Create its own daemon executor when the production constructor is used; close it in `@PreDestroy`. The async task must catch `RuntimeException` and never propagate.

- [ ] **Step 4: Write the failing controller IT**

`PerceptionScreenIT extends PostgresIntegrationTest` with `@AutoConfigureMockMvc` and a `@TestConfiguration` exposing a `FakeOcrClient` bean (`@Primary`). Setup a published free task and start a session (same pattern as `SessionControllerIT`). Tests:
- multipart JPEG (`MockMvcRequestBuilders.multipart`) with `at_ms=1000`, `change_score=0.3` → `202`, `frame_id` present, `status=pending`;
- poll `SessionFrameStore.find` until `PROCESSED`; assert `InMemoryMaterialObjectStorage` has the object key and `SessionEventStore.after(sessionId,0,100)` contains `perception.screen_changed`;
- fake OCR failure → frame `FAILED`, no `perception.screen_changed`, session still `IN_PROGRESS`;
- non-owner student → 403; preparing session → 409; wrong content type / >5 MB / negative at_ms → 400.

Use the header helper from `SessionControllerIT` (`X-Account-Id`, `X-School-Id`, `X-Account-Type`) and a small polling helper (50 ms interval, 5 s timeout).

- [ ] **Step 5: Run tests to verify they fail**

Run: `docker compose --profile test run --rm test gradle test --tests '*PerceptionScreenIT' --no-daemon --console=plain`
Expected: 404 (no controller), so the tests fail.

- [ ] **Step 6: Implement the controller and DTO**

Delegate to the service; read `file.getBytes()` and let `IOException` become `DomainException(VALIDATION_ERROR, "截图读取失败")`. Return `ResponseEntity.accepted().body(...)`. No business logic in the controller.

- [ ] **Step 7: Run tests to verify they pass**

Run:
`docker compose --profile test run --rm test gradle test --tests '*PerceptionApplicationServiceTest' --tests '*PerceptionScreenIT' --no-daemon --console=plain`
Expected: both classes pass.

- [ ] **Step 8: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/application/PerceptionApplicationService.java \
  server/src/main/java/com/shijiangtai/m3classroom/application/view/PerceptionFrameView.java \
  server/src/main/java/com/shijiangtai/m3classroom/api/PerceptionScreenController.java \
  server/src/main/java/com/shijiangtai/m3classroom/api/dto/PerceptionScreenResponse.java \
  server/src/test/java/com/shijiangtai/m3classroom/application/PerceptionApplicationServiceTest.java \
  server/src/test/java/com/shijiangtai/m3classroom/api/PerceptionScreenIT.java \
  server/src/test/java/com/shijiangtai/m3classroom/support/FakeOcrClient.java \
  server/src/test/java/com/shijiangtai/m3classroom/support/InMemorySessionFrameStore.java
git commit -m "[M3B] 屏幕截图接口与异步 OCR"
```

---

### Task 7: Perception WebSocket, protocol parsing, and connection lifecycle

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/ws/PerceptionProtocolParser.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/ws/PerceptionConnectionRegistry.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/ws/PerceptionWebSocketHandler.java`
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/ws/WebSocketConfig.java` — register `PERCEPTION_PATH = "/ws/v1/sessions/{sessionId}/perception"` with the same `WsHandshakeInterceptor`.
- Test: `server/src/test/java/com/shijiangtai/m3classroom/infrastructure/ws/PerceptionProtocolParserTest.java`
- Test: `server/src/test/java/com/shijiangtai/m3classroom/ws/PerceptionWebSocketIT.java`
- Test support: `server/src/test/java/com/shijiangtai/m3classroom/support/{FakeAsrStreamClient,FakeTeacherSpeechClassifier}.java`

**Interfaces:**
- Consumes: `AsrStreamClient`/`AsrSession`/`AsrChunk` (Task 4), `TeacherSpeechClassifier` (Task 3), `SessionActor` hooks (Task 2), `WsHandshakeInterceptor` attributes, `SessionRepository`, `SessionRuntimeRegistry`, `SchedulerPort`, `ObjectMapper`, `ClockProvider`.
- Produces:
  - `sealed interface PerceptionProtocolParser.Message permits AudioStart, AudioStop` with `record AudioStart(int sampleRate, String format, int channels)` and `record AudioStop()`; `static Message parse(String json)` throws `DomainException(VALIDATION_ERROR, ...)` for unknown/invalid messages.
  - `@Component final class PerceptionConnectionRegistry` with `void register(SessionId, WebSocketSession, SessionActor)` and `void unregister(SessionId, WebSocketSession)`. Registration replaces any previous socket for the session (close 1000); subscribes via `actor.addStatusListener`; on any terminal status closes the current socket 4002 and removes the listener. `unregister` removes only when the mapping still points at that socket.
  - `@Component final class PerceptionWebSocketHandler extends AbstractWebSocketHandler`, constants `CLOSE_AUTH=4001`, `CLOSE_FAILED=4002`, `CLOSE_STATE=4003`; drain timeout 2 s.
- Server→client messages (no envelope):
  - `{"type":"asr.partial","text":"...","at_ms":1234}`
  - `{"type":"asr.final","text":"...","text_id":"...","at_ms":1500}`
  - `{"type":"error","code":"VALIDATION_ERROR|SERVICE_UNAVAILABLE","message":"..."}`

Handler behavior:
- `afterConnectionEstablished`: auth attribute → 4001; missing session/account → 4001; persisted session lookup → 4002 for terminal, 4003 for `PREPARING`; account must equal `studentAccountId` → 4001; `runtimeRegistry.getOrCreate` failure → 4002; register in `PerceptionConnectionRegistry`; set binary size limit 256 KB.
- First text message must parse as `AudioStart`; validate `format=pcm16`, `sample_rate=16000`, `channels=1`; `actor.isInProgress()` must be true; open `AsrStreamClient.open(new AsrRequest(sessionId, 16000, "pcm", "zh"))`; on open failure send `error SERVICE_UNAVAILABLE` and keep the socket open (audio discarded). Record `audioStartAtMs = actor.currentAtMs()`.
- Binary before start, duplicate start, `audio.stop` before start, unknown text → send `error VALIDATION_ERROR` then close 4003.
- On `AudioStop`: `asr.finish()`, schedule a 2 s timeout via `SchedulerPort`; on ASR `done` or timeout close 1000. Ignore further binary.
- On ASR chunk:
  - partial → send `asr.partial` (at_ms = `audioStartAtMs + (endMs != null ? endMs : 0)` clamped ≥0);
  - final → compute `startMs/endMs` with `audioStartAtMs`; determine classroom vs interview from `actor.session().mode() == TaskMode.INTERVIEW`; `textId = actor.recordSpeechFinal(speakerType, speakerRef, text, startMs, endMs, confidence)`; if present send `asr.final`; for classroom only, submit classification to a per-connection single-thread daemon executor: `classify(text, List.of(), mode.wireValue())`; if `label=question` call `actor.onTeacherSpeechClassified(confidence.doubleValue())`. Exceptions are logged WARN and never close the socket.
- On ASR `error`/unexpected close: send `error SERVICE_UNAVAILABLE`; keep the perception socket open until `audio.stop`; log WARN.
- `afterConnectionClosed`: `registry.unregister`, close ASR, `shutdown()` the classifier executor (queued tasks finish), cancel the drain timeout.
- Socket writes from ASR callback threads must synchronize on the `WebSocketSession`; ignore `IOException` on a closed socket.

- [ ] **Step 1: Write the failing parser test**

`PerceptionProtocolParserTest`: valid start (`{"type":"audio.start","format":"pcm16","sample_rate":16000,"channels":1}`), valid stop, missing type, `format=mp3`, `sample_rate=8000`, `channels=2`, malformed JSON → `DomainException` with `VALIDATION_ERROR`.

- [ ] **Step 2: Run parser test to verify it fails**

Run: `docker compose --profile test run --rm test gradle test --tests '*PerceptionProtocolParserTest' --no-daemon --console=plain`
Expected: FAIL at compile.

- [ ] **Step 3: Implement parser + registry + handler + WebSocketConfig registration**

Follow the exact close-code and message contract above. Keep the handler thin: protocol parsing in the parser, connection replacement/terminal close in the registry, actor mutation in `SessionActor`.

- [ ] **Step 4: Write the failing WebSocket IT**

`PerceptionWebSocketIT extends PostgresIntegrationTest` with `@SpringBootTest(webEnvironment=RANDOM_PORT)` and a `@TestConfiguration` providing `@Primary FakeAsrStreamClient` and `@Primary FakeTeacherSpeechClassifier`. Setup a published free task and an `IN_PROGRESS` session (create → recording.ready via actor → start), request a ticket via `POST /api/v1/sessions/{id}/ws-ticket`, then connect with Spring `StandardWebSocketClient` to `ws://localhost:{port}/ws/v1/sessions/{id}/perception?ticket=...`.

Tests:
1. `partialAndFinalArePushedAndFinalIsPersisted`: send `audio.start`; fake ASR emits a partial then a final; assert messages `asr.partial` / `asr.final`; poll `SessionTextStore.bySession(sessionId,"teacher")` for the final text; assert `kind=utterance`.
2. `highConfidenceQuestionTriggersVoiceQa`: fake classifier returns `question`/0.9; emit final; poll `SessionEventStore.after` for `classroom.question_asked` with `source=voice` and a following `classroom.state_changed`.
3. `lowConfidenceQuestionOnlySuggests`: classifier returns `question`/0.4; assert `classroom.question_suggested`, no `question_asked`.
4. `asrOpenFailureKeepsSessionAlive`: fake ASR throws on open; send `audio.start`; assert `error SERVICE_UNAVAILABLE`, then `audio.stop` → 1000; session still `IN_PROGRESS` via `SessionRepository`.
5. `invalidStartCloses4003` and `binaryBeforeStartCloses4003`.
6. `invalidTicketCloses4001`, `preparingSessionCloses4003`, `terminalSessionCloses4002`.
7. `secondConnectionReplacesFirst`: connect two sockets; first receives 1000; second can start audio.
8. `sessionEndClosesPerception4002`: end the session via REST/actor while connected; socket closes 4002.
9. `audioStopDrainsFinalThenCloses1000`: fake ASR sends a final 100 ms after `finish()`; assert `asr.final` arrives and close is 1000.
10. `lateFinalAfterEndIsNotPersisted`: end session, then emit final; assert no new `session_texts` row and no exception.

Use `LinkedBlockingQueue` for messages and a 10 s timeout, mirroring `ClassroomWebSocketIT`.

- [ ] **Step 5: Run IT to verify it fails**

Run: `docker compose --profile test run --rm test gradle test --tests '*PerceptionWebSocketIT' --no-daemon --console=plain`
Expected: FAIL (handler/path missing → handshake fails or 404). Read the failure to confirm the path is registered but the handler is incomplete, then implement.

- [ ] **Step 6: Implement until green**

Run the IT plus parser test:
`docker compose --profile test run --rm test gradle test --tests '*PerceptionProtocolParserTest' --tests '*PerceptionWebSocketIT' --no-daemon --console=plain`
Expected: `BUILD SUCCESSFUL`; all cases pass.

- [ ] **Step 7: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/infrastructure/ws/PerceptionProtocolParser.java \
  server/src/main/java/com/shijiangtai/m3classroom/infrastructure/ws/PerceptionConnectionRegistry.java \
  server/src/main/java/com/shijiangtai/m3classroom/infrastructure/ws/PerceptionWebSocketHandler.java \
  server/src/main/java/com/shijiangtai/m3classroom/infrastructure/ws/WebSocketConfig.java \
  server/src/test/java/com/shijiangtai/m3classroom/infrastructure/ws/PerceptionProtocolParserTest.java \
  server/src/test/java/com/shijiangtai/m3classroom/ws/PerceptionWebSocketIT.java \
  server/src/test/java/com/shijiangtai/m3classroom/support/FakeAsrStreamClient.java \
  server/src/test/java/com/shijiangtai/m3classroom/support/FakeTeacherSpeechClassifier.java
git commit -m "[M3B] 感知 WebSocket、ASR 落库与语音问答"
```

---

### Task 8: Contract docs, evidence, progress board, full verification

**Files:**
- Modify: `docs/design/05A-M3-课堂状态机与WebSocket.md` §3.3 and §6.2 — add perception WS message shapes/close codes, `audio.start` validation, end ordering, frame type rules, and the P0 decision to omit acoustic events.
- Modify: `docs/implementation/04-M3-实现设计.md` — add the new classes and method signatures from this plan.
- Create: `docs/team/evidence/2026-11-05-m3b-perception.md` — scope, RED/GREEN evidence, test counts, smoke transcript, deferred items.
- Modify: `docs/team/02-进度看板.md` — only the M3B row blocker and one §5 update row with the evidence link; do not change other rows or percentages beyond what the board's rules allow.

- [ ] **Step 1: Run the full suite**

Run: `docker compose --profile test run --rm test gradle clean test --no-daemon --console=plain`
Expected: `BUILD SUCCESSFUL`; M3B commit scope should be the previous 53 classes / 410 tests plus the new test classes; 0 failure / 0 error. Record the exact classes/tests count.

- [ ] **Step 2: Compose smoke (degraded AI path)**

```bash
docker compose up -d --build
curl -fsS http://localhost:8080/actuator/health
```
Create a free task, publish, create a session, start it, request a ticket, connect to the perception WS, send `audio.start`, one binary frame, `audio.stop`. With `m3.ai.base-url` unset, expect an `error SERVICE_UNAVAILABLE` message, then normal 1000 close, and `GET /api/v1/sessions/{id}` still `in_progress`. POST a JPEG screenshot and expect 202 + a `failed` frame (OCR unavailable). Record the transcript in the evidence file.

- [ ] **Step 3: Update docs and evidence**

In 05A, state explicitly: partial/final are perception-WS messages; `perception.acoustic` is deferred to M4's audio-feature work; `final`/`chart` frame types require the vision model and are deferred. In implementation 04, add `PerceptionApplicationService`, `PerceptionWebSocketHandler`, `PerceptionConnectionRegistry`, `PythonAsrStreamClient`, `PythonPerceptionHttpClient`, `SessionFrameStore`.

- [ ] **Step 4: Commit docs**

```bash
git add docs/design/05A-M3-课堂状态机与WebSocket.md \
  docs/implementation/04-M3-实现设计.md \
  docs/team/evidence/2026-11-05-m3b-perception.md \
  docs/team/02-进度看板.md
git commit -m "[M3B] 感知通道契约、证据与进度"
```

---

## Self-Review

- **Spec coverage:** §2.1 perception WS → Tasks 4/7; §2.2 classification → Tasks 2/3/7; §2.3 screen/OCR → Tasks 1/5/6; §2.4 degradation → Tasks 3/4/6/7 failure tests; §2.5 no-Python CI → fake ports in Tasks 6/7; §4 every decision is reflected in the task interfaces; §7 failure matrix covered by Task 6/7 tests; §8 test matrix covered.
- **Type consistency:** `OcrBox` lives in `domain/session` and is used by `OcrResult`, `SessionFrame`, and `SessionFrameStore`; `AsrChunk.finalChunk` is consistently named; `recordSpeechFinal` returns `Optional<UUID>` everywhere; `onScreenProcessed(UUID,long,String)` matches the handler call.
- **Review Focus tests:** illegal messages → Task 7 parser/IT; AI outage → Tasks 4/6/7; null confidence → Task 1; late final/terminal → Tasks 2/7; duplicate connections → Task 7; voice ordering → Task 2 + 7.
- **Proportion:** code blocks are limited to signatures, test assertions, and exact contracts; bodies are left to the implementer.
