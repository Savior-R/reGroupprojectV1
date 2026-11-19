# M3 Frontend Classroom, Interview, and Replay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the M3 frontend slice: a minimal shared app shell, student task → session entry, classroom/interview rooms with three/two-stream MediaRecorder upload, WebSocket-driven state, and the review/replay page with synchronized multi-stream playback and event seek.

**Architecture:** Add the missing Vue 3 toolchain pieces (Vue Router, Pinia, Element Plus, Axios, Vitest) and build the M3 pages under `frontend/src/views/{task,classroom,recording,replay}` with M3 components under `frontend/src/components/{classroom,recording,replay}`. Shared infrastructure (`app/router.ts`, `api/client.ts`, `stores/auth.ts`, `layouts/ImmersiveLayout.vue`) is minimal and explicitly marked dev-only until M1/M2/M7 land. Pure logic (WS reducer, uploader buffering, replay sync) lives in `src/utils/` and is unit-tested with Vitest; browser media is mocked in tests.

**Tech Stack:** Vue 3.5 + `<script setup>`, TypeScript 6, Vite 8, Vue Router 4, Pinia, Element Plus, Axios, `fix-webm-duration`, Vitest + Vue Test Utils + jsdom.

**Spec:** `docs/design/09-前端设计.md` §2/§4.1/§6.5/§8/§10/§15, `docs/design/05A` §8/§13, `docs/design/05B` §3/§4/§7/§10, `docs/design/02` §6, `docs/design/10` §6.5/§6.6 (component-level coverage; browser E2E is out of scope).

## Global Constraints

- **Cross-module authorization:** the user chose full-stack on 2026-11-04. This plan creates the minimal shared frontend paths `frontend/src/app/**`, `frontend/src/api/**`, `frontend/src/stores/auth.ts`, `frontend/src/layouts/**`, and `frontend/package.json`/`vite.config.ts` (shared layer, Score/QA Owner) because none exist yet. The approval covers only these files for this plan; it does not extend to M1/M2/M7 pages or a full auth implementation.
- Dev identity only: `X-Account-Id` / `X-School-Id` / `X-Account-Type` headers from a `devIdentity` store; M1 JWT replaces this later. Never persist answer points, weights, or reference material in `localStorage`.
- Chrome/Edge only for recording/replay; unsupported browsers show a blocking message.
- Recording: camera/screen/audio MediaRecorder, 5 s timeslice, 5 MB parts, first part retained and re-uploaded after `fix-webm-duration`; interview uses camera+audio only.
- WebSocket: ticket is one-time (30 s); control WS reconnect with 30 s grace and `session.resume {last_seq, recording_state}`; page refresh/`recording_state=lost` fails the session; `seq` dedupe; `ping`→`pong`.
- Replay: audio is the master clock; 500 ms drift check, correct when >200 ms; events seek to `at_ms`; `?t={ms}` initial position; presigned URLs are 15 min.
- No Playwright/E2E in this plan; no perception WS; no training pages; no full visual design system (use Element Plus + minimal dark tokens).
- Tests: `npm --prefix frontend run test` (Vitest). Build: `npm --prefix frontend run build`. Install once with `npm --prefix frontend install`.
- Commit after every task with prefix `[M3-FE]`; stage only the task's files.

## Review Focus

1. **Upload completeness:** the uploader uploads all buffered data, re-uploads part 1 with the patched duration, and completes only after every part has an ETag; a failed part stops the flow and surfaces an error. Tests in Task 4.
2. **WS resume correctness:** events are deduped by `seq`, reconnect replays from `last_seq`, and expired grace/lost recording shows the failure state instead of silently reconnecting. Tests in Task 3.
3. **Replay drift/seek:** audio master clock, offset application, clamping, and event seek stay within the 200 ms correction rule. Tests in Task 7.
4. **No privileged data in UI:** virtual student cards show only name/avatar/state; classroom/interview pages never read weights/material/answer points. Tests in Task 5/6.
5. **Browser media failure paths:** permission denial, track ended, and unsupported browser stop recording and show a retryable error without leaving a half-started session. Tests in Task 4/5.

---

### Task 1: Frontend toolchain, shared API/auth/router shell

