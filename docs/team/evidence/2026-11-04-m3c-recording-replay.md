# 2026-11-04 M3C 录制 / 回放后端、契约测试与 Compose smoke 证据

> 分支：`feat/m3a-task-config`
> 计划：`docs/superpowers/plans/2026-11-04-m3c-recording-backend.md`（Task 1–8）
> 执行人：M3 Owner（当前使用者，经用户确认）
> 环境：Docker Engine 29.6.2 / Compose v5.3.1 / PostgreSQL 18.6 / Spring Boot 4.1.2 / Java 21 / MinIO / Redis 8
> 结论：M3C 录制 / 回放后端 P0（Task 1–7）完成；Task 8 契约测试、全量套件、Docker 全栈 smoke 通过。最终审查修复了并行录制启动的会话级并发缺陷，并补齐失败会话、上传清理与内容校验；后续死锁回归修复（§9）拆分锁定事务与 actor 失败通知，修复后全量 90 classes / 672 tests 通过。G2 设计冻结未关闭，G3—G8 均未标记，模块完成度保持 10%；测试完成度按 UT / IT / 契约 3 个层级记 60%，E2E / 性能安全待续。

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

未改动：数据库 schema / 迁移、`build.gradle.kts`、`docs/design/02` / `12` / 前端；未修改 M3B / M4 / 前端进度行。Task 3 经用户授权修改了 `server/src/main/resources/application-docker.yml` 与 `docker-compose.yml` 的 MinIO 环境变量（`M3_MINIO_*`），属于已批准的配置接线；除这两处外没有计划外 YAML 改动。

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
- `server/src/test/java/com/shijiangtai/m3classroom/contract/RecordingWireContractTest.java`（1 条，最终审查补充）

前两个测试类用 `JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build()` 本地镜像 snake_case，用 `readTree` / `propertyNames()` 断言，不依赖空白或字段顺序。它们是 DTO 字段名的快速契约网，但**不能单独证明全局 Jackson 配置**；`RecordingWireContractTest` 注入实际 Spring `ObjectMapper`，MockMvc IT（`RecordingControllerIT` / `ReplayControllerIT` / `SubmissionControllerIT`）守卫 HTTP 层 snake_case，三者共同覆盖全局配置回归。未为了让测试通过而修改生产 DTO。

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

恢复 `SNAKE_CASE` 后重新执行：`BUILD SUCCESSFUL`，10 条契约测试全部通过。RED 探针证明字段改名会让本地契约测试失败；全局配置本身由 `RecordingWireContractTest`（实际 Spring `ObjectMapper`）与 MockMvc IT 额外守卫。

### 3.3 最终审查修复补充（2026-11-06）

- 新增 `RecordingConcurrencyIT`（1 条）：PostgreSQL 中三线程同时 `multipart/start` camera/screen/audio，断言 1 条 recording、3 条 stream、无异常；修复前该用例稳定触发 `uq_recordings_session` 的 `DataIntegrityViolationException`。
- 新增 `RecordingWireContractTest`（1 条）：使用实际 Spring `ObjectMapper` 验证录制视图的 snake_case 键。
- 契约总数变为 11 条（6 + 4 + 1）。

## 4. 全量测试

```bash
docker compose --profile test run --rm test \
  gradle clean test --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL in 1m 59s`；**90 个测试类 / 663 条用例，0 failure / 0 error / 0 skipped**（解析 `build/test-results/test/TEST-*.xml` 汇总）。Task 8 新增 2 个契约测试类 / 10 条用例；最终审查修复新增 20 条（并发 IT 1、Spring mapper 契约 1、失败会话 / 内容校验 / 清理 / 回放 / 提交 / attempt 收敛 18），全量从 643 增至 663。

> 运行提示：最终全量运行时只启动了 `postgres` 容器。若同时运行 `app` 容器，其 Hikari 连接池会与测试的 11 个 Spring 上下文叠加，可能触发 PostgreSQL `too many clients already`；这是测试环境连接数问题，不是本切片的业务回归。

M3C 相关测试类：

