# 2026-11-04 M3C 录制 / 回放后端、契约测试与 Compose smoke 证据

> 分支：`feat/m3a-task-config`
> 计划：`docs/superpowers/plans/2026-11-04-m3c-recording-backend.md`（Task 1–8）
> 执行人：M3 Owner（当前使用者，经用户确认）
> 环境：Docker Engine 29.6.2 / Compose v5.3.1 / PostgreSQL 18.6 / Spring Boot 4.1.2 / Java 21 / MinIO / Redis 8
> 结论：M3C 录制 / 回放后端 P0（Task 1–7）完成；Task 8 契约测试、全量套件、Docker 全栈 smoke 通过。G2 设计冻结未关闭，G3—G8 均未标记，模块完成度保持 10%；测试完成度按 UT / IT / 契约 3 个层级记 60%，E2E / 性能安全待续。

## 1. 范围与授权

### 1.1 授权

用户在 2026-11-05 明确确认：当前 M3 Owner 继续完成 M3C 实现（Task 1 验收 + Task 2–8）。记录见 `.superpowers/sdd/2026-11-04-m3c-recording-backend/progress.md` 第 5 行：

> Authorization: user confirmed 2026-11-05 that the current M3 Owner continues M3C implementation (Task 1 acceptance + Tasks 2–8).

### 1.2 本次范围

- 录制领域规则、对象键、必需流（`RequiredStreams`）。
- `recordings` / `recording_streams` JPA 持久化与仓储。
- MinIO multipart 适配器与内存测试适配器（start / part-url / complete / abort / presign GET / stat / delete）。
- REST：`multipart/start`、`part-url`、`complete`、`abort`、`recordings` 列表。
- 回放：`GET /replay`、`GET /events`、`GET /texts`。
- 提交：`POST /sessions/{id}/submit`（必需流校验、submission / assignment / 事件、提交后清理其他 attempt）。
- 临时 attempt 上限（5 个）与取消 / 淘汰清理。
- 契约测试、全量套件、Docker smoke、进度与证据。

不做：浏览器 MediaRecorder / 上传器 / 回放 UI、感知 ASR / OCR / 屏幕、保留期定时任务、服务端转码、断点续传、训练记录（M6）。这些按计划属于前端计划或 M6 / 后续切片。

### 1.3 跨模块说明

Task 6 为提交接通了 M3B 的 `SessionController` / `SessionApplicationService` 提交入口；Task 7 在 `SessionApplicationServiceImpl.createSession` 中加入 attempt 上限与淘汰，并给 `SessionRepository` 增加删除能力。两处均在 Task 6 / Task 7 的 controller ruling 与上述用户授权范围内执行，只改提交 / attempt 相关接线，未改 M3B 课堂 / WS 契约。

未改动：数据库 schema / 迁移、`build.gradle.kts`、YAML、`docs/design/02` / `12` / 前端；未修改 M3B / M4 / 前端进度行。

## 2. 实现摘要（Tasks 1–7）

| Task | 提交 | 内容 |
|---|---|---|
| 1 | `d5fcfae` | `Recording` / `RecordingStream` / `StreamType` / `StreamStatus` / `RequiredStreams` / `RecordingObjectKeyBuilder`；对象键 `recordings/{school_id}/task/{task_id}/{session_id}/{stream_type}.webm` |
| 2 | `1558a66` | `RecordingRepository` / `RecordingStreamRepository`、JPA entity / adapter、按 session 读取与删除 |
| 3 | `8b6514c`、`5407f92` | `RecordingObjectStorage` 端口、MinIO multipart 适配器（内部端点 + 公网预签名客户端、region）、内存适配器；预签名 part / GET URL |
| 4 | `88fd5af`、`7bd5a95`、`b817ecc` | `RecordingApplicationService` / `RecordingLifecycleService`、`RecordingAccessPolicy`；5 个 REST 接口；15 分钟 part URL；complete 后 `stat` 校验大小；独立事务保存失败状态 |
| 5 | `a8b37e1` | `ReplayQueryService` + `ReplayController`：回放 manifest（screen/camera/audio 顺序、面试 camera/audio）、events 分页、texts 过滤；`RECORDING_INCOMPLETE` + `missing_streams` |
| 6 | `088fdca`、`168330c` | `Submission` 领域 / JPA / API；提交事务顺序、必需流校验、`submission.created` 事件、assignment 更新；提交成功提交后清理其他 attempt |
| 7 | `8cf01e3`、`cd58810` | 临时 attempt 上限 5、最旧可删 attempt 淘汰、取消时 abort multipart + 标记 `DELETED` + after-commit 删除对象；`attempt_no` 在删除前按 max + 1 计算 |

