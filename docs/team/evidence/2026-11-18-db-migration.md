# 2026-11-18 数据库迁移执行记录

> 授权：用户于 2026-11-18 明确要求“执行数据库迁移”，结合 2026-11-16 全项目整合授权执行。
> 范围：本地 Docker `shijiangtai_test` / `shijiangtai_demo` 两个数据库；**未执行任何业务代码修改**（除新增迁移文件与重建本地后端镜像）。
> 迁移文件：`V15__m1_audit_event_type.sql`、`V16__m6_personal_templates.sql`、`V17__m3_m6_soft_delete_contract.sql`。

## 1. 执行结果

| 数据库 | 迁移前 | 已应用 | 结果 |
|---|---|---|---|
| `shijiangtai_test` | V15 | V16、V17 | 成功；当前 V17 |
| `shijiangtai_demo` | V14 + V900/V901 | V15、V16、V17（out-of-order） | 成功；当前 V17 |
| `shijiangtai`（主库） | V950 dev upstream | 未执行 | 未迁移，避免影响 dev 数据 |

- `shijiangtai_demo` 在执行前备份：`/tmp/shijiangtai_demo_before_v16.dump`（181 KB，pg_dump custom format）。
- Demo 的 V900 seed 校验和与本地不一致；先执行 Flyway `repair` 更新历史校验和，再以 `outOfOrder=true` 应用 V15/V16/V17。
- Test 的 V17 初次应用后迁移文件调整过约束顺序；执行 `repair` 更新校验和，再验证 V17。

## 2. V17 内容

`V17__m3_m6_soft_delete_contract.sql`：

- M3：
  - `tasks` 增加 `deleted_at`、`deleted_by`。
  - 现有 `draft` 任务转为 `deleted`，保留数据但从列表隐藏。
  - `tasks.status` 约束改为 `published / closed / deleted`。
  - `config_version` 默认值与下限改为 1。
  - 增加 `ck_tasks_deleted`。
- M6 下架原因调整未纳入 V17 文件；当前 V17 仅包含 M3 任务软删除。M6 `template_deleted` 需要 M6 Owner 在其 schema 迁移中单独补齐。

## 3. 验证

### 3.1 Flyway

- `shijiangtai_test`：`Successfully validated 17 migrations`，当前 V17。
- `shijiangtai_demo`：应用 V15、V16、V17；当前 V17。

### 3.2 Demo 数据

- `m3_classroom.tasks`：`published=5`、`closed=1`、`deleted=1`（原 draft 任务转为 deleted）。
- `m6_training.training_templates`：`active=1`。
- `training_template_publications`：`unpublished/manual=1`。
- 约束存在：`ck_tasks_status`、`ck_tasks_config_version`、`ck_tasks_deleted`。

### 3.3 后端

- 使用新迁移文件重建 `shijiangtai-app` 镜像。
- `actuator/health` 返回 `200`；容器状态 `healthy`。
- 登录 `teacher.a1` 成功。

### 3.4 已知不兼容（必须记录）

2026-11-18 后续：M3 前后端代码已按 V17 契约迁移并通过单元 / 集成测试；M3 任务列表、创建、更新、软删除接口已验证可用。

仍未迁移：

- `GET /api/v1/training/my-templates` 与 `/teacher/training/templates` 仍返回 500，因为 M6 旧代码仍查询 V16 已删除 / 重命名的列（如 `course_id`、`created_by`）。
- M6 代码迁移不在本次 M3 范围内。

## 4. 回滚

Demo 备份：`/tmp/shijiangtai_demo_before_v16.dump`。

```bash
docker exec -T shijiangtai-postgres-1 pg_restore -U shijiangtai -d shijiangtai_demo --clean --if-exists /tmp/shijiangtai_demo_before_v16.dump
```

注意：回滚后需使用与旧 schema 匹配的后端镜像，否则 Flyway 校验与 JPA 列映射仍会失败。

## 5. 未执行

- 未迁移 `shijiangtai` 主库。
- 未修改 M3 / M6 Java 代码。
- 未执行 M3 / M6 集成测试 / E2E。
- 未验证 MinIO 中模板材料 / 训练录像的物理清理。
