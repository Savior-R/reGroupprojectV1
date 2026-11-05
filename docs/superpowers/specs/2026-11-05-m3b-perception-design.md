# M3B 感知通道（ASR / OCR）实现设计

> 日期：2026-11-05　Owner：M3 Owner（当前使用者）　状态：待批准
> 上位文档：[M3 需求](../../requirements/M3-虚拟课堂与录制.md) 第 18/19 条、
> [05A](../../design/05A-M3-课堂状态机与WebSocket.md) §3.3/§5.3/§6.2、
> [02](../../design/02-API与实时协议.md) §2.3/§5.2/§6.1、
> [implementation/04](../../implementation/04-M3-实现设计.md)。
> 已完成的前置切片：`docs/superpowers/plans/2026-11-04-m3b-classroom-backend.md`
> （12 个任务全部完成，提交 `7a476b5..71ea427`）。

## 1. 为什么要做

M3B 的课堂 / 面试 / 控制 WebSocket / 事件与文本落库已经完成，但计划明确把**感知通道**
留给后续切片：

- 没有 ASR，教师语音提问无法触发 `classroom.question_asked(source=voice)`，
  05A §4.4 的 M3-T12 只覆盖了手动触发；
- 没有屏幕截图与 OCR，`m3.frames` 永远为空，M4 拿不到屏幕上下文与关键帧；
- 需求第 18/19 条要求“实时 ASR / OCR 故障不终止录制，写入事件，M4 标记 partial”，
  目前没有承载故障与降级的通道。

本次切片把 M3B 后端剩余功能补齐：**感知 WS + 屏幕截图 + OCR + 教师话语分类 +
帧落库 + 降级**。前端感知通道、M4 的 partial 评测、声学特征仍不在本次范围。

## 2. 目标

1. `WS /ws/v1/sessions/{session_id}/perception?ticket=...`：
   - 一次性 ticket，仅 session 所属学生可连接；
   - 首条 JSON `audio.start {format:"pcm16", sample_rate:16000, channels:1}`，
     随后二进制 PCM 帧，`audio.stop` 结束；
   - Java 把 PCM 转发给 Python `/internal/v1/asr/stream`；
   - Python partial 实时回推给感知 WS 客户端；final 写入 `m3.session_texts`；
   - `audio.stop` 后等待 ASR final 收尾最多 2 秒，不阻塞 session 结束。
2. 教师话语分类：ASR final → Python `/internal/v1/classroom/classify`；
   - `label=question` 且 `confidence >= 0.8`、课堂处于 `lecturing`、距上次语音触发
     > 10 秒 → 自动进入 `qa.waiting_hands`，事件 `source=voice`；
   - `label=question` 且低置信 → `classroom.question_suggested {confidence}`；
   - `label=not_question` / 非课堂模式 / 非 `in_progress` → 不产生课堂事件。
3. 屏幕截图：
   - `POST /api/v1/sessions/{session_id}/perception/screen`（multipart：
     `file` JPEG、`at_ms`、可选 `change_score`）；
   - 同步写 `m3.frames(status=pending)`，返回 202 `{frame_id, status}`；
   - 异步 OCR（15 秒超时）→ 更新 `text/bounding_boxes/confidence/frame_type/status`；
   - 成功后通过控制 WS 推送 `perception.screen_changed {frame_id, at_ms, frame_type}`；
   - 图片写入 MinIO（`m3/frames/{sessionId}/{frameId}.jpg`），失败不阻塞 OCR。
4. 降级：ASR / OCR / 分类任何失败都**不改变 session 状态、不停止录制、不影响控制 WS**；
   感知 WS 收到 `error` 消息，帧标记 `failed`，日志带 `session_id`。
5. 测试：单元 + 集成 + 失败路径；CI 不依赖 Python 服务（用 fake 端口）。

## 3. 非目标（明确不做）

- **声学事件** `perception.acoustic`：需求把音量 / 停顿 / 语速特征归给 M4
  （`requirements/M4` 第 104 行），05A 的阈值也标注“待实测”。本次不实现，
  不发明阈值；PCM 原样转发 ASR。
- **视觉模型 / 关键帧类型**：`final` / `chart` 需要视觉模型；本次只按 OCR 置信度与
  `change_score` 产生 `normal` / `slide_change` / `low_confidence`。
