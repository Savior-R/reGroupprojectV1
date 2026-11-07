# Score/QA Owner — M7 教师评测与统计 + E2E 启动提示词

> 模板来源：`docs/team/prompts/template.md`　基线：`main`（开始前 `git fetch origin`，以最新 main 为准）　日期：2026-11-07

你是 **Score/QA Owner**，负责 **M7 教师评测与数据统计** 以及 **跨模块 E2E / Playwright 验收**。先完整阅读本提示词、必读文档和仓库规则，再开始编辑。

## 1. 仓库与分支

- 仓库：`/home/forever_alone/project_school_temp`（GitHub `Savior-R/reGroupprojectV1`）。
- 基线：`main`；开始前 `git fetch origin`，以最新 main 为准。
- 从 `main` 新建 `feat/m7-e2e`。
- 不得清理 / 提交他人未提交文件；提交使用 `git commit --only <显式路径>`。

## 2. 必读文档

1. `CLAUDE.md`、`docs/team/01-模块归属与修改规则.md`。
2. `docs/requirements/M7-教师评测与数据统计.md`：FR-M7-01—10。
3. `docs/design/07-M7-教师评测与数据统计.md`。
4. `docs/implementation/06-M7-实现设计.md`。
5. `docs/design/02-API与实时协议.md` **V1.7** M7 接口；`01` / `12` M7 表；`10` **V0.5** M7-T01—T11、E2E-01—06。
6. M4 契约：`design/06`、`implementation/05`；M3 提交 / 录像契约：`05B`。
7. `docs/design/09-前端设计.md` **V0.3**、`docs/design/10` 的 E2E / NFR 章节。
8. `docs/team/02-进度看板.md` M7 / E2E 行。

## 3. 允许编辑路径

```text
server/src/main/java/com/shijiangtai/m7teacher/**
server/src/test/java/com/shijiangtai/m7teacher/**
server/src/main/resources/db/migration/V6*__m7_*.sql
frontend/src/views/grading/**
frontend/src/views/stats/**
frontend/src/router/**（E2E 所需路由，需 Score/QA 自评）
e2e/**
docs/team/evidence/*m7*、*e2e*
```

不得编辑 M1—M6、`shared/**`、`docs/team/01`、owner 表、其他模块测试。发现 M3 / M4 缺陷时，记录场景并交对应 Owner 修复；不得直接改其代码绕过。

## 4. 当前状态与依赖

- 本模块 gate：G1 提交，G2 待复审；尚无 P0 实现。
- 上游：M4 `EvaluationTerminalEvent`、M3 提交件 / 录像 / 事件 / 配置快照、M1 / M2 课程与人员。
- 待冻结契约：M7 评分 / 配置 API、最终分合成、`EvaluationTerminalEvent` 消费、统计 / 排行榜 / CSV、归档只读。
- E2E：6 组场景（E2E-01—06）；当前 M3 已具备独立浏览器验收，可作为 M3 fixture；完整 E2E 需 M1/M2/M4 完成。

## 5. 目标与范围

### 5.1 P0 目标

- 任务评分配置：默认 70/30、维度模式、权重和 / 越界 / 模式校验、版本冲突、首次评分锁定。
- 评分名单与状态；教师评分；最终分合成（权重 0、AI pending / partial / failed、教师先评分）。
- 统计 / 薄弱项 / 排行榜（NULLS LAST、班级快照、in_progress 处理）。
- CSV 导出：BOM、转义、表头、10000 行、公式注入防护。
- 学生结果查看；教师组 / 管理员权限；归档只读；保留 / 清理。
- E2E：搭 Playwright 骨架、账号 / 种子数据 fixture、6 组场景；模块 Owner 提供 fixture 与缺陷修复。

### 5.2 不做

- M4 AI 评测实现、M3 课堂 / 录像实现、M1 / M2 实现。
- 管理员访问 M7 业务；教师查看训练记录；学生申诉 / 复核。
- 自定义评分细则、批量批阅、语音点评（本轮不做）。
- 用 mock 替代 E2E 验收。

### 5.3 必须遵守的契约

- `EvaluationTerminalEvent` 是最终分 / 统计唯一触发；partial / failed 不产生总分，但不得永久 pending。
- 学生转班 / 退课使用 `class_id_snapshot`；历史评分保留。
- 课程归档后只读；`end_at` 不阻止评分；任务关闭可继续评分。
- CSV 以 `= + - @` 开头的字段防公式注入；导出上限 10000 行。
- E2E 必须能在 4 vCPU / 8 GB demo 环境跑通；录制 / 回放仅 Chromium。
- 任何契约变更先改 `02` / `07` / `12` 并让 M3 / M4 / 架构 owner 确认。

## 6. 执行顺序

1. G2 冻结 M7 配置 / 最终分 / 统计 / 导出契约与 M4 terminal 消费。
2. 迁移 V6 + 领域 / JPA：评分配置、教师评分、最终分。
3. 评分配置 / 名单 / 评分 / 版本冲突 / 首次锁定。
4. 最终分合成、状态优先级、清空重算、终态对账。
5. 统计 / 薄弱项 / 排行榜 / CSV / 学生结果 / 权限 / 归档。
6. E2E 骨架与 6 组场景；M3 独立验收纳入 fixture。
7. 测试与证据：M7-T01—T11、E2E-01—06；更新 M7 / E2E 行。

## 7. 测试门禁

- 后端：`docker compose --profile test run --rm test gradle test --tests '*m7teacher*' --no-daemon --console=plain`
- 全量：`docker compose --profile test run --rm test gradle test --no-daemon --console=plain`
- 前端：`npm --prefix frontend run test -- --run`、`npm --prefix frontend run build`
- E2E：`npx playwright test`（首次需安装 Chromium；CI 使用 demo 环境）。
- 重点：M7-T01—T11、E2E-01—06，尤其最终分状态、排行榜 NULL、CSV 注入、权限 403、归档只读。

## 8. 交付物与汇报格式

1. 分支、commit、变更文件；
2. Owner、跨模块情况与确认人；
3. 实现内容、M4 终态消费方式；
4. 测试命令与结果（含 E2E 场景通过 / 失败）；
5. 证据文件路径与 Playwright report / trace；
6. 未完成 / 阻塞 / 风险（M4 未完成时 E2E 范围）；
7. 是否合并 / 推送（默认等用户选择）。

## 9. 反模式

- 不得改 M3 / M4 代码来让统计通过。
- 不得让管理员访问评分 / 统计 / 导出。
- 不得删除 / 跳过失败 E2E 或伪造 trace。
- 不得把训练记录纳入 M7。
