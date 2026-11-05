# M3B 多轮对话（教师语音回应驱动）实现设计

> 日期：2026-11-05　Owner：M3 Owner（当前使用者）　状态：待批准
> 上位文档：[M3 需求](../../requirements/M3-虚拟课堂与录制.md) 第 10 条（对话可多轮）、
> [05A](../../design/05A-M3-课堂状态机与WebSocket.md) §4.4/§5.2/§5.5/§6.2/§7.3、
> [02](../../design/02-API与实时协议.md) §6.2 事件表。
> 前置切片：M3B 感知通道（`docs/superpowers/specs/2026-11-05-m3b-perception-design.md`，
> 已完成）提供教师 ASR final；`DialogueState`（`domain/classroom/DialogueState.java`）已存在。

## 1. 为什么要做

`DialogueState` 与 `ClassroomStateMachine.answerFinished(studentId, true, now)` 已经实现，
但 `SessionActor` 固定调用 `answerFinished(..., false, ...)`：每次学生回答后立即回到
`waiting_hands`，`dialogue.*` / `student.follow_up` 永远不产生。需求要求：

> 对话可多轮：学生自己判断是否继续追问，最多 3 轮；教师可随时结束。
> 教师语音回应进入对话上下文。

感知通道现在会把教师 ASR final 写入 `session_texts`，正好可以作为“教师回应”的触发点。
本次切片把这条链路接通。

## 2. 目标

1. 学生回答后按 `p = question_rate × 0.5` 判断是否追问；最多 3 轮
   （第 1 轮为初始回答，最多再 2 次追问）。
2. 追问时进入 `qa.dialogue`，写 `dialogue.started`、`student.follow_up`，学生行为状态
   进入 `dialogue`，注意力 +0.05。
3. 对话中教师每条 ASR final 先落 `session_texts`，然后作为“教师回应”：
   - 学生再次按概率判断是否追问；是 → 下一轮 `student.follow_up`；
   - 否 → 自动写 `dialogue.ended(reason=no_follow_up)`，回 `waiting_hands`；
   - 已到第 3 轮 → 写 `dialogue.ended(reason=max_rounds)`，回 `waiting_hands`。
4. `teacher.end_dialogue` 写 `dialogue.ended(reason=teacher)`，回 `waiting_hands`；
   `teacher.end_qa` / `teacher.ask_class` / `teacher.ask_student_question` /
   结束试讲 / 失败 都要先结束活动对话，事件顺序在终止事件之前。
5. 追问文本由现有 `ContentGenerationService.questionOrFallback` 生成，写入
   `session_texts`（`speaker_type=student`、`source=llm`、`kind=follow_up`），
   `student.follow_up {student_id, answer_id, text_id}` 的 `answer_id` 绑定本轮对话的
   初始回答。
6. 对话中教师 ASR final 不再触发 `classroom.question_suggested`；语音提问检测只在
   `lecturing` 生效。

## 3. 非目标

- 结构化面试追问（05C 明确本轮不做 LLM 追问）。
- 自动超时结束对话（05A Q14：不做自动超时 / 语音判断结束；只有学生判断不追问或教师结束）。
- 独立教师“回应”命令；教师回应只通过 ASR final 进入上下文。
- 修改 02 的 `student.follow_up` payload 形状；不新增 `dialogue_id` 字段。
- 前端展示改造、M4 评测口径、感知声学事件。

## 4. 关键决定

