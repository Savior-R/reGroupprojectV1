# M3B 异步持久化（M3-T16）与页面刷新 / 录制丢失验收（M3-T19）设计

> 日期：2026-11-05　Owner：M3 Owner（当前使用者）　状态：执行中
> 上位文档：[05A](../../design/05A-M3-课堂状态机与WebSocket.md) §9、§16.6；
> [实现设计 04](../../implementation/04-M3-实现设计.md) §3.1、§5；
> [测试与验收 10](../../design/10-测试与验收.md) §6.5（M3-T16 / M3-T19）。
> 前置切片：M3B 课堂 / WebSocket、感知通道、多轮对话均已完成。

## 1. 为什么要做

`SessionActor` 目前在 actor 线程内同步调用 `SessionEventStore.append` 和
`SessionTextStore.save`，每个 tick、每条 ASR final 都会等待一次 DB 写入；
`forceFlush()` 仍是空实现。05A §9.2 要求“事件先入有序持久化队列再推送，DB
异步批量落库；关键事件在结束 / 失败前同步 flush”，M3-T16 是 M3B 最后一个未实现的
核心后端功能。

同时，M3-T19 的行为已在 actor 中实现（`recording_state=lost` → failed、
grace 超时 → failed、正常 ended 不 failed），但缺少完整验收证据与 WS 级用例。

## 2. 目标

1. 每个 session 一个有序持久化队列；actor 只入队，不等待 DB。
2. 触发批量落库：每 1 秒、100 条或 64 KB，先到先 flush。
3. `session.ended`、`interview.ended`、`session.failed` 三个关键事件同步强制 flush，
   flush 成功后才推送 / 返回成功；flush 失败 → session failed，不产生成功提交。
4. 普通异步 flush 失败：记录日志、保留队列、下一轮重试，不阻塞 tick。
5. 一批 events + session_texts 在同一事务内幂等写入（事件 `ON CONFLICT DO NOTHING`，
   文本按主键 upsert）。
6. 活跃 actor 的教师文本上下文改读内存，避免异步落库导致 LLM 上下文缺句。
7. 补齐 M3-T19：`recording_state=lost`、grace 超时、正常 ended 的单元 / WS 验收。
8. 补齐多轮对话已知非阻塞用例：null / 重复 `dialogue_id`、`ask_student_question`
   和 tick 超时终止对话。

## 3. 非目标

- 不把 `m3.frames` 纳入异步队列。帧由 `PerceptionApplicationService` 在感知线程
  同步写入 / 更新；OCR 成功更新依赖“pending 帧已存在”的 read-after-write 语义，
  放入异步队列会在 pending 尚未落库时丢失 OCR 结果。帧写入本来就不在 actor 线程，
  不违反“活跃课堂不被 DB 阻塞”的目标。原 05A “events + texts + frames 同批事务”
  的帧部分在本轮显式 defer，文档注明差异。
- 不实现 submission / 提交前 flush 顺序；submission 属于 M3C 计划，本轮只提供
  `forceFlush` 接缝，M3C 提交时调用。
- 不实现 Java 重启恢复、跨实例分布式队列、消息中间件、指标 / 告警。
- 不实现 M4 partial 标记（依赖 M4）、前端 UI、E2E / NFR。

## 4. 关键决定

| 决定 | 值 | 理由 |
|---|---|---|
| 队列粒度 | 每 session 一个 `ArrayDeque` + 单 flush 锁；共享线程池消费 | 05A §9.2；同 session 顺序由 actor 串行入队 |
| 入队内容 | 只含 `SessionEvent` 与 `SessionText`；帧保持同步 | 见非目标 |
| 触发 | 1 s 定时、100 条、64 KB；关键事件同步 | 05A §9.2 |
| 关键事件 | `session.ended`、`interview.ended`、`session.failed`；`cancel` 做 best-effort flush | 结束 / 失败后不得再有半份上下文 |
| 关键事件推送顺序 | 先入队 → flush 成功 → 再 push WS | 避免客户端看到未持久化的终态事件 |
| flush 失败 | 重试 3 次（50 ms / 100 ms / 200 ms）；仍失败抛 `PersistenceFlushException` | 05A §9.3 |
| 结束路径 flush 失败 | `session.status = failed`，写 `session.failed`，命令返回 `SESSION_FAILED` | 05A §9.3 |
| 普通异步 flush 失败 | 日志 + 原批回队首 + 下一轮重试 | 不阻塞 tick |
| 队列上限 | 10 000 条；溢出先同步 flush，仍满则抛异常 | 有界；正常情况下每 1 s 清空 |
| 事务 | `JpaSessionBatchStore.write` 单事务：先 texts 后 events | 事件引用 text_id；幂等 upsert |
| 文本上下文 | `SessionContext` 增加内存文本流；`recentTeacherTexts` 读内存 | 05A §9.1“活跃课堂读内存” |
| `SessionView.last_seq` | 活跃 actor 用 `context.lastSeq()`，否则用 DB `maxSeq` | 异步落库下 DB 可能滞后，REST 仍返回最新 seq |
| 测试接缝 | 旧 actor / registry 构造器保留，内部使用直写 writer；生产注入异步 writer | 不破坏既有单测；异步路径单独测 |
| 帧 | 不进入 writer；文档记录与 05A 的差异 | 见非目标 |

## 5. 组件

