# 2026-11-04 M3B 课堂 WebSocket、信封契约与 Compose smoke 证据

> 分支：`feat/m3a-task-config`（M3B 计划在该分支继续；提交主题 `[M3B] ...`）
> 计划：`docs/superpowers/plans/2026-11-04-m3b-classroom-backend.md`（Task 12）
> 执行人：M3B Owner（当前使用者）
> 环境：Docker Engine 29.6.2 / Compose v5.3.1 / PostgreSQL 18.6 / Spring Boot 4.1.2 / Spring Framework 7.0.10 / Java 21 / Tomcat 11.0.27
> 结论：控制 WebSocket P0 可连接、可收发命令、事件只推送一次、30 秒 grace 重连可补发、Docker 全栈 smoke 通过。G2 设计冻结未关闭，G3—G8 均未标记，完成度保持 10%。

## 1. 范围与授权

本次切片只包含 M3B Task 12：

- Raw WebSocket `/ws/v1/sessions/{sessionId}?ticket=...`（不使用 STOMP）。
- 服务端信封 `{v,type,event_id,seq,at_ms,session_id,payload}`；客户端信封 `{v,type,command_id,payload}`。
- 连接时消费一次性 ticket、发送 `session.snapshot`、注册事件 sink；命令经 `SessionActor.handle`，只回 `server.ack` / `error`，不重发 `CommandResult.events()`。
- `session.resume` 通过 sink 推送 `session.resumed` + 缺失事件尾部；`session.time_tick` / `ping` 不回放。
- 15 秒 `ping` / 30 秒无 `pong` 关闭 4004；64 KB 限制 / 慢消费者关闭 4005；ticket 失败 4001；失败会话 4002；状态不允许 4003。
- 新连接替换旧连接，旧连接正常关闭且不触发 grace。
- 契约测试、WebSocket 集成测试、Docker smoke、进度证据。

不做：感知通道（音频 / OCR）、前端课堂、M3C 录制 / 回放、E2E、NFR。按 `docs/superpowers/plans/2026-11-04-m3c-recording-backend.md` 与前端计划后续实现。

文件归属：`server/src/main/java/com/shijiangtai/m3classroom/infrastructure/ws/**` 与 `server/src/test/java/com/shijiangtai/m3classroom/ws/**` 属于 M3 Owner 的 M3B 后端范围，由 M3B 实现执行；`docs/team/02-进度看板.md` 只改 M3B 行并新增一条 §5 记录；`server/README.md` 按 Task 12 要求补充 smoke 命令。未改动其他模块代码。

> `docs/team/01-模块归属与修改规则.md` 的路径表尚未登记 `infrastructure/ws/**`；本任务不改该表，路径归属更新待 owner 审批后再补。

## 2. TDD RED（先写失败测试）

新增：

- `server/src/test/java/com/shijiangtai/m3classroom/ws/WsEnvelopeTest.java`（6 条）
- `server/src/test/java/com/shijiangtai/m3classroom/ws/ClassroomWebSocketIT.java`（10 条）

首次执行：

```bash
docker compose --profile test run --rm test \
  gradle test --tests '*WsEnvelopeTest' --tests '*ClassroomWebSocketIT' \
  --no-daemon --console=plain
```

结果：`compileTestJava` 失败，9 个 `cannot find symbol` 错误（`ClientCommandEnvelope`、`ServerEventEnvelope`、`WsCommandParser` 尚不存在），与预期一致，确认测试在实现前为 RED。

## 3. 实现与 GREEN

新增生产类：