**Files:**
- Modify: `frontend/package.json`, `frontend/vite.config.ts`, `frontend/src/main.ts`
- Create: `frontend/src/app/router.ts`
- Create: `frontend/src/api/client.ts`
- Create: `frontend/src/stores/auth.ts`
- Create: `frontend/src/layouts/ImmersiveLayout.vue`
- Create: `frontend/src/types/{api,session,recording,event}.ts`
- Create: `frontend/src/types/fix-webm-duration.d.ts` (module declaration if the package has no types)
- Create: `frontend/src/views/errors/{ForbiddenPage,NotFoundPage}.vue`
- Test: `frontend/src/api/client.spec.ts`, `frontend/src/stores/auth.spec.ts`, `frontend/src/app/router.spec.ts`

**Interfaces:**
- Produces:
  - `authStore` with `accountId`, `schoolId`, `accountType`, `setDevIdentity(role: 'student' | 'teacher')`; presets use the `DevDataConfig` IDs (`...0010` teacher, `...0020` student, school `...0001`).
  - `apiClient` (Axios) base `/api/v1`, injects `X-Account-Id` / `X-School-Id` / `X-Account-Type`, maps 401/403/409/422/429/503 to `ApiError`.
  - `router` initially with `/` → `/student/tasks`, `/403`, `/:pathMatch(.*)*` → 404. Later tasks append the task, classroom, interview, and review routes.
  - `ImmersiveLayout` with top bar (back, title, status slot, exit confirm) and `<slot />`.
  - `types/session.ts`: `SessionView`, `SessionStatus`, `SessionSnapshot`, `ServerEvent`, `ClientCommand`.
  - `types/recording.ts`: `StreamType`, `RecordingStreamView`, `MultipartStart`, `PartUrl`, `ReplayView`.
- Dependencies: `vue-router`, `pinia`, `element-plus`, `axios`, `fix-webm-duration`; dev: `vitest`, `@vue/test-utils`, `jsdom`.

- [ ] **Step 1: Write the failing client/store tests**

```ts
it('injects dev identity headers', async () => { /* intercept adapter sees X-Account-Id etc. */ })
it('maps 422 to ApiError with fields', async () => { /* ... */ })
it('defaults to student identity and can switch to teacher', () => { /* ... */ })
it('registers the error routes and root redirect', () => { /* router.getRoutes() contains /403 and the catch-all */ })
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npm --prefix frontend run test -- --run src/api/client.spec.ts src/stores/auth.spec.ts src/app/router.spec.ts`
Expected: dependencies/modules not found.

- [ ] **Step 3: Add dependencies, test config, and implement the shell**

`vite.config.ts` adds the Vitest `jsdom` config and dev proxies:

```ts
server: { proxy: {
  '/api': { target: 'http://localhost:8080', changeOrigin: true },
  '/ws': { target: 'ws://localhost:8080', ws: true },
}}
test: { environment: 'jsdom', globals: true }
```

`main.ts` installs Pinia, Router, Element Plus, imports `element-plus/dist/index.css`. `client.ts` uses an Axios instance with a request interceptor reading the auth store; response interceptor normalizes errors to `{code,message,details,trace_id}`. The dev identity is stored in `sessionStorage` and can be overridden by `?dev_role=teacher` on first load.

- [ ] **Step 4: Run tests to verify they pass**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/package.json frontend/vite.config.ts frontend/src/main.ts frontend/src/app \
  frontend/src/api frontend/src/stores/auth.ts frontend/src/layouts frontend/src/types \
  frontend/src/views/errors
git commit -m "[M3-FE] 前端工具链、共享客户端与沉浸式外壳"
```

---

### Task 2: Student task entry and session creation

**Files:**
- Create: `frontend/src/api/{tasks,sessions}.ts`
- Create: `frontend/src/views/task/{StudentTasksPage,StudentTaskDetailPage}.vue`
- Modify: `frontend/src/app/router.ts` (add `/student/tasks` and `/student/tasks/:taskId`)
- Test: `frontend/src/views/task/StudentTaskDetailPage.spec.ts`

**Interfaces:**
- Produces:
  - `listStudentTasks(page,size)` → `PageResult<StudentTaskView>`.
  - `getStudentTask(taskId)` → `StudentTaskDetailView`.
  - `createSession(taskId)` → `SessionView`; `getSession(sessionId)`; `startSession`; `endSession`; `cancelSession`.
  - Task detail shows topic/mode/duration/virtual-student names only; “开始试讲” creates a session and routes to `/student/sessions/{id}/classroom` or `/interview` by mode; if an active session exists, route to its resume path.
  - Interview pre-check: show “2 路录制（摄像头 + 麦克风）” and question count; no answer points.

- [ ] **Step 1: Write the failing page test**

```ts
it('renders only public task fields and navigates to classroom', async () => { /* mock API; assert no weights/material/answer_points in text; click starts and routes */ })
it('routes interview mode to the interview room', async () => { /* ... */ })
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm --prefix frontend run test -- --run src/views/task/StudentTaskDetailPage.spec.ts`
Expected: module not found.

- [ ] **Step 3: Implement the pages and API modules**

Use Element Plus cards/buttons; keep the page minimal (no theme system). On 409 `SESSION_ACTIVE_EXISTS`, fetch the student list/detail to find the active session; if unavailable, show the error. Use `router.push` for navigation.

- [ ] **Step 4: Run test to verify it passes**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/api frontend/src/views/task frontend/src/views/task/StudentTaskDetailPage.spec.ts
git commit -m "[M3-FE] 学生任务入口与会话创建"
```

