# 2026-11-05 M3B 多轮对话（初始追问 / 教师语音推进 / 终止）证据

> 分支：`feat/m3a-task-config`（提交主题 `[M3B] ...`）
> 计划：`docs/superpowers/plans/2026-11-05-m3b-multiturn-dialogue.md`（Task 1—6）
> 设计：`docs/superpowers/specs/2026-11-05-m3b-multiturn-dialogue-design.md`（2026-11-05 用户批准）
> 执行人：M3 Owner（当前使用者）
> 环境：Docker Compose / PostgreSQL / Redis / MinIO / Spring Boot 4.1.2 / Java 21
> 结论：M3B 多轮对话后端 P0 完成：初始回答追问掷骰、教师 ASR final 按
> `dialogueAwaitingSinceMs` 推进、`no_follow_up` / `max_rounds` 自动终止、
> `teacher.end_dialogue` 的 `dialogue_id` 校验、对话中分类忽略均通过测试。
> G2 未关闭，模块完成度保持 10%。

## 1. 范围与授权

本次切片只包含 M3B 多轮对话后端：

- 初始回答后按 `p_follow_up = question_rate * 0.5` 决定是否进入 `qa.dialogue`，
  事件顺序 `classroom.state_changed` → `student.state_changed` →
  `dialogue.started` → `student.follow_up`。
- 教师 ASR final 落库后，仅当 `finalStart >= dialogueAwaitingSinceMs` 时推进一轮；
  早于边界的 final 只落库（同一句被 ASR 切成多个 final 时不重复追问）。
- 最多 3 轮；未命中追问 → `dialogue.ended(no_follow_up)` + `qa.waiting_hands`；
  到上限 → `dialogue.ended(max_rounds)` + `qa.waiting_hands`。
- 所有终止 / 重置路径统一 `dialogue.ended` → 学生 `focused` → 调用方领域事件
  （`teacher.end_dialogue` / `end_qa` / `ask_class` / `ask_student_question` /
  `end_session` / 超时 / `fail`）。
- `teacher.end_dialogue` 可选 `dialogue_id`：提供且不匹配 / 无活动对话时返回
  `SESSION_STATE_INVALID`，不改变状态。
- 教师话语分类只在 `lecturing` 处理；`qa`（含 `dialogue`）中的教师 final 不产生
  `classroom.question_asked` / `question_suggested`。
- `SessionRuntimeRegistry` 增加 `Supplier<RandomGenerator>` 测试接缝；生产构造器
  继续使用 `Random::new`，行为不变。
- 感知驱动的端到端 IT、设计 / 实现文档、证据与进度。

不做（本轮显式 deferred）：

- 前端课堂 / 面试 UI、控制 WS 的对话命令交互、M3C 录制 / 回放、E2E、NFR /
  性能 / 安全验收。
- 对话内容的真实 LLM 联调；本轮使用内容兜底模板与 fake ASR / 分类器。

文件归属：生产 / 测试路径属于 M3 Owner 的 M3B 后端范围（`application/Session*`、
`domain/classroom/**`、`ws/**`）；未修改 M3C、M4、前端或其他模块代码。
`docs/team/02-进度看板.md` 只改 M3B 行并新增一条 §5 记录。`02` 的
`teacher.end_dialogue` 旧描述属 Foundation Owner，按 CLAUDE.md 未直接修改，只在
05A / 实现文档注明差异。

## 2. 实现摘要

| 切片 | 实现 |
|---|---|
| 状态机触发 | `ClassroomStateMachine.endDialogue(String trigger, Instant now)`；`TRIGGER_END_DIALOGUE` / `TRIGGER_NO_FOLLOW_UP` / `TRIGGER_MAX_ROUNDS`；`endDialogue(Instant)` 委托 `end_dialogue` |
| 学生动态 | `VirtualStudentDynamics.applyFollowUp(UUID)` 返回 `student.state_changed(answering → dialogue)` 事件；`applyDialogueEnded(UUID)` 返回 `dialogue → focused`（reason=`dialogue_ended`） |
| 初始追问 | `SessionActor.emitStudentAnswer` 按 `DialogueState.followUpProbability(question_rate)` 掷骰；命中时 `maybeStartDialogue` 写 `dialogue.started` + `student.follow_up`，并把对话事件放进 `CommandResult.events()` |
| 教师 final 推进 | `continueDialogueAfterTeacherFinal`：`finalStart = start_ms ?: end_ms ?: currentAtMs()`；`< dialogueAwaitingSinceMs` 只落库；`canFollowUp` / `shouldFollowUp` 决定下一轮或终止；每次追问前移边界 |
| 终止 | `endActiveDialogue(reason)` 统一写 `dialogue.ended` → 学生 `focused`，清空 `dialogue` / `dialogueAnswerId` / `dialogueAwaitingSinceMs`；调用方追加 `classroom.endDialogue(trigger)` |
| `dialogue_id` 校验 | `requireMatchingDialogueId` 无条件校验（含 `student_answering`）；stale id 返回 `SESSION_STATE_INVALID`，不改变状态 |
| 分类守卫 | `onTeacherSpeechClassified` 仅在 `ClassroomMacroState.LECTURING` 处理；`qa` 中的 final 只落库 |
| 测试接缝 | `SessionRuntimeRegistry(..., ContentClient, Supplier<RandomGenerator>)`；生产构造器 `@Autowired` 并委托 `Random::new`；`buildActor` 用 `randomFactory.get()` |
| IT | `PerceptionWebSocketIT` 的 `@TestConfiguration` 提供 `@Primary` registry + 共享 `MutableRandom`；新增 `teacherVoiceResponseDrivesFollowUpRoundsAndEndsWithoutFollowUp` |