- `infrastructure/ws/ClientCommandEnvelope.java`：客户端信封，`command_id` 必填。
- `infrastructure/ws/ServerEventEnvelope.java`：服务端信封；持久化事件沿用 actor 的 `event_id/seq/at_ms`，`snapshot/ack/error/ping` 使用最后已知 seq。
- `infrastructure/ws/WsCommandParser.java`：信封校验 + 15 种文档命令映射；v≠1、缺 `command_id`、未知类型、非法 payload、超过 64 KB 均返回 `VALIDATION_ERROR`。
- `infrastructure/ws/WsMessageSender.java`：串行化并发送服务端信封。
- `infrastructure/ws/WsHandshakeInterceptor.java`：握手消费 ticket，校验 session 绑定；失败 HTTP 401/403。
- `infrastructure/ws/ClassroomWebSocketHandler.java`：连接 / 命令 / 心跳 / 关闭码 / 单连接替换 / grace 接入。
- `infrastructure/ws/WebSocketConfig.java`：注册 `/ws/v1/sessions/{sessionId}`，dev-only `allowedOrigins("*")`。

同一命令（聚焦）：

```bash
docker compose --profile test run --rm test \
  gradle test --tests '*WsEnvelopeTest' --tests '*ClassroomWebSocketIT' \
  --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL`，**16 条用例全部通过**（`WsEnvelopeTest` 6 + `ClassroomWebSocketIT` 10），0 failure / 0 error。

覆盖点：

| 用例 | 断言 |
|---|---|
| `serverEnvelopeUsesSnakeCaseAndRequiredFields` | 顶层键严格为 `v,type,event_id,seq,at_ms,session_id,payload`，无 camelCase |
| `clientEnvelopeParsesCommandIdAndPayload` | `command_id` / payload 正确解析并映射为 `SessionCommand.CallOn` |
| `mapsRecordingReadyAndResumePayloads` | `stream_type`、`recording_state`、`last_seq` 映射 |
| `rejectsMissingCommandIdAndUnsupportedVersion` | 缺 `command_id`、v=2、空类型、未知类型、非法 JSON → `VALIDATION_ERROR` |
| `rejectsMessagesLargerThan64Kilobytes` | >64 KB 帧被拒绝 |
| `mapsEveryDocumentedNoPayloadCommand` | 02 §6.3 的无 payload 命令全部可映射 |
| `ticketConnectSnapshotAndStart` | REST 取 ticket → 101 → snapshot（`last_seq=0`）→ `recording.ready_ack` → `session.started`，事件恰好一次 |
| `duplicateCommandIdAcksOnce` | 重复 `command_id` 只回 ack，`session.started` 仍为 1 条 |
| `invalidCommandDoesNotChangeState` | `teacher.ask_class` 在 `preparing` → `error SESSION_STATE_INVALID`，DB 仍 `preparing`，无课堂事件 |
| `reconnectWithinGraceReplaysMissingEvents` | 断线后重连 → `session.resumed` 后补发 >`last_seq` 的事件；`session.time_tick` 不在回放中 |
| `reconnectOutsideGraceCloses4002` | 失败会话新连接关闭 4002，actor 不残留 |
| `secondConnectionReplacesTheFirstWithNormalClose` | 旧连接 1000 关闭，新连接可继续发命令 |
| `invalidTicketCloses4001` | 非法 ticket 握手完成后关闭 4001 |
| `timeSyncAckCarriesServerTime` | `time.sync` ack 含 `at_ms` / `server_time_ms` |
| `oversizedFrameCloses4005` | >64 KB ASCII 帧关闭 4005（容器不先报 1009） |
| `terminalSessionConnectClosesNormallyAndEvictsActor` | `ended` 会话连接受 snapshot 后 1000 关闭，registry 不残留 actor |

## 4. 全量测试

```bash
docker compose --profile test run --rm test \
  gradle clean test --no-daemon --console=plain
```

结果（Task 12 初始实现）：`BUILD SUCCESSFUL in 1m 9s`，**50 个测试类 / 385 条用例，0 failure / 0 error / 0 skipped**。Task 11 基线为 365 条，本任务新增 20 条（6 条信封契约 + 10 条 WS IT + 4 条 `SessionReconnectTest` 用例：`preparing` 断线 grace、重连/start 取消 grace、终态 eviction 保护）。

