# 2026-11-21 M3 对话与教师意图分类第一版设计

> Owner：当前使用者（架构 owner）　范围：M3B 契约文档　状态：文档冻结，代码实现待续

## 1. 背景

M3B 现有的“多轮对话”是学生追问驱动：学生回答后按 `question_rate * 0.5` 掷骰进入
`qa.dialogue`，教师 ASR final 只决定学生是否继续追问（见历史
[2026-11-05-m3b-multiturn-dialogue](2026-11-05-m3b-multiturn-dialogue.md)）。这不符合
“教师点名后一问一答”和“教师追问 → 学生回答”的目标，也不支持教师话语的多意图识别。

本次先冻结第一版契约，再实施代码。

## 2. 已确认的产品决策

| 编号 | 决策 |
|---|---|
| 1 | 「坐下 / 请坐」同时结束整个问答：`dialogue.ended(sit_down)` → `classroom.qa_ended(sit_down)` → `lecturing`，不经过 `waiting_hands` |
| 2 | 不做自动标记；`comment_answer` 只写 `teacher.reply`，教师手动 `teacher.mark_answer` 仍是唯一权威 |
| 3 | 点名即进入对话。教师发起方向由教师追问驱动；学生发起方向由学生追问驱动 |
| 4 | 第一版教师意图分类用 Java 规则（9 类多标签）；本地训练模型下一版接入，不阻塞对话逻辑 |
| 5 | 学生自主举手本轮不做；只预留 `student.hand_raised.reason=spontaneous` 与配置开关 |
| 6 | 训练 / 评估数据后续按“真实 ASR + 按钮弱标注 + 人工 golden set + 合成增强”获取 |

## 3. 第一版契约摘要

### 3.1 状态与事件

- `dialogue.started {dialogue_id, student_id, initiator}`；`initiator=teacher|student`。
- `dialogue.ended {dialogue_id, student_id, reason, rounds}`；新增 `reason=sit_down`。
- `student.answer / student.question / student.follow_up` 增加可选 `dialogue_id` / `turn`。
- 新增 `teacher.reply {dialogue_id, turn, text_id, intent, confidence, student_name?}`。
- 新增 `teacher.intent_classified {text_id, primary_intent, confidence, intents[], student_name?, polarity?, model_version}`，record-only。
- 首轮事件顺序：`teacher.called_on` → `student.state_changed(called_on)` →
  `dialogue.started` → `classroom.state_changed(... → dialogue, trigger=call_on)` →
  `student.answer/question(turn=1)` → `student.state_changed(... → dialogue)`。

### 3.2 两个方向

- 教师发起：首答后 `phase=awaiting_teacher`；教师追问 → `student.answer`（round+1）；
  评论 → 只写 `teacher.reply`；坐下 → 结束整个问答。
- 学生发起：首问后 `phase=awaiting_teacher`；教师回答 → `teacher.reply` +
  `phase=awaiting_student`；学生按 `question_rate * 0.5` 追问或 `no_follow_up`。
- 最多 3 轮；不做自动超时 / 自动评价 / 自动标记。

### 3.3 教师意图规则

- 标签：`ask_class` / `ask_student` / `call_on` / `prompt_student_question` /
  `manage_discipline` / `comment_answer` / `end_dialogue` / `end_qa` / `other`。
- 多标签；姓名只与任务快照名单精确匹配，歧义不自动点名。
- 高风险动作（点名、坐下）需要高置信 + 姓名唯一匹配；低置信只写
  `teacher.intent_classified`。
- 端口：`TeacherIntentClassifier`；第一版 `RuleBasedTeacherIntentClassifier`，
  本地模型下一版替换。数据方案见 05A §7.7。

## 4. 本次修改的文档

| 文档 | 修改 |
|---|---|
| `docs/requirements/M3-虚拟课堂与录制.md` | 版本 V1.3；新增 FR-M3-17 / 18 / 19，更新 A29—A31 与 §12.1 |
| `docs/design/05A-M3-课堂状态机与WebSocket.md` | 版本 V1.2；状态机、`qa.dialogue` 两个方向、事件表、§7.6 规则 / §7.7 数据、测试与状态图 |
| `docs/design/02-API与实时协议.md` | 版本 V2.0；`teacher.reply` / `teacher.intent_classified`、dialogue / student 事件字段、`end_dialogue` 语义 |
| `docs/implementation/04-M3-实现设计.md` | 版本 V0.4；新增领域类、actor 方法、4.3 流程与测试点 |
| `docs/design/09-前端设计.md` | 版本 V0.7；对话回合展示与低置信提示 |
| `docs/design/10-测试与验收.md` | 版本 V0.8；新增 M3-T28—T31 |
| `docs/design/12-数据库设计.md` | 版本 V0.6；第一版不新增表，意图写 events |
| `docs/design/00-总体设计.md` / `13` / `14` | 教师意图第一版 Java 规则、本地模型后续 |
| `docs/README.md` / `docs/implementation/README.md` / `docs/team/02-进度看板.md` | 版本索引与进度记录 |
| `docs/superpowers/specs/2026-11-05-m3b-multiturn-dialogue-design.md` / `plans/2026-11-05-m3b-multiturn-dialogue.md` | 标记为已被 05A V1.2 取代 |
| `ai/training/teacher_intent/README.md` / `ai/models/teacher_intent/v1/README.md` / `ai/README.md` | 数据管线、模型产物、启用方式与限制说明 |

