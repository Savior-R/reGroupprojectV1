# All-Module Migrations + Full Demo Seed Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create the complete PostgreSQL schema for M1–M7 + ops via Flyway, seed account types and API permissions, add the M5 question-bank JSON seed, and import a full demo dataset (schools, accounts, courses, question bank, tasks, sessions, submissions, recordings, context streams, evaluations, teacher scores, training data) into a dedicated demo database.

**Architecture:** Flyway SQL migrations under `server/src/main/resources/db/migration` create schemas and tables first (`V0`–`V8`), then add all cross-schema foreign keys (`V10`) and indexes (`V11`), then system seed (`V12`). Demo data lives in `server/src/main/resources/db/demo/V900__demo_seed.sql` and is loaded only by the `demo` Flyway location. Docker Compose provides a Flyway CLI service, a demo Flyway service, PostgreSQL 18, and the Spring test service.

**Tech Stack:** PostgreSQL 18, Flyway 12.4.0 (CLI image `flyway/flyway:12`), Docker Compose v5, SQL, Spring Boot test service (for `MigrationIT`), Python `bcrypt` for the demo password hash.

**Spec:** `docs/design/12-数据库设计.md` (§3–§15), `docs/design/01-数据模型与迁移.md` (§2–§11), `docs/design/02-API与实时协议.md` (§3–§6), `docs/design/10-测试与验收.md` (§4), `docs/team/01-模块归属与修改规则.md` (§3).

## Global Constraints

- **Migration order (user-confirmed 2026-11-04):**
  - `V0__create_schemas.sql`
  - `V1__m1_auth.sql`
  - `V2__m2_course.sql`
  - `V3__m5_question.sql`
  - `V4__m3_classroom.sql`
  - `V5__m4_eval.sql`
  - `V6__m7_teacher.sql`
  - `V7__m6_training.sql`
  - `V8__ops_cleanup_jobs.sql`
  - `V10__foreign_keys.sql`
  - `V11__indexes.sql`
  - `V12__seed_account_types_and_permissions.sql`
  - `db/demo/V900__demo_seed.sql` (demo location only)
- **No inline cross-schema FKs in table migrations.** `V10` adds every FK from `12` §11 after all tables exist. `V11` adds every index/partial unique index from `12` §12. Natural-key UNIQUE constraints and CHECK constraints stay in the table migrations.
- **Source of truth:** Copy table/column/constraint definitions verbatim from `12` §3–§10. Do not invent columns or change names. If a definition is missing, stop and report; do not guess.
- **Forward-only:** Do not edit a migration after it has been applied to a database. During authoring, reset the test/demo DB before re-running.
- **Cross-module authorization (user, 2026-11-04):** the user authorized creating migrations and seed for M1/M2/M5/M4/M6/M7 + Foundation for this task only. Record the authorization and file list in the evidence file. Do not modify unrelated shared paths, `nginx/**`, `deploy/**`, or the root `.gitignore`.
- **Demo seed isolation:** demo data only in `db/demo`, only with the `demo` Flyway location; never load into the `test` or production database. Use fixed UUIDs and fixed plaintext password `Demo1234!` (BCrypt cost 12) for all demo accounts; document the plaintext in the evidence/README only.
- **No binary assets in Git:** the demo seed references MinIO object keys but does not commit WebM files. `seed-demo-assets.sh` optionally generates/uploads small sample files when `ffmpeg` is available; otherwise it logs a warning and continues.
- **M5 seed mechanism (user-confirmed):** add `server/src/main/resources/seed/question-bank.json` for the real school-creation seed, and also insert demo categories/questions in `V900` for immediate queryability.
- **Permissions seed:** one `api_permissions` row per route in `02` §4.1–§4.7 plus the control/perception WS routes. Use a unique `code`; when one 02 permission code covers multiple routes, suffix it with `#<n>`. Store the original 02 permission code in `description`. Map account types per `02` §3.1.
- **Testing environment:** PostgreSQL 18 in Compose. Migrations are applied by the Flyway CLI service for rapid iteration and by the Spring test service for integration tests. The test database is `shijiangtai_test`; the demo database is `shijiangtai_demo`.
- Never run `git add -A` or `git commit -a`. Stage only the files created/modified by the current task.
- Commit after each task; do not push. Use commit message prefix `[DB]`.

## Review Focus

1. **FK order:** all 41 tables exist before `V10`; M4→M6, M7→M4, M6→M3, M3→M1/M2 FKs must validate. A table created after its FK target is fine because FKs are deferred.
2. **CHECK constraints in demo data:** `sessions` task/training constraints, `evaluations` source/total-score constraints, `final_scores` completed/non-completed constraints, `training_records.expires_at > selected_at`, `task_grading_configs` weight sum, `teacher_scores.teacher_total` range.
3. **Permission coverage:** every route in `02` has one `api_permissions` row; `account_type_permissions` matches the 02 §3.1 matrix; no duplicate `code`; admin has no M3/M4/M6/M7 business routes.
4. **Demo isolation:** demo data only in `shijiangtai_demo` with the `demo` location; `test` DB contains schema/system seed only; second school data supports cross-school tests.
5. **Repeatability:** reset test/demo DB → Flyway migrate → demo seed → queries; rerunning on a fresh DB must succeed; no migration checksum drift.

---

### Task 1: Migration harness, schemas, and Flyway Compose services