## 3. 契约测试（RED / GREEN）

新增：

- `server/src/test/java/com/shijiangtai/m3classroom/contract/RecordingContractTest.java`（6 条）
- `server/src/test/java/com/shijiangtai/m3classroom/contract/ReplayContractTest.java`（4 条）

两个测试类都用 `JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build()` 镜像全局 `spring.jackson.property-naming-strategy=SNAKE_CASE`，用 `readTree` / `propertyNames()` 断言，不依赖空白或字段顺序；未为了让测试通过而修改生产 DTO。

冻结的 wire key：

| 契约面 | 冻结 key |
|---|---|
| multipart start response | `upload_id`、`object_key`、`part_size` |
| part-url response | `url`、`part_number`、`expires_in_seconds` |
| part-url request | `part_number` |
| complete request | `parts[].part_number`、`parts[].etag`、`duration_ms`、`size`、`mime` |
| recording stream view | `stream_type`、`status`、`upload_id`、`object_key`、`part_size`、`parts_uploaded`、`bytes_uploaded`、`duration_ms`、`offset_ms`、`mime` |
| replay view | `session_id`、`mode`、`duration_ms`、`streams[]`（`stream_type`、`url`、`offset_ms`、`duration_ms`、`mime`） |
| event view | `event_id`、`seq`、`type`、`at_ms`、`payload` |
| text view | `id`、`speaker_type`、`speaker_ref`、`source`、`kind`、`text`、`start_ms`、`end_ms`、`at_ms`、`confidence`、`status` |
| submission view | `submission_id`、`task_id`、`session_id`、`student_account_id`、`status`、`submitted_at` |
| `RECORDING_INCOMPLETE` error body | `code`、`message`、`details.missing_streams`；HTTP 422 |

### 3.1 首次执行

```bash
docker compose --profile test run --rm test \
  gradle test --tests '*RecordingContractTest' --tests '*ReplayContractTest' \
  --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL in 31s`；`RecordingContractTest` 6 条 + `ReplayContractTest` 4 条全部通过（Gradle 过滤器同时执行 3 条 `ArchitectureTest`，合计 13 条，0 failure / 0 error / 0 skipped）。

说明：Task 8 计划 Step 2 期望的「编译失败 / DTO 缺字段」RED 没有复现，因为 Task 1–7 已经实现并验证了这些 DTO（`RecordingControllerIT` / `ReplayControllerIT` / `SubmissionControllerIT` 已断言 snake_case）。因此契约测试首次运行即为 GREEN，无需调整 DTO。

### 3.2 RED 探针（证明测试能捕获命名漂移）

为确认契约测试不是空跑，临时把两个测试类的 mapper 改成 Jackson 默认 camelCase，再执行同一命令：

```text
RecordingContractTest > submissionViewFreezesSubmissionReceipt() FAILED
RecordingContractTest > multipartStartResponseFreezesUploadCoordinates() FAILED
RecordingContractTest > recordingStreamViewFreezesUploadMetadata() FAILED
RecordingContractTest > recordingIncompleteErrorBodyFreezesMissingStreamsDetails() FAILED
RecordingContractTest > partUrlResponseFreezesPresignedUrlShape() FAILED
RecordingContractTest > completeRequestAndPartUrlRequestReadSnakeCaseBodies() FAILED
ReplayContractTest > sessionEventViewFreezesTimelineEntryKeys() FAILED
ReplayContractTest > textViewKeepsNullableAuditFieldsAsContractKeys() FAILED
ReplayContractTest > sessionTextViewFreezesUnifiedTranscriptKeys() FAILED
ReplayContractTest > replayViewFreezesManifestAndStreamKeys() FAILED
13 tests completed, 10 failed
```

