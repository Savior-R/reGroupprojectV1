# 2026-11-05 M3B 异步持久化（M3-T16）与 T19 验收证据

> 分支：`feat/m3a-task-config`（提交主题 `[M3B] ...`）
> 计划：`docs/superpowers/plans/2026-11-05-m3b-persistence-t19.md`（Task 1—6）
> 设计：`docs/superpowers/specs/2026-11-05-m3b-persistence-t19-design.md`（2026-11-05 用户批准）
> 上位文档：`docs/design/05A-M3-课堂状态机与WebSocket.md` §9 / §16.6；
> `docs/implementation/04-M3-实现设计.md` §1 / §2 / §3.6 / §5 / §7；
> `docs/design/10-测试与验收.md` §6.5（M3-T16 / M3-T19）
> 执行人：M3 Owner（当前使用者）
> 环境：Docker Compose / PostgreSQL 18 / Redis / MinIO / Spring Boot 4.1.2 / Java 21
> 结论：M3B 异步持久化后端 P0 完成：每 session 有序队列、1 秒 / 100 条 / 64 KB
> 触发、终态同步强制 flush、flush 失败转 failed、批次 `REQUIRES_NEW` 事务、
> 内存教师文本上下文；M3-T19 的录制丢失 / grace 超时 / 正常结束验收与多轮对话
> 终止路径缺口已补齐。G2 未关闭，模块完成度保持 10%，M3B 测试完成度保持 60%
> （UT / IT / 契约已通过，E2E 与性能 / 安全未开始）。

## 1. 范围与授权

本次切片只包含 M3B 异步持久化与验收补测：

- 新增应用端口 `ContextPersistenceWriter`（`appendEvent` / `saveText` / `flush` /
  `close`）与同步直写实现 `DirectContextPersistenceWriter`；旧 actor / registry
  构造器继续使用直写实现，既有测试 fixture 无需改动。
- 新增生产实现 `JpaContextPersistenceWriter`：每 session 一个有序队列，共享线程池
  消费；1 秒 / 100 条 / 64 KB 先到先 flush；队列上限 10 000 条；普通失败重试并
  回队首；终态同步 flush。
- 终态路径都会在 actor 线程同步 drain 队列（覆盖 `session.ended`、
  `interview.ended`、`session.failed`）；**只有 `session.ended` 是
  flush-before-push**（`appendCritical`）。
  `interview.ended` 走普通 `append`，先推送，随后由 `session.ended` 的终态 flush
  一并落库；`session.failed` 先推送，再由 `fail()` 的终态 forceFlush 落库
  （`persistenceFailed` 路径为 best-effort）。`session.ended` 的 flush 失败 →
  `session.status=failed`、追加 `session.failed`、命令返回 `SESSION_FAILED`、
  禁止提交。
- 新增 `SessionBatchStore`：一批 events + texts 在同一事务写入（先 texts 后
  events），使用 `REQUIRES_NEW` 独立提交。
- `SessionContext` 增加内存文本流；活跃 actor 的教师文本上下文改读内存（最近
  20 条）；`SessionView.last_seq` 对活跃 actor 返回内存 `lastSeq()`。
- M3-T19 验收：`session.resume {recording_state=lost}`、页面刷新 / grace 超时、
  正常 `teacher.end_session` 不 failed。
- REST `end` 终态 flush 失败验收（最终审查修复）：`SessionEndPersistenceFailureIT`
  验证 `session.ended` 关键 flush 失败时命令抛 `SESSION_FAILED`、registry 派生的
  `failed` 行在 `DomainException` 传播时仍提交、actor 被移出 registry、writer 记录到
  终态 flush 尝试。
- 多轮对话终止路径缺口补测：null / 重复 `dialogue_id`、`ask_student_question`
  与 tick 超时终止对话。
- 05A / implementation 04 文档、证据文件、进度看板。

显式不做（deferred）：

- 帧（`m3.frames`）进入异步队列。帧仍由 `PerceptionApplicationService` 在感知
  线程同步写入 / 更新，原因见 §6。
- 提交（submission）前 flush 顺序与 pending frame OCR barrier；本轮只提供 actor
  `forceFlush()` 接缝，提交流程属 M3C。