结果（最终审查修复后复跑）：`docker compose --profile test run --rm test gradle clean test --no-daemon --console=plain` → `BUILD SUCCESSFUL`，**52 个测试类 / 401 条用例，0 failure / 0 error / 0 skipped**。相对 385 条基线新增 16 条最终审查回归用例：`SessionActorTest` 5、`SessionApplicationServiceTest` 2、`SessionReconnectTest` 2、`VirtualStudentDynamicsTest` 1、`SessionRepositoryIT` 1、`ClassroomWebSocketIT` 2、新增 `SessionStartupReconcilerTest` 2、新增 `ClassroomWebSocketHandlerTest` 1。

## 5. Docker Compose smoke

```bash
docker compose up -d --build
curl -fsS http://localhost:8080/actuator/health
# {"groups":["liveness","readiness"],"status":"UP"}
```

Smoke 流程（docker profile：Flyway + PostgreSQL + Redis ticket store + MinIO）：

| 步骤 | 请求 | 结果 |
|---|---|---|
| 1. 创建 free 任务 | `POST /api/v1/courses/...0100/tasks`（教师 ...0010） | `status=draft`，`task_id=01a1a70a-19bf-7851-9df9-539839476eed` |
| 2. 上传参考材料 | `POST /api/v1/tasks/{id}/material`（`material.txt`，38 字） | `parse_status=parsed`，`char_count=38` |
| 3. 发布 | `POST /api/v1/tasks/{id}/publish` | `status=published`，`config_version=1`，`assigned_count=2` |
| 4. 创建会话 | `POST /api/v1/tasks/{id}/sessions`（学生 ...0020） | `session_id=01a1a730-b1b2-74d0-8163-08ec1aacf4d7`，`status=preparing` |
| 5. 申请 ticket ×3 | `POST /api/v1/sessions/{id}/ws-ticket` | 三个一次性 ticket（bad-ticket / oversized / good / terminal 场景使用） |
| 6. WebSocket 场景 | `ws://localhost:8080/ws/v1/sessions/{id}?ticket=...` | 4001 / 4005 / 正常流程 / 终态，见 transcript |

WebSocket transcript（Node `ws` 客户端，四段场景顺序执行）：

```text
[bad-ticket] CLOSE 4001 认证或会话状态不允许连接
[oversized] << session.snapshot {"state":"preparing",...,"last_seq":0,"elapsed_ms":0}
[oversized] CLOSE 4005 消息超过 64 KB
[good] << session.snapshot {"state":"preparing",...,"last_seq":0,"elapsed_ms":0}
[good] << recording.ready_ack {"required_streams":["camera","screen","audio"],"ok":true}
[good] << server.ack {"command_id":"smoke-ready","ok":true}
[good] << session.started {"started_at_ms":0}
[good] << server.ack {"command_id":"smoke-start","ok":true}
[good] << server.ack {"command_id":"smoke-time","ok":true,"at_ms":40,"server_time_ms":1793806350965}
[good] << session.time_tick {"elapsed_ms":1005,"remaining_ms":1198995,"discipline_level":0,"states":[...]}
[good] << classroom.state_changed {"from":"lecturing","to":"ended","qa_phase":null,"trigger":"end_session"}
[good] << session.ended {"reason":"manual","duration_ms":1247}
[good] << server.ack {"command_id":"smoke-end","ok":true}
[good] CLOSE 1000 smoke done
[terminal] << session.snapshot {"state":"ended",...,"last_seq":0,"elapsed_ms":1323}
[terminal] CLOSE 1000 会话已结束
RESULT {"bad-ticket":4001,"oversized":4005,"good":1000,"terminal":1000}
```

说明：

- 非法 ticket 不再在 HTTP 升级阶段返回 401；升级完成后由 handler 关闭 4001。
- 70,000 字节 ASCII 帧穿透 256 KB 容器缓冲，由 handler 按 UTF-8 字节数判定 >64 KB 并关闭 4005。
- `time.sync` 的 `server.ack` 额外带 `at_ms=40`（session 相对毫秒）和 `server_time_ms=1793806350965`（epoch 毫秒）。
- 已结束会话再次连接：收到 `session.snapshot`（`state=ended`）后正常关闭 1000；registry 中不再保留该 actor。