**Files:**
- Create: `server/src/main/resources/db/migration/V0__create_schemas.sql`
- Create: `server/docker/reset-test-db.sh`
- Modify: `server/docker/postgres/init/01-create-test-db.sql` (add demo DB)
- Modify: `docker-compose.yml` (add `flyway` and `flyway-demo` services)
- Create: `server/src/test/java/com/shijiangtai/m3classroom/persistence/MigrationIT.java` (skeleton)

**Interfaces:**
- Produces:
  - Schemas `m1_auth`, `m2_course`, `m3_classroom`, `m4_eval`, `m5_question`, `m6_training`, `m7_teacher`, `ops`.
  - Compose services:
    - `flyway` (profile `migrate`) applies `db/migration` to `shijiangtai_test`.
    - `flyway-demo` (profile `demo`) applies `db/migration` + `db/demo` to `shijiangtai_demo`.
  - `reset-test-db.sh [test|demo]` drops/recreates the target database.

- [ ] **Step 1: Create the branch and V0**

Run:

```bash
git switch -c feat/db-all-migrations
```

`V0__create_schemas.sql`:

```sql
CREATE SCHEMA IF NOT EXISTS m1_auth;
CREATE SCHEMA IF NOT EXISTS m2_course;
CREATE SCHEMA IF NOT EXISTS m3_classroom;
CREATE SCHEMA IF NOT EXISTS m4_eval;
CREATE SCHEMA IF NOT EXISTS m5_question;
CREATE SCHEMA IF NOT EXISTS m6_training;
CREATE SCHEMA IF NOT EXISTS m7_teacher;
CREATE SCHEMA IF NOT EXISTS ops;
```

- [ ] **Step 2: Add the Flyway Compose services and demo DB**

Append to `docker-compose.yml`:

```yaml
  flyway:
    profiles: ["migrate"]
    image: flyway/flyway:12
    depends_on:
      postgres: { condition: service_healthy }
    volumes:
      - ./server/src/main/resources/db/migration:/flyway/sql:ro
    command:
      - "-url=jdbc:postgresql://postgres:5432/shijiangtai_test"
      - "-user=shijiangtai"
      - "-password=${POSTGRES_PASSWORD:-shijiangtai_dev}"
      - "-locations=filesystem:/flyway/sql"
      - "migrate"

  flyway-demo:
    profiles: ["demo"]
    image: flyway/flyway:12
    depends_on:
      postgres: { condition: service_healthy }
    volumes:
      - ./server/src/main/resources/db/migration:/flyway/sql:ro
      - ./server/src/main/resources/db/demo:/flyway/demo:ro
    command:
      - "-url=jdbc:postgresql://postgres:5432/shijiangtai_demo"
      - "-user=shijiangtai"
      - "-password=${POSTGRES_PASSWORD:-shijiangtai_dev}"
      - "-locations=filesystem:/flyway/sql,filesystem:/flyway/demo"
      - "migrate"
```

Update `server/docker/postgres/init/01-create-test-db.sql`:

```sql
CREATE DATABASE shijiangtai_test;
CREATE DATABASE shijiangtai_demo;
```

`server/docker/reset-test-db.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
target="${1:-test}"
db="shijiangtai_${target}"
docker compose up -d postgres
docker compose exec -T postgres psql -U shijiangtai -d postgres -v ON_ERROR_STOP=1 \
  -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='${db}' AND pid <> pg_backend_pid();" \
  -c "DROP DATABASE IF EXISTS ${db};" \
  -c "CREATE DATABASE ${db} OWNER shijiangtai;"
```

Make it executable: `chmod +x server/docker/reset-test-db.sh`.

- [ ] **Step 3: Add the MigrationIT skeleton**

```java
@SpringBootTest
@ActiveProfiles("test")
class MigrationIT {
    @Autowired JdbcTemplate jdbc;

    @Test void allSchemasAndMigrationHistoryExist() {
        List<String> schemas = jdbc.queryForList(
            "select schema_name from information_schema.schemata", String.class);
        assertThat(schemas).contains("m1_auth", "m2_course", "m3_classroom",
            "m4_eval", "m5_question", "m6_training", "m7_teacher", "ops");
        List<String> versions = jdbc.queryForList(
            "select version from flyway_schema_history where success order by installed_rank", String.class);
        assertThat(versions).contains("0");
    }
}
```

- [ ] **Step 4: Verify V0**

```bash
./server/docker/reset-test-db.sh test
docker compose --profile migrate run --rm flyway
docker compose exec -T postgres psql -U shijiangtai -d shijiangtai_test \
  -c "select schema_name from information_schema.schemata where schema_name like 'm%' or schema_name='ops' order by 1;"
```

Expected: all eight schemas. Then run `docker compose --profile test run --rm test gradle test --tests '*MigrationIT' --no-daemon --console=plain`; expected pass.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/resources/db/migration/V0__create_schemas.sql \
  server/docker/reset-test-db.sh server/docker/postgres/init/01-create-test-db.sql \
  docker-compose.yml server/src/test/java/com/shijiangtai/m3classroom/persistence/MigrationIT.java