| 决定 | 值 | 理由 |
|---|---|---|
| 轮次定义 | 初始回答 = 第 1 轮；`DialogueState.canFollowUp()` 在 round < 3 时可追问；最多 3 轮 | `DialogueState` 已有语义，M3-T13 “最多 3 轮” |
| 追问触发 | 每次学生回答后；以及对话中教师 ASR final 之后 | 需求“学生自己判断是否继续追问”；感知通道提供教师回应 |
| 每轮决策次数 | 每个“学生追问”只对**开始时间不早于该追问 at_ms** 的教师 ASR final 决策一次；用 `dialogueAwaitingSinceMs` 时间边界，不用布尔标记 | 教师一句话可能被 ASR 切成多个 final；开始早于追问的 final 属于上一段话，不能当作回应；时间边界避免“刚追问就被同句第二个 final 再次追问” |
| 教师回应边界 | final 的 `start_ms`（缺省 `end_ms`，再缺省当前 `at_ms`）必须 `>= dialogueAwaitingSinceMs`；不满足只落库、不推进对话 | ASR final 自带 session 相对 begin/end；与追问 at_ms 可比 |
| 不追问 | `dialogue.ended(reason=no_follow_up)` + `classroom.state_changed(trigger=no_follow_up)` + 回 `waiting_hands` + 学生回 `focused` | 学生没有更多问题；教师可再点名或结束问答 |
| 到上限 | `dialogue.ended(reason=max_rounds)` + `classroom.state_changed(trigger=max_rounds)` + 回 `waiting_hands` | 避免停在无法再追问的 `dialogue` |
| 状态机 trigger | 为 `ClassroomStateMachine` 增加 `endDialogue(String trigger, Instant)` 重载；现有 `endDialogue(Instant)` 委托 `"end_dialogue"` | 现有 API 硬编码 trigger，无法产生 `no_follow_up`/`max_rounds`；`domain/classroom/**` 属于 M3B |
| `teacher.end_dialogue` 的 `dialogue_id` | 可选；若提供且无活动对话或不匹配 → `SESSION_STATE_INVALID`，不改变状态；缺省保持兼容 | 防止陈旧 `dialogue_id` 结束新对话 |
| 02 的 `teacher.end_dialogue` 描述 | 不改 02；在 05A/实现文档注明“只结束当前对话，回 waiting_hands”，02 的“结束问答”为待 Foundation Owner 修正的旧描述 | 02 属于 Foundation Owner，按 CLAUDE.md 不直接改 |
| 教师结束 | `dialogue.ended(reason=teacher)` + 回 `waiting_hands` | 05A §5.5 |
| 问答/试讲终止 | 先 `dialogue.ended(reason=qa_ended / qa_restarted / session_ended)`，再 `classroom.qa_ended` / `session.ended` / `session.failed` | 事件只追加，对话必须有终态 |
| 追问文本 | `content.questionOrFallback(contentRequest(student))`；失败用模板 | 复用现有内容服务与 2 s 超时降级 |
| 追问文本落库 | `SessionText(student, llm, follow_up, final)`，`speaker_ref=student_id` | 05A §7.5 / 统一文本流 |
| `student.follow_up` payload | `{student_id, answer_id, text_id}`，不加 `dialogue_id` | 遵循 02 现有契约；`dialogue.started` 提供绑定 |
| 注意力 | 追问学生 +0.05（`applyFollowUp`） | 05A §5.3 事件矩阵 |
| 学生行为 | `answering → dialogue`（追问时）；`dialogue → focused`（结束时） | 05A §5.2 |
| 语音分类 | `onTeacherSpeechClassified` 只在 `lecturing` 处理 | 对话/问答中的教师话语不再产生 `question_suggested` |
| 随机源 | 复用 actor 注入的 `RandomGenerator` | 测试可确定性注入 |

## 5. 状态与事件顺序

### 5.1 初始回答后

```text
teacher.call_on
  → teacher.called_on
  → student.answer {student_id, answer_id, text_id, quality}
  → student.state_changed (focused/called_on → answering)
  → [followUp=false]
      classroom.state_changed (qa.student_answering → qa.waiting_hands, trigger=answer_finished)
      student.state_changed (answering → focused, reason=answer_finished)
  → [followUp=true]
      classroom.state_changed (qa.student_answering → qa.dialogue, trigger=follow_up)
      student.state_changed (answering → dialogue, reason=follow_up)
      dialogue.started {dialogue_id, student_id}
      student.follow_up {student_id, answer_id, text_id}
```

说明：`student.answer` 的 `answer_id` 同时用于后续 `teacher.mark_answer` 和所有
`student.follow_up.answer_id`。

### 5.2 对话中教师 ASR final

```text
教师 ASR final
  → session_texts (teacher, asr, utterance)
  → [dialogue active && awaiting teacher]
      [canFollowUp && shouldFollowUp]
          dialogue 下一轮
          session_texts (student, llm, follow_up)
          student.follow_up {student_id, answer_id, text_id}
          （学生行为已是 dialogue，不再发 state_changed）
      [canFollowUp 但 !shouldFollowUp]
          dialogue.ended {dialogue_id, student_id, reason=no_follow_up}
          classroom.state_changed (qa.dialogue → qa.waiting_hands, trigger=no_follow_up)
          student.state_changed (dialogue → focused, reason=dialogue_ended)
      [!canFollowUp]
          dialogue.ended {dialogue_id, student_id, reason=max_rounds}
          classroom.state_changed (qa.dialogue → qa.waiting_hands, trigger=max_rounds)
          student.state_changed (dialogue → focused, reason=dialogue_ended)
```

### 5.3 结束/重置路径的事件顺序（权威）

统一规则：`endActiveDialogue` 只写 **`dialogue.ended` → 学生 `focused`**，不调用
`classroom.endDialogue`；classroom 状态转移由调用方紧接着追加，因此不会出现多余的
`waiting_hands` 中间状态。