- **`perception.failed` 新事件**：不新增跨模块事件契约；失败通过帧 `status=failed`、
  `object_key` 缺失与日志体现，M4 可据数据完整性标 partial。
- **控制 WS 新增事件**：partial/final 只在感知 WS 上回推；控制 WS 事件表不变。
- 前端感知通道、M4 partial、录制 / 回放、保留清理、异步批量落库、多轮对话。

## 4. 关键决定

| 决定 | 值 | 理由 |
|---|---|---|
| 感知 WS 服务端消息 | `{"type":"asr.partial","text","at_ms"}`、`{"type":"asr.final","text","text_id","at_ms"}`、`{"type":"error","code","message"}`；无信封 | 02 没有 partial 事件，控制 WS 契约保持不变；感知 WS 是独立客户端连接，可承载自己的响应 |
| 感知 WS 关闭码 | 4001 认证失败、4002 会话失败/取消/已提交、4003 状态不允许（非 `in_progress`）、1000 正常结束 | 与控制 WS 语义一致 |
| 感知 WS 连接时机 | 只允许 `in_progress`；`preparing` 返回 4003，客户端在 `session.started` 后连接 | ASR 时间基准以 `session.started` 为 0，避免准备阶段产生负时间 |
| ticket | 复用 `POST /sessions/{id}/ws-ticket` 与 `WsHandshakeInterceptor` | 一次性、30 秒、绑定 session + account；两条通道独立消费 |
| ASR final 落库 | 课堂：`speaker_type=teacher`、`source=asr`、`kind=utterance`、`status=final`；面试：`speaker_type=candidate`、`source=asr`、`kind=answer`、`status=final`、`speaker_ref=当前 answer_id` | 05A §7 / 05C §6.4 明确考生 ASR 为 `kind=answer` 且按 `answer_id` 聚合 |
| ASR 时间 | `audio.start` 时记录 `audioStartAtMs`；`start_ms/end_ms = audioStartAtMs + chunk.begin_ms/end_ms`，缺省用当前 `at_ms` | 事件 / 文本 / 帧共用 session 相对毫秒 |
| partial | 不落库、不进入控制 WS；只回推感知 WS | 05A “partial 约 500ms 一次，只推前端” |
| `audio.stop` | 发 `finish` 给 Python，最多等 2 秒 `done`；已收到的 final 先落库，然后 1000 关闭 | 05C §6.3 结束流程 |
| 结束竞争 | 客户端**必须先 `audio.stop` 并等待感知 WS 1000 关闭，再发 `session.end`**；服务端不阻塞结束命令。session 离开 `in_progress` 后迟到的 final 丢弃并记 WARN | 05C §6.3 的 2 秒收尾在感知连接内完成；避免把结束流程耦合进 actor 命令 |
| 感知连接数 | 每个 session 同时最多一个；新连接替换旧连接（旧连接 1000 关闭），旧连接关闭不影响 session | 与控制 WS 的替换语义一致，避免重复 final / 重复触发问答 |
| 感知连接生命周期 | session 进入终态（`ended` / `failed` / `cancelled` / `submitted` / `reviewing`）时，服务端关闭活动感知连接（4002） | 避免终态后继续使用 ASR；连接建立时同样用 4002 |
| `audio.start` 校验 | 必须是首条消息；仅接受 `format=pcm16`、`sample_rate=16000`、`channels=1`；`language` 固定 `zh`，不在消息里 | 02 §6.1 只定义了这三个字段；固定值避免客户端与服务端模型不一致 |
| 非法消息序列 | 二进制先于 `audio.start`、重复 `audio.start`、`audio.stop` 先于 start、非法格式 / 采样率 / 声道、未知文本消息：回 `error VALIDATION_ERROR` 后以 4003 关闭 | 与控制 WS 的 `VALIDATION_ERROR` 语义一致；非法序列不进入模糊状态 |
| 分类结果 | 只有 `label=question` 才调用 `ClassroomStateMachine.onTeacherUtterance(confidence)`；`not_question` 丢弃 | 现有状态机对任何非触发输入都会发 suggested，不能把 not_question 传进去 |
| 分类执行 | 每个感知连接一个单线程执行器，按 final 顺序串行调用；session 已结束则丢弃结果 | 不阻塞 actor 监视器 / tick；结果顺序稳定 |
| 屏幕接口 | multipart/form-data：`file`、`at_ms`、可选 `change_score`；202 + `{frame_id, status:"pending"}`；仅 `image/jpeg`、≤ 5 MB、`at_ms >= 0`、`change_score` 缺省或 `[0,1]`，否则 `VALIDATION_ERROR` | 与材料上传一致，5 MB 图片不需要 base64 膨胀；控制器显式校验，不复用全局 20 MB 限制 |
| 帧类型 | 仅当 OCR `confidence != null && < 0.60` → `low_confidence`；否则 `change_score != null && >= 0.125`（dHash 8/64）→ `slide_change`；否则 `normal` | Python OCR provider 当前通常不返回 confidence；null 视为“未知”，不能让所有真实帧变成 low_confidence |
| 帧图片 | 复用 `MaterialObjectStorage`，key `m3/frames/{sessionId}/{frameId}.jpg`；存储失败 `object_key=null`，OCR 继续 | 不新增基础设施；M4 关键帧需要图片 |
| 帧注意力 | `perception.screen_changed` **不**修改虚拟学生注意力 | 05A 标注“可选，避免过度”；每分钟最多 30 帧会淹没衰减 |
| AI 适配 | `m3.ai.base-url` 配置；有值启用 Python 适配器，无值用不可用 fallback | 不修改 Foundation 的 `application*.yml`；本地 / CI 无 Python 也能启动 |
| Python 调用 | JDK `HttpClient` + `java.net.http.WebSocket`；`X-Service-Token` | 不新增依赖，不触碰 `build.gradle.kts` |
| OCR 执行 | 有界单线程 daemon 执行器（队列 32，拒绝时标记帧 failed） | 图片频率低；避免无界队列 |
| 权限 | 截图接口走 `SessionAccessPolicy.requireOwner` + `session:start` 语义；感知 WS 校验 account 归属 | 与现有 REST / WS 一致 |

