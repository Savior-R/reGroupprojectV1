# 2026-11-05 M3B 感知通道（ASR / OCR / 降级）证据

> 分支：`feat/m3a-task-config`（提交主题 `[M3B] ...`）
> 计划：`docs/superpowers/plans/2026-11-05-m3b-perception.md`（Task 1—8）
> 设计：`docs/superpowers/specs/2026-11-05-m3b-perception-design.md`（2026-11-05 用户批准）
> 执行人：M3 Owner（当前使用者）
> 环境：Docker Compose v5.3.1 / PostgreSQL / Redis / MinIO / Spring Boot 4.1.2 / Java 21
> 结论：感知通道后端 P0 完成：感知 WebSocket、ASR final 落库、语音问答、截图异步 OCR、`m3.frames`、AI 降级均通过测试与 Compose smoke。G2 未关闭，模块完成度保持 10%。

## 1. 范围与授权

本次切片只包含 M3B 感知通道后端：

- 感知 WebSocket `/ws/v1/sessions/{sessionId}/perception?ticket=...`：`audio.start` / 二进制 PCM / `audio.stop`，服务端 `asr.partial` / `asr.final` / `error`，关闭码 4001 / 4002 / 4003 / 1000。
- ASR final 落库：课堂 `speaker_type=teacher` / `kind=utterance`，结构化面试 `speaker_type=candidate` / `kind=answer`；课堂 final 异步教师话语分类，高置信问题触发语音问答。
- 屏幕截图 `POST /api/v1/sessions/{id}/perception/screen`：同步校验 + pending 帧落库，异步 OCR；`m3.frames` 更新为 `processed` / `failed`，成功帧写 `perception.screen_changed`。
- AI 降级：未配置 `m3.ai.base-url` 时 ASR 返回 `error SERVICE_UNAVAILABLE`，OCR 帧标记 `failed`，session 保持 `in_progress`。
- TDD RED / GREEN、全量测试、Compose 降级 smoke、证据与进度。

不做（本轮显式 deferred）：

- `perception.acoustic` 与音量 / 静音 / 语速等声学特征（M4 音频特征工作）。
- `final` / `chart` 帧类型（需要视觉模型；本轮只产出 `normal` / `slide_change` / `low_confidence`）。
- 前端课堂 / 面试 UI、M3C 录制 / 回放、E2E、NFR / 性能 / 安全验收。

文件归属：所有生产 / 测试路径属于 M3 Owner 的 M3B 后端范围；未修改 M3C、M4、前端或其他模块代码。`docs/team/02-进度看板.md` 只改 M3B 行并新增一条 §5 记录。`docs/team/01-模块归属与修改规则.md` 的路径表尚未登记 `infrastructure/perception/**` 与感知 WS 路径；本任务不改该表，归属更新待 owner 审批。

## 2. 实现摘要