数据库校验：

```sql
select status, started_at is not null, ended_at is not null
from m3_classroom.sessions where id = '<session>';
-- ended | t | t

select seq, type from m3_classroom.events where session_id = '<session>' order by seq;
-- 1 recording.ready_ack
-- 2 session.started
-- 3 session.time_tick
-- 4 classroom.state_changed
-- 5 session.ended
```

结论：命令事件只出现一次；`session.started` 后 ack 的 seq 与事件 seq 一致；结束事件落库并按序推送；4001/4005/time.sync/终态路径与契约测试一致；客户端正常关闭 1000。

### 5.1 `preparing` 断线重连 + 31 秒 grace 场景

独立会话 `01a1a748-aa90-7f34-894a-dfa3ec138dd2`（redis ticket store，真实 30 秒调度器）：

```text
<< session.snapshot {"state":"preparing",...,"last_seq":0,"elapsed_ms":0}
CLOSE 1000 arming grace
# 重新申请 ticket 并连接（onWsConnected 取消旧 grace）
<< session.snapshot {"state":"preparing",...}
<< recording.ready_ack ... / server.ack grace-ready
<< session.started ... / server.ack grace-start
# 等待 31 秒，期间收到 15 秒 ping 并回复 pong
<< ping {"server_time_ms":1793807937203}
<< server.ack {"command_id":"pong-...","ok":true}
# 31 秒后旧 grace deadline 已过，会话仍可执行命令
<< classroom.question_asked {"intent":"answer","source":"manual"}
<< classroom.state_changed {"from":"lecturing","to":"qa","qa_phase":"waiting_hands","trigger":"manual"}
<< server.ack {"command_id":"grace-ask","ok":true}
<< session.ended {"reason":"manual","duration_ms":31423}
<< server.ack {"command_id":"grace-end","ok":true}
CLOSE 1000 grace smoke done
RESULT {"firstClose":1000,"secondClose":1000,"askAckOk":true}
```

数据库：该会话最终 `ended|t|t`，事件中无 `session.failed`；证明重连/start 后旧 `PREPARING` grace 不再误杀 `in_progress` 会话。该场景同时验证了 15 秒 `ping` 与客户端 `pong`。

### 5.2 最终审查修复后的 Compose WS 复跑

`docker compose up -d --build` 后 `actuator/health` 为 UP；`SessionStartupReconciler` 启动日志为 `Session startup reconciliation marked 0 task session(s) failed`。使用 Node 22 内置 WebSocket 客户端复跑 Task 12 的四段场景（会话 `01a1a91e-dadf-7a89-80dc-88a033009fce`，free 任务，2 名虚拟学生）：

```text
[bad-ticket] CLOSE 4001 认证或会话状态不允许连接
[oversized] << session.snapshot {"state":"preparing",...}
[oversized] CLOSE 4005 消息超过 64 KB
[good] << session.snapshot {"state":"preparing",...,"last_seq":0,"elapsed_ms":0}
[good] << recording.ready_ack {"required_streams":["camera","screen","audio"],"ok":true}
[good] << session.started {"started_at_ms":0} / server.ack smoke-start ok
[good] << server.ack {"command_id":"smoke-time","ok":true,"at_ms":34,"server_time_ms":1793838763653}
[good] << classroom.question_asked {"source":"manual","intent":"answer"}
[good] << student.hand_raised {"student_id":"2222...","hand_raise_id":"13bdea63-..."}
[good] << student.state_changed {"student_id":"2222...","from":"focused","to":"hand_raised","reason":"hand_raised"}
[good] << student.state_changed {"student_id":"2222...","from":"hand_raised","to":"called_on","reason":"called_on"}
[good] << student.state_changed {"student_id":"2222...","from":"called_on","to":"answering","reason":"answer_started"}
[good] << student.state_changed {"student_id":"2222...","from":"answering","to":"focused","reason":"answer_finished"}
[good] << session.ended {"reason":"manual","duration_ms":4414}
[good] CLOSE 1000 smoke done
[terminal] << session.snapshot {"state":"ended",...,"last_seq":22,"elapsed_ms":4414}
[terminal] CLOSE 1000 会话已结束
RESULT {"failures":0}
```