---

### Task 3: WebSocket client and classroom event reducer

**Files:**
- Create: `frontend/src/utils/wsClient.ts`
- Create: `frontend/src/utils/classroomReducer.ts`
- Create: `frontend/src/stores/interview.ts`
- Test: `frontend/src/utils/wsClient.spec.ts`, `frontend/src/utils/classroomReducer.spec.ts`

**Interfaces:**
- Produces:
  - `createWsClient(options: { sessionId: string; ticketProvider: () => Promise<string>; onEvent: (event: ServerEvent) => void; onStatus: (status: WsStatus) => void; recordingState: () => 'active' | 'lost' }): WsClient`.
  - `WsClient.connect()`, `send(type, payload)`, `resume()`, `close()`, `lastSeq()`.
  - Statuses: `connecting | connected | reconnecting(secondsLeft) | failed`.
  - On message: parse envelope, drop `seq <= lastSeq`; update `lastSeq`; handle `ping` by sending `pong`; `session.snapshot` seeds state; `session.resumed` triggers event replay from the server.
  - On close while in progress: request a new ticket, reconnect, send `session.resume {last_seq, recording_state}`; stop after 30 s or when `recordingState() === 'lost'`, emit `failed`.
  - `classroomReducer(state, event)` handles `session.snapshot`, `classroom.*`, `student.*`, `teacher.*`, `dialogue.*`, `interview.*`, `session.*`; unknown events are ignored; `session.time_tick` updates elapsed/remaining/discipline/states only.
- `interview` store holds session, current question, remaining time, recording status.

- [ ] **Step 1: Write the failing WS tests**

```ts
it('drops duplicate and out-of-order seq', () => { /* events 1,2,2,3 -> handled once */ })
it('responds to ping with pong', () => { /* sent command */ })
it('reconnects with last_seq within grace', () => { /* fake WS: close -> new ticket -> resume */ })
it('fails instead of reconnecting when recording_state=lost', () => { /* ... */ })
```

- [ ] **Step 2: Write the failing reducer tests**

```ts
it('applies snapshot without privileged fields', () => { /* virtual students have no params/personality */ })
it('tracks hand raise and called-on states', () => { /* ... */ })
it('tracks interview question and remaining time', () => { /* ... */ })
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `npm --prefix frontend run test -- --run src/utils/wsClient.spec.ts src/utils/classroomReducer.spec.ts`
Expected: module not found.

- [ ] **Step 4: Implement the WS client and reducer**

Use the browser `WebSocket` global behind a small factory so tests can inject a fake. Use `crypto.randomUUID()` for `command_id`. Reconnect timers use an injectable `setTimeout`/clock in tests. Do not use a local timer to infer classroom state.

- [ ] **Step 5: Run tests to verify they pass**

Run the Step 3 command. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/utils frontend/src/stores/interview.ts frontend/src/utils/*.spec.ts
git commit -m "[M3-FE] WebSocket 客户端与课堂事件 reducer"
```

---

### Task 4: MediaRecorder wrapper and multipart uploader

**Files:**
- Create: `frontend/src/api/recordings.ts`
- Create: `frontend/src/utils/media.ts`
- Create: `frontend/src/utils/recorderUploader.ts`
- Test: `frontend/src/utils/recorderUploader.spec.ts`, `frontend/src/utils/media.spec.ts`

