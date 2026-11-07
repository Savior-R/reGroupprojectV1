# Score Owner — M7 教师评测与统计 启动提示词

> 模板来源：`docs/team/prompts/template.md`　基线：`main`（开始前 `git fetch origin`，以最新 main 为准）　日期：2026-11-07

你是 **Score Owner**，负责 **M7 教师评测与数据统计**。E2E 由独立的 QA Prompt 负责，不在本 Prompt 范围内。先完整阅读本提示词、必读文档和仓库规则，再开始编辑。

## 1. 仓库与分支

- 仓库：`/home/forever_alone/project_school_temp`（GitHub `Savior-R/reGroupprojectV1`）。
- 基线：`main`；开始前 `git fetch origin`，以最新 main 为准。
- 从 `main` 新建 `feat/m7-score`。
- 不得清理 / 提交他人未提交文件；提交使用 `git commit --only <显式路径>`。

## 2. 必读文档

1. `CLAUDE.md`、`docs/team/01-模块归属与修改规则.md`。
2. `docs/requirements/M7-教师评测与数据统计.md`：FR-M7-01—10。
3. `docs/design/07-M7-教师评测与数据统计.md`。
4. `docs/implementation/06-M7-实现设计.md`。
5. `docs/design/02-API与实时协议.md` **V1.7** M7 接口；`01` / `12` M7 表；`10` **V0.5** M7-T01—T11。
6. M4 契约：`design/06`、`implementation/05`；M3 提交 / 录像契约：`05B`。
7. `docs/design/09-前端设计.md` **V0.3** 的评分 / 统计页面。
8. `docs/team/02-进度看板.md` M7 行。

## 3. 允许编辑路径

```text
server/src/main/java/com/shijiangtai/m7teacher/**
server/src/test/java/com/shijiangtai/m7teacher/**
server/src/main/resources/db/migration/V6*__m7_*.sql
frontend/src/views/grading/**
frontend/src/views/stats/**
docs/team/evidence/*m7*
```

不得编辑 M1—M6、`e2e/**`、`shared/**`、`docs/team/01`、owner 表、其他模块测试。发现 M3 / M4 缺陷时，记录场景并交对应 Owner 修复；不得直接改其代码绕过。

## 4. 当前状态与依赖

- 本模块 gate：G1 提交，G2 待复审；尚无 P0 实现。
- **并行开发约定**：M7 可立即开始；G2 评审并行；M4 `EvaluationTerminalEvent` 未完成时用 fixture / 端口；M3 提交 / 录像契约已具备。
- 上游：M4 terminal event、M3 提交件 / 录像 / 事件 / 配置快照、M1 / M2 课程与人员。
- 待冻结契约：M7 评分 / 配置 API、最终分合成、`EvaluationTerminalEvent` 消费、统计 / 排行榜 / CSV、归档只读。
- E2E 验收由 `06-e2e.md` 的 QA 负责人执行；M7 提供 fixture / seed 与缺陷修复。

## 5. 目标与范围

### 5.1 P0 目标

- 任务评分配置：默认 70/30、维度模式、权重和 / 越界 / 模式校验、版本冲突、首次评分锁定。
- 评分名单与状态；教师评分；最终分合成（权重 0、AI pending / partial / failed、教师先评分）。
- 统计 / 薄弱项 / 排行榜（NULLS LAST、班级快照、in_progress 处理）。
- CSV 导出：BOM、转义、表头、10000 行、公式注入防护。
- 学生结果查看；教师组 / 管理员权限；归档只读；保留 / 清理。
- 前端评分 / 统计 / 导出页。

### 5.2 不做

- M4 AI 评测实现、M3 课堂 / 录像实现、M1 / M2 实现。
- 管理员访问 M7 业务；教师查看训练记录；学生申诉 / 复核。
- 自定义评分细则、批量批阅、语音点评（本轮不做）。
- 跨模块 Playwright 场景维护（由 E2E 负责人负责）。
- 完整 UT / IT / 契约 / E2E 测试（本阶段先实现 + smoke，测试后续补齐）。

### 5.3 必须遵守的契约

- `EvaluationTerminalEvent` 是最终分 / 统计唯一触发；partial / failed 不产生总分，但不得永久 pending。
- 学生转班 / 退课使用 `class_id_snapshot`；历史评分保留。
- 课程归档后只读；`end_at` 不阻止评分；任务关闭可继续评分。
- CSV 以 `= + - @` 开头的字段防公式注入；导出上限 10000 行。
- 任何契约变更先改 `02` / `07` / `12` 并让 M3 / M4 / 架构 owner 确认。

## 6. 执行顺序（可并行）

1. 按 `docs/design/02/07/12` 实现 M7 配置 / 最终分 / 统计 / 导出；M4 terminal 用 fixture / 端口并行；G2 不阻塞编码。
2. 迁移 V6 + 领域 / JPA：评分配置、教师评分、最终分。
3. 评分配置 / 名单 / 评分 / 版本冲突 / 首次锁定。
4. 最终分合成、状态优先级、清空重算、终态对账。
5. 统计 / 薄弱项 / 排行榜 / CSV / 学生结果 / 权限 / 归档。
6. 前端评分 / 统计 / 导出页。
7. 模块 smoke + 自测；完整测试后置；提供 E2E 所需 seed / fixture。

## 7. 测试策略（本阶段后置）

- 本阶段最小验证：后端 / 前端编译与 M7 关键路径 smoke。
- 完整测试后置：M7-T01—T11（最终分状态、排行榜 NULL、CSV 注入、权限 403、归档只读）。
- 未跑全量在汇报中写“测试后置”，不得伪造结果。

## 8. 交付物与汇报格式

1. 分支、commit、变更文件；
2. Owner、跨模块情况与确认人；
3. 实现内容、M4 终态消费方式；
4. 编译 / smoke 命令与结果；
5. 证据文件路径、E2E seed / fixture 说明；
6. 未完成测试项 / 阻塞 / 风险；
7. 是否合并 / 推送（默认等用户选择）。

## 9. 反模式

- 不得改 M3 / M4 代码来让统计通过。
- 不得让管理员访问评分 / 统计 / 导出。
- 不得把训练记录纳入 M7。
- 不得把“测试后置”当成“可以不做测试”或伪造结果。