git commit -m "[DB] 迁移骨架、schema 与 Flyway 服务"
```

---

### Task 2: M1 tables (V1)

**Files:**
- Create: `server/src/main/resources/db/migration/V1__m1_auth.sql`
- Modify: `MigrationIT.java` (assert 11 M1 tables)

**Interfaces:**
- Produces tables: `schools`, `account_types`, `accounts`, `school_admins`, `majors`, `classes`, `student_profiles`, `teacher_profiles`, `teacher_majors`, `api_permissions`, `account_type_permissions`.

- [ ] **Step 1: Write V1**

Copy the `CREATE TABLE` statements from `12` §3.1 verbatim. Do **not** include the FK `ALTER TABLE` statements (V10) or the partial indexes (V11). Keep all inline CHECK/UNIQUE constraints.

- [ ] **Step 2: Verify table count**

```bash
./server/docker/reset-test-db.sh test
docker compose --profile migrate run --rm flyway
docker compose exec -T postgres psql -U shijiangtai -d shijiangtai_test \
  -c "select count(*) from information_schema.tables where table_schema='m1_auth';"
```

Expected: `11`.

- [ ] **Step 3: Extend MigrationIT**

Add an assertion that the 11 table names exist in `m1_auth`; use `information_schema.tables`.

- [ ] **Step 4: Run MigrationIT**

```bash
docker compose --profile test run --rm test \
  gradle test --tests '*MigrationIT' --no-daemon --console=plain
```

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/resources/db/migration/V1__m1_auth.sql \
  server/src/test/java/com/shijiangtai/m3classroom/persistence/MigrationIT.java
git commit -m "[DB] M1 账号与组织表"
```

---

### Task 3: M2 + M5 tables (V2, V3)

**Files:**
- Create: `server/src/main/resources/db/migration/V2__m2_course.sql`
- Create: `server/src/main/resources/db/migration/V3__m5_question.sql`
- Modify: `MigrationIT.java` (4 M2 + 3 M5 tables)

**Interfaces:**
- M2: `courses`, `course_majors`, `course_teachers`, `course_students`.
- M5: `libraries`, `categories`, `questions`.

- [ ] **Step 1: Write V2 and V3**

Copy `12` §4 and §6 `CREATE TABLE` statements verbatim; no FKs/indexes.

- [ ] **Step 2: Verify counts**

```bash
./server/docker/reset-test-db.sh test
docker compose --profile migrate run --rm flyway
docker compose exec -T postgres psql -U shijiangtai -d shijiangtai_test \
  -c "select table_schema, count(*) from information_schema.tables where table_schema in ('m2_course','m5_question') group by 1 order by 1;"
```

Expected: `m2_course 4`, `m5_question 3`.

- [ ] **Step 3: Extend MigrationIT and run**

Add table-name assertions; run `docker compose --profile test run --rm test gradle test --tests '*MigrationIT' --no-daemon --console=plain`. Expected: pass.

- [ ] **Step 4: Commit**

```bash
git add server/src/main/resources/db/migration/V2__m2_course.sql \
  server/src/main/resources/db/migration/V3__m5_question.sql \
  server/src/test/java/com/shijiangtai/m3classroom/persistence/MigrationIT.java
git commit -m "[DB] M2 课程与 M5 题库表"
```

---

### Task 4: M3 tables (V4)

**Files:**
- Create: `server/src/main/resources/db/migration/V4__m3_classroom.sql`
- Modify: `MigrationIT.java` (10 M3 tables)

**Interfaces:**
- M3: `tasks`, `task_assignments`, `virtual_students`, `sessions`, `submissions`, `recordings`, `recording_streams`, `session_texts`, `frames`, `events`.

- [ ] **Step 1: Write V4**

Copy `12` §5.1–§5.3 `CREATE TABLE` statements verbatim. Include all inline CHECK/UNIQUE constraints. Do not include the partial unique indexes from `12` §12 (V11) or FKs (V10).

- [ ] **Step 2: Verify count**

```bash
./server/docker/reset-test-db.sh test
docker compose --profile migrate run --rm flyway
docker compose exec -T postgres psql -U shijiangtai -d shijiangtai_test \
  -c "select count(*) from information_schema.tables where table_schema='m3_classroom';"
```

Expected: `10`.

- [ ] **Step 3: Extend MigrationIT and run**

Add table-name assertions; run the MigrationIT command. Expected: pass.

- [ ] **Step 4: Commit**

```bash
git add server/src/main/resources/db/migration/V4__m3_classroom.sql \
  server/src/test/java/com/shijiangtai/m3classroom/persistence/MigrationIT.java
git commit -m "[DB] M3 课堂、提交与录制表"
```

---

### Task 5: M4 + M7 + M6 + ops tables (V5–V8)

**Files:**
- Create: `server/src/main/resources/db/migration/V5__m4_eval.sql`
- Create: `server/src/main/resources/db/migration/V6__m7_teacher.sql`
- Create: `server/src/main/resources/db/migration/V7__m6_training.sql`
- Create: `server/src/main/resources/db/migration/V8__ops_cleanup_jobs.sql`
- Modify: `MigrationIT.java` (5 M4 + 3 M7 + 4 M6 + 1 ops tables)

**Interfaces:**
- M4: `evaluations`, `dimension_scores`, `issues`, `input_snapshots`, `evaluation_jobs`.
- M7: `task_grading_configs`, `teacher_scores`, `final_scores`.
- M6: `training_templates`, `training_session_meta`, `training_records`, `training_materials`.
- ops: `cleanup_jobs`.

- [ ] **Step 1: Write V5–V8**

Copy `12` §8 (M4), §9 (M7), §7 (M6), §10 (ops) verbatim. No FKs/indexes.