- Java 重启恢复、跨实例分布式队列、消息中间件、指标 / 告警、M4 partial、前端
  UI、E2E / NFR。

文件归属：生产 / 测试路径属于 M3 Owner 的 M3B 后端范围。生产：
`m3classroom/application/**`、`m3classroom/domain/session/**`、
`m3classroom/infrastructure/persistence/jpa/**`；测试：`m3classroom/application/**`、
`m3classroom/infrastructure/persistence/jpa/**`、`m3classroom/persistence/**`
（`ContextPersistenceWriterIT`）、`m3classroom/ws/**`（`ClassroomWebSocketIT`）、
`m3classroom/support/**`（`BufferingContextPersistenceWriter`）。完整清单见 §7。
未修改 build 文件、YAML、`docs/design/02` / `10` / `12`、前端或 M3C 代码。
`docs/team/02-进度看板.md` 只改 M3B 行并新增一条 §5 记录。API / DTO / 错误码 /
WS 事件 / 表结构无契约变更。

同一工作区包含暂停中的 M3C 未跟踪测试（`server/src/test/java/com/shijiangtai/
m3classroom/domain/recording/` 下 3 个类 / 13 条用例），它们会进入全量测试计数，
但不进入本次提交。

## 2. 实现摘要（Task 1—3）

| 切片 | 实现 |
|---|---|
| 写入端口（Task 1） | `application/port/ContextPersistenceWriter`；`DirectContextPersistenceWriter` 直写 `SessionEventStore` / `SessionTextStore`；`PersistenceFlushException` 携带 `SessionId` |
| Actor 写路径（Task 1） | `SessionActor.append` / 文本保存 / `forceFlush` 全部走 writer；`SessionContext` 增加插入有序 `texts` 列表与 `texts(speakerType)`；`recentTeacherTexts` 读内存最近 20 条 |
| 终态 flush（Task 2） | `appendCritical`：入队 → `persistence.flush` → 成功后才 `push`；`endClassroomSession` / `endInterview` 的 `session.ended` 用 `appendCritical`，失败走 `persistenceFailed`；命令返回 `SESSION_FAILED`；`interview.ended` 与 `session.failed` 走普通 `append`，由同一轮终态 flush drain；`fail` / `cancel` 清理后 best-effort flush |
| 异步 writer（Task 3） | `JpaContextPersistenceWriter`：每 session `ArrayDeque` + flush 锁 + UTF-8 字节估算；1 秒定时、100 条、64 KB 触发；`flushAsync` 每 session 最多一个在途任务；10 000 条有界；失败重试 3 次（50 / 100 / 200 ms）后抛 `PersistenceFlushException`，原批按序回队首 |
| 批次事务（Task 3） | `JpaSessionBatchStore.write` 单事务：先 `saveAll` texts 后批量 events；`SessionEventStore.appendAll` 单次 JDBC batch；`SessionTextStore.saveAll` 用 `repository.saveAll` |
| 事务隔离（Task 3 fix） | `JpaSessionBatchStore.write` 使用 `@Transactional(propagation = REQUIRES_NEW)`；终态 flush 在终态行保存前完成，批次失败只回滚自身，不污染 REST 命令事务 |
| Registry 接线（Task 3） | 生产构造器注入 `ContextPersistenceWriter`；`buildActor` 用 `textStore.bySession(id, "teacher")` 回填教师文本；`remove` / `removeIfPersistedTerminal` / 成功终态分支 `closeWriter` best-effort 关闭队列 |
| Live seq（Task 3） | `SessionApplicationServiceImpl.toView`：活跃 actor 用 `lastSeq()`，否则用 `eventStore.maxSeq(id)` |
| 测试接缝（Task 3） | `JpaContextPersistenceWriter(store, executor)` 包级构造器：无 1 秒调度、零退避；旧构造器保留并委托直写实现 |

## 3. TDD RED / GREEN 证据

所有命令在仓库根目录执行，测试运行在 `docker compose --profile test` 容器内。
Task 4 / Task 5 属于验收 / 覆盖补测，行为已存在，因此没有生产 RED；它们补齐的是
既有行为的验收证据。