## 5. 组件与职责

```text
api/
  PerceptionScreenController           multipart 截图 → application
infrastructure/ws/
  PerceptionWebSocketHandler           audio.start/stop、PCM 转发、partial/final 回推
  PerceptionConnectionRegistry         每 session 单连接、替换、终态 4002 关闭
application/
  PerceptionApplicationService         截图落帧、异步 OCR、事件发送、权限
application/port/
  AsrStreamClient / AsrSession         流式 ASR 端口（open/send/finish/callback）
  OcrClient                            图片 OCR 端口
  TeacherSpeechClassifier              教师话语分类端口
  SessionFrameStore                    帧持久化端口
domain/session/
  SessionFrame, FrameType, FrameStatus, OcrBox  帧记录、枚举与 OCR 框
infrastructure/perception/
  PythonAsrStreamClient                JDK WebSocket 客户端 → Python ASR
  PythonPerceptionHttpClient           OCR + classify（JDK HttpClient/Jackson）
  PerceptionAiProperties               m3.ai.* 配置
infrastructure/persistence/jpa/
  SessionFrameEntity / JpaRepository / StoreAdapter
```

### 5.1 端口签名（锁定）

```java
public interface AsrStreamClient {
    AsrSession open(AsrRequest request);   // 连接失败抛 RuntimeException
}

public interface AsrSession extends AutoCloseable {
    void send(byte[] pcm);                  // 非阻塞；底层按连接顺序发送
    void finish();                          // 发送 finish，等待 Python done
    void onChunk(Consumer<AsrChunk> listener);   // partial / final
    void onClosed(Runnable listener);            // Python 正常 done
    void onError(Consumer<RuntimeException> listener);
    @Override void close();
}

public record AsrRequest(SessionId sessionId, int sampleRate, String format, String language) {}

public record AsrChunk(String text, boolean finalChunk, Long beginMs, Long endMs,
                       BigDecimal confidence) {}

public interface OcrClient {
    OcrResult recognize(byte[] jpeg);
}

public record OcrResult(String text, List<OcrBox> boxes, BigDecimal confidence,
                        String modelVersion) {}
// OcrBox 是 domain/session 的纯记录：text, x1, y1, x2, y2, confidence

public interface TeacherSpeechClassifier {
    ClassifyResult classify(String text, List<String> recentTexts, String mode);
}

public record ClassifyResult(String label, BigDecimal confidence,
                             String modelVersion, String promptVersion) {}

public interface SessionFrameStore {
    void save(SessionFrame frame);
    Optional<SessionFrame> find(UUID frameId);
    void markProcessed(UUID frameId, String text, List<OcrBox> boxes,
                       BigDecimal confidence, FrameType frameType, String objectKey);
    void markFailed(UUID frameId, String errorMessage);
}
```