**Interfaces:**
- Produces:
  - `acquireMedia(mode: 'free'|'scenario'|'interview'): Promise<{ camera: MediaStream; screen?: MediaStream; audio: MediaStream; stop(): void }>`; throws `MediaError` with `permission | unsupported | missing`; checks Chrome/Edge.
  - `createRecorder(stream, mimeType, timesliceMs = 5000): MediaRecorder`.
  - `class RecorderUploader { constructor(api, sessionId, streamType, recorder); start(); stop(): Promise<RecordingStreamView>; onProgress(cb); onError(cb); }`
  - Flow: multipart start → on each `dataavailable` append Blob; once buffered ≥ 5 MB, concatenate and upload the next part via presigned PUT, retrying each part up to 3 times; keep part 1’s blob; on stop, `fixWebmDuration(combinedFirstPart, durationMs)` then re-upload part 1, upload the tail, complete with `{parts, duration_ms, size, mime}`. If buffered data exceeds 30 MB or a part still fails after 3 retries, abort and surface a failure (the session becomes unsubmittable). A `fixWebmDuration` failure also aborts and blocks submission.
  - `RecordedPart { partNumber: number; etag: string }`; progress `{uploadedBytes, totalBytes, status}`.
- API: `startMultipart(sessionId, type)`, `partUrl(sessionId, type, partNumber)`, `completeMultipart(sessionId, type, body)`, `abortMultipart(sessionId, type)`.

- [ ] **Step 1: Write the failing uploader tests**

```ts
it('uploads 5MB parts and completes with all etags', async () => { /* fake api + blobs; assert part numbers/etags */ })
it('retains and re-uploads part 1 after duration patch', async () => { /* fixWebmDuration mock; two PUTs for part 1 */ })
it('retries a failed part three times before reporting', async () => { /* 3 PUT attempts, then error, no complete */ })
it('aborts when the buffer exceeds 30MB', async () => { /* backlog threshold */ })
it('aborts on track ended', async () => { /* abort API called */ })
```

- [ ] **Step 2: Write the failing media tests**

```ts
it('rejects unsupported browsers', () => { /* navigator.userAgent mock */ })
it('propagates permission denial as MediaError(permission)', async () => { /* getUserMedia rejects */ })
it('interview does not request screen', async () => { /* getDisplayMedia not called */ })
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `npm --prefix frontend run test -- --run src/utils/recorderUploader.spec.ts src/utils/media.spec.ts`
Expected: module not found.

- [ ] **Step 4: Implement the media and uploader logic**

Use `getUserMedia({video, audio})` for camera/audio and `getDisplayMedia({video})` for screen; stop all tracks on `stop()`. Use `fetch` with `method: 'PUT'` for presigned parts and read `ETag` from the response (fall back to a deterministic test ETag in mocks). `fixWebmDuration` is imported as a default function; wrap it so tests can mock it.

- [ ] **Step 5: Run tests to verify they pass**

Run the Step 3 command. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/api/recordings.ts frontend/src/utils/media.ts frontend/src/utils/recorderUploader.ts \
  frontend/src/utils/*.spec.ts
git commit -m "[M3-FE] 三路录制与 multipart 上传"
```

---

### Task 5: Classroom page and components

**Files:**
- Create: `frontend/src/views/classroom/ClassroomPage.vue`
- Create: `frontend/src/components/classroom/{VirtualStudentGrid,VirtualStudentCard,ClassroomControls,RecordingStatus,WsStatus,ScreenShare,CameraPreview}.vue`
- Modify: `frontend/src/app/router.ts` (add `/student/sessions/:sessionId/classroom`)
- Test: `frontend/src/views/classroom/ClassroomPage.spec.ts`, `frontend/src/components/classroom/VirtualStudentCard.spec.ts`

**Interfaces:**
- Produces:
  - Classroom page loads `SessionView`, acquires media by mode, creates three `RecorderUploader`s, connects WS, sends `recording.ready` with stream statuses, sends `session.start` after `recording.ready_ack.ok`, renders `session.snapshot`/events, and stops recorders + `session.end` on “结束试讲”.
  - Controls: 向全班提问 (`teacher.ask_class`), 让学生提问 (`teacher.ask_student_question`), 点名 (`teacher.call_on`), 标记对/错/不完整 (`teacher.mark_answer`), 提醒 (`teacher.manage_student`), 结束对话 (`teacher.end_dialogue`), 结束问答 (`teacher.end_qa`), 结束试讲 (`teacher.end_session`).
  - `VirtualStudentCard` shows name/avatar/state only; never parameters/personality.
  - `RecordingStatus` shows three stream states; `WsStatus` shows reconnect countdown; `ScreenShare` displays the screen stream; `CameraPreview` displays camera.
  - On recording failure/track ended: abort uploads, send `session.failed`? The server decides; show failure page and disable submit; page refresh sets `recording_state=lost`.