恢复 `SNAKE_CASE` 后重新执行：`BUILD SUCCESSFUL`，10 条契约测试全部通过。RED 探针证明任何字段改名或命名策略回退都会使契约测试失败。

## 4. 全量测试

```bash
docker compose --profile test run --rm test \
  gradle clean test --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL in 2m 14s`；**88 个测试类 / 643 条用例，0 failure / 0 error / 0 skipped**（解析 `build/test-results/test/TEST-*.xml` 汇总）。Task 8 新增 2 个契约测试类 / 10 条用例；Task 6 全量基线为 623 条，Task 7 的 attempt 清理用例与 Task 8 的契约用例合计新增 20 条。

M3C 相关测试类：

| 层级 | 测试类（用例数） | 小计 |
|---|---|---:|
| 领域 UT | `RecordingTest`(9)、`RequiredStreamsTest`(3)、`RecordingObjectKeyBuilderTest`(2) | 14 |
| 应用 UT | `RecordingAccessPolicyTest`(8)、`RecordingApplicationServiceTest`(15)、`RecordingLifecycleServiceTest`(6)、`ReplayQueryServiceTest`(13)、`SubmissionServiceTest`(10)、`AttemptCleanupTest`(4) | 56 |
| API IT | `RecordingControllerIT`(5)、`ReplayControllerIT`(5)、`SubmissionControllerIT`(5) | 15 |
| 持久化 IT | `RecordingRepositoryIT`(7)、`SubmissionPersistenceIT`(5)、`SubmissionTransactionIT`(2) | 14 |
| 存储 | `InMemoryRecordingObjectStorageTest`(5)、`MinioRecordingObjectStorageTest`(2) | 7 |
| 契约 | `RecordingContractTest`(6)、`ReplayContractTest`(4) | 10 |

备注：全量运行日志中仍有既有的 `JpaContextPersistenceWriter` / Hikari shutdown INFO 日志（M3B 异步持久化测试清理时的正常关闭），不影响用例结果。

## 5. Docker Compose smoke

### 5.1 构建与健康

首次执行 `docker compose up -d --build` 遇到两个环境问题，均未改动仓库文件：

1. Dockerfile 前端 `docker/dockerfile:1.7` 解析失败：`error getting credentials - err: exit status 1`。原因是 WSL 下 `~/.docker/config.json` 的 `credsStore: desktop.exe` 不可用；用临时空 Docker config 重试解决：
   ```bash
   mkdir -p /tmp/m3c-docker-config && printf '{}\n' > /tmp/m3c-docker-config/config.json
   DOCKER_CONFIG=/tmp/m3c-docker-config docker compose build
   ```
2. 首次 Gradle 构建阶段下载 Maven Central 依赖时出现 `Connection refused`（`repo.maven.apache.org:443`），约 6 分钟后失败；随后从 host 与 `gradle:8.14.3-jdk21` 容器复测均可访问，重试构建成功：`BUILD SUCCESSFUL in 3m 48s`，镜像 `shijiangtai-app:latest`。

```bash
DOCKER_CONFIG=/tmp/m3c-docker-config docker compose up -d
curl -fsS http://localhost:8080/actuator/health
# {"groups":["liveness","readiness"],"status":"UP"}
```

容器状态：`app` / `minio` / `redis` / `postgres` 均 healthy。

### 5.2 主流程

教师头：`X-Account-Id=...0010`、`X-School-Id=...0001`、`X-Account-Type=TEACHER`；学生头：`X-Account-Id=...0020`。

