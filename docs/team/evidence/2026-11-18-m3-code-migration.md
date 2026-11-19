# 2026-11-18 M3 前后端代码迁移

> 授权：用户于 2026-11-18 明确要求“完成 M3 的前后端代码迁移，不变跟其他模块”。
> 范围：仅 M3（`m3classroom` 后端与 `frontend/src/api/tasks.ts`、`frontend/src/views/task/**`）。
> 未修改 M1 / M2 / M4 / M5 / M6 / M7 业务代码。

## 1. 后端变更

- `TaskStatus`：移除 `DRAFT`，增加 `DELETED`。
- `Task`：
  - 创建任务时不再进入草稿；`create` + `publishInitial` 在同一次创建流程中完成。
  - 增加 `deletedAt` / `deletedBy` 与 `softDelete()`。
  - `updateConfiguration` / `republish` 支持无 session / 提交时的编辑。
- `TaskController`：
  - `POST /courses/{id}/tasks` 支持 `multipart/form-data`：`config` JSON + 可选 `material` 文件；创建即发布。
  - `PATCH /tasks/{id}` 支持 multipart 编辑，可替换参考材料。
  - 移除 `POST /tasks/{id}/publish`。
  - `DELETE /tasks/{id}` 改为软删除。
- `TaskApplicationService` / `TaskPublishService`：
  - `create` / `update` / `softDelete` / `close`。
  - 编辑 / 删除前检查是否存在进行中 session；编辑还检查是否已有提交。
  - 创建时先保存 task，再保存 task_assignments，避免外键顺序失败。
- `TaskEntity` / `TaskRepositoryAdapter`：增加 `deleted_at` / `deleted_by`。
- `TaskAssignmentRepositoryAdapter`：`deleteByTask` 后 flush，避免编辑任务时唯一键冲突。
- `SessionApplicationServiceImpl`：`DELETED` 任务按不可见处理；`CLOSED` 仍拒绝新 session。
- `ErrorCode`：增加 `TASK_HAS_SESSIONS` / `TASK_HAS_SUBMISSIONS` / `TASK_DELETED` / `TASK_VERSION_CONFLICT`。
- 测试准备钩子：`createPreparedTask` / `publish` 仅由测试使用，不通过 `TaskController` 暴露。

## 2. 前端变更

- `frontend/src/api/tasks.ts`
  - `TaskStatus` 改为 `published / closed / deleted`。
  - 创建 / 更新任务改为 multipart `config` + 可选 `material`。
  - 移除 `publishTask`。
- `TeacherTaskEditorPage.vue`
  - 新建任务直接发布，不再有「保存草稿」。
  - 已发布任务在无试讲 / 提交时仍可编辑；关闭 / 删除任务只读。
  - 参考材料随创建 / 保存一起上传；移除单独的删除材料入口。
- `TeacherTaskListPage.vue`
  - 移除草稿状态、草稿筛选与发布按钮。
  - 已发布 / 已关闭任务支持软删除，确认文案说明回放 / 报告 / 评分保留。
- `StudentTaskDetailPage.vue`：状态标签同步 `deleted`。

## 3. 验证

- 后端编译：`gradle testClasses` 通过。
- M3 单元测试：`domain.*` + `application.*` 共 455 个测试通过，覆盖 `domain.task.*`、`TaskApplicationServiceTest`、`TaskPublishServiceTest`、`TaskQueryServiceTest`、`SessionApplicationServiceTest`。
- M3 集成测试（PostgreSQL `shijiangtai_test`）：`TaskControllerIT`、`TaskPublishPersistenceIT`、`TaskRepositoryIT`、`TaskMaterialControllerIT`、`StudentTaskControllerIT` 均通过。
- 前端：
  - `npm run build` 通过。
  - `npm test -- --run`：18 个测试文件、143 个测试通过。
- 真实 Demo API 冒烟：
  - `GET /courses/{id}/tasks` 200。
  - multipart 创建任务 200（status=published、config_version=1）。
  - multipart 更新任务 200（topic 更新、config_version=2）。
  - 软删除 204，删除后 `GET /tasks/{id}` 404。

## 4. 已知事项

- M6 旧代码仍与 V16 模板 schema 不兼容，模板接口仍 500；按用户要求未修改 M6。
- 任务材料独立上传 / 删除接口尚未重构为“修改快照”的语义；前端已改为随任务编辑上传，接口保留兼容但不应作为新入口。
- `createPreparedTask` / `publish` 为测试准备钩子，后续可在测试迁移完成后移除。

## 5. 文件

后端（M3）：
- `server/src/main/java/com/shijiangtai/m3classroom/domain/task/Task.java`
- `TaskStatus.java`
- `TaskPublishPolicy.java`
- `application/TaskApplicationService.java` / `TaskApplicationServiceImpl.java`
- `application/TaskPublishService.java` / `TaskPublishServiceImpl.java`
- `application/TaskDraftFactory.java`
- `application/ReferenceMaterialFactory.java`
- `application/command/MaterialUpload.java`
- `api/TaskController.java`
- `infrastructure/persistence/jpa/TaskEntity.java` / `TaskRepositoryAdapter.java` / `TaskAssignmentRepositoryAdapter.java`
- `application/SessionApplicationServiceImpl.java`
- `shared/domain/ErrorCode.java`
- 相关 M3 测试

前端：
- `frontend/src/api/tasks.ts`
- `frontend/src/views/task/TeacherTaskEditorPage.vue`
- `frontend/src/views/task/TeacherTaskListPage.vue`
- `frontend/src/views/task/StudentTaskDetailPage.vue`
