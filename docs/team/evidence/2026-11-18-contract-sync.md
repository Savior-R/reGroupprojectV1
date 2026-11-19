# 2026-11-18 M3 / M6 契约同步

> 授权：用户于 2026-11-17—18 明确确认 M3 / M6 删改语义，并要求按静态原型 V1.5 继续同步文档。
> 范围：仅文档与原型文案；未修改 `frontend/`、`server/`、`ai/` 代码，未执行数据库迁移。

## 1. 已确认决策

### M3

1. 任务草稿：**彻底移除**。任务创建即执行发布校验并生成配置快照；后端不保留 `draft` 状态。
2. 任务删除：**软删除**。任务从课程 / 学生列表隐藏，不能开始新 session。
3. 已提交任务删除后：**保留**回放、AI 报告、教师评分与最终分，只读可查；统计 / 排行榜默认排除软删除任务。

### M6

4. 模板删除：**软删除**；保留模板行用于引用审计，不提供恢复。
5. 删除模板时：自动下架全部发布、释放 30 额度、清理该模板关联的 TrainingRecord（含录像 / 评测报告）与参考材料；学生副本、任务快照不受影响。
6. 学生模板接口：**不需要裁剪权重与参考材料**；前端按场景隐藏，API 可返回完整配置。

## 2. 契约变更

| 文档 | 版本 | 变更 |
|---|---|---|
| `docs/requirements/M3-虚拟课堂与录制.md` | V1.2 | 移除草稿；新增 FR-M3-12A 任务软删除；任务状态 published / closed / deleted；权限与异常 |
| `docs/requirements/M6-训练模式.md` | V0.5 | 模板删除替代归档 / 恢复；删除清理记录 / 材料；任务预填直接发布；A22 修订 |
| `docs/requirements/M7-教师评测与数据统计.md` | V0.4 | 软删除任务无名单、统计排除、报告直链保留 |
| `docs/design/03-M1-M2-账号组织课程设计.md` | V0.7 | 课程状态矩阵移除草稿任务；编辑 / 软删除表述对齐 |
| `docs/design/05A-M3-课堂状态机与WebSocket.md` | V1.1 | end_at 后不再新建任务 / session，移除“发布草稿” |
| `docs/design/01-数据模型与迁移.md` | V1.4 | `tasks.status` 移除 draft、增加 deleted 与 deleted_at / deleted_by；模板软删除清理规则 |
| `docs/design/02-API与实时协议.md` | V1.9 | 任务创建即发布、移除 publish 端点；任务编辑 / 软删除；模板 DELETE；错误码更新 |
| `docs/design/05C-M3-任务配置与结构化面试.md` | V0.6 | 状态机、创建 / 发布事务、编辑条件、软删除、错误码、权限、测试与流程图 |
| `docs/design/08-M6-训练模式.md` | V0.5 | 模板 DELETE、自动下架、释放额度、清理 TrainingRecord / 录像 / 报告 / 材料；无恢复 |
| `docs/design/09-前端设计.md` | V0.6 | 任务无草稿、模板删除语义、Q11 关闭 |
| `docs/design/10-测试与验收.md` | V0.7 | M3 软删除保留、M6 删除清理测试；移除草稿测试 |
| `docs/design/12-数据库设计.md` | V0.5 | tasks DDL：published 默认、deleted 状态、deleted_at / deleted_by；template unpublish reason `template_deleted` |
| `docs/design/13-代码设计.md` | V0.6 | 前端状态 `useTaskDraft` → `useTaskEditor`；模板动作删除 |
| `docs/design/14-后端代码实现设计.md` | V0.5 | TaskService 创建即发布 / 软删除；TemplateService 删除 |
| `docs/implementation/04-M3-实现设计.md` | V0.3 | M3 任务接口、发布流程、类职责按无草稿 / 软删除更新；标注现有 M3B 代码待迁移 |
| `docs/implementation/07-M6-实现设计.md` | V0.3 | M6 模板接口、删除流程、清理规则、测试按软删除更新 |
| `docs/prototype/README.md` + 删除确认弹窗 | V1.5 口径 | 任务删除保留回放 / 报告 / 评分；模板删除清理记录 / 材料，学生副本与任务快照保留 |

## 3. 关键规则

### 任务

- `POST /courses/{id}/tasks`：创建 + 发布校验 + 配置快照 + 分配名单，单事务。
- `PATCH /tasks/{id}`：仅任务无 session / 提交时允许；成功后更新快照并递增 config_version；否则 409 `TASK_HAS_SESSIONS`。
- `DELETE /tasks/{id}`：课程老师组软删除；有进行中 session 拒绝；任务材料、提交件、录像、评测、教师评分与最终分保留。
- 任务状态：`published / closed / deleted`；任务统计默认排除 deleted，报告直链可读。

### 模板

- `DELETE /training/my-templates/{id}`：仅 owner；`status=archived` 软删除，记录 `archived_at`。
- 自动下架全部 published 发布（`unpublish_reason=template_deleted`）。
- 释放 30 个 active 额度。
- 清理该模板关联的 TrainingRecord / session / 录像 / 评测报告与模板参考材料；不保留。
- 学生副本、任务快照保留；删除不可恢复。
- 学生模板 / 教师模板接口可返回权重与参考材料，前端按场景隐藏，不做 API 裁剪。

## 4. 验证

- 设计文档本地链接检查：缺失 0。
- Markdown 表格列数检查：0 错误。
- 静态原型：34 个 HTML，本地链接缺失 0，重复 `id` / 标签嵌套 / `<button>` 开闭 0 错误。
- 未改业务代码；未运行构建 / 单元测试（无代码变更）。

## 5. 待 Owner 复核

- M3 Owner：确认任务软删除权限为课程老师组、存在进行中 session 时拒绝。
- M6 Owner：确认 `status=archived` 作为内部软删除、删除清理 TrainingRecord / 材料且不提供恢复。
- 架构 Owner：确认 01 / 02 / 12 / 14 的契约变更与迁移顺序。