- [ ] **Step 2: Verify counts**

```bash
./server/docker/reset-test-db.sh test
docker compose --profile migrate run --rm flyway
docker compose exec -T postgres psql -U shijiangtai -d shijiangtai_test \
  -c "select table_schema, count(*) from information_schema.tables where table_schema in ('m4_eval','m6_training','m7_teacher','ops') group by 1 order by 1;"
```

Expected: `m4_eval 5`, `m6_training 4`, `m7_teacher 3`, `ops 1`.

- [ ] **Step 3: Extend MigrationIT and run**

Add table-name assertions; run MigrationIT. Expected: pass.

- [ ] **Step 4: Commit**

```bash
git add server/src/main/resources/db/migration/V5__m4_eval.sql \
  server/src/main/resources/db/migration/V6__m7_teacher.sql \
  server/src/main/resources/db/migration/V7__m6_training.sql \
  server/src/main/resources/db/migration/V8__ops_cleanup_jobs.sql \
  server/src/test/java/com/shijiangtai/m3classroom/persistence/MigrationIT.java
git commit -m "[DB] M4 评测、M7 评分、M6 训练与运维表"
```

---

### Task 6: All foreign keys (V10)

**Files:**
- Create: `server/src/main/resources/db/migration/V10__foreign_keys.sql`
- Modify: `MigrationIT.java` (FK assertions + violation test)

**Interfaces:**
- Produces every FK in `12` §11, including M3→M1/M2, M4→M3/M6, M6→M3/M1/M2, M7→M3/M4/M1/M2.

- [ ] **Step 1: Write V10**

Copy the `ALTER TABLE ... ADD CONSTRAINT fk_...` statements from `12` §11 verbatim. Do not reorder or rename constraints. Keep the `ON DELETE` behavior exactly as documented.

- [ ] **Step 2: Verify all FKs validate**

```bash
./server/docker/reset-test-db.sh test
docker compose --profile migrate run --rm flyway
docker compose exec -T postgres psql -U shijiangtai -d shijiangtai_test \
  -c "select count(*) from pg_constraint where contype='f' and connamespace in ('m1_auth'::regnamespace,'m2_course'::regnamespace,'m3_classroom'::regnamespace,'m4_eval'::regnamespace,'m5_question'::regnamespace,'m6_training'::regnamespace,'m7_teacher'::regnamespace);"
```

Expected: a non-zero count; the migration must not fail.

- [ ] **Step 3: Add FK assertions and a violation test**

In `MigrationIT`:
- Assert every expected FK name exists in `pg_constraint`.
- Attempt to insert a `m3_classroom.tasks` row with a random `course_id`; expect a `DataIntegrityViolationException`/SQLException. Wrap the insert in a transaction that rolls back or use `assertThatThrownBy`.

- [ ] **Step 4: Run MigrationIT**

Run the MigrationIT command. Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/resources/db/migration/V10__foreign_keys.sql \
  server/src/test/java/com/shijiangtai/m3classroom/persistence/MigrationIT.java
git commit -m "[DB] 全部跨模块外键与级联"
```

---

### Task 7: All indexes (V11)

**Files:**
- Create: `server/src/main/resources/db/migration/V11__indexes.sql`
- Modify: `MigrationIT.java` (index assertions)

**Interfaces:**
- Produces every index/partial unique index in `12` §12.

- [ ] **Step 1: Write V11**

Copy the `CREATE UNIQUE INDEX` / `CREATE INDEX` statements from `12` §12 verbatim. Do not duplicate inline UNIQUE constraints.

- [ ] **Step 2: Verify index count**

```bash
./server/docker/reset-test-db.sh test
docker compose --profile migrate run --rm flyway
docker compose exec -T postgres psql -U shijiangtai -d shijiangtai_test \
  -c "select schemaname, count(*) from pg_indexes where schemaname like 'm%' group by 1 order by 1;"
```

Expected: each module has non-zero indexes; the migration must not fail.

- [ ] **Step 3: Add index assertions and run**

Assert key indexes by name: `uq_school_admins_account`, `uq_course_teachers_active`, `uq_sessions_task_active`, `uq_training_templates_published`, `idx_final_scores_task_status`, etc. Run MigrationIT. Expected: pass.

- [ ] **Step 4: Commit**

```bash
git add server/src/main/resources/db/migration/V11__indexes.sql \
  server/src/test/java/com/shijiangtai/m3classroom/persistence/MigrationIT.java
git commit -m "[DB] 全部业务索引与部分唯一索引"
```

---

### Task 8: System seed — account types and permissions (V12)

**Files:**
- Create: `server/src/main/resources/db/migration/V12__seed_account_types_and_permissions.sql`
- Modify: `MigrationIT.java` (seed assertions)

**Interfaces:**
- Produces:
  - `m1_auth.account_types`: `student` (`00000000-0000-0000-0000-000000000101`), `teacher` (`...0102`), `admin` (`...0103`).
  - `m1_auth.api_permissions`: one row per route in `02` §4.1–§4.7 and the control/perception WS routes.
  - `m1_auth.account_type_permissions`: role matrix per `02` §3.1.

- [ ] **Step 1: Insert account types**

Use fixed UUIDs above and `name` values `学生` / `教师` / `管理员`.

- [ ] **Step 2: Generate the route/permission rows**

For every route in the `02` API tables plus:
- `WS /ws/v1/sessions/{session_id}`
- `WS /ws/v1/sessions/{session_id}/perception`

Use:
- `code`: the 02 permission code when unique; otherwise `<permission_code>#<n>` to satisfy `UNIQUE(code)`.
- `name`: a short route description.
- `module`: `M1`–`M7` or `WS`.
- `protocol`: `REST` or `WS`.
- `method`: HTTP method or `CONNECT`.
- `path_pattern`: exact path from 02.
- `description`: the original 02 permission code.
- `status`: `active`.