## 5. 代码实现（2026-11-21）

- 领域：新增 `TeacherIntent` / `TeacherIntentHit` / `TeacherUtteranceIntent` /
  `TeacherUtteranceContext` / `TeacherIntentClassifier` / `RuleBasedTeacherIntentClassifier`；
  `DialogueState` 增加 `initiator` / `phase` / `awaitingSinceMs`；
  `ClassroomStateMachine` 增加 `beginDialogue` 与 `endQa(reason, trigger, now)`。
- Actor：点名即创建对话并生成首条回答 / 提问；教师发起方向由教师追问驱动；
  学生发起方向由学生追问驱动；`sit_down` → `qa_ended` → `lecturing`；
  每条教师 final 写 `teacher.intent_classified`，实际回合写 `teacher.reply`；
  不做自动标记；迟到 final 按 `dialogue.awaitingSinceMs` 丢弃。
- 前端：`teacher.reply` / `teacher.intent_classified` 加入事件类型与回放标签；
  reducer 对二者不做状态变更；对话文本仍由 `session_texts` 渲染。
- 测试基础设施修复（随第一版一并）：
  - `ContextPersistenceWriterIT` / `JpaSessionBatchStoreTransactionIT` 的任务 fixture
    从默认 `draft` 改为 `published`，对齐 V17 `ck_tasks_status`；
  - `ArchitectureTest` 增加 `ExcludeGradleTestOutput`，避免把 Gradle
    `/classes/java/test/` 输出当成生产类；
  - `GlobalExceptionHandlerTest` 旧断言 `TASK_NOT_DRAFT` 改为现行 `TASK_CLOSED`。

## 6. 验证

### 6.1 后端

- `m3classroom.domain.*` + `m3classroom.application.*`：**465 tests / 0 failure / 0 error**。
- 第一版定向类（`ClassroomStateMachineTest`、`RuleBasedTeacherIntentClassifierTest`、
  `SessionActorDialogueTest`、`SessionActorTest`、`SessionActorPerceptionTest`）：
  **95 tests / 0 failure**。
- 全量 `m3classroom.*Test`（含非 IT 的 api / contract / ArchUnit）：
  **543 tests / 0 failure / 0 error**（配置测试 DB URL 后）。
  - `GlobalExceptionHandlerTest` 旧断言 `TASK_NOT_DRAFT` 已改为现行 `TASK_CLOSED`；
  - `RecordingWireContractTest` 在配置测试 DB URL 后通过；
  - `ArchitectureTest` 排除 Gradle 测试输出后 3 条规则通过。
- M3 集成测试（本地便携 PostgreSQL 16.4 + `application-test.yml`）：
  **155 tests / 0 failure / 0 error**（含 `ArchitectureTest` 3 条）；关键链路：
  - `ClassroomWebSocketIT` 16/16、`WsHeartbeatIT` 2/2、`PerceptionWebSocketIT` 24/24、
    `PerceptionScreenIT` 5/5、`RecordingControllerIT` 5/5、`ReplayControllerIT` 6/6、
    `SubmissionControllerIT` 5/5；
  - 原 `ContextPersistenceWriterIT`（2）+ `JpaSessionBatchStoreTransactionIT`（1）的
    `ck_tasks_status` 失败已修复：测试 fixture 从默认 `draft` 改为 `published`，与 V17 契约一致；
  - `ArchitectureTest` 原先把 Gradle 的 `/classes/java/test/` 测试输出当作生产代码分析；
    新增 `ExcludeGradleTestOutput` import option 后规则只扫描生产类，3 条规则通过。
- `PerceptionWebSocketIT` 的 `teacherVoiceFollowUpDrivesAnswersAndSitDownEndsQa` 已在实库 +
  真实 HTTP/WS 链路通过，验证 `teacher.reply`、`student.answer` 轮次、`sit_down` → `lecturing`。

### 6.2 前端

- `npm test -- --run`：**18 files / 143 tests passed**。
- `npm run build`：通过（vue-tsc + vite）。

### 6.3 环境说明

- 本环境未预装 JDK / Gradle / PostgreSQL；使用 `/tmp/toolchain` 临时 JDK 21 +
  Gradle 8.14.3、`/tmp/pg` 便携 PostgreSQL 16.4；依赖通过阿里云镜像下载；构建输出
  重定向到 `/tmp/m3-build`，避免覆盖工作区既有 Windows 锁定构建目录。
- 工作区 `m4eval/.../RedisEvaluationQueue.java` 曾出现与本任务无关的语法损坏
  （类名中混入空格）；该文件随后已恢复到 HEAD 内容。最终全项目 `compileTestJava`
  在没有排除 M4 / M6 的情况下通过。
