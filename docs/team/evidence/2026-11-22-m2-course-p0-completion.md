# M2 课程模块 P0 完成证据（2026-11-22）

> Owner：Course/Question Owner（M2）
> 分支：`feat/m2-course`（基于 `main`，基线 commit `be993a8`）
> 范围：FR-M2-01—09、课程 `end_at` / 归档状态矩阵、前端课程列表 / 详情 / 管理 / 成员页

## 1. 验证命令与结果

| 验证项 | 命令 | 结果 |
|---|---|---|
| 后端主代码编译 | `gradle compileJava --no-daemon`（JDK 21，Gradle 9.7.1） | ✅ BUILD SUCCESSFUL |
| 后端测试编译 | `gradle compileTestJava --no-daemon` | ✅ BUILD SUCCESSFUL |
| 前端类型检查 + 生产构建 | `npm run build`（vue-tsc -b && vite build，Node 22） | ✅ 成功 |
| 模块全量测试 | `gradle test --tests "com.shijiangtai.m2course.*"`（JDK 21 / 真实 PostgreSQL 16.4） | ✅ 55 tests，0 failures / 0 errors / 0 skipped |

测试类明细（`build/test-results/test/TEST-*.xml`）：

| 测试类 | 用例数 | 结果 |
|---|---|---|
| `M2ArchitectureTest` | 4 | ✅ |
| `CourseTest`（领域） | 6 | ✅ |
| `M2ContractTest`（错误码 / 端点） | 7 | ✅ |
| `CourseControllerIT`（API + 真实 PG） | 27 | ✅ |
| `CourseRepositoryIT` | 7 | ✅ |
| `CourseMemberRepositoryIT` | 4 | ✅ |

> 说明：本机初始无 Docker / PostgreSQL / Redis；本次临时搭建便携 PostgreSQL 16.4（`localhost:5432/shijiangtai`，账号 `shijiangtai`）
> 后运行真实库测试，M2 全量 55 条全部通过。Redis 未运行，M2 用例未依赖 Redis 连接。

## 2. 本次修复（后端）

1. **并发唯一冲突不再冒泡 500**：`CourseApplicationServiceImpl.create/update`、
   `CourseMembershipServiceImpl.addTeacher/addStudentByAccount/join` 捕获
   `DataIntegrityViolationException`，分别映射 `COURSE_NAME_TAKEN` / `DUPLICATE_RESOURCE` / `ALREADY_JOINED`（409），
   与 02 §8.2 错误码契约一致（原先会落到全局兜底 500）。
2. **课程状态变更加行锁**：`update/archive/restore` 改用 `findByIdForUpdate`（`SELECT ... FOR UPDATE`），
   与 14-后端实现设计「archive 锁课程行」一致，避免并发丢失更新。
3. **分页排序稳定**：`CourseRepositoryAdapter.load` 原先用 `WHERE id IN (...)` 回读且不保证顺序，
   现按分页查询的 id 顺序重排，保证 `created_at DESC, id` 顺序稳定。

## 3. 本次修复（前端）

1. **课程元数据编辑**：`CourseDetailPage` 新增「编辑课程」对话框（名称 / 结束日期），调用已有 `updateCourse`。
2. **管理员入口修正**：课程详情「课程任务」入口改为仅本组教师可见（管理员不再看到任务 / 评分入口）。
3. **已结束课程状态**：学生卡片在 `ended` 或归档状态下不再显示「退出课程」。
4. **学生名单筛选**：`CourseStudentsPage` 新增按姓名 / 账号 / 班级的当前页筛选。

## 4. 新增测试

`CourseControllerIT` 新增 2 条（编译通过，随 IT 阶段执行）：

- `patchCannotChangeSchoolOrMajors`：PATCH 传入 `school_id` / `major_ids` 不改变学校与专业范围（M2-T02）。
- `teacherListHidesArchivedCourseByDefault`：教师列表默认隐藏归档课程，管理员可按 `status=archived` 检索（M2-T08）。

## 5. 已有实现盘点（基线）

- 15 个契约端点全部实现（`CourseController` + `CourseMemberController`）。
- 四张表 `V2__m2_course.sql` 与 12 §4 一致；外键 `V10`、索引 `V11`、权限 seed `V12` 齐全。
- 前端 4 个页面 + 创建抽屉 + 路由已挂载（`app/router.ts` 展开 `courseRoutes`）。
- 已有测试：`CourseTest`、`CourseRepositoryIT`、`CourseMemberRepositoryIT`、`CourseControllerIT`、
  `M2ContractTest`、`M2ArchitectureTest`。

## 6. 未完成 / 后置项

- **E2E / 契约跨模块测试后置**：M2-T01—T09 的 UT / IT 已在本机真实 PG 上全量通过（见 §1）；
  Playwright E2E 与跨模块契约（M2 ↔ M1/M3/M6/M7）由 Score/QA Owner 后续补齐。
- **低优先级差距**（来自契约审查，未改）：
  - `course_majors(major_id)` 等 3 个索引在 01 与 12 文档间不一致，迁移未建；
  - 学生名单为内存分页（当前页筛选）；
  - `addStudentsByClass` 在并发冲突时仍会整批回滚（业务跳过已支持）；
  - `markStudentStatus` 对 `removed` 也写 `left_at`。
- **跨模块**：`CourseArchivedEvent` / `CourseTeacherRemovedEvent` 已发布，但全仓暂无消费者
  （M6 自动下架训练模板未落地），需 M6 / 架构 owner 后续接入。