Do not omit actuator/health or public routes that are whitelisted by Nginx; if they are not in `api_permissions`, add a comment explaining the whitelist. The registry defaults to deny.

- [ ] **Step 3: Map account types to permissions**

Insert `account_type_permissions` per `02` §3.1:
- Student: `course:read`, `course:join`/`leave`, `task:read`, `session:start/read/submit/replay/events:read`, `evaluation:read`, `training:template:read`, `training:session:start`, `training:record:read/manage`, `student_result:read`, `ws:session`.
- Teacher: `course:read/create/manage/members:read/manage`, `task:read/create/publish/close/assignments:read`, `session:read/replay/events:read`, `evaluation:read`, `question_bank:read/manage`, `training:template:read/manage`, `teacher_eval:config/read/write/export`.
- Admin: `admin:school`, `admin:org`, `admin:teacher_majors`, `course:read`, `course:manage`, `course:archive`, `course:members:read/manage`, `question_bank:read/manage`.
- Do not grant admin `task:*`, `session:*`, `evaluation:*`, `teacher_eval:*`, `training:*`, or `student_result:*`.
- Map every route row; the `description`/permission code is the join key.

- [ ] **Step 4: Verify the seed**

```bash
./server/docker/reset-test-db.sh test
docker compose --profile migrate run --rm flyway
docker compose exec -T postgres psql -U shijiangtai -d shijiangtai_test \
  -c "select code from m1_auth.account_types order by code;" \
  -c "select count(*) from m1_auth.api_permissions;" \
  -c "select at.code, count(*) from m1_auth.account_type_permissions p join m1_auth.account_types at on at.id=p.account_type_id group by 1 order by 1;"
```

Expected: `admin/student/teacher`; permissions > 0; three role counts > 0. Then run MigrationIT; add assertions for 3 account types and non-zero permission counts.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/resources/db/migration/V12__seed_account_types_and_permissions.sql \
  server/src/test/java/com/shijiangtai/m3classroom/persistence/MigrationIT.java
git commit -m "[DB] 账号类型、接口权限与角色映射 seed"
```

---

### Task 9: M5 question-bank JSON seed

**Files:**
- Create: `server/src/main/resources/seed/question-bank.json`
- Test: `server/src/test/java/com/shijiangtai/m3classroom/persistence/QuestionBankSeedTest.java`

**Interfaces:**
- JSON shape (per `04-M5` §4.1):
  ```json
  {
    "seed_version": "2026-11-04",
    "categories": [
      {"name": "教育理念", "sort_order": 1},
      {"name": "班级管理", "sort_order": 2},
      {"name": "应急应变", "sort_order": 3}
    ],
    "questions": [
      {"category": "教育理念", "stem": "...", "answer_points": "..."},
      {"category": "班级管理", "stem": "...", "answer_points": "..."}
    ]
  }
  ```
- At least 3 categories and 6 questions; every question has non-blank `stem`/`answer_points` ≥20 chars; category names match a category entry.

- [ ] **Step 1: Write the JSON**

Use the shape above; include 2 questions per category. The content is demo/placeholder; do not copy copyrighted material.

- [ ] **Step 2: Write the test**

Use Jackson 3 to parse the file; assert 3 categories and ≥6 questions; assert each question has non-blank stem/answer points and a valid category.

- [ ] **Step 3: Run the test**

```bash
docker compose --profile test run --rm test \
  gradle test --tests '*QuestionBankSeedTest' --no-daemon --console=plain
```

Expected: pass.

- [ ] **Step 4: Commit**

```bash
git add server/src/main/resources/seed/question-bank.json \
  server/src/test/java/com/shijiangtai/m3classroom/persistence/QuestionBankSeedTest.java
