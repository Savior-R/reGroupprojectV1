# 2026-11-04 全模块迁移与 Demo Seed 证据

> 分支：`feat/db-all-migrations`
> 计划：`docs/superpowers/plans/2026-11-04-all-module-migrations-demo-seed.md`
> 执行人：当前使用者（M3 Owner，经用户授权协调本次跨模块迁移）
> 环境：Docker Desktop 4.84 / Docker Engine 29.6.2 / Compose v5.3.1 / PostgreSQL 18.6 / Flyway CLI 12.11.0

## 1. 授权范围

用户于 2026-11-04 明确授权本次创建全部模块迁移、系统 seed、demo seed，范围仅限：

| 文件 / 目录 | 模块 / Owner |
|---|---|
| `server/src/main/resources/db/migration/V0__create_schemas.sql` | Foundation |
| `V1__m1_auth.sql` | Foundation |
| `V2__m2_course.sql` | Course/Question |
| `V3__m5_question.sql` | Course/Question |
| `V4__m3_classroom.sql` | M3 |
| `V5__m4_eval.sql` | AI |
| `V6__m7_teacher.sql` | Score/QA |
| `V7__m6_training.sql` | Media/Training |
| `V8__ops_cleanup_jobs.sql` | Foundation |
| `V10__foreign_keys.sql` / `V11__indexes.sql` | Foundation / DB 设计 |
| `V12__seed_account_types_and_permissions.sql` | Foundation / M1 |
| `server/src/main/resources/db/demo/V900__demo_seed.sql` | Foundation / Demo |
| `server/src/main/resources/seed/question-bank.json` | Course/Question |
| `server/docker/postgres/init/*`、`server/docker/reset-test-db.sh`、`server/docker/seed-demo-assets.sh`、根 `docker-compose.yml` | Docker 环境（用户另行授权的最小文件） |

> 该授权仅对本次列出的文件有效，不推广到其他共享路径或其他模块。

## 2. 迁移顺序与结果

实际顺序（用户确认的 Team 前缀 + 延迟 FK/索引）：

```text
V0__create_schemas.sql
V1__m1_auth.sql
V2__m2_course.sql
V3__m5_question.sql
V4__m3_classroom.sql
V5__m4_eval.sql
V6__m7_teacher.sql
V7__m6_training.sql
V8__ops_cleanup_jobs.sql
V10__foreign_keys.sql
V11__indexes.sql
V12__seed_account_types_and_permissions.sql
db/demo/V900__demo_seed.sql
```

`flyway info` 结果：

- 测试库 `shijiangtai_test`：schema version **12**，V0–V8、V10–V12 全部 `Success`。
- Demo 库 `shijiangtai_demo`：schema version **900**，V0–V8、V10–V12、V900 全部 `Success`。
- V9 未使用（保留给后续运维迁移）。

## 3. Schema 验证

| 指标 | 结果 |
|---|---:|
| 业务表总数（M1–M7 + ops） | **41** |
| 跨模块外键（pg_constraint contype='f'） | **83** |
| M1 表 | 11 |
| M2 表 | 4 |
| M3 表 | 10 |
| M4 表 | 5 |
| M5 表 | 3 |
| M6 表 | 4 |
| M7 表 | 3 |
| ops 表 | 1 |

验证命令（节选）：

```bash
./server/docker/reset-test-db.sh test
docker compose --profile migrate run --rm flyway
docker compose --profile migrate run --rm flyway \
  -url=jdbc:postgresql://postgres:5432/shijiangtai_test \
  -user=shijiangtai -password=shijiangtai_dev \
  -locations=filesystem:/flyway/sql info
```

外键违反用例：向 `m3_classroom.tasks` 插入不存在的 `course_id`，数据库返回
`fk_tasks_course` 违反（SQLSTATE 23503），证明 FK 已生效。

## 4. 系统 seed

| 指标 | 结果 |
|---|---:|
| `account_types` | 3（student / teacher / admin） |
| `api_permissions` | **108** 条路由权限 |
| `account_type_permissions` | **163**（student 56 / teacher 66 / admin 41） |
| 管理员 M3/M4/M6/M7 业务权限 | 0（负向检查通过） |
| 学生管理类权限 | 0（负向检查通过） |
| 教师管理类/他人 session 权限 | 0（负向检查通过） |

权限 seed 约定：一条路由一行；02 的重复权限码用 `#<n>` 后缀保证 `code` 唯一，原始权限码写入 `description`；角色映射按 `02` §3.1。公开路由与 `/actuator/health` 也进入目录并映射到所有角色，Nginx/Spring 的 permitAll 白名单仍然有效。

## 5. Demo seed 结果