| 切片 | 实现 |
|---|---|
| 帧领域 | `FrameType` / `FrameStatus` / `OcrBox` / `SessionFrame`；`FrameTypeClassifier`：`confidence != null && < 0.60` → `low_confidence`，否则 `change_score != null && >= 0.125` → `slide_change`，否则 `normal`（`confidence = null` 走 `change_score`） |
| Actor 钩子 | `recordSpeechFinal`（ASR final 落库）、`onTeacherSpeechClassified`（语音问答）、`onScreenProcessed`（`perception.screen_changed`）、`currentAtMs` / `currentAnswerId`、`addStatusListener`、`isInProgress` |
| ASR 适配 | `AsrStreamClient` / `AsrSession` / `AsrRequest` / `AsrChunk` 端口；`PythonAsrStreamClient`：`http→ws`、`https→wss`，query `session_id/sample_rate/format/language`，有界发送队列 + 单发送线程，`finish` 发 `{"type":"finish"}` |
| OCR / 分类适配 | `OcrClient` / `OcrResult`、`TeacherSpeechClassifier` / `ClassifyResult`；`PythonPerceptionHttpClient`：OCR `image_base64`、分类 `text/recent_texts/mode`（snake_case）；超时 / 非 2xx / 缺字段抛 `RuntimeException` |
| 帧持久化 | `SessionFrameStore` 端口 + `SessionFrameEntity` / `SessionFrameJpaRepository` / `SessionFrameStoreAdapter`（`m3.frames`、jsonb `bounding_boxes`）；`markFailed` 只降级 pending 帧 |
| 截图服务 | `PerceptionApplicationService`：JPEG ≤5 MB、`at_ms >= 0`、`change_score ∈ [0,1]`、owner、`in_progress` 同步校验；pending 落库后有界单线程 OCR；OCR / 落库失败 → `failed`，事件失败不降级 processed 帧 |
| 感知 WS | `PerceptionProtocolParser`（`pcm16` / 16000 / 单声道精确校验）、`PerceptionWebSocketHandler`（ASR 转发、partial/final、2 秒 drain、AI 降级、协议错误 4003）、`PerceptionConnectionRegistry`（单连接替换 1000、终态关闭 4002） |
| 配置 | `PerceptionAiProperties`（`m3.ai.*`，OCR 15 s / 分类 3 s / ASR connect 3 s）；`M3Config` 为 `OcrClient` / `TeacherSpeechClassifier` / `AsrStreamClient` 提供 `@ConditionalOnMissingBean` 降级实现，抛 `SERVICE_UNAVAILABLE` |

## 3. TDD RED / GREEN 证据

所有命令均在仓库根目录执行，测试运行在 `docker compose --profile test` 容器内。

| Task | 提交 | 测试类（用例数） | RED | GREEN |
|---|---|---|---|---|
| 1 帧领域与分类 | `24d02d1` | `FrameTypeClassifierTest`（4） | `compileTestJava` 12 个 `cannot find symbol`，BUILD FAILED | 4/4，0 failure / 0 error |
| 2 Actor 感知钩子 | `8940ece` | `SessionActorPerceptionTest`（7） | `compileTestJava` 16 个 `cannot find symbol`，BUILD FAILED | 7/7；`SessionActorTest` 30/30 回归 |
| 3 OCR / 分类适配 | `a487ff6` | `PythonPerceptionHttpClientTest`（4） | `compileTestJava` 12 个 `cannot find symbol`，BUILD FAILED | 4/4 |
| 4 ASR WebSocket 适配 | `17053dd` | `PythonAsrStreamClientIT`（2） | `compileTestJava` 17 个 `cannot find symbol`，BUILD FAILED | 2/2；`ArchitectureTest` 通过 |
| 5 帧持久化 | `3d837af` | `SessionFramePersistenceIT`（4，修复后） | `SessionFrameStore` 不存在，BUILD FAILED | 4/4；`ArchitectureTest` 通过 |
| 6 截图服务 + 接口 | `54fae32` / 修复 `c3e1116` | `PerceptionApplicationServiceTest`（8）、`PerceptionScreenIT`（5） | 服务 RED：`PerceptionApplicationService` 不存在（8 errors）；控制器 RED：5 条预期 202/400/403/409 实际 500 | 8/8 + 5/5；修复后 `SessionFramePersistenceIT` 4/4 |
| 7 感知 WS | `3cf3111` / 修复 `a4b78ee` | `PerceptionProtocolParserTest`（8）、`PerceptionWebSocketIT`（20） | 解析器 RED：10 errors；IT RED：15/15 因 `/perception` 路由不存在而失败 | 8/8 + 20/20；全量 clean suite 65 classes / 485 tests |

补充 RED / GREEN：

- Task 6 修复（`c3e1116`）：临时回退两处生产修复后，`actorFailureAfterOcrSuccessKeepsFrameProcessed` 与 `markFailedAfterProcessedKeepsProcessedFrame` 两条用例失败；恢复后 17/17 通过（服务 8 + 接口 5 + 持久化 4）。
- Task 7 修复（`a4b78ee`）：新增 5 条 IT（drain 超时、流中 ASR error、意外 close、PCM 原样转发、面试 final 映射），IT 从 15 条增至 20 条；全量 clean suite 仍为 65 classes / 485 tests。