git commit -m "[DB] M5 题库 JSON 种子"
```

---

### Task 10: Demo seed part 1 — schools, accounts, courses, question bank (V900)

**Files:**
- Create: `server/src/main/resources/db/demo/V900__demo_seed.sql`
- Test/verification: SQL queries in the plan (no JUnit yet)

**Interfaces:**
- Fixed UUID prefixes:
  - School A `10000000-0000-0000-0000-000000000001`; School B `...0002`.
  - Accounts: admin A `2000...0001`, admin B `...0002`, teacher A1 `...0011`, teacher A2 `...0012`, teacher B `...0013`, students A `...0101`–`...0110`, student B `...0111`.
  - Majors `3000...0001`/`0002`; classes `...0011`/`0012`; courses `...0101`–`0104`.
  - M5 library A `5000...0001`; categories `...0011`/`0012`; questions `...0021`–`...0026`.
- Demo password: `Demo1234!`, BCrypt cost 12. Generate the hash:
  ```bash
  python3 -c 'import bcrypt; print(bcrypt.hashpw(b"Demo1234!", bcrypt.gensalt(rounds=12)).decode())'
  ```
  Use the same hash for every demo account. Do not commit a plaintext password into production config; the demo seed is dev-only.

- [ ] **Step 1: Insert schools and accounts**

Insert School A/B, 1 admin each, 2 teachers + 10 students in A, 1 teacher + 1 student in B. Use `account_type_id` from V12 (`...0101`/`...0102`/`...0103`). Insert `school_admins`, `majors`, `classes`, `student_profiles`, `teacher_profiles`, `teacher_majors`. All `accounts.school_id` set; admin A/B also set (MVP allows school-bound admins).

- [ ] **Step 2: Insert courses and memberships**

Courses A: `语文教学论` (`...0101`), `数学教学论` (`...0102`), `英语教学论` (`...0103`); course B: `物理教学论` (`...0104`). Insert `course_majors`, `course_teachers` (teacher A1/A2 active; course B teacher B), `course_students` (students distributed across A courses; course B student B). Use `join_method='class'` or `'admin'`.

- [ ] **Step 3: Insert M5 library/categories/questions**

Insert `libraries` for school A with `seed_version='2026-11-04'`; categories `教育理念`, `班级管理`, `应急应变`; six questions with fixed UUIDs. Keep `created_by/updated_by` NULL. The interview task will use the first two questions.

- [ ] **Step 4: Verify part 1**

```bash
./server/docker/reset-test-db.sh demo
docker compose --profile demo run --rm flyway-demo
docker compose exec -T postgres psql -U shijiangtai -d shijiangtai_demo \
  -c "select count(*) from m1_auth.schools; select count(*) from m1_auth.accounts; select count(*) from m2_course.courses; select count(*) from m5_question.questions;"
```

Expected: 2 schools, 16 accounts (2 admins + 3 teachers + 11 students), 4 courses, 6 questions.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/resources/db/demo/V900__demo_seed.sql
git commit -m "[DB] Demo seed：学校、账号、课程与题库"
```

---

### Task 11: Demo seed part 2 — M3 tasks and assignments

**Files:**
- Modify: `server/src/main/resources/db/demo/V900__demo_seed.sql`

**Interfaces:**
- Tasks:
  - Free published `6000...0001`: weights `{content:30,expression:20,interaction:20,board:15,time:15}`, reference material JSON, 3 virtual students `6100...0001`–`0003`, config_version 1, published_at.
  - Scenario published `...0002`: weights same, scenario JSON, 2 virtual students, config_version 1.
  - Interview published `...0003`: total 300s, question_config with 2 M5 question IDs, weights `{content:40,expression:30,time:30}`, config_snapshot with the two questions.
  - Draft `...0004`: incomplete but valid; no assignments; weights default.
  - Closed `...0005`: published then closed_at; assignments remain.
- Assignments: free task to students A1–A5; scenario to A6–A7; interview to A8–A10; closed task to A1/A2. Use `class_id_snapshot`; status `assigned` except free task students 1–5 later set `submitted` in part 3.

- [ ] **Step 1: Insert tasks**

Write the five rows. For `reference_material` use:
```json
{"material_id":"...","filename":"荷塘月色.txt","object_key":"materials/...","mime":"text/plain","size":1234,"parsed_text":"...","char_count":1234,"parse_status":"parsed"}
```
For snapshots, use the `05C` §4.6 shape. The free/scenario snapshots include material/virtual students; the interview snapshot includes ordered questions with `answer_points`.

- [ ] **Step 2: Insert virtual students**

3 rows for the free task, 2 for the scenario task; parameters 0–1; names ≤20; personality ≤500; `seat_no`/`sort_order` unique within task.

- [ ] **Step 3: Insert assignments**

Free task: 5 students; scenario: 2; interview: 3; closed: 2. `class_id_snapshot` from `m1_auth.classes`; status `assigned` initially. The draft task has no assignments.

- [ ] **Step 4: Verify part 2**