## 3. TDD RED / GREEN 证据

所有命令均在仓库根目录执行，测试运行在 `docker compose --profile test` 容器内。

| Task | 提交 | 测试类（用例数） | RED | GREEN |
|---|---|---|---|---|
| 1 状态机 trigger | `f54425d` | `ClassroomStateMachineTest`（27） | 新增 3 条 trigger 用例编译 / 断言失败 | 27/27 |
| 2 学生动态对话转移 | `22d5f38` | `VirtualStudentDynamicsTest`（21） | 新增用例 RED（`applyFollowUp` 无返回值 / 无 `applyDialogueEnded`） | 21/21；`ClassroomStateMachineTest` 27/27 回归 |
| 3 初始回答 → 对话 | `b9f42eb` / `c440173` | `SessionActorDialogueTest`（4） | 4 条新增用例失败（无 `dialogue.started` / `student.follow_up`） | 4/4；`SessionActorTest` 30/30；当轮全量 67 classes / 498 tests |
| 4 教师 final 推进 | `89b97c1` | `SessionActorDialogueTest`（9） | 5 条新增用例失败（无第二轮 / 无边界 / 无 max_rounds 终止） | 9/9；`SessionActorTest` 30/30、`ClassroomStateMachineTest` 27/27 |
| 5 终止路径 + id 校验 | `b0e0db8` / `54183aa` | `SessionActorDialogueTest`（16） | 7 条新增用例失败（终止路径未清空对话、stale id 未拒绝） | 16/16；`SessionActorTest` 30/30、`ClassroomStateMachineTest` 27/27 |
| 6 感知 IT + 文档 | 本次提交 | `PerceptionWebSocketIT`（24，新增 1） | 去掉 registry 接缝后测试编译失败：`constructor SessionRuntimeRegistry ... cannot be applied to given types` | 24/24 |

Task 6 的 RED / GREEN 细节：

- RED（未加 registry 接缝时）：

  ```text
  /workspace/src/test/java/com/shijiangtai/m3classroom/ws/PerceptionWebSocketIT.java:201:
  error: constructor SessionRuntimeRegistry in class SessionRuntimeRegistry cannot be applied
  to given types;
    required: SessionRepository,TaskRepository,SessionEventStore,SessionTextStore,
              IdGenerator,ClockProvider,SchedulerPort,ContentClient
    found:    ... ,ContentClient,()->mutableRandom
  BUILD FAILED
  ```

- 首次 GREEN 运行暴露并修复两处测试自身问题（生产代码未改）：
  1. DB 回读的事件 payload 经 JSON 往返后 `student_id` / `hand_raise_id` 为 String，
     不能直接强转 `UUID`；改为 `UUID.fromString(String.valueOf(...))`。
  2. `classroom.state_changed` 的 `to` 是宏观状态 `qa`，`qa_phase` 才是
     `dialogue` / `waiting_hands`；断言改为 `to=qa` + `qa_phase` + `trigger`。
- 最终 GREEN：`PerceptionWebSocketIT` tests=24, failures=0, errors=0, skipped=0。

## 4. 测试计数

最终全量命令：

```bash
docker compose --profile test run --rm test gradle clean test --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL in 1m 17s`；XML 汇总 **67 个测试类 / 511 条用例，
0 failure / 0 error / 0 skipped**。

口径拆分：

- M3B 提交范围：**64 个测试类 / 498 条用例**（相对上一基线 63 类 / 477 条新增
  **1 个类 / 21 条用例**）。
- 同一工作区包含暂停中的 M3C 未跟踪测试 3 个类 / 13 条用例
  （`RecordingTest` 8、`RecordingObjectKeyBuilderTest` 2、`RequiredStreamsTest` 3），
  合计 67 类 / 511 条；M3C 文件不进入本次提交。
- 本切片新增 / 扩充：
  - 新增 `SessionActorDialogueTest` 16 条（初始追问、教师 final 边界、max_rounds、
    `no_follow_up`、分类忽略、全部终止路径、`dialogue_id` 校验）。
  - `ClassroomStateMachineTest` +3（trigger 重载 / `no_follow_up` / `max_rounds`）。
  - `VirtualStudentDynamicsTest` +2（`applyFollowUp` 事件 + `applyDialogueEnded`）。
  - `PerceptionWebSocketIT` +1（感知驱动的多轮对话 IT）。