数据库校验（同一会话）：

```sql
select status, started_at is not null, ended_at is not null
from m3_classroom.sessions where id = '01a1a91e-dadf-7a89-80dc-88a033009fce';
-- ended | t | t
```

事件共 22 条，seq 7—10 为两名学生的 `student.hand_raised` + `student.state_changed`，seq 11—12 为其余举手放下，seq 14—19 为 `teacher.called_on` 后的 `called_on → answering → focused`；seq 22 为 `session.ended`。说明行为状态事件按序落库、`at_ms` 正常、终态快照 `last_seq=22` 与持久化最大 seq 一致（证明 registry 重建时从 `eventStore.maxSeq` 续 seq）。

## 6. smoke 中发现并修复的问题

### 6.1 Alpine JRE 缺少 `jdk.random`，首个 WS 连接 1011

- 现象：首次 Compose smoke 连接 WebSocket 立即 `CLOSE 1011`，未收到 `session.snapshot`。
- 日志：`java.lang.IllegalArgumentException: No implementation of the random number generator algorithm "L32X64MixRandom" is available`，栈顶为 `SessionRuntimeRegistry.buildActor` → `RandomGenerator.getDefault()`。
- 根因：`eclipse-temurin:21-jre-alpine` 运行时不包含 `jdk.random` 模块，而 `RandomGenerator.getDefault()` 依赖该模块的算法实现；Gradle 测试镜像使用完整 JDK，因此此前未暴露。
- 修复：`SessionRuntimeRegistry` 与 `VirtualStudentDynamics` 的默认随机源改为 `java.util.Random`（`java.base` 内实现，仍满足 `RandomGenerator` 接口）；测试中的固定种子构造不受影响。
- 回归：全量 382 tests 通过；重建 app 后扩展 smoke 流程通过（见 §5）。该修改只涉及 M3B 运行时随机源，不改变协议、表或错误码契约。

### 6.2 审查复审修复（4 项 Important）

1. **`time.sync` 返回时间信息**：`server.ack` payload 增加 `at_ms`（session 相对毫秒）和 `server_time_ms`（epoch 毫秒）；其他命令 ack 形状不变。覆盖：`timeSyncAckCarriesServerTime` + §5 smoke。
2. **ticket 失败关闭 4001**：握手拦截器不再返回 HTTP 401/403，而是让升级完成并把 `invalid_ticket` / `forbidden` 写入 `ws.auth_error`；handler 统一关闭 4001（`failed`→4002、`state`→4003）。覆盖：`invalidTicketCloses4001` + §5 smoke。
3. **超大帧关闭 4005**：注册 `ServletServerContainerFactoryBean`，文本/二进制缓冲提升到 256 KB（MOCK 测试上下文无 `ServerContainer` 时安全跳过），handler 仍按 UTF-8 字节数执行 64 KB 协议限制并关闭 4005；容器层面的 `SessionLimitExceededException` 也映射到 4005。覆盖：`oversizedFrameCloses4005` + §5 smoke（70,000 字节帧）。
4. **终态 actor 不缓存 + `preparing` 断线 grace**：连接终态会话时发送 snapshot 后关闭（`ended/reviewing` 1000，`failed` 4002，`cancelled/submitted` 4003），并从 `SessionRuntimeRegistry` 移除 actor；`SessionActor.onWsDisconnected` 现在同时接受 `PREPARING` 和 `IN_PROGRESS`，30 秒超时以 `grace_timeout` 失败。覆盖：`terminalSessionConnectClosesNormallyAndEvictsActor`、`preparingDisconnectFailsAfterGraceWithoutStart`、`reconnectOutsideGraceCloses4002` 的 registry 断言 + §5 terminal 场景。