Reset demo, run `flyway-demo`, query:
```sql
select status, count(*) from m3_classroom.tasks group by 1 order by 1;
select task_id, count(*) from m3_classroom.task_assignments group by 1 order by 1;
select task_id, count(*) from m3_classroom.virtual_students group by 1 order by 1;
```
Expected: 3 published, 1 draft, 1 closed; assignment counts 5/2/3/2; virtual student counts 3/2.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/resources/db/demo/V900__demo_seed.sql
git commit -m "[DB] Demo seed：任务、虚拟学生与分配名单"
```

---

### Task 12: Demo seed part 3 — sessions, submissions, context, evaluations, scores, training, ops

**Files:**
- Modify: `server/src/main/resources/db/demo/V900__demo_seed.sql`

**Interfaces:**
- 5 task sessions for the free task, 5 submissions, recordings/streams, texts/frames/events.
- 5 evaluations covering `completed`, `pending`, `completed+pending_teacher`, `partial`, `failed`; corresponding final scores `completed`, `pending_ai`, `pending_teacher`, `ai_partial`, `ai_failed`.
- M7 grading config (AI 70 / teacher 30), one teacher score for the completed submission, final scores for all five.
- M6 training template + training session/meta + training record + training material.
- One `ops.cleanup_jobs` row.

- [ ] **Step 1: Insert sessions/submissions/recordings/context**

For students A1–A5 on the free task:
- `sessions` status `submitted`, `session_type='task'`, `task_id` free, `course_id` course A1, `mode='free'`, `attempt_no=1`, `started_at`/`ended_at`/`duration_ms`, `stream_offsets` JSON.
- `submissions` unique per task/student/session; class snapshot; `status='submitted'`.
- `recordings` status `uploaded`; `recording_streams` camera/screen/audio with object keys, part sizes, durations, offsets, status `uploaded`.
- `session_texts` teacher/student lines; `frames` processed; `events` seq 1..n with payload JSON.
- Update the free task assignments for these students to `submitted`.

Use timestamps in 2026-11; all `timestamptz` UTC.

- [ ] **Step 2: Insert M4 evaluations**

For the five submissions:
| Submission | Evaluation status | total_score | final_scores.status |
|---|---|---|---|
| 1 | completed | 86.50 | completed |
| 2 | pending | NULL | pending_ai |
| 3 | completed | 90.00 | pending_teacher |
| 4 | partial | NULL | ai_partial |
| 5 | failed | NULL | ai_failed |

Insert `evaluations` with `source_type='task_submission'`, `source_id=submission_id`, `submission_id` non-null, `training_record_id` NULL, session/task/course/student, `weights` JSON, `attempt_count`, `warnings`/`partial_reasons` arrays, `error_code`/`error_message` for failed, `started_at`/`completed_at`, `model_version`/`prompt_version`.
Insert `dimension_scores` for completed/partial evaluations (5 dimensions), `issues` (2–3 per evaluation), `input_snapshots` (one per evaluation), `evaluation_jobs` (statuses matching).

- [ ] **Step 3: Insert M7 scores**

- `task_grading_configs`: free task, ai 70, teacher 30, scoring_mode `dimension`, config_version 1, updated_by teacher A1.
- `teacher_scores`: submission 1, teacher_total 80.00, dimension_scores JSON, version 1, graded_by teacher A1; submission 3 has no teacher score (pending_teacher).
- `final_scores`: all five submissions with statuses above. For completed: `ai_evaluation_id` evaluation 1, `teacher_score_id` teacher score 1, `ai_total=86.50`, `teacher_total=80.00`, `final_total=84.55`, `final_dimensions` JSON, `config_version=1`. For non-completed: totals/dimensions NULL; reference the evaluation IDs where applicable.

- [ ] **Step 4: Insert M6 and ops**

- `training_templates`: source free task, config_version 1, course A1, `training_level='progressive'`, `classroom_mode='free'`, `config_snapshot` (copy the free task snapshot), created_by teacher A1, status `published`.
- `sessions`: one training session `session_type='training'`, `task_id=NULL`, `course_id=course A1`, student A1, mode `free`, status `selected`, timestamps/duration.
- `training_session_meta`: session, template, level, effective_config_snapshot.
- `training_records`: session, student A1, template, selected_at, expires_at (selected + 30 days), status `selected`.
- `training_materials`: student A1, filename/mime/size/object_key/parsed_text/char_count, status `uploaded`, expires_at +24h.
- `ops.cleanup_jobs`: root_type `temp_attempt`, root_id a task session, status `pending`.

- [ ] **Step 5: Verify part 3 and constraints**

Reset demo, run `flyway-demo`, query counts:
```sql
select count(*) from m3_classroom.sessions;
select count(*) from m3_classroom.submissions;
select count(*) from m3_classroom.recordings;
select count(*) from m3_classroom.recording_streams;
select count(*) from m3_classroom.session_texts;
select count(*) from m3_classroom.frames;
select count(*) from m3_classroom.events;
select status, count(*) from m4_eval.evaluations group by 1 order by 1;
select status, count(*) from m7_teacher.final_scores group by 1 order by 1;
select count(*) from m6_training.training_records;
select count(*) from ops.cleanup_jobs;
```
Expected: 6 sessions (5 task + 1 training), 5 submissions, 5 recordings, 15 streams, non-zero texts/frames/events, evaluation statuses `completed 2 / pending 1 / partial 1 / failed 1`, final statuses `completed 1 / pending_ai 1 / pending_teacher 1 / ai_partial 1 / ai_failed 1`, 1 training record, 1 cleanup job. The migration must not fail any CHECK/FK.

- [ ] **Step 6: Commit**

```bash
git add server/src/main/resources/db/demo/V900__demo_seed.sql
git commit -m "[DB] Demo seed：课堂、提交、评测、评分与训练数据"
```

---

### Task 13: Demo profile, assets script, full verification, evidence, and M3A plan update

**Files:**
- Create: `server/src/main/resources/application-demo.yml`
- Create: `server/docker/seed-demo-assets.sh`
- Modify: `server/README.md` (demo commands + credentials)
- Modify: `docs/superpowers/plans/2026-11-04-m3a-task-configuration.md` (remove the temporary V4/FK deferral; reference the all-module migrations)
- Create: `docs/team/evidence/2026-11-04-all-module-migrations-demo-seed.md`
- Modify: `docs/team/02-进度看板.md` (§5 update log only)
- Modify: `MigrationIT.java` (final table/FK/index/seed assertions)

**Interfaces:**
- `application-demo.yml`:
  ```yaml
  spring:
    flyway:
      locations: classpath:db/migration,classpath:db/demo
  ```
- `seed-demo-assets.sh` checks for `ffmpeg` and `docker`; if available, generates 3 short WebM files and uploads them with `mc` to `shijiangtai` bucket under the demo object keys; otherwise logs `SKIP: ffmpeg not found` and exits 0.
- M3A plan Task 10 uses `V4__m3_classroom.sql` from this plan; it no longer creates its own V4 or defers FKs.

- [ ] **Step 1: Add the demo profile and asset script**

Write `application-demo.yml` and `seed-demo-assets.sh`; make the script executable. Document demo DB URL (`jdbc:postgresql://localhost:5432/shijiangtai_demo?stringtype=unspecified`), credentials (`shijiangtai` / `shijiangtai_dev`), and demo account password (`Demo1234!`) in `server/README.md`.