| 数据 | 数量 |
|---|---:|
| 学校 | 2（主校 + 跨校隔离校） |
| 账号 | 16（2 管理员 + 3 教师 + 11 学生） |
| 专业 / 班级 | 3 / 3 |
| 课程 | 4 |
| M5 题库 / 类别 / 题目 | 1 / 3 / 6 |
| 任务 | 5（3 published / 1 draft / 1 closed） |
| 任务分配 | 12 |
| 虚拟学生 | 7 |
| Session | 6（5 task submitted + 1 training selected） |
| 提交件 | 5 |
| 录像 / 录像流 | 5 / 15 |
| 文本 / 帧 / 事件 | 10 / 5 / 25 |
| M4 评测 | 5（completed 2 / pending 1 / partial 1 / failed 1） |
| M4 维度分 / 问题 / 输入快照 / job | 14 / 6 / 5 / 5 |
| M7 评分配置 / 教师评分 / 最终分 | 1 / 1 / 5（completed / pending_ai / pending_teacher / ai_partial / ai_failed 各 1） |
| M6 模板 / meta / record / material | 1 / 1 / 1 / 1 |
| ops 清理任务 | 1 |

演示账号密码统一为 `Demo1234!`（BCrypt `$2a$12$...`，cost 12）。示例用户名：`admin.a`、`teacher.a1`、`teacher.a2`、`student.a01`…`student.a10`、`admin.b`、`teacher.b1`、`student.b01`。

Demo 导入命令：

```bash
./server/docker/reset-test-db.sh demo
docker compose --profile demo run --rm flyway-demo
docker compose --profile demo run --rm flyway-demo \
  -url=jdbc:postgresql://postgres:5432/shijiangtai_demo \
  -user=shijiangtai -password=shijiangtai_dev \
  -locations=filesystem:/flyway/sql,filesystem:/flyway/demo info
```

## 6. 环境修正与已知限制

- PostgreSQL 18 镜像要求数据卷挂载在 `/var/lib/postgresql`（不是 `/var/lib/postgresql/data`）；已修正 `docker-compose.yml` 与 M3A 计划。
- Flyway CLI 把 dollar-quote 开头的 `$tag${` 误判为 `${...}` 占位符；已在 V900 的 JSON 开头插入换行，并在两个 Flyway 服务上设置 `-placeholderReplacement=false`。
- `ffmpeg` 当前不可用：`server/docker/seed-demo-assets.sh` 输出 `SKIP`，demo 数据库行中的 MinIO object key 仍保留；安装 ffmpeg 后可重新执行脚本上传样例录像/关键帧。
- `MigrationIT` / `QuestionBankSeedTest` 暂未创建：Spring Boot server scaffold 尚未落地；本次用 Flyway CLI + `psql` 断言 schema/seed，M3A scaffold 落地后补 Java IT。
- M5 `SeedService` 的 Java 建校复制逻辑、M1 `PermissionRegistry` 运行时加载、M3A 应用本身仍在后续计划中。

## 7. 复现命令

```bash
# 测试库：41 表 + 83 FK + 108 权限
./server/docker/reset-test-db.sh test
docker compose --profile migrate run --rm flyway
docker compose exec -T postgres psql -U shijiangtai -d shijiangtai_test \
  -c "select count(*) from information_schema.tables where table_schema in ('m1_auth','m2_course','m3_classroom','m4_eval','m5_question','m6_training','m7_teacher','ops');"

# Demo 库：完整数据集
./server/docker/reset-test-db.sh demo
docker compose --profile demo run --rm flyway-demo
docker compose exec -T postgres psql -U shijiangtai -d shijiangtai_demo \
  -c "select count(*) from m1_auth.accounts; select count(*) from m3_classroom.tasks; select count(*) from m4_eval.evaluations;"

# 样例 MinIO 资产（可选；无 ffmpeg 时跳过）
bash server/docker/seed-demo-assets.sh
```

## 8. 整分支复审与修复

独立复审结论：**无 Critical / 阻塞问题**；schema 与 `12` §3–§12 逐语句一致（41/41 表、83/83 外键、索引一致），Flyway validate 通过，测试库无 demo 行。

已修复的 4 个 Important demo 契约问题：

1. 评测 / 最终分时间线：eval 3/4/5 与 final 3/4/5 全部移到对应提交之后。
2. M6 模板 / 有效配置快照：改用 `08` §3.2 / §5.4 的键，包含 3 个虚拟学生与完整参考材料。
3. M4 输入快照：`recording_ids` 改为指向 `recording_streams`；`flags` 改用 `06` §4.4 的键。
4. M7 分数一致性：AI 总分改为 86.60 / 90.15，教师总分 80.20，最终分 84.68。

修复验证：`verify-review-fixes.sh` RED→GREEN，随后全量 `verify-task-13.sh` 通过。

Deferred minors（未在本次修复，记录待后续）：

- 部分任务分配学生不是任务所属课程成员（面试 / 已关闭任务）。
- `task_grading_configs.locked_at` 在已有教师评分时仍为空。
- demo 事件 payload 字段名与 05A/02 契约不完全一致。
- `dimension_scores.sub_scores` 使用维度键而非子项键。
- README / 计划中的 `flyway info` 简写缺少连接参数。
- `seed-demo-assets.sh` 缺少 MinIO 可用性保护，且未上传材料对象。
- demo 的 `created_at` 仍取当前时间；训练材料 `expires_at` 为 2026-11-04。
- `12` §14/§15.2 仍写 `V901` / `migration-demo` 与另一套顺序。
