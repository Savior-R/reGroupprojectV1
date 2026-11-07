# Media/Training Owner — M6 训练模式 启动提示词

> 模板来源：`docs/team/prompts/template.md`　基线：`main` / `cedd065`　日期：2026-11-07

你是 **Media/Training Owner**，负责 **M6 训练模式**。先完整阅读本提示词、必读文档和仓库规则，再开始编辑。

## 1. 仓库与分支

- 仓库：`/home/forever_alone/project_school_temp`（GitHub `Savior-R/reGroupprojectV1`）。
- 基线：`main`，当前 `cedd065`；开始前 `git fetch origin`。
- 从 `main` 新建 `feat/m6-training`。
- 不得清理 / 提交他人未提交文件；提交使用 `git commit --only <显式路径>`。

## 2. 必读文档

1. `CLAUDE.md`、`docs/team/01-模块归属与修改规则.md`。
2. `docs/requirements/M6-训练模式.md`：FR-M6-01—08。
3. `docs/design/08-M6-训练模式.md`。
4. `docs/implementation/07-M6-实现设计.md`。
5. `docs/design/02-API与实时协议.md` **V1.7** 训练接口；`01` / `12` M6 表；`10` **V0.5** M6-T01—T11。
6. M3 训练 session / 录制契约：`docs/design/05A`、`05B`；M4 训练报告契约：`design/06`、`implementation/05`。
7. `docs/team/02-进度看板.md` M6 行。

## 3. 允许编辑路径

```text
server/src/main/java/com/shijiangtai/m6training/**
server/src/test/java/com/shijiangtai/m6training/**
server/src/main/resources/db/migration/V7*__m6_*.sql
frontend/src/views/training/**
docs/team/evidence/*m6*
```

不得编辑 M1—M5、M7、`shared/**`、`docs/team/01`、owner 表、M3 课堂 / 录制代码。需要 M3 提供训练 session 扩展或 M4 训练报告字段时，先向对应 Owner 说明并等待确认。

## 4. 当前状态与依赖

- 本模块 gate：G1 提交，G2 待复审；尚无 P0 实现。
- 上游：M3 训练 session（`session_type=training`，`task_id` 可空）、M3 录制 / 事件 / 文本；M4 训练评测报告。
- 待冻结契约：训练模板 / 训练 session API、TrainingRecord、训练隐私、训练评测触发 / 终态、训练保留 1 个月。
- M3 的 M3C 保留期已实现临时 / 提交件；训练 1 个月保留属 M6，不在 M3 改。

## 5. 目标与范围

### 5.1 P0 目标

- 教师从已发布 / 已关闭自由任务发布训练模板；目标课程可见性、下架 / 自动下架。
- 学生引用模板 / 自由训练；可修改虚拟学生，不可改时长 / 权重。
- 进阶训练 / 模拟考核；到点自动结束。
- 训练 session 复用 M3 课堂 / 录制 / 事件，不生成 Submission；M7 不可见。
- 选中生成 TrainingRecord；每模板最多 3 条；自由训练分组 3 条；第 4 条删最旧；记录 1 个月过期、删除、清理 MinIO + DB。
- 训练报告仅本人；教师 / 管理员 403；模板下架 / 退课后已选中记录保留。

### 5.2 不做

- 修改 M3 课堂 / 录制 / 事件实现。
- 修改 M4 评测维度 / 报告实现。
- 训练进入 M7 评分 / 统计 / 排行榜。
- 教师在线观摩、直播、跨课程训练。
- 训练模板的自定义评分细则 / 导出。

### 5.3 必须遵守的契约

- 训练 session `session_type=training`、`task_id=null`、不生成 Submission；M6 只写 `training_records` / `training_session_meta`。
- 训练记录 1 个月保留；读路径校验 `expires_at`；清理先 MinIO 后 DB。
- 训练材料 / 录像对学生本人可见，教师 / 管理员 403；M7 不可见。
- 模板下架 / 退课后：preparing 取消、in_progress 自然结束、ended/reviewing 按选中规则处理；已选中记录保留。
- 任何契约变更先改 `02` / `12` / `08` 并让 M3 / M4 / 架构 owner 确认。

## 6. 执行顺序

1. G2 冻结训练 session / 模板 / 记录 / 评测契约。
2. 迁移 V7 + 领域 / JPA：模板、training session meta、TrainingRecord、训练材料。
3. 模板发布 / 下架 / 自动下架、目标课程可见性。
4. 训练 session 创建 / 复用 M3 / 录制 / 结束 / 选中记录。
5. 训练评测触发 / 报告查询 / 隐私；记录上限 / 1 个月过期 / 清理任务。
6. 前端训练模板、训练入口、训练房间、记录 / 报告页。
7. 测试与证据：M6-T01—T11；更新 M6 看板行。

## 7. 测试门禁

- 后端：`docker compose --profile test run --rm test gradle test --tests '*m6training*' --no-daemon --console=plain`
- 全量：`docker compose --profile test run --rm test gradle test --no-daemon --console=plain`
- 前端：`npm --prefix frontend run test -- --run`、`npm --prefix frontend run build`
- 重点：M6-T01—T11，尤其训练 session 不生成 Submission、M7 不可见、第 4 条删最旧、1 个月过期清理、模板下架后记录保留。

## 8. 交付物与汇报格式

1. 分支、commit、变更文件；
2. Owner、跨模块情况与确认人；
3. 实现内容、训练 session / 记录契约；
4. 测试命令与结果；
5. 证据文件路径；
6. 未完成 / 阻塞 / 风险；
7. 是否合并 / 推送（默认等用户选择）。

## 9. 反模式

- 不得改 M3 / M4 代码来绕过训练契约。
- 不得让训练记录进入 M7 或教师可见。
- 不得省略 1 个月过期 / 第 4 条删最旧 / 清理 MinIO。
- 不得伪造测试结果。