- `teacher.end_dialogue`（`qa.dialogue`）：
  `dialogue.ended(reason=teacher)` → `student.state_changed(dialogue → focused,
  reason=dialogue_ended)` → `classroom.state_changed(qa.dialogue → qa.waiting_hands,
  trigger=end_dialogue)`。
- `teacher.end_dialogue`（`qa.student_answering`）：保留现有行为（结束回答），不产生
  dialogue 事件。
- `teacher.end_qa`：`dialogue.ended(reason=qa_ended)` → 学生 `focused` →
  `classroom.qa_ended` → `classroom.state_changed(qa → lecturing, trigger=end_qa)`。
  没有中间 `waiting_hands` 事件。
- `teacher.ask_class` / `teacher.ask_student_question`：
  `dialogue.ended(reason=qa_restarted)` → 学生 `focused` → `question_asked` →
  `classroom.state_changed(qa → qa.waiting_hands, trigger=manual|voice)`。
- `teacher.end_session` / 超时：`dialogue.ended(reason=session_ended)` → 学生 `focused`
  → `classroom.state_changed(qa → ended, trigger=end_session)` → `session.ended`。
- `fail`：`dialogue.ended(reason=session_ended)` → 学生 `focused` → `session.failed`。
- 非追问路径（学生选择不追问 / 到上限）：`dialogue.ended` → 学生 `focused` →
  `classroom.state_changed(dialogue → waiting_hands, trigger=no_follow_up|max_rounds)`。
- `dialogue.started` 的初始顺序以 §5.1 为准：`classroom.state_changed` →
  `student.state_changed` → `dialogue.started` → `student.follow_up`。

## 6. 组件改动

```text
domain/classroom/
  DialogueState                       （已存在，不改）
  ClassroomStateMachine
    endDialogue(String trigger, Instant now) → 新增重载，允许 no_follow_up / max_rounds
    endDialogue(Instant now)                  → 委托 "end_dialogue"
  VirtualStudentDynamics
    applyFollowUp(UUID)               → 返回 List<ClassroomEvent>：attention +0.05，
                                        behavior → dialogue，发 student.state_changed
    applyDialogueEnded(UUID)          → 返回 List<ClassroomEvent>：behavior → focused，
                                        发 student.state_changed(reason=dialogue_ended)
application/SessionActor
  emitStudentAnswer(...)              → 回答后按概率进入 dialogue 或 waiting_hands
  recordSpeechFinal(...)              → 教师 final 落库后，若 final 起点 >= 等待边界则推进一轮
  onTeacherSpeechClassified(...)      → 仅 lecturing 处理
  handleEndDialogue / handleEndQa /
  handleAsk / endClassroomSession /
  fail                                → 先结束活动对话
  fields: DialogueState dialogue; UUID dialogueAnswerId;
          long dialogueAwaitingSinceMs
```

### 6.1 actor 内部方法（锁定）

```java
private void maybeStartDialogue(VirtualStudentRuntime student, UUID answerId, Instant now);
private void continueDialogueAfterTeacherFinal(String text, Instant now);
private List<SessionEvent> endActiveDialogue(String reason, Instant now);
private List<SessionEvent> emitFollowUp(VirtualStudentRuntime student, Instant now);
```

- `maybeStartDialogue`：`random.nextDouble() < DialogueState.followUpProbability(question_rate)`
  为真时：
  1. 追加 `classroom.answerFinished(studentId, true, now)` 的事件（`state_changed`）；
  2. 调用 `students.applyFollowUp` 并追加其 `student.state_changed`；
  3. 创建 `DialogueState`，追加 `dialogue.started`；
  4. 调用 `emitFollowUp(student, now, false)`（初始轮不重复加注意力）。
- `continueDialogueAfterTeacherFinal(text, startMs, endMs, now)`：
  - 若 `dialogue == null` → 返回；
  - `finalStart = startMs != null ? startMs : endMs != null ? endMs : currentAtMs()`；
  - 若 `finalStart < dialogueAwaitingSinceMs` → 返回（上一段话的 final，只落库）；
  - 若 `!dialogue.canFollowUp()` → `endActiveDialogue("max_rounds")` +
    `classroom.endDialogue("max_rounds", now)`；
  - 若 `!dialogue.shouldFollowUp(question_rate, random)` → `endActiveDialogue("no_follow_up")`
    + `classroom.endDialogue("no_follow_up", now)`；
  - 否则 `dialogue.nextRound()` + `emitFollowUp(student, now, true)`。