### 6.3 复审修复（第 3 轮，2 项）

1. **`PREPARING` grace 在重连 / start 时取消**：新增 `SessionActor.onWsConnected()`（handler 连接建立时调用）并在 `handleStart` 中取消 `graceTask`；否则 30 秒后旧 timer 会失败一个已连接且 `in_progress` 的会话。覆盖：
   - `reconnectAndStartCancelsPreparingGrace`：`preparing` 断线 → `onWsConnected` → 推进 31 s 仍 `PREPARING` → `session.start` → 再推进 30 s 仍 `IN_PROGRESS`，无 `session.failed`。
   - `startCancelsPreparingGraceWithoutReconnect`：即使没有 `onWsConnected`，`session.start` 也会取消旧 grace。
   - Docker smoke 的 grace 场景：断线 → 重连 → start → 等待 31 s → `teacher.ask_class` ack `ok=true` → `session.ended`，证明真实调度器下未被旧 timer 失败。
2. **终态 eviction 不绕过 registry 的保存失败保护**：新增 `SessionRuntimeRegistry.removeIfPersistedTerminal(sessionId)`，只有 actor 与持久化行都为终态时才移除；若终态保存失败、DB 行仍为 `in_progress`，保留 actor 作为防重建 shield。handler 的终态连接分支改用该方法。覆盖：`terminalActorIsEvictedOnlyWhenPersistedRowIsTerminal`（先让保存失败 → `removeIfPersistedTerminal=false` 且 actor 保留；恢复保存并持久化终态后 → `true` 且 actor 移除）。

### 6.4 最终审查修复（第 4 轮，4 项必需 + 5 项次要）