| 层级 | 测试类（用例数） | 小计 |
|---|---|---:|
| 领域 UT | `RecordingTest`(9)、`RequiredStreamsTest`(3)、`RecordingObjectKeyBuilderTest`(2) | 14 |
| 应用 UT | `RecordingAccessPolicyTest`(8)、`RecordingApplicationServiceTest`(26)、`RecordingLifecycleServiceTest`(9)、`ReplayQueryServiceTest`(14)、`SubmissionServiceTest`(11)、`AttemptCleanupTest`(5) | 73 |
| API IT | `RecordingControllerIT`(5)、`ReplayControllerIT`(5)、`SubmissionControllerIT`(5) | 15 |
| 持久化 IT | `RecordingRepositoryIT`(8)、`RecordingConcurrencyIT`(1)、`SubmissionPersistenceIT`(5)、`SubmissionTransactionIT`(2) | 16 |
| 存储 | `InMemoryRecordingObjectStorageTest`(5)、`MinioRecordingObjectStorageTest`(2) | 7 |
| 契约 | `RecordingContractTest`(6)、`ReplayContractTest`(4)、`RecordingWireContractTest`(1) | 11 |

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
| M3-T23 时长补全、part 1 重传；补时长失败禁止提交 | 服务端在 complete 校验 `duration_ms > 0`、`size > 0`、对象 content-type 与流类型匹配；提交要求每个必需已上传流的 `bytes_uploaded > 0` 且 `duration_ms > 0`，否则 422 `RECORDING_INCOMPLETE`（`missing_streams` / `invalid_streams`）。服务端不做 WebM 时长补全或写入。 | 服务端参数 / 非空校验 ✅；WebM 时长写入由浏览器 `fix-webm-duration` 完成，E2E 待续 |
| M3-T24 required_streams 按模式；只保留选中录像 | `RequiredStreamsTest`、`SubmissionControllerIT`（缺流 422）、`SubmissionTransactionIT` / `AttemptCleanupTest`（其他 attempt 行与对象删除、选中录像保留）；回放 manifest 按模式过滤，面试不会泄漏 screen 流 | ✅ 后端集成覆盖 |
| M3-T25 回放 offset / seek / 漂移 <200ms / 倍速 / 全屏 | 回放 manifest 返回 `offset_ms` / `duration_ms` / 预签名 URL，结构由 `ReplayControllerIT` 验证；**生产 API 目前不填充 per-stream offset（恒为 0）**，`duration_ms` 来自客户端 complete 参数并只做 >0 校验，不做媒体级时长校验。`ReplayControllerIT` 的非零 offset 是测试直接写 `session.putStreamOffset` 构造的。 | 服务端 manifest 结构 ✅；offset 传输契约未定（见 §7 第 9 条），seek / 漂移 / 倍速 / 全屏属前端，E2E 待续 |
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
8. 全量测试仍输出既有的 JPA / Hikari shutdown INFO 与异步 flush 重试日志，0 failure / 0 error / 0 skipped。
9. **回放 `offset_ms` 契约缺口（Important，本波不实现；已于 §11 实现）**：生产 API 不填充 per-stream offset，manifest 的 `offset_ms` 当前恒为 0。offset 的传输方式（在 `complete` 请求中新增 `offset_ms`，还是新增独立命令）属于契约变更，必须先与 Media/Training / Foundation owner 达成一致并更新 `02` / `05B`，之后才能实现；本波明确不实现。2026-11-06 经用户确认，采用 `complete` 新增可选 `offset_ms`，详见 §11。
10. **全量测试连接数**：测试套件会缓存多个 Spring 上下文（每个 Hikari 池默认最多 10 连接）；若 Docker `app` 容器同时运行，可能与 PostgreSQL 默认 `max_connections=100` 叠加。§9 的并发 IT 因新增可控存储 `@TestConfiguration` 拥有独立上下文，已用 `@DirtiesContext(AFTER_CLASS)` 在该类结束后释放连接；最后一次全量运行仅启动 `postgres`，`90 classes / 672 tests` 通过。该问题属测试环境隔离，不影响生产代码。

## 8. 结论