| Task | 提交 | 测试类（最终用例数） | RED | GREEN |
|---|---|---|---|---|
| 1 writer 端口 / 内存文本流 | `bda1e9a` | `SessionActorPersistenceTest`（3）、`BufferingContextPersistenceWriter` | 结构性 RED：新测试引用 writer-aware 构造器 / `SessionContext.addText`，旧代码不存在；首次执行捕获的是 `ContentRequest.recentTexts()` 访问器编译错误 | 定向 60 tests（含 `SessionActorTest` 30、`SessionActorDialogueTest` 17、`SessionActorPerceptionTest` 7、`ArchitectureTest` 3）0 failure / 0 error |
| 2 终态强制 flush / 失败转 failed | `ce780a7` | `SessionActorPersistenceTest`（+3，共 6） | 3 条新用例在旧 actor 上失败：`session.ended` 推送前未 flush；flush 失败未转 failed；失败后定时器 / registry 未清理 | `SessionActorPersistenceTest` 6/6、`SessionActorTest` 30/30、`SessionReconnectTest` 19/19（55 tests） |
| 3 异步 writer / batch store / 接线 | `fb46e8a` | `JpaContextPersistenceWriterTest`（5）、`ContextPersistenceWriterIT`（2） | `compileTestJava` 失败：缺少 `SessionBatchStore` / `JpaContextPersistenceWriter` 符号（5 errors） | 定向 89 tests + `ArchitectureTest` 3 = 92 tests，0/0/0 |
| 3 修复：`REQUIRES_NEW` / 合并 flush | `0591f6c` | `JpaSessionBatchStoreTransactionIT`（1） | 临时把 batch store 改回默认 `REQUIRED`，回归用例在外层事务保存 marker 时因连接事务已中止而失败（`JpaSystemException → GenericJDBCException → PSQLException`）；恢复 `REQUIRES_NEW` 后通过 | 定向 52 tests + `ArchitectureTest` 3 = 55；更广回归 79 tests，0/0/0 |
| 4 M3-T19 验收 | `e88cc61` | `SessionReconnectTest`（21）、`ClassroomWebSocketIT`（16） | 无生产 RED：录制丢失 / grace / 正常结束行为已存在，本次补验收断言 | 21/21、16/16；`ArchitectureTest` 3/3 |
| 5 多轮对话终止缺口 | `fa4895b` / `ffa3c4b` | `SessionActorDialogueTest`（21） | 无生产 RED：既有实现已满足终止 / 校验行为，属覆盖缺口 | 21/21 |
| 最终审查修复：REST 结束失败验收与准确性 | `3561653` | `SessionEndPersistenceFailureIT`（2）、`JpaContextPersistenceWriterTest`（+2，共 7） | 新 IT 在临时移除 `@Transactional(noRollbackFor = DomainException.class)` 后失败：`expected: FAILED but was: IN_PROGRESS`；临时移除 registry 终态行 save 同样失败（actor 已移除但 DB 行仍 `in_progress`）。UTF-8 64 KB 用例在按字符计数时保持缓冲，不触发写入。 | 定向 `SessionEndPersistenceFailureIT` 2/2 + `JpaContextPersistenceWriterTest` 7/7；全量 72 类 / 538 条，0/0/0 |

Task 5 控制器裁决（覆盖切片设计 §7.3 的原始描述）：`teacher.end_dialogue` 的
`dialogue_id` 是可选字段；活动对话中传 null 必须成功结束对话（
`nullDialogueIdEndsActiveDialogue`），只有 stale 的非空 id 才返回
`SESSION_STATE_INVALID`。文档与测试以该裁决为准。

## 4. 全量测试计数

最终全量命令：

```bash
docker compose --profile test run --rm test \
  gradle clean test --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL in 1m 17s`，`EXIT=0`。

Gradle XML（`server/build/test-results/test/TEST-*.xml`）汇总：

| 口径 | 测试类 | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|---:|
| 同一工作区全量 | **72** | **538** | **0** | **0** | **0** |
| M3B 提交范围 | 69 | 525 | 0 | 0 | 0 |
| M3C 未跟踪测试（不提交） | 3 | 13 | 0 | 0 | 0 |

M3C 未跟踪测试明细：`RecordingTest` 8、`RecordingObjectKeyBuilderTest` 2、
`RequiredStreamsTest` 3。相对上一基线（多轮对话切片：全量 67 类 / 512 条，
M3B 口径 64 类 / 499 条），本切片新增 **5 个测试类 / 26 条用例**：