## 4. 测试计数

最终全量命令：

```bash
docker compose --profile test run --rm test gradle clean test --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL in 1m 7s`；XML 汇总 **65 个测试类 / 485 条用例，0 failure / 0 error / 0 skipped**。

口径拆分：

- M3B 提交范围：**62 个测试类 / 472 条用例**（相对上一基线 53 类 / 410 条新增 **9 个类 / 62 条用例**）。
- 同一工作区包含暂停中的 M3C 未跟踪测试 3 个类 / 13 条用例（`domain/recording`），合计 65 类 / 485 条；M3C 文件不进入本次提交。
- 新增 9 个测试类：`FrameTypeClassifierTest` 4、`SessionActorPerceptionTest` 7、`PythonPerceptionHttpClientTest` 4、`PythonAsrStreamClientIT` 2、`SessionFramePersistenceIT` 4、`PerceptionApplicationServiceTest` 8、`PerceptionScreenIT` 5、`PerceptionProtocolParserTest` 8、`PerceptionWebSocketIT` 20。

关键覆盖：

- 分类边界：`null confidence` 走 `change_score`；低置信优先；`0.60` / `0.125` 边界。
- ASR final：课堂 teacher/utterance、面试 candidate/answer + `speaker_ref=当前 answer_id`；空文本 / 终态后 late final 丢弃。
- 语音问答：`question` 高置信 → `classroom.question_asked {source=voice}` + 举手窗口；低置信 → `classroom.question_suggested`；分类失败 WARN-only。
- 感知 WS：partial/final 推送、PCM 原样转发、`audio.stop` 2 秒 drain、drain 超时 1000、连接替换、session end 4002、非法 ticket 4001、`preparing` / 协议非法 4003、终态 4002。
- 截图：202 + pending、异步 processed + `perception.screen_changed`、OCR 失败 failed 且无事件、非 owner 403、非 `in_progress` 409、空 / 非 JPEG / >5 MB / 负 `at_ms` 400。
- AI 降级：ASR open 失败 / 流中 error / 意外 close 均只发 `error SERVICE_UNAVAILABLE`，session 保持 `IN_PROGRESS`，`audio.stop` 后 1000。

## 5. Docker Compose smoke（降级 AI 路径）

```bash
docker compose up -d --build
curl -fsS http://localhost:8080/actuator/health
# {"groups":["liveness","readiness"],"status":"UP"}
```

Compose 未配置 `M3_AI_BASE_URL` / `m3.ai.base-url`，因此 ASR / OCR 走 `M3Config` 的降级 bean。

| 步骤 | 请求 | 结果 |
|---|---|---|
| 1. 创建 free 任务 | `POST /api/v1/courses/00000000-0000-0000-0000-000000000100/tasks`（教师 `...0010`） | `task_id=01a1a9c1-0e9f-73d3-9766-b22a71ef67e7`，`status=draft` |
| 2. 上传参考材料 | `POST /api/v1/tasks/{id}/material`（151 B 文本） | `parse_status=parsed`，`char_count=51` |
| 3. 发布 | `POST /api/v1/tasks/{id}/publish` | `status=published`，`config_version=1`，`assigned_count=2` |
| 4. 创建会话 | `POST /api/v1/tasks/{id}/sessions`（学生 `...0020`） | `session_id=01a1a9c2-6467-712d-a573-f2ad3afe89ae`，`status=preparing` |
| 5. 控制 WS 开始 | `recording.ready`（camera/screen/audio）+ `session.start` | `recording.ready_ack ok=true`、`session.started`、`server.ack ok=true`；session `in_progress` |
| 6. 申请感知 ticket | `POST /api/v1/sessions/{id}/ws-ticket` | 一次性 ticket（30 秒） |
| 7. 感知 WS | `ws://localhost:8080/ws/v1/sessions/{id}/perception?ticket=...` | 见下方 transcript |
| 8. 截图 | `POST /api/v1/sessions/{id}/perception/screen`（multipart `file`、`at_ms=1000`、`change_score=0.3`） | HTTP 202 `{"frame_id":"01a1a9c2-a2be-7e17-b9b2-735782e2f0f5","status":"pending"}` |
| 9. 帧落库 | `select status,error_message from m3_classroom.frames order by created_at desc limit 1` | `failed` / `OCR 服务不可用` |
| 10. 会话状态 | `GET /api/v1/sessions/{id}` | `status=in_progress`（ASR / OCR 降级均未改变 session） |