- Task 1–7 的 M3C 后端实现可运行，REST / WS / PostgreSQL / MinIO 全链路 smoke 通过。
- Task 8 新增 2 个契约测试类 / 10 条用例；契约 RED 探针显示字段或命名漂移会被捕获。
- 最终审查修复：`start/complete/abort` 在读取 recording 前锁定 session（`FOR NO KEY UPDATE`），`RecordingRepositoryAdapter.save` 改为 merge-only；并行三路 start 的 PostgreSQL IT 通过。complete/abort 失败会将会话置为 `FAILED(recording_lost)`（终态除外），并 best-effort 中止 multipart；清理会中止所有带 uploadId 的流（含 FAILED）。complete 校验时长 / 大小 / content-type，提交校验已上传流非空且时长 > 0。
- 全量套件 90 classes / 672 tests，0 failure / 0 error / 0 skipped。
- 进度：M3C 测试行 UT / IT / 契约 ✅、E2E / 性能安全 ⬜、测试完成度 60%；模块完成度保持 10%，G2—G8 未标记。回放 `offset_ms` 生产填充按契约变更延期，见 §7 第 9 条。

## 9. 死锁回归修复（2026-11-06）

### 9.1 问题

最终审查把 `RecordingApplicationServiceImpl.start/complete/abort` 整体放进 `@Transactional`，并在读取 recording 前对 session 行取 `FOR NO KEY UPDATE`。失败路径在**仍持有该行锁**时同步调用 `runtimeRegistry.find(...).fail(...)`（`SessionActor.fail` 是 `synchronized`）。与此同时，并发的 end / 超时回调持有 actor 监视器，并在 `SessionRuntimeRegistry.onStatusChanged` 中 `sessionRepository.save(actor.session())`（`saveAndFlush`）等待同一 session 行锁。形成 Java monitor ↔ PostgreSQL 行锁的硬死锁，PostgreSQL 不会检测。

### 9.2 修复

| 项 | 方案 |
|---|---|
| 锁 / 监视器环 | 新增包级 `RecordingMutationService`：`start/complete/abort` 的全部行锁聚合变更与 multipart IO 都在该 bean 的 `@Transactional` 方法内；公开的 `RecordingApplicationServiceImpl` 不再有写事务，只有在 mutation 方法返回或抛出（事务已提交 / 回滚）后才执行 `failSessionOnRecordingLoss`。 |
| 失败写不丢失 | 新增包级 `RecordingSessionFailureService.failDirectly(...)`，`@Transactional(propagation = REQUIRES_NEW)`；无 actor 时重新读取 session 后落 `FAILED` 与 `session.failed`。actor 路径抛错后走该路径；若持久化状态已是 `FAILED` 或事件表已存在 `session.failed`，不再追加第二个事件。 |
| 失败分类 | multipart 已消费后的 DomainException 包装为 `RecordingLostDomainException`（仍触发 `noRollbackFor` 提交失败流），基础设施 RuntimeException 包装为 `RecordingLostRuntimeException`（默认回滚）；公开方法最终重新抛出原始异常。 |
| 清理 abort 范围 | `RecordingLifecycleService.abortStreamUpload` 仅对 `UPLOADING` / `FAILED` 且 `uploadId != null` 的流调用 abort；`UPLOADED` 流只安排对象删除。 |
| 清理锁序 | `removeAttemptRows` 先对候选 session 执行 `findByIdForUpdate`（与 complete/abort 相同的 `FOR NO KEY UPDATE`），在锁下复核 `isDeletable`，再删除 recording 行与会话行；采用“先锁 session”方案，不依赖 V10 级联删除会话。 |
| content-type | 去掉参数并小写后要求与 `video/webm` / `audio/webm` 精确相等，不再使用 `startsWith`，拒绝 `video/webmfoo`。 |
| 测试重命名 | `completeRejectsZeroDurationAndFailsTheSession` → `completeRejectsZeroDurationBeforeConsumingMultipart`；零大小用例同步改为 `...BeforeConsumingMultipart`。 |

### 9.3 测试

- `RecordingConcurrencyIT.abortAndConcurrentActorEndDoNotDeadlock`：可控存储让 abort 在持有 session 行锁时阻塞在 `abortMultipart`；随后启动 `SessionApplicationService.end` 使 actor 持有监视器并阻塞在终态保存；释放 abort 后要求两个 future 在 20s 内完成，最终会话 `FAILED`、流 `FAILED`。旧实现会在该编排下必然死锁。
- `RecordingConcurrencyIT.sessionFailureSurvivesRecordingTransactionRollback`：注入 `completeMultipart` 基础设施异常（非 DomainException），断言异常原样抛出后 session 行为 `FAILED` 且恰有 1 条 `session.failed`。`RecordingRepositoryAdapter.save` 本身是 `REQUIRES_NEW`，所以不把流行状态作为回滚证据；该用例证明的是回滚场景下 session 失败写仍独立持久化。
- 单测：失败通知顺序（mutation 调用先于 `failDirectly`）、multipart 前校验不触发会话失败、actor 在已持久化 `FAILED` / 已有 `session.failed` 事件后抛错不重复追加、content-type 前缀拒绝、清理不 abort `UPLOADED` 流但仍删对象。
- 定向：`RecordingApplicationServiceTest` 31、`RecordingLifecycleServiceTest` 11、`RecordingConcurrencyIT` 3、`RecordingControllerIT` 5（合计 50；Gradle 同时自动执行 ArchitectureTest 3 条），0 failure / 0 error / 0 skipped。
- 全量：`90 classes / 672 tests`，0 failure / 0 error / 0 skipped。