| 步骤 | 请求 | 结果 |
|---|---|---|
| 1. 创建 free 任务 | `POST /api/v1/courses/...0100/tasks` | HTTP 200；`task_id=01a1ac65-95d0-7afe-8d60-229d8fc1c565`，`status=draft`，`mode=free` |
| 2. 上传参考材料 | `POST /api/v1/tasks/{id}/material`（`material.txt`，49 字） | HTTP 200；`parse_status=parsed`，`char_count=49` |
| 3. 发布 | `POST /api/v1/tasks/{id}/publish` | HTTP 200；`status=published`，`config_version=1`，`assigned_count=2` |
| 4. 创建会话 | `POST /api/v1/tasks/{id}/sessions`（学生） | HTTP 200；`session_id=01a1ac65-b82e-7efc-bbc0-749a429dd5ea`，`status=preparing`，`attempt_no=1` |
| 5. WS 就绪 + 开始 | `POST /ws-ticket` → Node 22 WebSocket 发送 `recording.ready` + `session.start` | `recording.ready_ack {"required_streams":["camera","screen","audio"],"ok":true}`；`session.started`；`server.ack` 均 ok |
| 6. 三路 multipart | 各自 `start` → `part-url` → `PUT` → `complete` | 三路 `status=uploaded`；`part_size=5242880`，`parts_uploaded=1` |
| 7. 结束会话 | `POST /api/v1/sessions/{id}/end`（学生） | HTTP 200；`status=ended`，`duration_ms=23340` |
| 8. 回放 | `GET /api/v1/sessions/{id}/replay`（学生） | HTTP 200；`mode=free`，`duration_ms=23340`，3 路顺序 `screen` / `camera` / `audio` |
| 9. 提交 | `POST /api/v1/sessions/{id}/submit`（学生） | HTTP 200；见 §5.5 submission 行 |
| 10. 教师回放 | `GET /api/v1/sessions/{id}/replay`（教师 ...0010） | HTTP 200；3 路齐全（提交后教师可见） |

WS transcript 关键行（完整过程还包括每秒 `session.time_tick` 与 `ping`/`pong`）：

```text
<< session.snapshot {"state":"preparing","recording":{"required_streams":["camera","screen","audio"],"ready":false},"last_seq":0}
>> recording.ready {"streams":[{"stream_type":"camera","status":"ready"},{"stream_type":"screen","status":"ready"},{"stream_type":"audio","status":"ready"}]}
<< recording.ready_ack {"required_streams":["camera","screen","audio"],"ok":true}
<< server.ack {"command_id":"smoke-ready","ok":true}
>> session.start
<< session.started {"started_at_ms":0}
<< server.ack {"command_id":"smoke-start","ok":true}
...
<< classroom.state_changed {"from":"lecturing","to":"ended","trigger":"end_session"}
<< session.ended {"reason":"manual","duration_ms":23340}
CLOSE 1000 smoke done
```

### 5.3 Multipart / MinIO

| stream | MinIO object key | bytes | ETag | complete |
|---|---|---:|---|---|
| camera | `recordings/00000000-0000-0000-0000-000000000001/task/01a1ac65-95d0-7afe-8d60-229d8fc1c565/01a1ac65-b82e-7efc-bbc0-749a429dd5ea/camera.webm` | 36 | `2bebceb91e12983fa78cb25955c30f94` | uploaded |
| screen | `recordings/00000000-0000-0000-0000-000000000001/task/01a1ac65-95d0-7afe-8d60-229d8fc1c565/01a1ac65-b82e-7efc-bbc0-749a429dd5ea/screen.webm` | 36 | `1f0f792680e08905638ee0a1611c44fa` | uploaded |
| audio | `recordings/00000000-0000-0000-0000-000000000001/task/01a1ac65-95d0-7afe-8d60-229d8fc1c565/01a1ac65-b82e-7efc-bbc0-749a429dd5ea/audio.webm` | 35 | `d4ff36b5c0ee6dbfcfa070e099b6b8dc` | uploaded |

`mc ls --recursive local/shijiangtai/recordings`：

```text
[2026-11-05 15:49:02 UTC]    35B STANDARD 00000000-...-0001/task/01a1ac65-.../01a1ac65-.../audio.webm
[2026-11-05 15:49:01 UTC]    36B STANDARD 00000000-...-0001/task/01a1ac65-.../01a1ac65-.../camera.webm
[2026-11-05 15:49:01 UTC]    36B STANDARD 00000000-...-0001/task/01a1ac65-.../01a1ac65-.../screen.webm
```

### 5.4 预签名 URL 检查

part-url 响应示例（camera）：

```json
{
  "url": "http://localhost:9000/shijiangtai/recordings/.../camera.webm?uploadId=...&partNumber=1&X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20261105%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20261105T154853Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=...",
  "part_number": 1,
  "expires_in_seconds": 900
}
```