## 5. IT 场景与断言

`PerceptionWebSocketIT.teacherVoiceResponseDrivesFollowUpRoundsAndEndsWithoutFollowUp`：

1. `@TestConfiguration` 提供共享 `MutableRandom(0.0)`；`@Primary` registry 的工厂
   `() -> mutableRandom` 让 actor、学生动态和追问掷骰使用同一个确定性生成器。
2. 创建 / 发布 free 任务（`question_rate=0.5`，`p_follow_up=0.25`），创建并开始
   session；打开控制 WS 并消费 `session.snapshot`。
3. actor 执行 `teacher.ask_class`；`MutableRandom.nextLong()==0` 使举手延迟固定 1 s；
   等待 `student.hand_raised` 后执行 `teacher.call_on`。
4. `0.0 < 0.25` 命中：断言 `student.follow_up` 与
   `classroom.state_changed {to=qa, qa_phase=dialogue, trigger=follow_up}`。
5. 申请感知 ticket，连接感知 WS，`audio.start`；fake ASR 发送教师 final，
   `begin_ms = firstFollowUp.at_ms + 1000`（感知层再加上 `audio.start` 的 session
   时间），断言第二个 `student.follow_up`（round 2）、期间无 `dialogue.ended`、
   教师 final 已落库。
6. `mutableRandom.set(0.99)`；再发教师 final（`begin_ms = secondFollowUp.at_ms + 1000`）。
7. 断言：
   - `dialogue.ended {reason=no_follow_up}`；
   - 紧随的 `classroom.state_changed {to=qa, qa_phase=waiting_hands,
     trigger=no_follow_up}`；
   - `student.state_changed {to=focused, reason=dialogue_ended}`；
   - session 仍为 `in_progress`；控制 WS 与感知 WS 均保持打开；fake ASR 回调无异常。

事件序列（同一 session，`seq` 递增）：

```text
teacher.ask_class            → classroom.question_asked {intent=answer, source=manual}
                             → classroom.state_changed {to=qa, qa_phase=waiting_hands, trigger=manual}
student.hand_raised
teacher.call_on              → student.answer {student_id, answer_id, text_id, quality}
                             → classroom.state_changed {to=qa, qa_phase=dialogue, trigger=follow_up}
                             → student.state_changed {to=dialogue, reason=follow_up}
                             → dialogue.started {dialogue_id, student_id}
                             → student.follow_up {student_id, answer_id, text_id}   # round 1
asr.final (begin >= boundary)→ session_texts(teacher, asr, utterance)
                             → student.follow_up ...                               # round 2
asr.final + random=0.99      → dialogue.ended {dialogue_id, student_id, reason=no_follow_up}
                             → student.state_changed {to=focused, reason=dialogue_ended}
                             → classroom.state_changed {to=qa, qa_phase=waiting_hands, trigger=no_follow_up}
session.status               → in_progress
```

## 6. 未覆盖 / 后续

- 前端课堂 UI（对话气泡 / 结束对话按钮）、控制 WS 命令驱动的多轮对话交互：未开始。
- M3C 录制 / 回放依赖 `required_streams`；录制 / 回放前端与 E2E 未开始。
- E2E、性能 / 安全 / NFR、真实 LLM 内容联调：未开始，G2—G8 均未标记。
- 已知非阻塞缺口（沿用前序 review 结论）：无 null `dialogue_id` 显式用例、无直接断言
  “终止后没有多余 `waiting_hands` 中间态”、无重复 `end_dialogue` 用例；不影响本轮 P0
  契约。
- 02 的 `teacher.end_dialogue` 旧描述待 Foundation Owner 修正；05A / 实现文档已注明。

## 7. 本次变更文件

生产：

- `server/src/main/java/com/shijiangtai/m3classroom/application/SessionRuntimeRegistry.java`
  （`Supplier<RandomGenerator>` 接缝；生产构造器 `@Autowired` + `Random::new`）

测试：

- `server/src/test/java/com/shijiangtai/m3classroom/ws/PerceptionWebSocketIT.java`
  （`@Primary` 测试 registry + 共享 `MutableRandom` + 多轮对话 IT）
- `server/src/test/java/com/shijiangtai/m3classroom/support/MutableRandom.java`
  （Task 3 已创建，本任务复用）

文档：

- `docs/design/05A-M3-课堂状态机与WebSocket.md`（§4.4 / §5.5 / §6.2 / §14：
  多轮事件顺序、`dialogueAwaitingSinceMs` 边界、`no_follow_up` / `max_rounds`、
  终止原因、02 旧描述说明）
- `docs/implementation/04-M3-实现设计.md`（§3.2 / §3.3 / §4.3 / §7：registry 接缝、
  `DialogueState`、actor 对话方法、`endDialogue(String, Instant)`）
- `docs/team/evidence/2026-11-05-m3b-multiturn-dialogue.md`
- `docs/team/02-进度看板.md`（仅 M3B 行 + 一条 §5 记录，完成度保持 10%）