## 10. 保留期（M3-T26，2026-11-06）

### 10.1 授权与范围

用户 2026-11-06 确认：当前 M3 Owner 代 Media/Training Owner 执行 M3C 保留期实现（候选查询与仓储端口、对象优先删除、清理服务与调度器及其测试）。该确认仅覆盖本切片列出的 M3C 保留期文件，不授权其他模块。

范围：`SessionRepository` / `SessionJpaRepository` / `SessionRepositoryAdapter` 的临时保留候选查询；`SubmissionRepository` / `SubmissionJpaRepository` / `SubmissionRepositoryAdapter` 的提交保留候选查询与按会话删除；`RecordingLifecyclePort` / `RecordingLifecycleService` 的临时与提交对象优先删除；`RecordingRetentionService`、`M3RetentionProperties`、`RecordingRetentionScheduler` 及对应测试 / 内存仓储。最终审查修复另在 M3B 提交路径 `SubmissionServiceImpl` 上增加 session 行锁（M3B 属当前 M3 Owner，非跨模块）。未改 schema / 迁移、`application*.yml`、`docker-compose.yml`、`ServerApplication`、`ops` schema、M2 Java、API / DTO / WS / 事件契约与权限。

### 10.2 语义

| 项 | 实现 |
|---|---|
| 临时候选 | `session_type = 'task'` 且状态为 `ended` / `reviewing` / `failed` / `cancelled`；锚点 `COALESCE(ended_at, updated_at)`，任务关闭更晚时取 `GREATEST(锚点, tasks.closed_at)`；`锚点 + 24h <= now`（含边界）。`submitted` / `preparing` / `in_progress` 永不入选。 |
| 提交候选 | `courses.end_at IS NOT NULL` 且 `end_at + INTERVAL '1 year' <= now`（含边界）；`end_at` 为空永不清理。 |
| 删除顺序 | 先对 UPLOADING / FAILED 流 best-effort `abortMultipart`（删除对象不会中止分片上传，行删除会丢失 uploadId），再同步删除所有非空 `objectKey` 的 MinIO 对象。临时：recording 行 → session 行；提交：submission 行（先释放 `ON DELETE RESTRICT` 外键并级联 M4 / M7 行）→ recording 行 → session 行。 |
| 失败 / 重试 | 对象删除异常不捕获，事务回滚、行全部保留；单候选失败计入 `failed` 并继续后续候选，下次调度重试（对象删除幂等）。 |
| 并发复核 | 两类删除都先 `findByIdForUpdate` 锁 session 行；临时路径重算状态与到期，提交路径只重算状态（课程结束 +1 年由候选查询固定）。提交路径在 `forceFlush` 之后、写入 submission 之前取同一 session 行锁：清理先到时提交看到会话已删除，提交先到时清理复核到 `submitted` 并跳过；`RecordingConcurrencyIT` 的确定性交错用例覆盖对象删除窗口。 |
| 调度器 | `@Component` + `@Profile("!test")`，单 daemon 线程 `scheduleWithFixedDelay`；默认 `m3.retention.enabled=true`、`initial-delay=1m`、`interval=1h`；无 `@EnableScheduling`，`runSafely` 吞异常避免停摆。训练保留（1 个月）不在本切片，临时查询只选 `session_type = 'task'`。 |

本节取代 §6 T26 行与 §7 第 2 条中「保留期属后续」的状态。

### 10.3 测试