- `SessionActorPersistenceTest` 6（Task 1 3 + Task 2 3）
- `JpaContextPersistenceWriterTest` 7（最终审查修复新增 UTF-8 64 KB 与 10 000 条上限各 1 条）
- `JpaSessionBatchStoreTransactionIT` 1
- `ContextPersistenceWriterIT` 2
- `SessionEndPersistenceFailureIT` 2（最终审查修复新增 REST 结束失败 + 活跃 `last_seq`）
- `SessionReconnectTest` +2（19 → 21）
- `ClassroomWebSocketIT` +2（14 → 16）
- `SessionActorDialogueTest` +4（17 → 21）

警告 / 噪声：编译期有 2 条既有 deprecation 提示
（`api/dto/TaskRequestMapper.java`、`api/TaskMaterialControllerIT.java`），JVM 有
1 条 CDS `Sharing is only supported for boot loader classes` 提示。测试 XML 中
还存在负向用例产生的预期 `WARN` / `ERROR` 日志（例如 flush 失败重试、事务回滚、
鉴权 / 校验失败），它们对应断言覆盖的失败路径，不是套件失败；汇总仍为
0 failures / 0 errors / 0 skipped。上述提示与负向日志均为非阻塞噪声，不影响结果。

## 5. M3-T16 / M3-T19 映射

### 5.1 M3-T16（异步持久化、强制 flush、失败 session failed；提交前 flush 顺序）

| 要求 | 实现 / 证据 |
|---|---|
| 每 session 有序队列 | `JpaContextPersistenceWriter.SessionQueue`；actor 串行入队；`JpaContextPersistenceWriterTest.flushWritesBufferedEventsAndTexts` |
| 1 秒触发 | `@PostConstruct` 固定延迟调度；`ContextPersistenceWriterIT.scheduledFlushPersistsWithoutManualCall`（不手动 flush，5 秒内可读） |
| 100 条触发 | `enqueue` 阈值分支；`JpaContextPersistenceWriterTest.hundredItemsTriggerAsyncFlush` |
| 64 KB 触发 | 与 100 条共用 `thresholdReached` 分支；按 UTF-8 字节估算；`JpaContextPersistenceWriterTest.chineseTextSizeIsMeasuredInUtf8BytesFor64KbTrigger`（22 000 个汉字 = 66 000 字节） |
| 终态强制 flush | `SessionActor.appendCritical`；`SessionActorPersistenceTest.endSessionFlushesBeforePushingEndedAndReturnsOk`、`endSessionFlushFailureMarksFailedAndDoesNotPushEnded`、`failFlushFailureStillCancelsTimersAndNotifiesRegistry` |
| flush 重试 / 回队首 | 3 次重试 + 原序回队；`flushFailureRetriesThenThrowsAndKeepsPending`、`asyncFailureKeepsBatchForNextFlush` |
| flush 失败 → failed / `SESSION_FAILED` | `persistenceFailed`；`SessionActorPersistenceTest.endSessionFlushFailureMarksFailedAndDoesNotPushEnded`；REST 端到端 `SessionEndPersistenceFailureIT.restEndCommitsFailedRowAndEvictsActorWhenTerminalFlushFails` |
| 批次事务 / 幂等 | `JpaSessionBatchStore` 先 texts 后 events；事件 `ON CONFLICT DO NOTHING`、文本主键 upsert；`ContextPersistenceWriterIT.manualFlushPersistsEventAndText` |
| `REQUIRES_NEW` 隔离 | `JpaSessionBatchStoreTransactionIT.failedBatchWriteDoesNotPoisonOuterTransaction` |
| 有界队列 | 10 000 条上限，溢出先同步 flush 再抛异常；`JpaContextPersistenceWriterTest.queueAtCapacityFlushesSynchronouslyAndPropagatesFailure` |
| 内存文本上下文 | `SessionContext.addText` / `texts("teacher")`；`SessionActorPersistenceTest.teacherFinalStillReachesNextContentRequestBeforeFlush` |
| 普通命令不强制 flush | `SessionActorPersistenceTest.nonTerminalCommandsDoNotForceFlush` |
| 活跃 `last_seq` | `toView` 优先 `lastSeq()`；`SessionEndPersistenceFailureIT.restViewReportsLiveLastSeqWhileAsyncWritesAreBuffered`（writer 未落库时 REST `last_seq` 仍不回退） |
| 提交前 flush 顺序 | **deferred M3C**：M3B 提供 `forceFlush()` 接缝；submission / required_streams / `submission.created` 同事务顺序尚未实现，故 T16 的“提交前 flush 顺序”一项不计入本轮完成 |