### 5.2 actor 新方法

```java
/** 记录教师 / 考生 ASR final；返回 text_id。session 非 in_progress 时返回空。 */
public synchronized Optional<UUID> recordSpeechFinal(String speakerType, UUID speakerRef,
        String text, Long startMs, Long endMs, BigDecimal confidence);

/** 分类结果进入 actor；内部调用 classroom.onTeacherUtterance 并调度举手窗口。 */
public synchronized void onTeacherSpeechClassified(double confidence);

/** OCR 完成后的 screen_changed 事件；非 in_progress 时丢弃。 */
public synchronized void onScreenProcessed(UUID frameId, long frameAtMs, String frameType);

/** audio.start 的时间基准。 */
public synchronized long currentAtMs();

/** 面试当前 answer_id（非面试或未开始时为 null）。 */
public synchronized UUID currentAnswerId();

/** 追加一个状态监听器（不替换 registry 的监听器）；返回移除句柄。 */
public Runnable addStatusListener(Consumer<SessionStatus> listener);
```

`onTeacherSpeechClassified` 复用 `handleAsk` 的举手调度：若
`classroom.onTeacherUtterance` 产出 `classroom.question_asked(source=voice)`，则
`++windowGeneration`、取消旧 timer、调度第一次举手与 30 秒窗口超时；否则只追加
`classroom.question_suggested`。

`PerceptionConnectionRegistry` 在连接建立时用 `addStatusListener` 订阅 actor；session
进入终态时向对应感知 socket 发 `error`（若仍可写）并关闭 4002，然后移除监听器。
`attachStatusListener` 的现有单监听器语义保持不变，`SessionRuntimeRegistry` 不需要改构造器。

## 6. 数据与一致性

- `m3.frames` 表已存在（V4），本切片只加 JPA 映射，不加迁移。
- `SessionFrameStore.save` 同步；OCR 更新 `status`、`text`、`bounding_boxes`、
  `confidence`、`frame_type`、`object_key`、`updated_at`。
- `bounding_boxes` 以 JSON 字符串写 jsonb；列定义沿用 `SessionEventEntity` 的
  `columnDefinition = "jsonb"` 与 `stringtype=unspecified` 运行配置。
- 事件仍由 actor `append(...)` 先落 `events` 再推送；帧行先更新为 processed，再发事件。
- 感知失败不写 `session.failed`；session 结束 / 失败路径不变。
- 帧 pending 在提交前的 barrier 属于 M3C / 05A §9.3；本切片只保证单帧最终
  processed / failed。

## 7. 失败与降级矩阵

| 失败点 | 行为 | session 状态 |
|---|---|---|
| 感知 WS ticket 无效 | 握手后 4001 | 不变 |
| session 非 `in_progress` | 4003 | 不变 |
| Python ASR 连接失败 | 感知 WS 收 `error SERVICE_UNAVAILABLE`，丢弃音频，保持连接；`audio.stop` 正常 1000 | 不变 |
| Python ASR 中途断开 | 同上，清理 AsrSession；已收到 final 保留 | 不变 |
| 分类 HTTP 失败 / 超时 | 日志 WARN，不产生课堂事件 | 不变 |
| OCR 失败 / 超时 | 帧 `failed` + `error_message`（截断 500 字符），不推 screen_changed | 不变 |
| MinIO 写失败 | `object_key=null`，继续 OCR 与事件 | 不变 |
| 帧写库失败 | 截图接口 500（客户端可重试）；不改变 session | 不变 |
| 感知 WS 断开 | 不影响控制 WS 的 30 秒 grace，不触发 session.failed | 不变 |