| 测试类 | 用例数 | 覆盖 |
|---|---:|---|
| `RetentionCandidateQueryIT` | 3 | PostgreSQL 候选查询、24h / 1 年边界、`reviewing` / `failed` / 锚点优先级、training 行排除、`deleteBySession` 先释放 RESTRICT 外键 |
| `RecordingRetentionServiceTest` | 3 | 删除 / 失败计数、单候选失败不阻塞后续、被生命周期跳过的候选不计数 |
| `RecordingRetentionSchedulerTest` | 5 | 默认配置、禁用、`runSafely` 使用 `ClockProvider` 并吞异常、启用后按 initialDelay 执行 |
| `RecordingLifecycleServiceTest`（新增 9 条，11 → 20） | 9 | 对象先于行删除、对象删除失败保留行、任务关闭延长窗口、拒绝 submitted、submission 先于 session、拒绝非 submitted、UPLOADING / FAILED 流先 abort multipart |
| `RecordingConcurrencyIT`（新增 1 条，3 → 4） | 1 | 确定性 submit ↔ 临时清理交错：清理持有行锁并阻塞在对象删除时，提交必须等待；清理删除会话后提交失败且不产生 submission |
| **新增合计** | **+21 条** | |

全量：

```bash
DOCKER="/mnt/c/Users/forever alone/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe"
"$DOCKER" compose --profile test run --rm test gradle test --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL in 3m 50s`；**93 个测试类 / 693 条用例，0 failure / 0 error / 0 skipped**（解析 `server/build/test-results/test/TEST-*.xml` 汇总）。相对切片前基线 90 classes / 672 tests，新增 3 类 / 21 条。

### 10.4 剩余缺口

- 训练模式 1 个月保留属 M6，本切片未实现；临时查询与提交候选查询都只处理 `session_type = 'task'`。
- 无 live MinIO 保留期 smoke：对象优先删除路径由 `RecordingLifecycleServiceTest` 使用内存存储覆盖；`RetentionCandidateQueryIT` 走真实 PostgreSQL，但不触碰 MinIO。
- 调度器在 `test` profile 不启动（按设计），因此全量套件不验证后台线程；调度器行为由直接实例化的 `RecordingRetentionSchedulerTest` 覆盖。
- 未单独增加 `RecordingRetentionService.cleanup` 的端到端 PostgreSQL IT；最风险的 submit ↔ 清理交错已由 `RecordingConcurrencyIT` 确定性覆盖，候选查询与生命周期删除分别有 IT / 单测。
- 残余风险：若清理已删除部分对象、后续对象删除失败，事务会回滚并保留行，但已删除对象无法恢复；会话仍可被提交，回放可能缺流。该窗口属对象存储部分故障，当前记录为残余风险；彻底修复需在失败时把 recording / stream 标记 failed 或增加对象级补偿。

### 10.5 最终审查修复

| 严重度 | 问题 | 修复 |
|---|---|---|
| Critical | 提交与临时清理的“对象已删、行未删”窗口可让提交在清理回滚后仍提交 `submitted`，回放指向已删除对象 | `SubmissionServiceImpl.submit` 在 `forceFlush` 之后取 session `findByIdForUpdate`，再复核状态并写入 submission；新增 `RecordingConcurrencyIT.temporaryCleanupAndConcurrentSubmitDoNotLoseSubmittedMedia` 确定性交错用例 |
| Important | 保留期删除只删对象、不中止 multipart；FAILED / UPLOADING 流的 uploadId 随行删除丢失 | `deleteObjectsNow` 先 `abortStreamUpload`（best-effort，UPLOADED 流 no-op）再删对象；`RecordingLifecycleServiceTest` 增加 abort 顺序用例 |
| Minor | 类注释、证据并发措辞、候选查询 training / status / 锚点覆盖、重复 info 日志、调度器 start 幂等与正间隔校验、文件尾换行 | 已同步修正；提交候选查询增加 `session_type = 'task'` 防御 |

## 11. 回放 offset_ms 生产填充（2026-11-06）

### 11.1 授权与契约

用户 2026-11-06 确认：当前 M3 Owner 代 Media/Training Owner（05B / M3C）、Foundation Owner（02）与架构 owner 执行 `offset_ms` 契约变更与实现。确认仅覆盖 `offset_ms` 传输契约及本节列出的文件。

契约（`02` §4.3、`05B` §3/§4.1）：