- [ ] **Step 1: Write the failing component tests**

```ts
it('virtual student card never renders parameters or personality', () => { /* props include them but output excludes */ })
it('disables classroom controls before in_progress', () => { /* ... */ })
it('shows three recording statuses and reconnect countdown', () => { /* ... */ })
```

- [ ] **Step 2: Write the failing page test**

```ts
it('sends recording.ready then session.start and renders snapshot', async () => { /* mock WS + media + uploaders */ })
it('ends the session and stops recorders', async () => { /* ... */ })
it('shows a failure state when a track ends', async () => { /* ... */ })
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `npm --prefix frontend run test -- --run src/views/classroom/ClassroomPage.spec.ts src/components/classroom/VirtualStudentCard.spec.ts`
Expected: module not found.

- [ ] **Step 4: Implement the classroom page**

Keep the page layout: screen main area + virtual-student sidebar + camera thumbnail + bottom control bar (05B §7.3/09 §6.5). Use `ImmersiveLayout`. All business state comes from the reducer/snapshot; components only emit commands. Use `router.push` to `/review` after `session.ended`; keep upload progress visible until all streams complete.

- [ ] **Step 5: Run tests to verify they pass**

Run the Step 3 command. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/views/classroom frontend/src/components/classroom
git commit -m "[M3-FE] 课堂页面与虚拟学生控制"
```

---

### Task 6: Interview room

**Files:**
- Create: `frontend/src/views/classroom/InterviewPage.vue`
- Create: `frontend/src/components/classroom/{QuestionPanel,InterviewTimer}.vue`
- Modify: `frontend/src/app/router.ts` (add `/student/sessions/:sessionId/interview`)
- Test: `frontend/src/views/classroom/InterviewPage.spec.ts`

**Interfaces:**
- Produces:
  - Interview page acquires camera+audio only, creates two `RecorderUploader`s, connects WS, sends `recording.ready` with `[camera, audio]`, starts the session, and renders `interview.question_shown`/`question` text and total remaining time.
  - “下一题” sends `interview.next_question`; “结束面试” sends `interview.end`; both disabled outside `in_progress`; last question disables next; timeout/`interview.ended` routes to review.
  - No virtual-student grid, no discipline UI, no answer points.

- [ ] **Step 1: Write the failing page tests**

```ts
it('requests only camera and audio', async () => { /* screen not requested */ })
it('renders current question and total timer', async () => { /* ... */ })
it('disables next on the last question', async () => { /* ... */ })
it('uses interview.next_question and interview.end commands', async () => { /* ... */ })
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npm --prefix frontend run test -- --run src/views/classroom/InterviewPage.spec.ts`
Expected: module not found.

- [ ] **Step 3: Implement the interview page**

Reuse `ImmersiveLayout`, `RecordingStatus`, `WsStatus`, `CameraPreview`. Timer derives from snapshot `remaining_ms` and `session.time_tick`; do not run a local countdown for business decisions.

