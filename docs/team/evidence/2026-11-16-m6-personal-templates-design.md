# 2026-11-16 M6 个人训练模板设计变更

> 范围：M6 训练模板模型修订；M3A 任务预填契约；文档 + SQL，不含 Java / Vue 实现
> 决策人：当前使用者（架构 owner）
> 关联：[M6 需求](../../requirements/M6-训练模式.md)、[08 M6 设计](../../design/08-M6-训练模式.md)、[V16 迁移](../../../server/src/main/resources/db/migration/V16__m6_personal_templates.sql)

## 1. 背景

原设计只支持“教师从已发布 / 已关闭自由试讲任务发布训练模板到单一课程”，学生只能直接使用，不能保存个人模板。新需求要求：

- 教师、学生各有个人模板库；
- 学生可复制教师发布模板到个人模板，或从零创建；学生模板可用于训练；
- 教师可用自己的模板预填新任务；
- 每个账号最多 30 个 active 模板；
- 只改设计文档与数据库 SQL，不写代码实现。

## 2. 已确认决策

| 决策 | 结果 |
|---|---|
| 教师模板来源 | 仅从零创建；历史任务派生模板保留 `source_type=task` 用于审计 |
| 学生副本权限 | 副本全字段可改（含档位、时长、权重、课题、学生、材料） |
| 发布范围 | 一个模板可发布到多门课程；每门课程一条当前 published |
| 模板额度 | 每账号 active ≤ 30；归档不计数；课程发布不计数 |
| 学生复制后记录额度 | 副本生成新模板 ID，独立计每组 3 条；30 模板上限约束总量 |
| 教师模板锁定 | 存在 published 发布时除名称外配置锁定；下架后可编辑 |
| 任务预填 | 任务创建时复制模板配置与材料；任务创建后与模板解耦 |
| 归档语义 | 软删除；不物理删除；已有记录 / 发布历史保留 |

## 3. 数据模型变更

`V16__m6_personal_templates.sql`：

- `m6_training.training_templates` 改为个人模板库：
  - 新增 `school_id`、`owner_role`、`name`、`source_type`、`source_template_id`、`source_publication_id`、`status(active/archived)`、`archived_at`；
  - `created_by` 改名 `owner_account_id`；
  - `source_task_id` / `source_task_config_version` 改为可空（历史来源）；
  - 移除 `course_id`、`published_at`、`unpublished_at`；
  - 旧 `published/unpublished` 状态回填为 `active`。
- 新增 `m6_training.training_template_publications`：
  - `template_id`、`course_id`、`status`、`published_at`、`unpublished_at`、`unpublish_reason`、`created_by`；
  - `UNIQUE(template_id, course_id) WHERE status='published'`。
- `training_session_meta` / `training_records` 增加 `publication_id`，并回填历史数据。
- `training_materials`：
  - `student_account_id` 改名 `owner_account_id`；
  - 新增 `usage(free_session/template)`、`template_id`；
  - `expires_at` 允许为空（已附着模板材料不过期）；
  - 状态增加 `attached`。
- `m3_classroom.tasks` 增加 `source_training_template_id`（无外键，仅追溯）。
- 权限 seed：新增 `training:template:personal`，并为个人模板 / 发布接口补充路由权限行。

## 4. 文档变更

- `docs/requirements/M6-训练模式.md`：V0.4，重写范围、规则、FR、流程、权限、验收。
- `docs/requirements/需求规格说明书.md`：M6 摘要更新。
- `docs/design/01-数据模型与迁移.md`：M6 表 / 约束 / 索引 / 迁移列表 / 保留 / 开放问题。
- `docs/design/02-API与实时协议.md`：权限码、M6 接口、任务创建可传模板 ID、错误码。
- `docs/design/05C-M3-任务配置与结构化面试.md`：新增“从训练模板创建草稿”契约与测试点。
- `docs/design/08-M6-训练模式.md`：V0.3，按个人模板 / 多课程发布 / 复制 / 任务预填重写。
- `docs/design/09-前端设计.md`：训练模板 / 我的模板 / 任务预填路由与交互。
- `docs/design/10-测试与验收.md`：M6-T01—15、E2E 训练闭环、数据范围。
- `docs/design/12-数据库设计.md`：ER 图、M6 DDL、FK、索引、清理与保留、迁移树。
- `docs/implementation/07-M6-实现设计.md`：V0.2，类职责、方法签名、调用流程、事务与并发、测试点。
- `server/src/main/resources/db/demo/V900__demo_seed.sql`：新模型 demo 模板 / 发布 / 材料 / 会话 / 记录。

## 5. 验证

### 5.1 新库全量迁移

临时数据库 `m6_v16_check` 按版本顺序执行：

```text
V0 → V1 → V2 → V3 → V4 → V5 → V6 → V7 → V8 → V10 → V11
→ V12 → V13 → V14 → V15 → V16 → V900 → V901
```

结果：全部成功，无错误。

### 5.2 旧数据升级回填

临时数据库 `m6_v16_upgrade` 先执行 V0—V15，插入旧模型模板 / 发布态 / session / record / material 数据，再执行 V16。验证：

- 旧模板行转为 `active`、`source_type=task`、`owner_role=teacher`，生成一条 publication；历史 `config_snapshot` 仍为 `schema_version=1`，读取兼容，复制 / 新建写 v2；
- `training_session_meta.publication_id`、`training_records.publication_id` 正确回填；
- `training_materials` 转为 `owner_account_id` + `usage=free_session`；
- `m3_classroom.tasks.source_training_template_id` 存在；
- 新权限码 `training:template:personal` 及各路由权限行存在。
- 升级不裁剪历史模板；如某账号历史 active 模板超过 30，后续新建 / 复制 / 恢复会被额度校验拒绝，需教师归档超额模板。

结果：全部通过。

### 5.3 文档检查

- 全局 grep 旧错误码 `TRAINING_TEMPLATE_EXISTS` / `TRAINING_SOURCE_INVALID`：无残留。
- 全局 grep `training_materials.student_account_id`：无残留。
- 全局 grep `training_templates` 的旧 `course_id` / `created_by` / `published_at` 引用：无残留（`training_template_publications` 的列除外）。
- 迁移树 / 版本列表已与实际文件对齐并新增 V16。

## 6. 未做 / 后续

- 未改 Java / Vue / Python 代码；现有 M6 实现仍按旧模型，需后续按本设计与 V16 实施。
- 未执行 M6 单元 / 集成 / 契约 / E2E；现有实现测试需在代码迁移后重跑。
- 因 V16 版本号低于已执行的 V900/V901，已有 demo 数据库需按 `./server/docker/reset-test-db.sh demo` 重建；新库按 V0→V16→V900→V901 顺序执行。
- 后续实现顺序建议：
  1. M6 领域 / 持久化 / 服务按个人模板 + 发布模型改造；
  2. API / 权限 / 前端我的模板与复制；
  3. M3A `TrainingTemplateQueryPort` 适配与任务预填；
  4. 重跑 M6 UT / IT / 契约 / E2E，更新 `02-进度看板` 与 `10-测试与验收`。