- `POST /api/v1/sessions/{session_id}/recordings/{stream_type}/multipart/complete` 请求体新增可选 `offset_ms`（整数 ≥ 0，缺省 0，向后兼容）。
- 语义：该路录制开始相对本轮 t0（最早启动的录制流）的毫秒偏移；录制先于 `session.start` 时仍从 0 起，不得为负。
- 服务端在 complete 时写入 `sessions.stream_offsets[stream_type]` 与 `recording_streams.offset_ms`；回放 manifest 原样返回。
- 无 schema / 迁移变更（两处字段已存在）。

### 11.2 实现

| 层 | 文件 | 变更 |
|---|---|---|
| 契约 | `docs/design/02-API与实时协议.md`、`docs/design/05B-M3-录制上传与回放.md` | complete 请求字段与 t0 语义 |
| 后端 DTO | `CompletePartCommand`、`RecordingRequestMapper.CompleteRequest` | 新增 `offsetMs`，4 参构造器保留为 0 缺省；负数映射为 `VALIDATION_ERROR` |
| 后端领域 | `Recording.updateStreamOffset`、`RecordingStream.withOffset` | 更新流偏移；负值拒绝 |
| 后端编排 | `RecordingMutationService.complete` | 校验偏移，成功后更新 recording 与 session 的 offset 并持久化 |
| 前端 | `types/recording.ts`、`utils/recorderUploader.ts`、课堂 / 面试页 | 以本轮 t0 计算并上报 `offset_ms` |
| 测试 | `RecordingContractTest`、`RecordingControllerIT`、`ReplayControllerIT`、`recorderUploader.spec.ts` | 缺省 0、非零值传输、complete 写入后回放读取、前端 offset 计算 |

### 11.3 测试

- 后端全量：`BUILD SUCCESSFUL in 4m 11s`；93 classes / 693 tests，0 failure / 0 error / 0 skipped。
- 前端：15 test files / 135 tests 通过；`npm run build` 通过（仅既有 Element Plus chunk 体积警告）。
- 定向：`RecordingControllerIT`、`ReplayControllerIT`、`RecordingContractTest`、`RecordingApplicationServiceTest` 通过。

### 11.4 剩余缺口

- 真实浏览器 E2E 仍未覆盖 offset 上报（MediaRecorder 实际启动时刻与 t0 的偏差）；前端单测使用注入时钟验证计算。
- `offset_ms` 为客户端上报值，服务端仅校验非负；无法在服务端独立校验其与媒体实际启动时刻的一致性。

## 12. 后端回放事件过滤（2026-11-06）

### 12.1 授权与契约

用户 2026-11-06 确认：当前 M3 Owner 代 Media/Training Owner 执行 `/events` 控制事件过滤。契约（`05B` §7.1）：`GET /sessions/{id}/events` 在应用 `limit` 前排除 `session.time_tick`、`session.resumed`、`ping`、`pong`、`time.sync`；`max_seq` 仍为会话总 seq，响应结构不变。

### 12.2 实现

- 新增领域常量 `ReplayEventTypes.NON_REPLAYABLE`，避免应用层与前端各自维护一份不同的集合（前端保留同集合作为兜底）。
- `SessionEventStore` 新增 `afterReplayable(id, seq, limit)`：默认在内存结果上过滤；JPA 适配器用 `type NOT IN :excluded` 在 SQL 层先过滤再 limit，避免“整页都是 tick → 空页”导致客户端分页提前结束。
- `ReplayQueryServiceImpl.events` 改用 `afterReplayable`；`maxSeq` 不变。
- `InMemorySessionEventStore` 同步实现先过滤后 limit，供单测 / 控制器测试使用。

### 12.3 测试

- `ReplayControllerIT.replayEventsExcludeControlPlaneRowsBeforeApplyingLimit`：插入 3 条业务事件 + 4 条控制事件 + 1 条业务事件，断言 `/events` 只返回 4 条业务事件、最后一条 `seq=8`，且控制事件不在响应中。
- 后端全量：`BUILD SUCCESSFUL in 4m 38s`；93 classes / 694 tests，0 failure / 0 error / 0 skipped。
- 前端不修改；既有 15 files / 135 tests 与构建保持通过。

### 12.4 剩余缺口

- 前端仍保留同一组过滤作为向后兼容兜底；后端与前端集合若未来新增控制事件需同步。
- 真实浏览器 E2E 未覆盖事件点击 seek 在过滤后的时间线行为。