1. **虚拟学生行为状态**：`VirtualStudentDynamics.applyHandRaised / applyCalledOn / applyAnswer / applyAnswerFinished / applyHandLowered` 现在统一通过 `applyStateChanged` 应用状态并返回 `student.state_changed {student_id, from, to, reason}`；`SessionActor` 在 `student.hand_raised`、`teacher.call_on`、`student.answer`、`student.hand_lowered` 后按协议顺序追加事件。快照与 `session.time_tick.states` 读取 `VirtualStudentRuntime.behavior()`，因此 `hand_raised / called_on / answering / focused` 对前端可见。覆盖：`SessionActorTest.handRaiseIsVisibleInSnapshotAndTickStates`、`callOnTransitionsToCalledOnAndAnsweringThenFocused`、`studentQuestionCallOnKeepsCalledOnState`；`VirtualStudentDynamicsTest.interactionTransitionsEmitStudentStateChangedEvents`。
2. **空举手窗口**：`teacher.ask_class` / `teacher.ask_student_question` 每次都会调度 30 秒超时；若 1—3 秒内无人举手（如所有学生都在 whispering / 对话中），超时仍触发 `classroom.hand_window_timeout {hand_raise_ids: []}`、`classroom.state_changed (trigger=hand_window_timeout)` 并回到 `lecturing`，不再永久停留在 `qa.waiting_hands`。有举手时超时改为从第一个举手起 30 秒。覆盖：`SessionActorTest.emptyHandWindowTimesOutWhenOnlyStudentIsIneligible`。
3. **Java 重启对账**：新增 `SessionStartupReconciler`（`ApplicationRunner`），启动时把仍为 `PREPARING` / `IN_PROGRESS` 的 `TASK` 会话置为 `FAILED`（`FailureReason.INTERNAL`），并在 `maxSeq + 1` 追加持久化 `session.failed {reason: internal}`；`SessionRuntimeRegistry.buildActor` 用 `eventStore.maxSeq` 初始化 `SessionContext.lastSeq`，重建 actor 的新事件不会撞上已落库的 `(session_id, seq)`。Docker 启动日志：`Session startup reconciliation marked 0 task session(s) failed`；终态会话重连快照 `last_seq=22`（对账/落库后重建，证明从持久化最大 seq 继续）。覆盖：`SessionStartupReconcilerTest` 2 条、`SessionReconnectTest.rebuiltActorSeedsLastSeqFromPersistedEventStore`、`SessionRepositoryIT.findsSessionsByStatusForRestartReconciliation`。
4. **归档 / 已结束课程开始校验**：新增 `SessionStartGuard`，校验 `PREPARING` + 课程 `active` + `endAt > now`，由 `SessionApplicationServiceImpl.start` 与 WS handler 在 `session.start` 前调用；重复 `command_id` 的 start 仍走 actor 命令历史返回原 ack。覆盖：`SessionApplicationServiceTest.archivedCourseRejectsStartAfterSessionCreation` / `endedCourseRejectsStartAfterSessionCreation`、`ClassroomWebSocketIT.archivedCourseStartIsRejected` / `endedCourseStartIsRejected`，并保留 `duplicateCommandIdAcksOnce`。
5. **次要修复**：连接 `lastSeq` 改为 `accumulateAndGet(..., Math::max)`（`session.resume` 先推高 seq 再补发低 seq 不倒退）；`at_ms` / snapshot `elapsed_ms` 在 `endedAt` 后钳制；`student.question` payload 增加 `question_id`；WS 命令持久化失败改为 `error INTERNAL_ERROR` + close 4002，不再 ack true；actor / handler 的内部异常日志带 `session_id`、命令类型与 `command_id`，对外只返回通用 `命令处理失败`。覆盖：`ClassroomWebSocketIT.reconnectWithinGraceReplaysMissingEvents`（ack seq 断言）、`SessionActorTest.snapshotElapsedIsClampedToEndedAt`、`ClassroomWebSocketHandlerTest.startPersistenceFailureSendsErrorAndCloses4002InsteadOfAck`、`SessionReconnectTest.internalCommandFailureReturnsGenericMessage`。

**RED / GREEN 记录**：

- 首次全量执行（修复 `SessionStartupReconciler` 的 `final` + `@Transactional` CGLIB 代理问题前）：`401 tests completed, 71 failed`，全部为上下文加载失败（`CglibAopProxy` / `Enhancer`），确认测试先于实现暴露启动缺陷。
- 修复后全量复跑：仅剩 2 条失败——`SessionRepositoryIT.findsSessionsByStatusForRestartReconciliation`（两个 active 会话触发 `uq_sessions_task_active`）与 `ClassroomWebSocketIT.duplicateCommandIdAcksOnce`（start guard 在 actor 去重前拦截了重复命令）。
- 两项修复后聚焦复跑 `*SessionRepositoryIT` + `*ClassroomWebSocketIT`：`BUILD SUCCESSFUL`；最终全量 clean run：**52 classes / 401 tests，0 failure / 0 error / 0 skipped**。

## 7. 覆盖与未覆盖

已覆盖：

- 信封契约（snake_case、必填字段、v=1、`command_id` 必填、64 KB）。
- ticket 握手、snapshot、`recording.ready`、`session.start`、事件只推送一次。
- 重复 `command_id` 幂等；非法命令不改变状态。
- 30 秒 grace 内重连 `session.resume` 补发；`time_tick` 不回放。
- 失败会话关闭 4002；非法 ticket 关闭 4001；超大帧关闭 4005。
- `time.sync` 返回 `at_ms` / `server_time_ms`。
- 新连接替换旧连接（旧连接 1000）；终态会话 snapshot 后正常关闭且不残留 actor。
- `preparing` 断线 30 秒 grace → `grace_timeout`；重连 / `session.start` 取消旧 grace，31 秒后仍 `in_progress`。
- 终态 actor 只在持久化行也是终态时移除；保存失败时保留 shield actor。
- Docker 全栈：Flyway、PostgreSQL 事件落库、Redis 一次性 ticket、真实 WS transcript（含 15 秒 ping / pong 与 31 秒 grace 场景）。