```text
application/port/
  ContextPersistenceWriter         新增：appendEvent / saveText / flush / close
  SessionBatchStore                新增：write(events, texts) 单事务批量写
  SessionEventStore                增加 appendAll 默认方法
  SessionTextStore                 增加 saveAll 默认方法

application/
  DirectContextPersistenceWriter   新增：直写实现，供测试构造器和旧 registry 构造器使用
  SessionActor                     写路径改走 writer；关键事件 flush 后推送；内存文本流
  SessionRuntimeRegistry           生产构造器注入 writer；buildActor 传入
  SessionApplicationServiceImpl    toView 使用活跃 actor 的 lastSeq

domain/session/
  SessionContext                   + texts(speakerType) / addText(text)

infrastructure/persistence/jpa/
  JpaSessionBatchStore             新增：@Transactional batch write
  JpaContextPersistenceWriter      新增：队列 / 定时 / 重试 / 有界 / 关闭
  SessionEventStoreAdapter         + appendAll 批量 JDBC
  SessionTextStoreAdapter          + saveAll repository.saveAll
```

### 5.1 actor 写入与终止

```java
private SessionEvent append(String type, Map<String, Object> payload, Instant now) {
    SessionEvent event = context.append(SessionEventDraft.of(type, payload), atMs(now));
    writer.appendEvent(event);
    push(event);
    return event;
}

private SessionEvent appendCritical(String type, Map<String, Object> payload, Instant now) {
    SessionEvent event = context.append(SessionEventDraft.of(type, payload), atMs(now));
    writer.appendEvent(event);
    writer.flush(session.id());   // 失败抛 PersistenceFlushException，事件不推送
    push(event);
    return event;
}
```

- `endClassroomSession` / `endInterview`：先追加所有领域事件（普通入队），
  `session.end(now)` 后用 `appendCritical` 追加 `session.ended`。
  捕获 `PersistenceFlushException` → `persistenceFailed(now)`；返回失败结果。
- `fail`：追加 `session.failed` 后 finally 中 `flush`；flush 异常先记录，
  仍执行 `cancelAllTasks` / `notifyStatusChanged`，最后重新抛出。
- `cancel`：best-effort `flush`，异常只记录。
- `persistenceFailed(now)`：`session.fail(INTERNAL)` → 追加 `session.failed`
  （普通入队）→ best-effort flush → 取消定时器 → `notifyStatusChanged`。

## 6. 失败与边界

| 场景 | 行为 |
|---|---|
| 普通异步 flush 失败 | 原批回队首，日志，下一轮重试；actor 状态不变 |
| 结束 / 面试结束 flush 失败 | 重试后仍失败 → session failed；`session.ended` 不推送；命令 `SESSION_FAILED` |
| `session.failed` 自身 flush 失败 | 状态仍 failed；清理定时器并通知 registry；异常向调用方抛出（WS 关闭 4002） |
| 队列满 | 先同步 flush；仍满 → `PersistenceFlushException`；结束路径按 flush 失败处理 |
| 关闭后迟到入队 | actor 在终态后不再写文本 / 事件；writer 队列在 registry 终态移除时 `close` |
| 同 session 并发 flush | 每队列 `ReentrantLock` 串行；新事件在 flush 期间继续入队，下一批处理 |
| 事件重复 | `ON CONFLICT (session_id, seq) DO NOTHING` |
| 文本重复 | 按主键 `saveAll` upsert |
| 应用关闭 | `@PreDestroy` best-effort flush + shutdown；未持久化尾部允许丢失（Java 重启本就不恢复） |

## 7. 测试

### 7.1 单元

- `JpaContextPersistenceWriterTest`：flush 前不写、flush 写 events+texts、
  100 条触发、失败重试后抛、异步失败回队重试、close 后清空队列。
- `SessionActorPersistenceTest`：结束 / 失败强制 flush；flush 失败 → failed +
  `session.failed`；普通命令不强制 flush；教师文本在未落库时仍进入 content 请求。
- `SessionReconnectTest`：正常 ended 不 failed；页面刷新等价（断开不 resume）
  grace 超时 failed；recording lost failed（既有用例保留）。

### 7.2 集成

- `ContextPersistenceWriterIT`（Postgres）：真实 batch store + 定时 1 s flush；
  flush 后事件 / 文本可读；不手动 flush 也能在 5 s 内落库。
- `ClassroomWebSocketIT`：`session.resume {recording_state:lost}` → `session.failed`
  + error；正常 `teacher.end_session` → `session.ended`，无 `session.failed`。

### 7.3 对话补测

- null `dialogue_id` 在活动对话中 → `SESSION_STATE_INVALID`，状态不变。
- 重复 `teacher.end_dialogue` → 第二次失败，无第二个 `dialogue.ended`。
- `teacher.ask_student_question` 在对话中 → `dialogue.ended(qa_restarted)` 后
  `classroom.question_asked`，无中间 `waiting_hands`。
- `onTick` 到时 → `dialogue.ended(session_ended)` → … → `session.ended`。

## 8. 约束

- 只改 M3B 路径；不新增依赖；不改 `build.gradle.kts` / `application*.yml` /
  `docs/design/02` / `docs/design/12` / M3C 文件。
- 领域层不引入 Spring / Jackson / JPA / HTTP。
- 应用层不引入 `infrastructure`；`ContextPersistenceWriter` 是应用端口。
- 事件只追加；`seq` 单调；WS 事件契约不变。
- 所有 commit 使用 `git commit --only <显式路径>`，避免带入工作区已有的暂存删除。