感知 WS transcript（Node 22 + `ws`）：

```text
OPEN perception
<< {"type":"error","code":"SERVICE_UNAVAILABLE","message":"ASR 服务不可用"}
SENT binary 3200 bytes
SENT audio.stop
CLOSE 1000 音频结束
RESULT {"error":"SERVICE_UNAVAILABLE","close":1000,"why":"closed"}
```

数据库校验：

```text
 status | error_message
--------+----------------
 failed | OCR 服务不可用
(1 row)
```

会话校验（感知 WS 之后、截图之后两次查询）：

```json
{"session_id":"01a1a9c2-6467-712d-a573-f2ad3afe89ae","status":"in_progress",
 "started_at":"2026-11-05T03:30:56.621378Z","ended_at":null,"last_seq":15}
```

说明：

- 首次尝试在 `session.start` 后立即关闭控制 WS，30 秒 grace 到期后 session 按设计变为 `failed`（`duration_ms=30197`）；这不是感知通道缺陷。复跑时用 keepalive 保持控制 WS（并回复 `pong`），感知 / OCR 检查期间 session 始终 `in_progress`。
- 感知 WS 未收到 `asr.partial` / `asr.final`，因为无 AI 服务；收到 `error SERVICE_UNAVAILABLE` 后仍保持连接，直到客户端 `audio.stop` 才以 1000 正常关闭，符合降级契约。
- 截图 202 返回时帧为 `pending`；2 秒内异步 OCR 失败并落库为 `failed`，未写 `perception.screen_changed`，session 保持 `in_progress`。

## 6. 未覆盖 / 后续

- `perception.acoustic`、音量 / 静音 / 语速等音频特征：deferred 到 M4。
- `final` / `chart` 帧类型与视觉模型：deferred；本轮分类器只产出 `normal` / `slide_change` / `low_confidence`。
- 真实 Python ASR / OCR 服务联调：本轮测试使用 fake 端口；Compose smoke 走降级路径（未配置 `m3.ai.base-url`）。真实云 smoke 见 `2026-11-04-m3-python-cloud-smoke.md`。
- 前端课堂 / 面试 UI、M3C 录制 / 回放、E2E、NFR / 性能 / 安全：未开始，G2—G8 均未标记。
- 审查 minor（已知、非阻塞）：ASR 早期 chunk 缓冲无上限、发送 vs finish 竞态、分类器线程池关闭不 await、部分错误路径测试缺口；不影响本轮 P0 契约。

## 7. 本次变更文件

生产（Task 1—7）：