### 5.2 M3-T19（页面刷新 / recording lost / grace 超时 → failed；正常 ended 不 failed）

| 场景 | 用例 |
|---|---|
| `recording_state=lost` → failed | `SessionReconnectTest.resumeLostFailsImmediately`（既有）；`ClassroomWebSocketIT.resumeWithLostRecordingFailsSession`（新增） |
| 页面刷新 / 断开不 resume → grace 超时 failed | `SessionReconnectTest.pageRefreshWithoutResumeFailsAfterGrace`（新增）；既有 `graceTimeoutFailsSessionAndRemovesActor`、`preparingDisconnectFailsAfterGraceWithoutStart` |
| 正常 ended 不 failed | `SessionReconnectTest.normalEndedSessionIsNotFailed`（新增）；`ClassroomWebSocketIT.normalEndSessionDoesNotFail`（新增） |
| 正常结束事件顺序 | 新增 WS 用例确认 `session.ended` → `server.ack`，无 `session.failed` |

## 6. 偏差与已知缺口

1. **帧保持同步（显式偏差）**：`m3.frames` 不进入 `ContextPersistenceWriter`。
   `PerceptionApplicationService` 在感知线程同步写入 pending 帧，再异步 OCR；
   OCR 成功更新依赖“pending 帧已存在”的 read-after-write 语义。若把帧放入异步
   队列，pending 尚未落库时 OCR 结果可能丢失。帧写入本来就不在 actor 线程，
   不违反“活跃课堂不被 DB 阻塞”的目标。05A §9.2 已注明与原“events + texts +
   frames 同一批事务”措辞的差异。
2. **提交前 flush 延后到 M3C**：本轮只提供 actor `forceFlush()` 接缝；
   submission 状态、required_streams 校验、submission / session / event 同事务
   以及 pending frame OCR barrier 由 M3C 提交流程实现。因此 T16 的提交顺序项
   仍为未完成，证据不将其计入。
3. **`REQUIRES_NEW` 独立提交**：批次事务独立于外层 REST 命令事务。若外层事务
   因无关原因回滚，已 flush 的事件 / 文本仍保留。这是终态 flush 持久性的有意
   取舍：只有 `session.ended` 是 flush-before-push；`interview.ended` 与
   `session.failed` 在终态 flush 运行时可能已经推送。仅当 `session.ended` 的
   关键 flush 失败并进入 `persistenceFailed` 路径时，才会在已推送的
   `interview.ended` / `session.failed`（如适用）之后追加并推送
   `session.failed`，且不会重复推送 `session.ended`；普通异步 flush 失败与
   `interview.ended` 之后的正常终态 flush 失败不触发该追加路径。这样可避免
   批次失败把命令事务标记为 rollback-only。
4. **教师文本上下文在 actor 重建时回填**：活跃 actor 的 LLM 上下文读内存；
   `SessionRuntimeRegistry.buildActor` 在重建（重连 / 终态对账）时从
   `m3.session_texts` 读取 `speaker_type=teacher` 的文本回填。Java 进程重启后
   `in_progress` session 仍标记 failed（不做恢复），因此该回填只覆盖工作区内
   的 actor 重建。
5. **未专门覆盖的分支**：最终审查修复已补 64 KB（UTF-8 字节）与 10 000 条上限
   用例；溢出用例只覆盖“溢出同步 flush 失败”路径，“flush 成功但并发入队仍满”
   的 `持久化队列已满` 分支仍无并发用例。异步 flush 合并（每 session 单在途
   任务）无并发用例；重试次数由代码检查与 `failNext(4)` 用例间接证明。以上为
   已知测试缺口，不改变本轮测试层级判定。