- M3 测试使用 `SPRING_DATASOURCE_URL` 指向本地 PostgreSQL；M3 IT 的 155 条测试运行在
  该实例上。

## 7. 本地教师意图模型与数据管线

### 7.1 训练 / 导出

- 目录：`ai/training/teacher_intent/`
  - `labels.py`：9 类多标签、阈值、优先级、模型格式版本；
  - `text.py`：姓名替换 `某同学` + 去空白 / 标点；Java 使用同一规则；
  - `seed_data.py`：**279 条合成种子**（模板生成，仅用于打通管线）；
  - `export_weak_labels.py`：从 `m3.session_texts` + `m3.events` 导出
    `teacher.reply` 动作标签与按钮事件弱标签；事件按“最近前一条教师文本”归属；
  - `annotate.py` / `merge_annotations.py`：生成 UTF-8 BOM CSV 标注表并合并回 JSONL；
  - `train.py`：字符 1–3 gram TF-IDF + 每标签独立 logistic regression；
    分组切分、per-label P/R/F1、macro F1、JSON 导出；
  - `infer.py`：纯 Python 参考推理，与 Java 使用同一 normalize / mask / TF-IDF / sigmoid；
  - `requirements.txt`：numpy / scikit-learn / psycopg，仅训练环境使用。
- 产物：`ai/models/teacher_intent/v1/model.json`（80.5 KiB）+
  `metrics.json`；模型不含真实姓名，训练时统一替换为 `某同学`。
- 合成测试集结果：macro F1 0.78 @0.5；**只证明训练 / 导出 / 推理链路可用，不代表真实课堂效果**；
  `end_qa` 等稀有标签在种子数据上召回不足。

### 7.2 Java 本地推理与混合

- `LinearTeacherIntentClassifier`（infrastructure/perception）：
  - 纯 Java 加载 JSON，字符 n-gram TF-IDF + 稀疏点积 + sigmoid；
  - 姓名掩码、低于 0.25 的标签不进入 hit 列表；
  - 无 Python / numpy / 网络依赖，毫秒级。
- `HybridTeacherIntentClassifier`（domain/classroom）：
  - 模型与规则按标签取最大置信度；
  - `student_name` / `polarity` 仍来自规则；
  - 模型无有效 hit 时保留规则结果。
- `M3Config.teacherIntentClassifier`：
  - `m3.classroom.teacher-intent.mode=off|shadow|active`，默认 `off`；
  - `shadow`：`ShadowTeacherIntentClassifier` 只记录模型预测，规则继续驱动课堂；
  - `active`：Hybrid 混合；`active-labels` 默认只开放提问 / 评论等低风险标签，
    `call_on` / `end_*` / `manage_discipline` 继续走规则；
  - 模型路径为空 / 加载失败 → 规则 + WARN；
  - `SessionRuntimeRegistry` 生产构造器注入该端口，测试构造器默认规则。
- 默认不启用模型，不改变现有课堂行为；模型只影响意图，不改变事件契约 / DB。

### 7.3 验证

- Java 新增：`LinearTeacherIntentClassifierTest`（3）、`HybridTeacherIntentClassifierTest`（3）、
  `LinearTeacherIntentModelArtifactTest`（2）、`M3ConfigTeacherIntentTest`（6）、
  `SessionActorPerceptionTest.activeLocalModelCanOpenVoiceQaWhileLecturing`（1）；
  真实产物加载 + 典型话语预测 + Java/Python 分数 `1e-4` 内一致 + off/shadow/active /
  active-labels 回退 + 讲解态本地语音提问覆盖。
- Python 新增 `ai/tests/training/test_teacher_intent_pipeline.py`（6 条）：
  标签覆盖、姓名掩码、弱标注归属、标注表 / 合并往返、导出模型与 sklearn 一致、产物可加载。
- `export_weak_labels.py` 已在本地 PostgreSQL 空库上完成 SQL smoke（0 records），
  确认表 / 列名与查询可用；真实弱标注需在有课例数据的环境导出。
- M3 非 IT **558 tests / 0 failure**；M3 IT **155 tests / 0 failure**（关键 WS IT 复跑通过）；
  AI 套件 **67 passed / 3 skipped**（清空 provider key 后运行）。

## 8. 未做 / 后续

- 未改数据库表；无 Flyway 迁移。
- 真实 ASR golden set、人工标注、影子模式对照、自动评价 / 自动标记、学生自主举手均属后续。
- 合成种子模型不能作为上线依据；需要 300–500 条真实人工标注后再评估是否启用。
- 后续可升级为冻结中文句向量 / 小 Transformer；更大模型再考虑 Python 推理服务。
- 前端 / 后端完整待办见 [进度看板](../02-进度看板.md) §8（2026-11-25 增补）。

## 9. 链接检查

本次文档修改后对 `docs/**/*.md` 做了相对链接存在性检查：缺失 0。