- `endActiveDialogue(reason)`：若 `dialogue != null`，写 `dialogue.ended`，调用
  `students.applyDialogueEnded`，清空 `dialogue` / `dialogueAnswerId` /
  `dialogueAwaitingSinceMs`。它**不**改变 classroom 状态机；状态转移由调用方决定
  （`classroom.endDialogue(trigger)` / `endQa` / `askClass` / `endSession`），避免重复
  `classroom.state_changed`。事件顺序统一为 `dialogue.ended` → 学生 `focused` →
  调用方的 `classroom.*` / `session.*` 事件。
- `emitFollowUp(student, now, applyAttention)`：生成文本、落 `SessionText`、必要时
  调用 `students.applyFollowUp`（仅加注意力，状态已是 `dialogue`）、追加
  `student.follow_up`、置 `dialogueAwaitingSinceMs = atMs(now)`。

## 7. 失败与边界

| 场景 | 行为 |
|---|---|
| 内容服务失败/超时 | `questionOrFallback` 返回模板问题；仍写 `student.follow_up`，confidence 0.0 |
| 教师 final 为空/blank | 不推进对话；`recordSpeechFinal` 原本就丢弃 blank |
| 对话中 session 进入终态 | actor 的 `recordSpeechFinal` 在非 in_progress 时直接返回，不产生追问 |
| 同一教师回应多个 ASR final | 以 `dialogueAwaitingSinceMs` 为界：起点早于追问的 final 只落库；起点不早于追问的按顺序决策，若产生新追问则边界前移，后续旧 final 自然被挡 |
| 对话中收到 `teacher.call_on` | 状态机要求 `waiting_hands`，返回 `SESSION_STATE_INVALID`，不改变状态 |
| 对话中 `teacher.end_dialogue` 重复命令 | 第二次 `qaPhase != DIALOGUE` → `SESSION_STATE_INVALID`，不改变状态 |
| 对话中 `teacher.end_qa` | 先 `dialogue.ended(qa_ended)`，再 `classroom.qa_ended` |
| 追问到第 3 轮后教师回应 | `dialogue.ended(max_rounds)`，回 `waiting_hands` |
| 学生不追问 | `dialogue.ended(no_follow_up)`，回 `waiting_hands` |

## 8. 测试与验收（M3-T13）

### 8.1 单元（actor）

- 回答后高概率 → `dialogue.started` + `student.follow_up`，状态 `qa.dialogue`，
  学生行为 `dialogue`，attention +0.05，`session_texts` 有 `kind=follow_up`。
- 回答后低概率 → 维持现有 `answer_finished` 路径，无 dialogue 事件。
- 教师 final 后继续追问 → 第二个 `student.follow_up`，轮次 2。
- 教师 final 后不追问 → `dialogue.ended(no_follow_up)` + `waiting_hands` + 学生 `focused`。
- 第 3 轮后教师 final → `dialogue.ended(max_rounds)`。
- `teacher.end_dialogue` → `dialogue.ended(teacher)` + `waiting_hands`。
- `teacher.end_qa` / `teacher.ask_class` / `teacher.end_session` / `fail` 在有活动对话时
  先写 `dialogue.ended`。
- 对话中 `onTeacherSpeechClassified` 不产生 `question_suggested`。
- `student.follow_up` 的 `answer_id` 等于初始 `student.answer.answer_id`。

### 8.2 集成

- `PerceptionWebSocketIT`（或新增 `MultiTurnDialogueIT`）：真实 control WS 点名 →
  学生回答进入 dialogue → 感知 WS 的 fake ASR 发教师 final → 学生追问事件出现；
  再发教师 final → 不追问时 `dialogue.ended`，session 仍 `in_progress`。
- 既有 `ClassroomWebSocketIT` / `SessionActorTest` / `VirtualStudentDynamicsTest` 全绿。

### 8.3 文档

- 05A §5.5 补充上述事件顺序与自动结束规则；§6.2 不需新增事件类型。
- 实现与证据写入 M3B 证据文件，更新进度看板 M3B 行（不改变 G2–G8 / 10%）。

## 9. 约束

- 只改 M3B 路径：`domain/classroom/{ClassroomStateMachine,VirtualStudentDynamics}`、
  `application/SessionActor`、对应测试、05A/implementation/04、M3B 证据/进度。
- 不新增依赖、不改 `build.gradle.kts` / `application*.yml` / 02 / 12 / M3C。
- 领域层不引入 Spring/Jackson/JPA/HTTP。
- 事件只追加；`answer_id` / `dialogue_id` 绑定清晰；非法命令不改状态。

## 10. 留给实现者的选择

- `endActiveDialogue` 的具体内部拆分与事件追加顺序（只要 §5 顺序不变）。
- 测试 fixture 的组织方式；确定性随机源建议用固定 seed 或常量 `RandomGenerator`。
- 是否在 `student.follow_up` payload 中额外放 `dialogue_id`（本设计选择不放，遵循 02）。