6. **`session.failed` 推送顺序**：`persistenceFailed` 先追加 / 推送
   `session.failed`，再 best-effort flush；只有 `session.ended` 保证
   flush-before-push。该行为在 Task 2 报告中记录，属非阻塞。
7. **关闭 / 应用停止**：`close` / `@PreDestroy` 为 best-effort flush，失败只
   记录日志并丢弃尾部；Java 重启恢复不在范围内。
8. **失败终态不能以事件存在性判断**：`session.ended` 的关键 flush 失败后，
   `persistenceFailed` 会 best-effort flush `session.failed`；若后续重试成功，
   同一队列里已入队的 `session.ended` 也可能最终落库，而客户端只看到过
   `session.failed`。M4 / 回放必须依据 session 状态（`failed`）判断终态，不得
   以某个终态事件是否存在作为成功 / 结束依据。

## 7. 变更文件

本切片提交（`9487b5a..ffa3c4b`，共 22 个文件）；最终审查修复提交
`3561653` 在此基础上前述生产文件的准确性修正与测试补充（新增
`SessionEndPersistenceFailureIT`，并调整 `JpaContextPersistenceWriterTest`）：

生产代码（14 个文件）：

```text
server/src/main/java/com/shijiangtai/m3classroom/application/DirectContextPersistenceWriter.java
server/src/main/java/com/shijiangtai/m3classroom/application/SessionActor.java
server/src/main/java/com/shijiangtai/m3classroom/application/SessionApplicationServiceImpl.java
server/src/main/java/com/shijiangtai/m3classroom/application/SessionRuntimeRegistry.java
server/src/main/java/com/shijiangtai/m3classroom/application/port/ContextPersistenceWriter.java
server/src/main/java/com/shijiangtai/m3classroom/application/port/PersistenceFlushException.java
server/src/main/java/com/shijiangtai/m3classroom/application/port/SessionBatchStore.java
server/src/main/java/com/shijiangtai/m3classroom/application/port/SessionEventStore.java
server/src/main/java/com/shijiangtai/m3classroom/application/port/SessionTextStore.java
server/src/main/java/com/shijiangtai/m3classroom/domain/session/SessionContext.java
server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/JpaContextPersistenceWriter.java
server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/JpaSessionBatchStore.java
server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/SessionEventStoreAdapter.java
server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/SessionTextStoreAdapter.java
```

测试（9 个文件；其中 8 个测试类 + 1 个测试支持类）：

```text
server/src/test/java/com/shijiangtai/m3classroom/application/SessionActorPersistenceTest.java
server/src/test/java/com/shijiangtai/m3classroom/application/SessionActorDialogueTest.java
server/src/test/java/com/shijiangtai/m3classroom/application/SessionReconnectTest.java
server/src/test/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/JpaContextPersistenceWriterTest.java
server/src/test/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/JpaSessionBatchStoreTransactionIT.java
server/src/test/java/com/shijiangtai/m3classroom/persistence/ContextPersistenceWriterIT.java
server/src/test/java/com/shijiangtai/m3classroom/persistence/SessionEndPersistenceFailureIT.java
server/src/test/java/com/shijiangtai/m3classroom/support/BufferingContextPersistenceWriter.java
server/src/test/java/com/shijiangtai/m3classroom/ws/ClassroomWebSocketIT.java
```

文档（本次 Task 6 提交）：

```text
docs/design/05A-M3-课堂状态机与WebSocket.md
docs/implementation/04-M3-实现设计.md
docs/team/evidence/2026-11-05-m3b-persistence-t19.md
docs/team/02-进度看板.md
```

最终审查修复同时更新 `docs/superpowers/plans/2026-11-05-m3b-persistence-t19.md`
（Task 6 的 M3B 测试完成度改为 60%，与进度看板 3/5 列一致）；进度看板不变。

## 8. 进度口径

- M3B 测试行保持 **60%**：UT / IT / 契约 ✅，E2E 与性能 / 安全 ⬜，3/5。
  T16 / T19 加深后端测试矩阵，但不翻转任何测试层级列。
- G2—G8 与 M3B 模块完成度 **10%** 不变。
- 阻塞 / 下一步：课堂 / WS、感知、多轮对话与异步持久化后端 P0 已完成；
  前端课堂 UI、M3C、E2E / NFR 待续。