三路 part URL 与回放 GET URL 解码后均只有：

```text
X-Amz-Algorithm=AWS4-HMAC-SHA256
X-Amz-Credential=minioadmin/20261105/us-east-1/s3/aws4_request   # access key id + date/region/scope
X-Amz-Date=...
X-Amz-Expires=900
X-Amz-SignedHeaders=host
X-Amz-Signature=<HMAC>
```

没有 `X-Amz-Secret`、`X-Amz-Security-Token` 或其他 secret 参数；`X-Amz-Signature` 是 HMAC 签名。注意本 dev 环境 `MINIO_ROOT_USER` 与 `MINIO_ROOT_PASSWORD` 都使用默认值 `minioadmin`，因此无法用「URL 不包含 `minioadmin` 子串」作区分；结构上 URL 只携带 access key id，secret 只参与 HMAC 计算。

### 5.5 数据库校验

```text
sessions:      01a1ac65-b82e-7efc-bbc0-749a429dd5ea | submitted | free | attempt_no=1 | started=t | ended=t | duration_ms=23340
recordings:    01a1ac66-141a-796a-a36a-4542ca84d3df | uploaded
recording_streams:
  audio  | uploaded | part_size=5242880 | parts_uploaded=1 | bytes_uploaded=35 | duration_ms=5000 | offset_ms=0 | audio/webm
  camera | uploaded | part_size=5242880 | parts_uploaded=1 | bytes_uploaded=36 | duration_ms=5000 | offset_ms=0 | video/webm
  screen | uploaded | part_size=5242880 | parts_uploaded=1 | bytes_uploaded=36 | duration_ms=5000 | offset_ms=0 | video/webm

submissions:
  id=01a1ac66-cfd1-7a50-b2a5-b8c73d06b4f2
  task_id=01a1ac65-95d0-7afe-8d60-229d8fc1c565
  session_id=01a1ac65-b82e-7efc-bbc0-749a429dd5ea
  student_account_id=00000000-0000-0000-0000-000000000020
  status=submitted
  class_id_snapshot=00000000-0000-0000-0000-000000000200
  submitted_at=2026-11-05 15:49:41.457621+00

task_assignments:
  student ...0020 -> submitted
  student ...0021 -> assigned

events (seq): 1 recording.ready_ack, 2 session.started,
  3..25 session.time_tick, 26 classroom.state_changed, 27 session.ended,
  28 submission.created
```

提交响应：

```json
{
  "submission_id": "01a1ac66-cfd1-7a50-b2a5-b8c73d06b4f2",
  "task_id": "01a1ac65-95d0-7afe-8d60-229d8fc1c565",
  "session_id": "01a1ac65-b82e-7efc-bbc0-749a429dd5ea",
  "student_account_id": "00000000-0000-0000-0000-000000000020",
  "status": "submitted",
  "submitted_at": "2026-11-05T15:49:41.457621217Z"
}
```

## 6. T20–T27 映射

