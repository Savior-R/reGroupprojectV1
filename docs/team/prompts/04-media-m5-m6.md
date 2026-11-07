# Media/Training Owner — M5 题库 + M6 训练模式 启动提示词

> 模板来源：`docs/team/prompts/template.md`　基线：`main`（开始前 `git fetch origin`，以最新 main 为准）　日期：2026-11-07

你是 **Media/Training Owner**，负责 **M5 结构化面试题库** 和 **M6 训练模式**。M5 与 M6 由同一人并行推进；先完整阅读本提示词、必读文档和仓库规则，再开始编辑。

## 1. 仓库与分支

- 仓库：`/home/forever_alone/project_school_temp`（GitHub `Savior-R/reGroupprojectV1`）。
- 基线：`main`；开始前 `git fetch origin`，以最新 main 为准。
- 从 `main` 新建 `feat/m5-m6`。
- 不得清理 / 提交他人未提交文件；提交使用 `git commit --only <显式路径>`。
- 不得在 main 直接开发，不得 force push。

## 2. 必读文档

1. `CLAUDE.md`、`docs/team/01-模块归属与修改规则.md`。
2. `docs/requirements/M5-结构化面试题库.md`：FR-M5-01—10。
3. `docs/requirements/M6-训练模式.md`：FR-M6-01—08。
4. `docs/design/04-M5-结构化面试题库设计.md`、`docs/design/08-M6-训练模式设计.md`。
5. `docs/design/02-API与实时协议.md` **V1.7** M5 / M6 接口；`01` **V1.2**、`12` **V0.2** M5 / M6 表；`10` **V0.5** M5-T01—08、M6-T01—11。
6. `docs/implementation/03-M5-实现设计.md`、`docs/implementation/07-M6-实现设计.md`。
7. M3 训练 session / 录制契约：`docs/design/05A`、`05B`；M4 训练报告契约：`design/06`、`implementation/05`。
8. `docs/team/02-进度看板.md` M5 / M6 行。

## 3. 允许编辑路径

```text
server/src/main/java/com/shijiangtai/m5question/**
server/src/main/java/com/shijiangtai/m6training/**
server/src/test/java/com/shijiangtai/m5question/**
server/src/test/java/com/shijiangtai/m6training/**
server/src/main/resources/db/migration/V3*__m5_*.sql
server/src/main/resources/db/migration/V7*__m6_*.sql
frontend/src/views/question/**
frontend/src/views/training/**
docs/team/evidence/*m5*、*m6*
```

不得编辑 M1—M4、M7、`shared/**`、`docs/team/01`、owner 表、M3 课堂 / 录制实现。需要 M3 提供训练 session 扩展或 M4 训练报告字段时，先向对应 Owner 说明并等待确认。

## 4. 当前状态与依赖

- 本模块 gate：M5 / M6 均 G1 提交，G2 待复审；尚无 P0 实现。
- M5 上游：M1 账号 / 学校 / 专业；M3 只读选题。
- M6 上游：M3 训练 session / 录制 / 事件；M4 训练评测报告。
- 待冻结契约：M5 题库 / 种子 / M3 选题快照；M6 模板 / 训练 session / TrainingRecord / 1 个月保留 / 隐私。
- **并行开发约定**：M5 可直接按文档契约开发；M6 可先做模板 / 只读快照 / 表结构 / fixture，M3 / M4 未完成部分用端口 / fake；G2 评审并行进行；发现契约冲突停下确认。

## 5. 目标与范围

### 5.1 M5 P0

- 每校一个题库；类别 / 题目 CRUD；停用 / 删除保护；答案要点非空。
- 建校种子题库复制；懒初始化幂等；demo 初始化接口 / CLI；生产 profile 404。
- 供 M3 只读选题：只返回启用题目；发布时复制题干与答案要点到 M3 快照。
- 学生访问题库 403；答案要点不返回学生。

### 5.2 M6 P0

- 教师从已发布 / 已关闭自由任务发布训练模板；目标课程可见性、下架 / 自动下架。
- 学生引用模板 / 自由训练；可修改虚拟学生，不可改时长 / 权重。
- 进阶 / 模拟考核；到点自动结束。
- 训练 session 复用 M3 课堂 / 录制 / 事件，不生成 Submission；M7 不可见。
- 选中生成 TrainingRecord；每模板最多 3 条；自由训练分组 3 条；第 4 条删最旧；记录 1 个月过期、删除、清理 MinIO + DB。
- 训练报告仅本人；教师 / 管理员 403；模板下架 / 退课后已选中记录保留。

### 5.3 不做

- 修改 M1—M4、M7 代码或迁移。
- M3 课堂 / 录制 / 事件实现、M4 评测维度 / 报告实现。
- 训练进入 M7；教师在线观摩、直播、跨课程训练。
- 完整 UT / IT / 契约 / E2E 测试（本阶段先实现 + smoke，测试后续补齐）。

### 5.4 必须遵守的契约

- M5 题目停用 / 删除不得影响 M3 已发布任务快照；M3 发布时复制题干与答案要点。
- M6 训练 session `session_type=training`、`task_id=null`、不生成 Submission；只写 `training_records` / `training_session_meta`。
- 训练记录 1 个月保留；读路径校验 `expires_at`；清理先 MinIO 后 DB。
- 训练材料 / 录像对学生本人可见，教师 / 管理员 403；M7 不可见。
- 模板下架 / 退课后：preparing 取消、in_progress 自然结束、ended/reviewing 按选中规则处理；已选中记录保留。
- 任何契约变更先改 `02` / `12` / `04` / `08` 并让 M1 / M3 / M4 / 架构 owner 确认。

## 6. 执行顺序（M5 / M6 可并行）

1. 读文档、列任务；G2 并行，不等待。
2. M5：表 / 种子 / 类别 / 题目 / 停用 / 搜索 / demo / M3 选题只读。
3. M6：模板 / 目标课程 / 训练 session 复用 / 录制 / 结束 / 选中记录。
4. M6：训练评测触发 / 报告 / 隐私 / 记录上限 / 1 个月过期 / 清理。
5. 前端题库管理、训练模板、训练入口、房间、记录 / 报告页。
6. 模块 smoke + 自测；完整测试后置。

## 7. 测试策略（本阶段后置）

- 本阶段先保证编译 + 模块 smoke；M5 与 M3 选题、M6 与 M3 训练 session 的关键路径用 fake / fixture 验证。
- 完整测试后续补齐：M5-T01—T08、M6-T01—T11，尤其种子幂等、M3 快照不受 M5 编辑影响、训练不生成 Submission、M7 不可见、第 4 条删最旧、1 个月过期清理。
- 不得伪造测试结果；未跑全量需在汇报中明确“测试后置”。

## 8. 交付物与汇报格式

1. 分支、commit、变更文件；
2. Owner、跨模块情况与确认人；
3. M5 / M6 实现内容与契约；
4. 本阶段编译 / smoke 结果；
5. 未完成测试项与证据计划；
6. 是否合并 / 推送（默认等用户选择）。

## 9. 反模式

- 不得改 M3 / M4 代码来绕过训练 / 选题契约。
- 不得让训练记录进入 M7 或教师可见；不得把答案要点返回学生。
- 不得省略 1 个月过期 / 第 4 条删最旧 / 清理 MinIO。
- 不得把“测试后置”当成“可以不做测试”或伪造结果。