- [ ] **Step 2: Update the M3A plan**

In `docs/superpowers/plans/2026-11-04-m3a-task-configuration.md`:
- Remove the “temporary schema deviation” line from Global Constraints.
- Replace references to `V4__m3_classroom_tasks.sql` with `V4__m3_classroom.sql` (provided by this plan).
- In Task 10, change “Write the Flyway migration” to “Verify the all-module V4/V10 migrations exist; do not create a second migration.”
- Remove the “M1/M2 FKs deferred” item from Out of Scope; state all FKs are added by V10.
- Add a prerequisite note: “Execute `2026-11-04-all-module-migrations-demo-seed.md` first.”

- [ ] **Step 3: Run the full migration and demo verification**

```bash
./server/docker/reset-test-db.sh test
docker compose --profile migrate run --rm flyway
docker compose --profile test run --rm test gradle test --tests '*MigrationIT' --no-daemon --console=plain
./server/docker/reset-test-db.sh demo
docker compose --profile demo run --rm flyway-demo
docker compose exec -T postgres psql -U shijiangtai -d shijiangtai_demo \
  -c "select count(*) from m1_auth.schools;" \
  -c "select count(*) from m1_auth.accounts;" \
  -c "select count(*) from m2_course.courses;" \
  -c "select count(*) from m5_question.questions;" \
  -c "select count(*) from m3_classroom.tasks;" \
  -c "select count(*) from m3_classroom.submissions;" \
  -c "select count(*) from m4_eval.evaluations;" \
  -c "select count(*) from m7_teacher.final_scores;" \
  -c "select count(*) from m6_training.training_records;"
```

Expected: all migrations V0–V12 + V900 applied; the test DB has schema/system seed only; the demo DB has the full dataset. Run `docker compose --profile demo run --rm flyway-demo info` (or `flyway info`) and record the versions/checksums. If `seed-demo-assets.sh` runs without ffmpeg, record the skip; DB seed still passes.

- [ ] **Step 4: Write evidence and update the progress board**

Evidence file:
- Migration versions, table counts, FK/index counts, seed counts, demo row counts.
- Cross-module authorization (user, 2026-11-04) and the full file list with owners.
- Demo credentials and the demo DB name.
- What is deferred (M5 Java SeedService, MinIO sample assets if ffmpeg missing, M1 permission registry runtime wiring).
- The exact commands and outputs.

Progress board:
- Add one §5 row: `2026-11-04 | 当前使用者（经用户确认） | 全部模块 / 数据库 | 完成 V0–V12 全部表迁移、系统权限 seed、V900 demo seed；证据见 evidence/2026-11-04-all-module-migrations-demo-seed.md | 10%`.
- Do not change module gate rows or other owners’ rows; this is schema/seed infrastructure, not module completion.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/resources/application-demo.yml server/docker/seed-demo-assets.sh server/README.md \
  docs/superpowers/plans/2026-11-04-m3a-task-configuration.md \
  docs/superpowers/plans/2026-11-04-all-module-migrations-demo-seed.md \
  docs/team/evidence/2026-11-04-all-module-migrations-demo-seed.md docs/team/02-进度看板.md \
  server/src/test/java/com/shijiangtai/m3classroom/persistence/MigrationIT.java
git commit -m "[DB] Demo profile、全量验证与证据"
```

---

## Out of Scope

- M1/M2/M3/M4/M5/M6/M7 Java implementations; this plan is schema/seed only.
- M5 `SeedService` runtime copy from `question-bank.json`; the JSON is provided, but the Java service is implemented in the M5 plan.
- Permission registry runtime loading and Nginx `auth_request`; the seed rows are provided, but runtime wiring is M1/Foundation.
- Real MinIO sample WebM assets when `ffmpeg` is unavailable; the DB object keys remain, and the script logs a skip.
- E2E tests; they come after the application modules.

## Plan Self-Review

**Spec coverage:** `12` §1.1 schemas, §3–§10 all 41 tables, §11 all FKs, §12 all indexes, §14 migration order (adapted to the user-confirmed team-prefix scheme), §15 system/demo seed; `01` §2–§11 table/constraint/index/seed rules; `02` §3–§4 permission matrix; `10` §4 demo data scope. All are mapped to Tasks 1–13.

**Step scan:** Each task has exact files, a Flyway/reset command, SQL verification, and a commit. The table migrations are copied from the design doc, not invented. The demo seed is split into three reviewable parts but lives in one `V900` file.

**Type consistency:** Migration versions follow the user-confirmed order; demo UUID prefixes are fixed; `V12` account-type IDs are referenced by `V900`; the M3A plan is updated to consume `V4__m3_classroom.sql` and `V10__foreign_keys.sql`.

**Review Focus coverage:** FK order (Task 6), demo CHECK constraints (Task 12), permission coverage (Task 8), demo isolation (Tasks 10–13), repeatability (Tasks 1–13 reset/migrate commands).