| 用例 | 本次覆盖 | 状态 / 后续 |
|---|---|---|
| M3-T20 三路录制；面试两路；track ended / 权限拒绝 failed | 后端三路 multipart + `RequiredStreams`（free 3 路 / interview 2 路）；`RecordingApplicationServiceTest` / `RequiredStreamsTest`；本 smoke 三路真实上传 | 后端部分 ✅；track ended / 权限拒绝属浏览器 MediaRecorder，E2E 待前端计划 |
| M3-T21 5s timeslice、5MB part、三路并行、complete | `part_size=5242880`；三路 start / part-url / PUT / complete 全通过；complete 后 `stat` 校验 | 服务端部分 ✅；timeslice 与并行策略在客户端，E2E 待续 |
| M3-T22 part 重试、积压阈值、abort 清理 | `RecordingLifecycleServiceTest`、`AttemptCleanupTest`、`RecordingApplicationServiceTest` 覆盖 abort / 取消清理；重试与 30 MB 积压阈值属客户端 | 服务端清理 ✅；客户端重试 / 背压 E2E 待续 |
| M3-T23 时长补全、part 1 重传；补时长失败禁止提交 | 服务端接受同 part_number 重传与 `duration_ms`；缺必需流时提交 422 `RECORDING_INCOMPLETE`（`SubmissionControllerIT`） | 服务端校验 ✅；WebM 时长写入由浏览器 `fix-webm-duration` 完成，E2E 待续 |
| M3-T24 required_streams 按模式；只保留选中录像 | `RequiredStreamsTest`、`SubmissionControllerIT`（缺流 422）、`SubmissionTransactionIT` / `AttemptCleanupTest`（其他 attempt 行与对象删除、选中录像保留） | ✅ 后端集成覆盖 |
| M3-T25 回放 offset / seek / 漂移 <200ms / 倍速 / 全屏 | 回放 manifest 的 `offset_ms` / `duration_ms` / 预签名 URL 由 `ReplayControllerIT` 与本 smoke 验证 | 服务端 manifest ✅；seek / 漂移 / 倍速 / 全屏属前端，E2E 待续 |
| M3-T26 临时 24h / 5 attempt；关闭后 24h；课程结束 +1 年；训练 1 个月 | 5 个未提交 attempt 上限与最旧淘汰（`AttemptCleanupTest`）；保留期定时任务、任务关闭窗口、训练保留不在本切片 | 5-attempt ✅；保留期调度与训练属后续 / M6 |
| M3-T27 学生本人 / 老师组 / 管理员 403；临时录像与训练私有 | `RecordingAccessPolicyTest`、`ReplayControllerIT`（本人 / 教师组提交后可见、管理员 / 跨校 / 其他学生 403、临时录像教师不可见） | ✅ 后端安全覆盖；训练私有属 M6 |

## 7. 已知缺口与备注

1. **浏览器录制 / 回放 UI**：MediaRecorder、5 秒 timeslice、5MB 缓冲、`fix-webm-duration`、seek / 漂移校正 / 倍速 / 全屏、T20 / T25 E2E 均在前端计划 `2026-11-04-m3-frontend-classroom-replay.md`，本切片未做。
2. **保留期定时任务**：临时录像 24h、任务关闭后 24h、课程结束 +1 年、训练 1 个月等调度清理不在本切片；当前只实现 5 attempt 上限、取消 / 淘汰 / 提交后清理。
3. **无 live MinIO IT**：自动化套件使用内存存储适配器；Task 3 的 MinIO 预签名 / region 逻辑为离线测试。本次 Compose smoke 用真实 MinIO 完成 create / presign PUT / PUT / complete / stat / presign GET，补足了 live 路径证据。
4. **预签名 URL**：只携带 access key id（`X-Amz-Credential` 的 scope 前缀）与 HMAC `X-Amz-Signature`，不携带 secret；本 dev 环境 access key 与 secret 同为 `minioadmin`，故不能以子串缺失证明 secret 未出现，按结构检查记录。
5. **attempt_no 中间态**：Task 7 的修复在删除最旧 attempt 之前按 `max(attemptNo) + 1` 计算，新的第 6 次 attempt 编号唯一；仅早期中间态已写入的重复 `attempt_no` 行没有迁移 / 唯一约束（`sessions.attempt_no` 无 unique constraint）。
6. **提交后清理事务**：`SessionApplicationServiceImpl.submit` 先提交 submission（独立事务），再清理其他 attempt；若清理失败，submission 已提交但接口可能返回 5xx（Task 6 note）。
7. **构建环境**：`docker compose up -d --build` 首次因 Docker credential helper 与 Maven Central 临时拒绝失败；使用临时空 Docker config、重试构建成功。该问题为 WSL / 网络环境，不是代码回归。
8. 全量测试仍输出既有的 JPA / Hikari shutdown INFO 日志，0 failure / 0 error / 0 skipped。

## 8. 结论

- Task 1–7 的 M3C 后端实现可运行，REST / WS / PostgreSQL / MinIO 全链路 smoke 通过。
- Task 8 新增 2 个契约测试类 / 10 条用例；契约 RED 探针显示字段或命名漂移会被捕获。
- 全量套件 88 classes / 643 tests，0 failure / 0 error / 0 skipped。
- 进度：M3C 测试行 UT / IT / 契约 ✅、E2E / 性能安全 ⬜、测试完成度 60%；模块完成度保持 10%，G2—G8 未标记。