未覆盖 / 后续：

- 15 秒 ping / 30 秒 4004 与慢消费者路径：代码已实现，但 IT 不等待真实 15/30 秒计时；建议后续用可注入时钟或调度器补时控用例。
- 终态会话由 `getOrCreate` 重建 actor 时，`session.snapshot.last_seq` 为 0（内存 context 不重放 DB 事件）；终态连接不用于事件回放，前端如需要应以 REST `SessionView.last_seq` 为准。是否在终态快照中补 `max_seq` 留待前端联调确认。
- 感知通道、前端课堂 / 面试 / 回放、M3C 录制、E2E、NFR 不在本任务范围。

## 8. 本次变更文件

生产：

- `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/ws/ClientCommandEnvelope.java`
- `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/ws/ServerEventEnvelope.java`
- `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/ws/WsCommandParser.java`
- `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/ws/WsMessageSender.java`
- `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/ws/WsHandshakeInterceptor.java`
- `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/ws/ClassroomWebSocketHandler.java`
- `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/ws/WebSocketConfig.java`
- `server/src/main/java/com/shijiangtai/m3classroom/application/SessionActor.java`（`preparing` 断线 grace、`onWsConnected` / start 取消 grace）
- `server/src/main/java/com/shijiangtai/m3classroom/application/SessionRuntimeRegistry.java`（随机源修复、`removeIfPersistedTerminal` 保护）
- `server/src/main/java/com/shijiangtai/m3classroom/domain/classroom/VirtualStudentDynamics.java`（随机源修复）

测试：

- `server/src/test/java/com/shijiangtai/m3classroom/ws/WsEnvelopeTest.java`
- `server/src/test/java/com/shijiangtai/m3classroom/ws/ClassroomWebSocketIT.java`
- `server/src/test/java/com/shijiangtai/m3classroom/application/SessionReconnectTest.java`（新增 `preparing` 断线 grace、重连/start 取消 grace、终态 eviction 保护 4 条用例）

文档：

- `docs/team/evidence/2026-11-04-m3b-classroom-ws.md`
- `docs/team/02-进度看板.md`（仅 M3B 行 blocker + 一条 §5 记录）
- `server/README.md`（WS smoke 命令）

最终审查修复（第 4 轮）新增 / 修改：

- 新增生产类：`server/src/main/java/com/shijiangtai/m3classroom/application/SessionStartGuard.java`、`SessionStartupReconciler.java`。
- 修改生产类：`SessionActor.java`（行为状态事件、空举手窗口、`endedAt` 钳制、`question_id`、内部异常日志）、`SessionApplicationServiceImpl.java`（start 前 guard）、`SessionRuntimeRegistry.java`（`eventStore.maxSeq` 续接 `lastSeq`）、`VirtualStudentDynamics.java`（行为状态转移 + `student.state_changed`）、`SessionContext.java`（持久化 `lastSeq` 构造器）、`SessionRepository.java` / `SessionJpaRepository.java` / `SessionRepositoryAdapter.java`（`findByStatusIn`）、`ClassroomWebSocketHandler.java`（start guard、持久化失败 error + 4002、`lastSeq` 单调、`endedAt` 钳制、异常日志）。
- 新增测试：`server/src/test/java/com/shijiangtai/m3classroom/application/SessionStartupReconcilerTest.java`、`server/src/test/java/com/shijiangtai/m3classroom/infrastructure/ws/ClassroomWebSocketHandlerTest.java`。
- 修改测试：`SessionActorTest.java`、`SessionApplicationServiceTest.java`、`SessionReconnectTest.java`、`VirtualStudentDynamicsTest.java`、`SessionRepositoryIT.java`、`InMemorySessionRepository.java`、`ClassroomWebSocketIT.java`。