- [ ] **Step 4: Run tests to verify they pass**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/views/classroom frontend/src/components/classroom
git commit -m "[M3-FE] 结构化面试房间"
```

---

### Task 7: Review/replay page and synchronized player

**Files:**
- Create: `frontend/src/api/replay.ts`
- Create: `frontend/src/utils/replaySync.ts`
- Create: `frontend/src/views/replay/{ReviewPage,ReplayPage}.vue`
- Create: `frontend/src/components/replay/{ReplayPlayer,EventTimeline,ReplayControls}.vue`
- Modify: `frontend/src/app/router.ts` (add `/student/sessions/:sessionId/review`)
- Test: `frontend/src/utils/replaySync.spec.ts`, `frontend/src/components/replay/ReplayPlayer.spec.ts`, `frontend/src/views/replay/ReviewPage.spec.ts`

**Interfaces:**
- Produces:
  - API: `getReplay(sessionId)`, `getEvents(sessionId, afterSeq, limit)`, `getTexts(sessionId, speakerType)`, `submitSession(sessionId)`.
  - `replaySync.ts`: `targetTime(sessionMs, offsetMs)`, `clampSeek(time, duration)`, `needsCorrection(masterTime, slaveTime, thresholdMs = 200)`, `seekAll(players, sessionMs)`.
  - `ReplayPlayer`: screen main + camera thumbnail for free/scenario, camera main for interview; audio master; play/pause/rate/volume/fullscreen; event click seeks; `?t=` initial seek; presigned refresh on 15 min/expiry error.
  - `ReviewPage`: upload progress, “提交” enabled only when all required streams uploaded, “重做” returns to task detail; after submit, read-only replay.
  - `EventTimeline`: filters teacher/student/discipline/interview, click → seek.

- [ ] **Step 1: Write the failing sync tests**

```ts
it('applies stream offset', () => { expect(targetTime(5000, 120)).toBeCloseTo(4.88) })
it('clamps seek within duration', () => { /* ... */ })
it('corrects only when drift exceeds 200ms', () => { /* ... */ })
it('seeks all streams to an event at_ms', () => { /* ... */ })
```

- [ ] **Step 2: Write the failing player/page tests**

```ts
it('uses audio as the master clock', () => { /* fake HTMLMediaElement */ })
it('renders event filters and seeks on click', () => { /* ... */ })
it('disables submit until required streams are uploaded', () => { /* ... */ })
it('submits and switches to read-only', () => { /* ... */ })
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `npm --prefix frontend run test -- --run src/utils/replaySync.spec.ts src/components/replay/ReplayPlayer.spec.ts src/views/replay/ReviewPage.spec.ts`
Expected: module not found.

- [ ] **Step 4: Implement replay and review**

Use `<video>` for camera/screen and `<audio>` for audio. The sync interval is 500 ms; when `needsCorrection`, set the slave `currentTime`. Event click computes `session_ms` and seeks the master. Interview layout uses camera as the main player. Submit calls the API, then reloads replay data and disables actions.

- [ ] **Step 5: Run tests to verify they pass**

Run the Step 3 command. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/api/replay.ts frontend/src/utils/replaySync.ts frontend/src/views/replay \
  frontend/src/components/replay
git commit -m "[M3-FE] 回看、提交与多流同步回放"
```

---

### Task 8: Build verification, manual smoke, and progress evidence

**Files:**
- Create: `docs/team/evidence/2026-11-04-m3-frontend-classroom-replay.md`
- Modify: `docs/team/02-进度看板.md` (M3B/M3C rows' blocker text and §5 update row only)
- Modify: `frontend/README.md`, `server/README.md` (dev proxy / run commands)
- Modify: `docker-compose.yml` only if MinIO CORS/public endpoint is not already set by Plan 2 (coordinate; do not duplicate).

**Interfaces:**
- Produces: a passing production build, a passing Vitest suite, a documented manual Chrome smoke, and evidence links.

- [ ] **Step 1: Run the frontend unit suite**

Run: `npm --prefix frontend run test`
Expected: all tests pass.

- [ ] **Step 2: Run the production build**

Run: `npm --prefix frontend run build`
Expected: `vue-tsc` and Vite build succeed with no type errors.

- [ ] **Step 3: Manual Chrome smoke**

Start `docker compose up -d --build` and `npm --prefix frontend run dev`. In Chrome:
1. Open `/student/tasks`, pick the seeded free task, start a session.
2. Grant camera/screen/mic; verify three recording indicators and WS connected.
3. Ask class, call on a student, mark an answer, end QA, end session.
4. Wait for upload complete; open review; play/pause/seek/rate; click an event.
5. Submit; verify the task no longer offers a new attempt and replay is read-only.

Record the observed results and any browser console errors in the evidence file. This is a manual smoke, not E2E.

- [ ] **Step 4: Update the progress board and README**

Add one §5 row: `2026-11-04 | M3 Owner（用户授权前端共享层最小实现） | M3B/M3C 前端 | 课堂/面试/回看/回放 + 录制上传；Vitest <n> 通过；证据见 evidence/2026-11-04-m3-frontend-classroom-replay.md | 10%`. Update only the M3B/M3C blocker text; do not mark G3–G8.

- [ ] **Step 5: Commit**

```bash
git add docs/team/evidence/2026-11-04-m3-frontend-classroom-replay.md docs/team/02-进度看板.md \
  frontend/README.md server/README.md
git commit -m "[M3-FE] 构建验证、手工 smoke 与进度证据"
```

---

## Out of Scope / Follow-up

- Playwright E2E (Score/QA Owner), full auth/login (M1), course/task management pages (M2/M3A UI), training pages (M6), report/grading pages (M4/M7), perception audio WS/OCR, retention jobs.