- `audio.stop` 的 2 秒收尾期间收到的 final 仍会落库并进入分类执行器；socket 正常
  关闭后执行器 `shutdown()`，已排队任务继续跑完，结果进入 actor 时若 session 已终态则
  丢弃。这样最后一句教师提问仍有机会在结束前触发问答，同时不会把结束流程拖住。
- session 在感知连接活动期间进入终态时，`PerceptionConnectionRegistry` 关闭 socket
  4002；正在发送的 ASR 回调先检查 socket 是否打开，写失败只记日志。

## 8. 测试与验收

### 8.1 单元

- `FrameTypeClassifierTest`：低置信优先、slide_change 阈值边界 0.125、normal。
- `SessionActorPerceptionTest`：语音高置信触发 QA + 举手调度；低置信只发 suggested；
  not_question 不进入 actor；面试模式忽略分类；终态丢弃；`recordSpeechFinal` 课堂 /
  面试 speaker_type 与 `speaker_ref`；`onScreenProcessed` 事件 payload。
- `PerceptionApplicationServiceTest`：202 帧 pending；OCR 成功更新 processed 并推事件；
  OCR 失败 failed 且无事件；MinIO 失败仍 processed；非 owner 403；非 in_progress 拒绝。
- `PythonPerceptionClientTest`：用 JDK `HttpServer` 验证 OCR / classify 请求体、
  `X-Service-Token`、响应映射、超时 → 异常。
- `WsPerceptionProtocolTest`：`audio.start` 解析、非法格式 / 采样率、
  `audio.stop` 收尾超时。

### 8.2 集成（真实 Tomcat + PostgreSQL，fake AI 端口）

- `PerceptionWebSocketIT`：
  - REST ticket → 连接 → `audio.start` → fake ASR 回 partial/final →
    感知 WS 收到 `asr.partial` / `asr.final`；`session_texts` 有 teacher final；
  - high-confidence question → 控制 WS 收到 `classroom.question_asked(source=voice)` /
    `classroom.state_changed` / 举手事件；
  - low-confidence → `classroom.question_suggested`，状态仍 `lecturing`；
  - ASR 连接失败 → `error`，session 仍 `in_progress`；
  - 无效 ticket 4001、preparing 4003、终态 4002；
  - `audio.stop` 等待 final 后 1000 关闭，session 可继续结束。
- `PerceptionScreenIT`：
  - 202 + pending；轮询到 processed；`perception.screen_changed` 到达控制 WS；
  - `m3.frames` 行字段与 object_key；MinIO memory key；
  - OCR 失败 → failed、无事件、session 不变；
  - 非 owner 403。
- `SessionFramePersistenceIT`：JSONB boxes / confidence / 枚举轮转。

### 8.3 验收

- M3-T12 的语音分支：高置信自动问答、低置信提示、10 秒冷却。
- M3-T17：ASR / OCR 失败不影响录制与 session 状态；失败可见（error / failed 帧）。
- 不破坏既有 410 条 M3B 测试。

## 9. 约束

- 仅修改 M3B 归属路径：`m3classroom/domain/session/**`、`application/**` 的
  perception 相关类、`api/Perception*`、`infrastructure/ws/Perception*`、
  `infrastructure/perception/**`、`infrastructure/persistence/jpa/SessionFrame*`、
  测试对应路径、`docs/design/05A`、`docs/implementation/04`、`docs/team/02`。
- 不修改 `build.gradle.kts`、`application*.yml`、`docs/design/02`、`docs/design/12`。
- 领域层不引入 Spring / Jackson / JPA / HTTP；控制器薄。
- 不新增跨模块事件 / 错误码 / DTO；感知 WS 消息是 M3 内部协议，写入 05A。
- 无 Python 服务时应用可启动、测试可运行。

## 10. 留给实现者的选择

- 类名、包内组织、执行器线程名。
- `m3.ai.*` 的具体默认值（建议 `base-url` 空、OCR 15s、classify 3s）。
- 测试 fake 的组织方式（`support/FakeAsrStreamClient` 等）。
- `recent_texts` 是否传最近 5 条教师文本（建议传，分类质量更好；失败不影响主流程）。