- `server/src/main/java/com/shijiangtai/m3classroom/domain/session/FrameType.java`
- `server/src/main/java/com/shijiangtai/m3classroom/domain/session/FrameStatus.java`
- `server/src/main/java/com/shijiangtai/m3classroom/domain/session/OcrBox.java`
- `server/src/main/java/com/shijiangtai/m3classroom/domain/session/SessionFrame.java`
- `server/src/main/java/com/shijiangtai/m3classroom/domain/session/FrameTypeClassifier.java`
- `server/src/main/java/com/shijiangtai/m3classroom/application/SessionActor.java`（感知钩子）
- `server/src/main/java/com/shijiangtai/m3classroom/application/PerceptionApplicationService.java`
- `server/src/main/java/com/shijiangtai/m3classroom/application/view/PerceptionFrameView.java`
- `server/src/main/java/com/shijiangtai/m3classroom/application/port/AsrStreamClient.java`
- `server/src/main/java/com/shijiangtai/m3classroom/application/port/AsrSession.java`
- `server/src/main/java/com/shijiangtai/m3classroom/application/port/AsrRequest.java`
- `server/src/main/java/com/shijiangtai/m3classroom/application/port/AsrChunk.java`
- `server/src/main/java/com/shijiangtai/m3classroom/application/port/OcrClient.java`
- `server/src/main/java/com/shijiangtai/m3classroom/application/port/OcrResult.java`
- `server/src/main/java/com/shijiangtai/m3classroom/application/port/TeacherSpeechClassifier.java`
- `server/src/main/java/com/shijiangtai/m3classroom/application/port/ClassifyResult.java`
- `server/src/main/java/com/shijiangtai/m3classroom/application/port/SessionFrameStore.java`
- `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/perception/PerceptionAiProperties.java`
- `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/perception/PythonPerceptionHttpClient.java`
- `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/perception/PythonAsrStreamClient.java`
- `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/config/M3Config.java`（降级 bean）
- `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/SessionFrameEntity.java`
- `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/SessionFrameJpaRepository.java`
- `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/SessionFrameStoreAdapter.java`
- `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/ws/PerceptionProtocolParser.java`
- `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/ws/PerceptionConnectionRegistry.java`
- `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/ws/PerceptionWebSocketHandler.java`
- `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/ws/WebSocketConfig.java`（注册 `/perception`）
- `server/src/main/java/com/shijiangtai/m3classroom/api/PerceptionScreenController.java`
- `server/src/main/java/com/shijiangtai/m3classroom/api/dto/PerceptionScreenResponse.java`

测试：

- `server/src/test/java/com/shijiangtai/m3classroom/domain/session/FrameTypeClassifierTest.java`
- `server/src/test/java/com/shijiangtai/m3classroom/application/SessionActorPerceptionTest.java`
- `server/src/test/java/com/shijiangtai/m3classroom/infrastructure/perception/PythonPerceptionHttpClientTest.java`
- `server/src/test/java/com/shijiangtai/m3classroom/infrastructure/perception/PythonAsrStreamClientIT.java`
- `server/src/test/java/com/shijiangtai/m3classroom/persistence/SessionFramePersistenceIT.java`
- `server/src/test/java/com/shijiangtai/m3classroom/application/PerceptionApplicationServiceTest.java`
- `server/src/test/java/com/shijiangtai/m3classroom/api/PerceptionScreenIT.java`
- `server/src/test/java/com/shijiangtai/m3classroom/infrastructure/ws/PerceptionProtocolParserTest.java`
- `server/src/test/java/com/shijiangtai/m3classroom/ws/PerceptionWebSocketIT.java`
- `server/src/test/java/com/shijiangtai/m3classroom/support/FakeOcrClient.java`
- `server/src/test/java/com/shijiangtai/m3classroom/support/InMemorySessionFrameStore.java`
- `server/src/test/java/com/shijiangtai/m3classroom/support/FakeAsrStreamClient.java`
- `server/src/test/java/com/shijiangtai/m3classroom/support/FakeTeacherSpeechClassifier.java`

文档：

- `docs/design/05A-M3-课堂状态机与WebSocket.md`（§3.3 / §6.2 感知协议）
- `docs/implementation/04-M3-实现设计.md`（感知类与签名）
- `docs/team/evidence/2026-11-05-m3b-perception.md`
- `docs/team/02-进度看板.md`（仅 M3B 行 + 一条 §5 记录）
- `docs/superpowers/plans/2026-11-05-m3b-perception.md`（`pendingCount` → `pendingTasks` 修正）
