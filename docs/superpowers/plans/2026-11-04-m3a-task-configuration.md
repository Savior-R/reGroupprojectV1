# M3A Task Configuration (Docker + PostgreSQL) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a runnable, Dockerized M3A backend slice: task draft/create/edit/delete, reference material parsing, virtual-student config, rubric weights, interview question selection, publish/close, immutable config snapshot, PostgreSQL persistence, MinIO material storage, and teacher/student task views.

**Architecture:** Spring Boot 4.1.2 + JPA/Hibernate 7.4 + Flyway + PostgreSQL 18, with Redis and MinIO provisioned in Docker Compose. Code follows the documented hexagonal layout inside `com.shijiangtai.m3classroom`: domain aggregates/value objects with no Spring/Jackson/JPA imports; application services behind ports; API controllers/DTOs; infrastructure JPA/MinIO adapters. M2 course data and M5 question data remain ports with in-memory dev adapters because those modules do not exist yet. All builds and tests run through Docker; no host JDK/Gradle is required.

**Tech Stack:** Java 21, Spring Boot 4.1.2, Gradle 8.14.3 (Docker `gradle:8.14.3-jdk21`), Jackson 3, Hibernate 7.4, Flyway 12.4.0, PostgreSQL 18, MinIO Java SDK 8.5.17, PDFBox 3.0.8, POI 6.0.0, Docker Compose v5, JUnit 5, AssertJ, MockMvc, ArchUnit 1.5.1.

**Spec:** `docs/design/05C-M3-任务配置与结构化面试.md` (§3–§8), `docs/implementation/04-M3-实现设计.md` (§3.1, §4.1–§4.2, §6), `docs/design/02-API与实时协议.md` (§3, §4.3, §8), `docs/design/12-数据库设计.md` (§5.1), `docs/design/10-测试与验收.md` (§6.4), `docs/design/15-代码规范与骨架.md`.

**Prerequisite:** execute `docs/superpowers/plans/2026-11-04-all-module-migrations-demo-seed.md` first. It provides `V4__m3_classroom.sql`, all foreign keys (`V10__foreign_keys.sql`), indexes (`V11__indexes.sql`), and the demo seed. This plan must not create a second V4 or defer FKs.

## Global Constraints

- Java 21 LTS; Spring Boot 4.1.2; Gradle 8.14.3; Hibernate 7.4; Jackson 3; PostgreSQL 18; Flyway 12.4.0.
- No host JDK/Gradle installation. Build, unit tests, integration tests, and the app run through Docker. The Docker engine is Docker Desktop 4.84 with WSL integration (`docker` CLI, Compose v5.3.1).
- No Lombok. Records for commands, views, value objects. Constructor injection only.
- Domain (`m3classroom/domain/**`, `shared/domain/**`) must not import Spring, Jackson, JPA, HTTP, or `m3classroom.api/**` / `application/**` / `infrastructure/**`.
- Application must not import `m3classroom.api/**` or `m3classroom.infrastructure/**`.
- API must not import `m3classroom.infrastructure/**`.
- JSON is `snake_case`; Java components are `camelCase`. Times are `Instant` in ISO-8601 UTC. Weights are integers 0–100. Virtual-student parameters are `BigDecimal` in [0,1] with scale 2.
- All business errors use `DomainException(ErrorCode, message, details)`; controllers never catch exceptions.
- M3A code only. Do not touch M3B/M3C, `ai/**`, `frontend/**`, or other modules’ tests/docs.
- **Cross-module scaffold authorization (user, 2026-11-04):** create `server/Dockerfile`, `server/.dockerignore`, root `docker-compose.yml`, and `server/docker/postgres/init/**` only. Do not modify `nginx/**`, `deploy/**`, the root `.gitignore`, `settings.gradle` at the repo root, or other shared paths. Build outputs (`server/build`, `server/.gradle`) stay untracked and are never staged.
- **Schema prerequisite:** the all-module migration plan creates `V4__m3_classroom.sql`, all foreign keys in `V10__foreign_keys.sql`, and indexes in `V11__indexes.sql`. Do not create a second V4 or defer FKs in this plan.
- **Storage:** PostgreSQL is the durable store for M3A tasks. Redis and MinIO are provisioned in Compose for M3B/C; M3A uses MinIO only for material objects. M2/M5 are not implemented; their ports use in-memory dev adapters.
- **JSONB binding:** JSON columns are `jsonb`; JPA maps them as `String` and the JDBC URL must include `stringtype=unspecified` (all Compose/Test URLs do). Do not silently change the schema if binding fails.
- **Transactions:** `@Transactional` only on application services. Publish uses a row lock (`PESSIMISTIC_WRITE`); events publish after commit; MinIO uploads happen outside DB transactions.
- **Testing environment:** integration tests run in the `test` Compose service against the `postgres` service (`shijiangtai_test` database), not Testcontainers. This is a deliberate environment choice; record it in the evidence file.
- **Dev identity:** `X-Account-Id`, `X-School-Id`, `X-Account-Type` headers. JWT and URL-level permissions belong to M1/Foundation and are out of scope.
- Never run `git add -A` or `git commit -a`: the working tree already contains unrelated staged deletions (`backend/**`, `README.md`, etc.). Stage only files created/modified by the current task.
- Commit after each task; do not push. Use commit message prefix `[M3A]`.

## Review Focus

1. **Mode switch clears incompatible config and deletes the old material object.** Switching `free`/`scenario`/`interview` must clear material, virtual students, scenario, and question config, reset weights to the new mode’s defaults, and remove the old MinIO object after commit.
2. **Publish is all-or-nothing.** Zero active students, M5 outage, disabled question, or missing answer points must roll back the DB transaction: task stays `draft`, no assignments, no snapshot, no event.
3. **Concurrent publish yields exactly one success.** Two simultaneous publishes must produce one `published` result and one `TASK_ALREADY_PUBLISHED` (409), with exactly one assignment set.
4. **Student-facing views never leak privileged config.** Student task list/detail must not expose `rubric_weights`, `reference_material`, `answer_points`, virtual-student parameters/personality, or all interview questions.
5. **JSONB/transaction correctness.** Every JSON column (`reference_material`, `scenario_config`, `rubric_weights`, `question_config`, `config_snapshot`) round-trips through PostgreSQL `jsonb` correctly; a failed publish leaves no partial rows.

---

### Task 1: Dockerized scaffold, Compose full stack, and minimal shared kernel

**Files:**
- Create: `server/settings.gradle.kts`
- Create: `server/build.gradle.kts`
- Create: `server/Dockerfile`
- Create: `server/.dockerignore`
- Create: `server/src/main/resources/application.yml`
- Create: `server/src/main/resources/application-docker.yml`
- Create: `server/src/main/resources/application-test.yml`
- Create: `server/src/main/java/com/shijiangtai/ServerApplication.java`
- Create: `server/src/main/java/com/shijiangtai/shared/domain/{AccountId,SchoolId,CourseId,ClassId,TaskId,QuestionId,CategoryId,PageResult,ErrorCode,DomainException}.java`
- Create: `server/src/main/java/com/shijiangtai/shared/application/{AccountType,CurrentUser,CurrentUserProvider,IdGenerator,ClockProvider,EventPublisher}.java`
- Create: `docker-compose.yml` (repository root)
- Create: `server/docker/postgres/init/01-create-test-db.sql`
- Create: `server/README.md`
- Create: `server/src/test/java/com/shijiangtai/ServerApplicationTests.java` (placeholder; full context test arrives in Task 10)

**Interfaces:**
- Produces:
  - `record AccountId(UUID value)` and sibling ID records (`SchoolId`, `CourseId`, `ClassId`, `TaskId`, `QuestionId`, `CategoryId`), each rejecting null.
  - `record PageResult<T>(List<T> items, long total, int page, int size)`.
  - `enum ErrorCode` with `int httpStatus()` for the M3A/generic codes listed in `02` §8.2.1: `VALIDATION_ERROR(400)`, `UNAUTHORIZED(401)`, `FORBIDDEN(403)`, `CROSS_SCHOOL_ACCESS(403)`, `ROLE_NOT_ALLOWED(403)`, `RESOURCE_NOT_FOUND(404)`, `TASK_ALREADY_PUBLISHED(409)`, `COURSE_ARCHIVED(422)`, `COURSE_ENDED(422)`, `TASK_NOT_DRAFT(422)`, `TASK_CLOSED(422)`, `TASK_NO_STUDENTS(422)`, `TASK_CONFIG_INVALID(422)`, `REFERENCE_MATERIAL_MISSING(422)`, `MATERIAL_NOT_APPLICABLE(422)`, `MATERIAL_PARSE_FAILED(422)`, `MATERIAL_TOO_LARGE(422)`, `MATERIAL_TYPE_UNSUPPORTED(422)`, `WEIGHT_SUM_INVALID(422)`, `QUESTION_COUNT_INVALID(422)`, `QUESTION_DUPLICATED(422)`, `QUESTION_BANK_EMPTY(422)`, `QUESTION_DISABLED(422)`, `ANSWER_POINTS_MISSING(422)`, `TEACHER_NOT_IN_GROUP(403)`, `INTERNAL_ERROR(500)`, `SERVICE_UNAVAILABLE(503)`.
  - `class DomainException extends RuntimeException` with `ErrorCode code()`, `Map<String,Object> details()`, and `static DomainException fields(ErrorCode, String, List<Map<String,Object>>)`.
  - `enum AccountType { STUDENT, TEACHER, ADMIN }`.
  - `record CurrentUser(AccountId accountId, SchoolId schoolId, AccountType type)`.
  - `interface CurrentUserProvider { CurrentUser require(); }`.
  - `interface IdGenerator { UUID nextId(); }`.
  - `interface ClockProvider { Instant now(); }`.
  - `interface EventPublisher { void publish(Object event); }`.

- [ ] **Step 1: Verify Docker desktop and Compose**

Run:

```bash
docker version
docker compose version
docker info | grep -A2 'Registry Mirrors'
```

Expected: Client/Server 29.x, Compose v5.x, registry mirrors include `docker.m.daocloud.io` and `docker.1ms.run`. If the engine is not running, start Docker Desktop first; do not install a host JDK.

- [ ] **Step 2: Write the Gradle build**

`server/settings.gradle.kts`:

```kotlin
rootProject.name = "shijiangtai-server"
```

`server/build.gradle.kts`:

```kotlin
plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.shijiangtai"
version = "0.1.0-SNAPSHOT"

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    implementation("org.apache.poi:poi-ooxml:6.0.0")
    implementation("io.minio:minio:8.5.17")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.1")
}

tasks.withType<Test> { useJUnitPlatform() }
```

`server/.dockerignore`:

```text
.gradle
build
.idea
*.iml
.env
src/test
```

- [ ] **Step 3: Write the Dockerfile, Compose stack, and config**

> The compose file already exists from the all-module migration plan with `postgres`, `flyway`, and `flyway-demo`. Extend it with `redis`, `minio`, `app`, and `test`; keep the Flyway services and the `postgres-data:/var/lib/postgresql` mount.

`server/Dockerfile`:

```dockerfile
# syntax=docker/dockerfile:1.7
FROM gradle:8.14.3-jdk21 AS build
WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts ./
COPY src/main ./src/main
RUN gradle bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar /app/app.jar
USER app
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

`docker-compose.yml` (repository root):

```yaml
name: shijiangtai

services:
  postgres:
    image: postgres:18-alpine
    environment:
      POSTGRES_DB: shijiangtai
      POSTGRES_USER: shijiangtai
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-shijiangtai_dev}
    ports: ["5432:5432"]
    volumes:
      - postgres-data:/var/lib/postgresql
      - ./server/docker/postgres/init:/docker-entrypoint-initdb.d:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U shijiangtai -d shijiangtai"]
      interval: 5s
      timeout: 3s
      retries: 20

  redis:
    image: redis:8-alpine
    command: ["redis-server", "--appendonly", "yes"]
    ports: ["6379:6379"]
    volumes: [redis-data:/data]
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 20

  minio:
    image: ${MINIO_IMAGE:-swr.cn-north-4.myhuaweicloud.com/ddn-k8s/quay.io/minio/minio:latest}
    command: ["server", "/data", "--console-address", ":9001"]
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER:-minioadmin}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD:-minioadmin}
    ports: ["9000:9000", "9001:9001"]
    volumes: [minio-data:/data]
    healthcheck:
      test: ["CMD", "mc", "ready", "local"]
      interval: 10s
      timeout: 5s
      retries: 10

  app:
    build: ./server
    environment:
      SPRING_PROFILES_ACTIVE: docker
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/shijiangtai?stringtype=unspecified
      SPRING_DATASOURCE_USERNAME: shijiangtai
      SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD:-shijiangtai_dev}
      M3_MINIO_ENDPOINT: http://minio:9000
      M3_MINIO_ACCESS_KEY: ${MINIO_ROOT_USER:-minioadmin}
      M3_MINIO_SECRET_KEY: ${MINIO_ROOT_PASSWORD:-minioadmin}
      M3_MINIO_BUCKET: shijiangtai
    ports: ["8080:8080"]
    depends_on:
      postgres: { condition: service_healthy }
      redis: { condition: service_healthy }
      minio: { condition: service_healthy }

  test:
    profiles: ["test"]
    image: gradle:8.14.3-jdk21
    user: "1000:1000"
    working_dir: /workspace
    volumes:
      - ./server:/workspace
      - gradle-cache:/home/gradle/.gradle
    environment:
      SPRING_PROFILES_ACTIVE: test
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/shijiangtai_test?stringtype=unspecified
      SPRING_DATASOURCE_USERNAME: shijiangtai
      SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD:-shijiangtai_dev}
      M3_STORAGE: memory
    depends_on:
      postgres: { condition: service_healthy }
    command: ["gradle", "test", "--no-daemon", "--console=plain"]

volumes:
  postgres-data:
  redis-data:
  minio-data:
  gradle-cache:
```

`server/docker/postgres/init/01-create-test-db.sql`:

```sql
CREATE DATABASE shijiangtai_test;
```

`server/src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: shijiangtai-server
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/shijiangtai?stringtype=unspecified}
    username: ${SPRING_DATASOURCE_USERNAME:shijiangtai}
    password: ${SPRING_DATASOURCE_PASSWORD:shijiangtai_dev}
  jpa:
    hibernate:
      ddl-auto: none
    open-in-view: false
    properties:
      hibernate:
        jdbc:
          time_zone: UTC
  flyway:
    enabled: true
    locations: classpath:db/migration
  jackson:
    property-naming-strategy: SNAKE_CASE
    serialization:
      write-dates-as-timestamps: false
  servlet:
    multipart:
      max-file-size: 20MB
      max-request-size: 21MB

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health

m3:
  storage: ${M3_STORAGE:memory}
```

`server/src/main/resources/application-docker.yml`:

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}

m3:
  storage: minio
  minio:
    endpoint: ${M3_MINIO_ENDPOINT:http://minio:9000}
    access-key: ${M3_MINIO_ACCESS_KEY:minioadmin}
    secret-key: ${M3_MINIO_SECRET_KEY:minioadmin}
    bucket: ${M3_MINIO_BUCKET:shijiangtai}
```

`server/src/main/resources/application-test.yml`:

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME:shijiangtai}
    password: ${SPRING_DATASOURCE_PASSWORD:shijiangtai_dev}
  jpa:
    hibernate:
      ddl-auto: none

m3:
  storage: memory
```

- [ ] **Step 4: Write the shared kernel**

Implement the shared types from the Interfaces block. `ServerApplication`:

```java
@SpringBootApplication
@ConfigurationPropertiesScan
public class ServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ServerApplication.class, args);
    }
}
```

`ServerApplicationTests` is a placeholder that does not start the context yet:

```java
class ServerApplicationTests {
    @Test
    void placeholder() {
        assertThat(ServerApplication.class).isNotNull();
    }
}
```

- [ ] **Step 5: Build the image and validate Compose**

Run:

```bash
docker compose config
docker compose build app
```

Expected: Compose config is valid and the app image builds. The build stage uses `gradle bootJar -x test`, so no database is needed. If the `gradle:8.14.3-jdk21` or `eclipse-temurin:21-jre-alpine` image is missing, pull it first; Docker Desktop’s mirrors are configured.

- [ ] **Step 6: Commit**

```bash
git add server/settings.gradle.kts server/build.gradle.kts server/Dockerfile \
  server/.dockerignore server/src/main/resources server/src/main/java/com/shijiangtai \
  server/src/test/java/com/shijiangtai/ServerApplicationTests.java server/docker docker-compose.yml server/README.md
git commit -m "[M3A] Docker 全栈骨架与共享内核"
```

---

### Task 2: Task value objects

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/domain/task/{TaskMode,TaskStatus,RubricWeights,VirtualStudentConfig,ScenarioConfig,QuestionConfig,ReferenceMaterial}.java`
- Test: `server/src/test/java/com/shijiangtai/m3classroom/domain/task/{RubricWeightsTest,VirtualStudentConfigTest,ScenarioConfigTest,QuestionConfigTest,ReferenceMaterialTest}.java`

**Interfaces:**
- Consumes: `DomainException`, `ErrorCode`, `QuestionId` from Task 1.
- Produces:
  - `enum TaskMode { FREE("free"), SCENARIO("scenario"), INTERVIEW("interview") }` with `String wireValue()`, `static TaskMode fromWire(String)`, `List<String> dimensions()`, `RubricWeights defaultWeights()`, `int defaultRecommendedSeconds()`, `int defaultMaxSeconds()`, `boolean requiresMaterial()`, `boolean requiresVirtualStudents()`.
  - `enum TaskStatus { DRAFT("draft"), PUBLISHED("published"), CLOSED("closed") }` with `String wireValue()` and `static TaskStatus fromWire(String)`.
  - `record RubricWeights(Map<String,Integer> values)` with `static RubricWeights of(TaskMode, Map<String,Integer>)`, `static RubricWeights defaults(TaskMode)`, `int sum()`, `int of(String)`, `Map<String,Integer> asMap()`.
  - `RubricWeights.defaults(mode)` is the single source of defaults; `TaskMode.defaultWeights()` delegates to it. Do not implement `defaults` by calling `mode.defaultWeights()`.
  - `record VirtualStudentConfig(UUID id, String name, String avatar, String personalityText, BigDecimal activity, BigDecimal questionRate, BigDecimal ability, BigDecimal attentionBaseline, BigDecimal disciplineProbability, int seatNo, int sortOrder)`.
  - `record ScenarioConfig(String role, String situation)`.
  - `record QuestionConfig(List<QuestionId> questionIds, int totalDurationSeconds)`.
  - `record ReferenceMaterial(UUID materialId, String filename, String objectKey, String mime, long size, String parsedText, int charCount)` with `String preview()`.

- [ ] **Step 1: Write the failing value-object tests**

```java
@Test void freeDefaultWeightsSumTo100() {
    assertThat(RubricWeights.defaults(TaskMode.FREE).sum()).isEqualTo(100);
}
@Test void interviewWeightsRejectFreeDimension() {
    assertThatThrownBy(() -> RubricWeights.of(TaskMode.INTERVIEW,
        Map.of("content", 40, "expression", 30, "board", 30)))
        .isInstanceOf(DomainException.class);
}
@Test void virtualStudentParamAboveOneIsRejected() {
    assertThatThrownBy(() -> studentWithActivity("1.01")).isInstanceOf(DomainException.class);
}
@Test void questionConfigRejectsElevenQuestions() {
    assertThatThrownBy(() -> configWithQuestionCount(11)).isInstanceOf(DomainException.class);
}
@Test void materialPreviewIsFirst500Chars() {
    assertThat(materialWithText("x".repeat(600)).preview()).hasSize(500);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
docker compose --profile test run --rm test \
  gradle test --tests '*RubricWeightsTest' --tests '*VirtualStudentConfigTest' \
  --tests '*QuestionConfigTest' --tests '*ReferenceMaterialTest' --no-daemon --console=plain
```

Expected: compilation failure because the classes do not exist. The test service starts Postgres; this task does not use it.

- [ ] **Step 3: Implement the value objects**

Rules:
- `TaskMode.dimensions()`: free/scenario = `content, expression, interaction, board, time`; interview = `content, expression, time`.
- Defaults: free/scenario `{content=30, expression=20, interaction=20, board=15, time=15}`; interview `{content=40, expression=30, time=30}`.
- Free/scenario default durations 900/1200 seconds; interview 300/300.
- `RubricWeights.of` requires exactly the mode’s dimension keys; each value 0–100. Sum is not checked here.
- `VirtualStudentConfig`: `name` trimmed 1–20; `avatar` 1–64; `personalityText` ≤500; each parameter in [0,1] with at most 2 decimals; `seatNo`/`sortOrder` ≥1.
- `ScenarioConfig`: `role` ≤200, `situation` ≤1000; null allowed; blank normalized to null.
- `QuestionConfig`: 0–10 IDs in draft; no duplicates; total 1–3600 seconds.
- `ReferenceMaterial`: non-null metadata, `size > 0`, `charCount >= 0`, `parsedText` non-null; `preview()` returns the first 500 chars.
- All errors throw `DomainException` with the appropriate `ErrorCode` and a `field` in `details`.

- [ ] **Step 4: Run tests to verify they pass**

Run the Step 2 command again. Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/domain/task \
  server/src/test/java/com/shijiangtai/m3classroom/domain/task
git commit -m "[M3A] 任务配置值对象与边界校验"
```

---

### Task 3: TaskDraft and Task aggregate

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/domain/task/{TaskDraft,Task}.java`
- Test: `server/src/test/java/com/shijiangtai/m3classroom/domain/task/{TaskDraftTest,TaskTest}.java`

**Interfaces:**
- Consumes: Task 2 value objects, shared IDs/`DomainException`.
- Produces:
  - `record TaskDraft(TaskMode mode, String topic, String textbookVersion, int durationRecommendedSeconds, int durationMaxSeconds, RubricWeights rubricWeights, ScenarioConfig scenario, List<VirtualStudentConfig> virtualStudents, QuestionConfig questionConfig)`.
  - `final class Task` with:
    - `static Task createDraft(TaskId id, CourseId courseId, AccountId createdBy, TaskDraft draft, Instant now)`
    - `TaskId id()`, `CourseId courseId()`, `AccountId createdBy()`, `TaskDraft draft()`, `TaskStatus status()`, `ReferenceMaterial referenceMaterial()`, `int configVersion()`, `Instant publishedAt()`, `Instant closedAt()`, `Instant createdAt()`, `Instant updatedAt()`
    - `boolean updateDraft(TaskDraft newDraft, Instant now)` — returns `true` if mode changed
    - `void attachMaterial(ReferenceMaterial material, Instant now)`
    - `void removeMaterial(Instant now)`
    - `void close(Instant now)`
    - `void assertDraft()`

**Ordering note:** Task 3 must not reference `TaskSnapshot`. Task 4 adds `Task.publish(TaskSnapshot, Instant)`, `Task.snapshot()`, and `Task.rehydrate(...)` when the snapshot type exists. Tasks 3 and 4 are separate commits.

- [ ] **Step 1: Write the failing TaskDraft test**

```java
@Test void interviewRequiresQuestionConfigAndEqualDurations() {
    assertThatThrownBy(() -> draft(TaskMode.INTERVIEW, null, 300, 300))
        .isInstanceOf(DomainException.class)
        .extracting("code").isEqualTo(ErrorCode.TASK_CONFIG_INVALID);
}
@Test void interviewCannotCarryVirtualStudents() { ... }
@Test void freeCannotCarryQuestionConfig() { ... }
@Test void topicLengthIsOneToHundred() { ... }
@Test void recommendedDurationMayNotExceedMax() { ... }
@Test void draftWeightsNeedNotSumTo100() { ... }
```

- [ ] **Step 2: Write the failing Task aggregate test**

```java
@Test void modeChangeReturnsTrueAndClearsMaterial() {
    Task task = draftWithMaterial(TaskMode.FREE);
    boolean changed = task.updateDraft(validInterviewDraft(), NOW);
    assertThat(changed).isTrue();
    assertThat(task.referenceMaterial()).isNull();
}
@Test void closeDraftIsRejected() {
    assertThatThrownBy(() -> draftTask().close(NOW))
        .extracting("code").isEqualTo(ErrorCode.TASK_NOT_DRAFT);
}
@Test void updateDraftKeepsCreatedAtAndAdvancesUpdatedAt() { ... }
```

- [ ] **Step 3: Implement TaskDraft and Task**

`TaskDraft` validation:
- `mode` non-null; `topic` trimmed 1–100; `textbookVersion` null or trimmed 1–100.
- Durations 1–3600 and `recommended <= max`.
- `rubricWeights` keys exactly match `mode.dimensions()`.
- `scenario` only for `SCENARIO`; free/interview must have null.
- Virtual students: null is normalized to empty; 0–30 in draft; interview must be empty.
- Interview: `questionConfig` required; `totalDurationSeconds == durationRecommendedSeconds == durationMaxSeconds`; 0–10 IDs in draft.
- Free/scenario: `questionConfig` must be null.
- Weight sum is not required in draft.

`Task`:
- `createDraft` stores `createdAt = updatedAt = now`, `status = DRAFT`, `configVersion = 0`.
- `updateDraft` calls `assertDraft()`, records `modeChanged`, sets draft/updatedAt, and clears `referenceMaterial` when the mode changes.
- `attachMaterial`/`removeMaterial` call `assertDraft()`.
- `close` requires `PUBLISHED`; `DRAFT` -> `TASK_NOT_DRAFT`, `CLOSED` -> `TASK_CLOSED`.
- `assertDraft` throws `TASK_NOT_DRAFT` when status is not `DRAFT`.

- [ ] **Step 4: Run tests**

```bash
docker compose --profile test run --rm test \
  gradle test --tests '*TaskDraftTest' --tests '*TaskTest' --no-daemon --console=plain
```

Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/domain/task/TaskDraft.java \
  server/src/main/java/com/shijiangtai/m3classroom/domain/task/Task.java \
  server/src/test/java/com/shijiangtai/m3classroom/domain/task/TaskDraftTest.java \
  server/src/test/java/com/shijiangtai/m3classroom/domain/task/TaskTest.java
git commit -m "[M3A] Task 聚合与草稿生命周期"
```

---

### Task 4: Immutable TaskSnapshot and factory

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/domain/task/{TaskSnapshot,TaskSnapshotFactory,PublishQuestion,CourseInfo,CourseStudentInfo}.java`
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/domain/task/Task.java` (add `publish`, `snapshot`, `rehydrate`)
- Test: `server/src/test/java/com/shijiangtai/m3classroom/domain/task/{TaskSnapshotFactoryTest,TaskTest}.java`

**Interfaces:**
- Consumes: `Task`, `TaskDraft`, `ReferenceMaterial`, `VirtualStudentConfig`, `QuestionConfig`.
- Produces:
  - `record TaskSnapshot(int schemaVersion, UUID taskId, UUID courseId, UUID schoolId, String mode, String topic, String textbookVersion, DurationSnapshot duration, Map<String,Integer> weights, MaterialSnapshot referenceMaterial, ScenarioSnapshot scenario, List<VirtualStudentSnapshot> virtualStudents, InterviewSnapshot interview, Instant publishedAt, UUID publishedBy)`
  - Nested records: `DurationSnapshot(int recommendedSeconds, int maxSeconds)`, `MaterialSnapshot(UUID materialId, String filename, String objectKey, String mime, long size, String parsedText, int charCount)`, `ScenarioSnapshot(String role, String situation)`, `VirtualStudentSnapshot(UUID virtualStudentId, String name, String avatar, String personalityText, BigDecimal activity, BigDecimal questionRate, BigDecimal ability, BigDecimal attentionBaseline, BigDecimal disciplineProbability, int seatNo, int sortOrder)`, `InterviewSnapshot(int totalDurationSeconds, int questionCount, List<QuestionSnapshot> questions)`, `QuestionSnapshot(UUID questionId, int order, String stem, String answerPoints, UUID categoryId, String categoryName)`.
  - `final class TaskSnapshotFactory { TaskSnapshot create(Task task, CourseInfo course, List<PublishQuestion> questions, Instant publishedAt); }`
  - `record PublishQuestion(UUID questionId, String stem, String answerPoints, UUID categoryId, String categoryName, UUID schoolId, boolean enabled)`.
  - `record CourseInfo(UUID id, UUID schoolId, String name, boolean active, Instant endAt, List<UUID> teacherAccountIds)`.
  - `record CourseStudentInfo(UUID accountId, UUID classId)`.
  - `Task.publish(TaskSnapshot snapshot, Instant now)`, `Task.snapshot()`, and `static Task rehydrate(...)` with all persisted fields.

- [ ] **Step 1: Write the failing factory and published-task tests**

```java
@Test void interviewSnapshotContainsOrderedQuestionsAndNoMaterial() {
    TaskSnapshot snapshot = factory.create(interviewTask, course, List.of(q2, q1), NOW);
    assertThat(snapshot.interview().questions()).extracting("order").containsExactly(1, 2);
    assertThat(snapshot.referenceMaterial()).isNull();
    assertThat(snapshot.virtualStudents()).isEmpty();
}
@Test void snapshotDoesNotContainStudentRoster() { ... }
@Test void snapshotListsAreImmutable() { ... }
@Test void publishStoresSnapshotAndSetsVersionOne() {
    Task task = draftTask();
    task.publish(factory.create(task, course, List.of(), NOW), NOW);
    assertThat(task.status()).isEqualTo(TaskStatus.PUBLISHED);
    assertThat(task.configVersion()).isEqualTo(1);
    assertThat(task.snapshot()).isNotNull();
}
@Test void publishedTaskCannotBeEdited() { ... TASK_NOT_DRAFT ... }
@Test void closePublishedMovesToClosed() { ... }
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
docker compose --profile test run --rm test \
  gradle test --tests '*TaskSnapshotFactoryTest' --tests '*TaskTest' --no-daemon --console=plain
```

Expected: compilation failure.

- [ ] **Step 3: Implement snapshot records, factory, and rehydration**

Rules:
- `schemaVersion = 1`; `publishedBy = task.createdBy().value()`.
- `mode`/`textbookVersion` are wire strings; `textbookVersion` may be null.
- Free: `scenario = null`, `interview = null`, material and virtual students included.
- Scenario: `scenario` included, `interview = null`.
- Interview: `referenceMaterial = null`, `virtualStudents = []`, `interview` populated with questions in the requested order, `order` starting at 1.
- Weights map is a copied, unmodifiable `LinkedHashMap` in mode dimension order.
- All lists are `List.copyOf`.
- `Task.publish` calls `assertDraft()`, sets `snapshot`, `status = PUBLISHED`, `configVersion = 1`, `publishedAt`, `updatedAt`.
- `Task.rehydrate(...)` reconstructs a task from persistence without re-running draft validation; it must copy lists and preserve `status`, `snapshot`, `referenceMaterial`, `configVersion`, and timestamps.

- [ ] **Step 4: Run tests**

Run the Step 2 command again. Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/domain/task \
  server/src/test/java/com/shijiangtai/m3classroom/domain/task
git commit -m "[M3A] 不可变任务配置快照"
```

---

### Task 5: Publish validation policy

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/domain/task/TaskPublishPolicy.java`
- Test: `server/src/test/java/com/shijiangtai/m3classroom/domain/task/TaskPublishPolicyTest.java`

**Interfaces:**
- Consumes: `Task`, `TaskDraft`, `CourseInfo`, `CourseStudentInfo`, `PublishQuestion`.
- Produces:
  - `final class TaskPublishPolicy { void validateForPublish(Task task, CourseInfo course, List<PublishQuestion> questions, List<CourseStudentInfo> activeStudents, Instant now); }`

- [ ] **Step 1: Write the failing policy tests**

```java
@Test void draftWithZeroStudentsIsRejected() { ... TASK_NO_STUDENTS ... }
@Test void closedCourseIsRejected() { ... COURSE_ARCHIVED ... }
@Test void endedCourseIsRejected() { ... COURSE_ENDED ... }
@Test void publishedTaskIsRejected() { ... TASK_ALREADY_PUBLISHED ... }
@Test void freeTaskWithoutMaterialIsRejected() { ... REFERENCE_MATERIAL_MISSING ... }
@Test void freeTaskWithoutVirtualStudentsIsRejected() { ... TASK_CONFIG_INVALID ... }
@Test void weightSumNot100IsRejected() { ... WEIGHT_SUM_INVALID ... }
@Test void interviewQuestionCountIsOneToTen() { ... QUESTION_COUNT_INVALID ... }
@Test void duplicatedQuestionIdsAreRejected() { ... QUESTION_DUPLICATED ... }
@Test void disabledOrMissingQuestionIsRejected() { ... QUESTION_DISABLED ... }
@Test void missingAnswerPointsIsRejected() { ... ANSWER_POINTS_MISSING ... }
@Test void crossSchoolQuestionIsRejected() { ... CROSS_SCHOOL_ACCESS ... }
@Test void invalidFieldsAreAggregated() { ... details.fields size > 1 ... }
```

- [ ] **Step 2: Run test to verify it fails**

```bash
docker compose --profile test run --rm test \
  gradle test --tests '*TaskPublishPolicyTest' --no-daemon --console=plain
```

Expected: compilation failure.

- [ ] **Step 3: Implement the policy**

Order of checks:
1. `task.status`: `PUBLISHED` -> `TASK_ALREADY_PUBLISHED` (409); `CLOSED` -> `TASK_NOT_DRAFT` (422).
2. Course: `!active` -> `COURSE_ARCHIVED`; `endAt != null && !endAt.isAfter(now)` -> `COURSE_ENDED`.
3. Accumulate `TASK_CONFIG_INVALID` field errors for: blank topic, textbook version missing (free/scenario), invalid durations, virtual-student count not 1–30 (free/scenario), interview duration mismatch.
4. Weight sum != 100 -> `WEIGHT_SUM_INVALID`.
5. Free/scenario: missing material -> `REFERENCE_MATERIAL_MISSING`; material with blank text -> `MATERIAL_PARSE_FAILED`.
6. Interview: zero questions -> `QUESTION_COUNT_INVALID`; >10 -> `QUESTION_COUNT_INVALID`; duplicates -> `QUESTION_DUPLICATED`; `questions.size() != questionIds.size()` -> `QUESTION_DISABLED` with `question_ids`; each `!enabled` -> `QUESTION_DISABLED`; blank `answerPoints` -> `ANSWER_POINTS_MISSING`; `schoolId` mismatch -> `CROSS_SCHOOL_ACCESS`.
7. Active students empty -> `TASK_NO_STUDENTS`.
8. If field errors accumulated, throw one `DomainException.fields(TASK_CONFIG_INVALID, "任务配置不完整", fields)`.

The policy does not mutate the task and does not generate IDs.

- [ ] **Step 4: Run tests**

Run the Step 2 command again. Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/domain/task/TaskPublishPolicy.java \
  server/src/test/java/com/shijiangtai/m3classroom/domain/task/TaskPublishPolicyTest.java
git commit -m "[M3A] 发布校验策略与字段错误聚合"
```

---

### Task 6: Application ports, test doubles, access policy, and domain-service config

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/domain/task/{TaskRepository,TaskAssignment,AssignmentStatus,TaskAssignmentRepository,TaskPublishedEvent}.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/application/port/{CourseQueryPort,QuestionBankQueryPort,MaterialObjectStorage,MaterialTextExtractor,MaterialExtractionException,MaterialExtractionError,AfterCommitPort}.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/application/TaskAccessPolicy.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/config/M3Config.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/events/{SpringEventPublisher,SpringAfterCommitHook}.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/time/{SystemClockProvider,UuidIdGenerator}.java`
- Create test doubles: `server/src/test/java/com/shijiangtai/m3classroom/support/{InMemoryTaskRepository,InMemoryTaskAssignmentRepository,FakeCourseQueryPort,FakeQuestionBankQueryPort,FakeMaterialObjectStorage,RecordingEventPublisher}.java`
- Test: `server/src/test/java/com/shijiangtai/m3classroom/application/TaskAccessPolicyTest.java`

**Interfaces:**
- Consumes: Task domain types; shared `CurrentUser`, `IdGenerator`, `ClockProvider`, `EventPublisher`.
- Produces:
  - `interface TaskRepository`:
    ```java
    Task save(Task task);
    Optional<Task> findById(TaskId id);
    List<Task> findByCourse(CourseId courseId);
    void delete(TaskId id);
    <T> T withLock(TaskId id, java.util.function.Function<Task, T> action);
    ```
  - `record TaskAssignment(UUID id, TaskId taskId, AccountId studentAccountId, ClassId classIdSnapshot, Instant assignedAt, AssignmentStatus status)`.
  - `enum AssignmentStatus { ASSIGNED("assigned"), SUBMITTED("submitted") }` with `String wireValue()` and `static AssignmentStatus fromWire(String)`.
  - `interface TaskAssignmentRepository`:
    ```java
    void saveAll(List<TaskAssignment> assignments);
    List<TaskAssignment> findByTask(TaskId taskId);
    List<TaskAssignment> findByStudent(AccountId studentId);
    Optional<TaskAssignment> find(TaskId taskId, AccountId studentId);
    long countByTask(TaskId taskId);
    long countByTaskAndStatus(TaskId taskId, AssignmentStatus status);
    void deleteByTask(TaskId taskId);
    ```
  - `record TaskPublishedEvent(UUID taskId, UUID courseId, UUID schoolId, Instant publishedAt, int assignedCount)`.
  - `interface CourseQueryPort { Optional<CourseInfo> findCourse(CourseId); boolean isTeacherInGroup(CourseId, AccountId); List<CourseStudentInfo> activeStudents(CourseId); }`
  - `interface QuestionBankQueryPort { List<PublishQuestion> getForPublish(SchoolId schoolId, List<QuestionId> questionIds); }`
  - `interface MaterialObjectStorage { void put(String objectKey, byte[] content, String contentType); void delete(String objectKey); }`
  - `interface MaterialTextExtractor { String extract(byte[] content, String filename, String mime); }`
  - `class MaterialExtractionException extends RuntimeException` with `MaterialExtractionError error()`.
  - `enum MaterialExtractionError { UNSUPPORTED_TYPE, NO_TEXT, TOO_SHORT, TOO_LARGE, CORRUPT }`.
  - `interface AfterCommitPort { void afterCommit(Runnable action); }`.
  - `@Component class TaskAccessPolicy`:
    ```java
    CourseInfo requireTeacherCourse(CurrentUser user, CourseId courseId);
    CourseInfo requireTeacherTask(CurrentUser user, Task task);
    void requireStudentAssigned(CurrentUser user, CourseInfo course, TaskAssignment assignment);
    ```
  - `@Configuration class M3Config` exposing `TaskSnapshotFactory`, `TaskPublishPolicy`, `IdGenerator`, `ClockProvider`, `EventPublisher`, `AfterCommitPort` beans.
  - `SpringEventPublisher` publishes after commit; `SpringAfterCommitHook implements AfterCommitPort`; `SystemClockProvider`/`UuidIdGenerator` implement shared ports.

- [ ] **Step 1: Write the failing access-policy test**

```java
@Test void studentCannotUseTeacherPolicy() {
    assertThatThrownBy(() -> policy.requireTeacherCourse(student, course.id()))
        .extracting("code").isEqualTo(ErrorCode.FORBIDDEN);
}
@Test void otherTeacherIsRejected() { ... TEACHER_NOT_IN_GROUP ... }
@Test void crossSchoolTeacherIsRejected() { ... CROSS_SCHOOL_ACCESS ... }
@Test void assignedStudentPasses() { ... no exception ... }
```

- [ ] **Step 2: Run test to verify it fails**

```bash
docker compose --profile test run --rm test \
  gradle test --tests '*TaskAccessPolicyTest' --no-daemon --console=plain
```

Expected: compilation failure.

- [ ] **Step 3: Implement ports, test doubles, and access policy**

Implementation notes:
- `TaskAccessPolicy`:
  - `requireTeacherCourse`: `ADMIN` -> `FORBIDDEN`; non-`TEACHER` -> `FORBIDDEN`; missing course -> `RESOURCE_NOT_FOUND`; different school -> `CROSS_SCHOOL_ACCESS`; not in group -> `TEACHER_NOT_IN_GROUP`.
  - `requireTeacherTask` loads the task’s course.
  - `requireStudentAssigned`: non-`STUDENT` -> `FORBIDDEN`; missing assignment -> `FORBIDDEN`; course school mismatch -> `CROSS_SCHOOL_ACCESS`.
- `InMemoryTaskRepository` (test-only): `ConcurrentHashMap<TaskId, Task>` + per-task `ReentrantLock`; `withLock` locks, loads, runs the action, unlocks in `finally`. `save` stores the object; `findByCourse` filters.
- `InMemoryTaskAssignmentRepository` (test-only): `ConcurrentHashMap<UUID, TaskAssignment>`; filter methods; `deleteByTask`.
- `FakeMaterialObjectStorage` (test-only): `ConcurrentHashMap<String, byte[]>` with defensive copies; used by application unit tests before the production storage adapters exist.
- `FakeCourseQueryPort` (test-only): mutable seed lists with `addCourse`, `addStudent`, `addTeacher`.
- `FakeQuestionBankQueryPort` (test-only): mutable questions with `addQuestion`, `setAvailable`.
- `RecordingEventPublisher` (test-only): records events in a list.
- `M3Config`: constructs the domain services as beans. Domain classes stay Spring-free.
- `SpringEventPublisher`: if a transaction is active, register a `TransactionSynchronization.afterCommit`; otherwise publish immediately. `SpringAfterCommitHook` implements `AfterCommitPort` with the same pattern for post-commit object deletes.

- [ ] **Step 4: Run tests**

Run the Step 2 command again. Expected: all pass. Also compile the main sources with `docker compose run --rm test gradle compileJava --no-daemon --console=plain`.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/domain/task \
  server/src/main/java/com/shijiangtai/m3classroom/application/port \
  server/src/main/java/com/shijiangtai/m3classroom/application/TaskAccessPolicy.java \
  server/src/main/java/com/shijiangtai/m3classroom/infrastructure \
  server/src/test/java/com/shijiangtai/m3classroom/application/TaskAccessPolicyTest.java \
  server/src/test/java/com/shijiangtai/m3classroom/support
git commit -m "[M3A] 应用端口、测试替身与访问策略"
```

---

### Task 7: Task commands, views, and CRUD application service

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/application/command/{Patch,CreateTaskCommand,UpdateTaskCommand,VirtualStudentSpec,TaskQuery,StudentTaskQuery}.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/application/view/{TaskView,TaskCardView,StudentTaskView,StudentTaskDetailView,TaskAssignmentView,MaterialView,PublishResult,ReferenceMaterialView,VirtualStudentView,PublishedQuestionView,StudentVirtualStudentView,QuestionConfigView,TaskViewMapper}.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/application/{TaskApplicationService,TaskApplicationServiceImpl}.java`
- Test: `server/src/test/java/com/shijiangtai/m3classroom/application/{TaskApplicationServiceTest,TaskQueryServiceTest}.java`
- Test support: `server/src/test/java/com/shijiangtai/m3classroom/TestFixtures.java`

**Interfaces:**
- Consumes: Task 6 ports/test doubles, Task 3/4 domain.
- Produces:
  - `record Patch<T>(boolean present, T value)` with `static <T> Patch<T> absent()` and `static <T> Patch<T> of(T value)`.
  - `record CreateTaskCommand(CourseId courseId, TaskMode mode, String topic, String textbookVersion, Integer durationRecommendedSeconds, Integer durationMaxSeconds, Map<String,Integer> rubricWeights, ScenarioConfig scenario, List<VirtualStudentSpec> virtualStudents, QuestionConfig questionConfig)`.
  - `record VirtualStudentSpec(UUID virtualStudentId, String name, String avatar, String personalityText, BigDecimal activity, BigDecimal questionRate, BigDecimal ability, BigDecimal attentionBaseline, BigDecimal disciplineProbability, int seatNo, int sortOrder)`.
  - `record UpdateTaskCommand(Patch<TaskMode> mode, Patch<String> topic, Patch<String> textbookVersion, Patch<Integer> durationRecommendedSeconds, Patch<Integer> durationMaxSeconds, Patch<Map<String,Integer>> rubricWeights, Patch<ScenarioConfig> scenario, Patch<List<VirtualStudentSpec>> virtualStudents, Patch<QuestionConfig> questionConfig)`.
  - `record TaskQuery(int page, int size, TaskStatus status, TaskMode mode)` and `record StudentTaskQuery(int page, int size)`; validate page ≥1 and size 1–100.
  - `interface TaskApplicationService` with the seven methods in `implementation/04` §3.1 plus `PageResult<TaskAssignmentView> listAssignments(CurrentUser teacher, TaskId taskId, int page, int size)`.
  - `@Service class TaskApplicationServiceImpl implements TaskApplicationService`.
  - View records exactly as named; `TaskViewMapper` is a stateless final class with static mapping methods.
  - `TaskView` includes full teacher config, material metadata (not full text), virtual-student parameters, and published question list (including `answer_points`).
  - `StudentTaskView`/`StudentTaskDetailView` include only: task ID, course ID/name, mode, topic, duration, question count or virtual-student name/avatar/seat, teacher account IDs, task status, assignment status. No weights, material, answer points, parameters, or personality text.

- [ ] **Step 1: Write the failing CRUD tests**

```java
@Test void createFreeDraftAppliesDefaults() {
    TaskView view = service.createDraft(teacher, freeCommand());
    assertThat(view.mode()).isEqualTo("free");
    assertThat(view.durationRecommendedSeconds()).isEqualTo(900);
    assertThat(view.rubricWeights()).containsEntry("content", 30);
}
@Test void modeSwitchClearsIncompatibleConfigAndResetsWeights() { ... }
@Test void modeSwitchDeletesOldMaterialObjectAfterCommit() { ... }
@Test void otherTeacherCannotEdit() { ... TEACHER_NOT_IN_GROUP ... }
@Test void adminCannotEdit() { ... FORBIDDEN ... }
@Test void deleteOnlyDraft() { ... }
@Test void publishedTaskCannotBeUpdatedOrDeleted() { ... TASK_NOT_DRAFT ... }
```

- [ ] **Step 2: Write the failing query tests**

```java
@Test void courseTaskListFiltersByStatusAndModeAndPaginates() { ... }
@Test void studentListContainsOnlyAssignedTasks() { ... }
@Test void studentDetailHidesPrivilegedFields() {
    StudentTaskDetailView view = service.getStudentTask(student, taskId);
    assertThat(view.toString()).doesNotContain("content=30", "answer", "material");
}
@Test void unassignedStudentGetsForbidden() { ... }
@Test void assignmentsListRequiresTeacherGroup() { ... }
```

- [ ] **Step 3: Implement commands/views/mapper and `TaskApplicationServiceImpl`**

Application rules:
- `createDraft`: resolve course via `TaskAccessPolicy.requireTeacherCourse`; fill defaults. For interview: if `questionConfig` is null, create an empty `QuestionConfig` using the supplied recommended duration or 300; set all three task durations equal to the total. Generate IDs for virtual-student specs where `virtualStudentId == null`; save; map.
- `updateDraft`: load task; require teacher; `assertDraft()`; merge patches:
  - Mode change: clear material/virtual students/scenario/questions, reset weights to new mode defaults, reset durations to new mode defaults unless explicitly patched; interview gets an empty `QuestionConfig` whose total is the patched recommended duration or 300 seconds if none is supplied.
  - Same mode: apply each `Patch`; for interview, if `questionConfig` is patched, derive all durations; an explicit null `question_config` is normalized to an empty `QuestionConfig` with the current total duration; if duration fields are patched independently and disagree with the total, throw `TASK_CONFIG_INVALID` with field `duration`.
  - Capture the old material object key before update, save, then schedule `materialObjectStorage.delete(oldKey)` through `AfterCommitPort` when the mode changed.
- `deleteDraft`: require teacher + draft; capture material key; delete task and assignments; schedule object deletion after commit.
- `get`/`listCourseTasks`/`listAssignments`: require teacher.
- `listStudentTasks`/`getStudentTask`: require `STUDENT`; load assignments; reject draft tasks; map public views.
- Pagination: course tasks by `updatedAt` descending, student tasks by task `updatedAt` descending, assignments by `assignedAt` ascending.
- Write methods are `@Transactional`; read methods `@Transactional(readOnly = true)`.
- `TaskCardView.durationSeconds` = `durationMaxSeconds` for free/scenario, `totalDurationSeconds` for interview.
- `TaskCardView.submittedCount` = `countByTaskAndStatus(taskId, SUBMITTED)`; always 0 in this plan.

- [ ] **Step 4: Run tests**

```bash
docker compose --profile test run --rm test \
  gradle test --tests '*TaskApplicationServiceTest' --tests '*TaskQueryServiceTest' \
  --no-daemon --console=plain
```

Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/application \
  server/src/test/java/com/shijiangtai/m3classroom/application \
  server/src/test/java/com/shijiangtai/m3classroom/TestFixtures.java
git commit -m "[M3A] 任务草稿 CRUD、查询与学生可见性"
```

---

### Task 8: Publish and close services

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/application/{TaskPublishService,TaskPublishServiceImpl}.java`
- Test: `server/src/test/java/com/shijiangtai/m3classroom/application/TaskPublishServiceTest.java`

**Interfaces:**
- Consumes: `TaskPublishPolicy`, `TaskSnapshotFactory`, ports, `EventPublisher`.
- Produces:
  - `interface TaskPublishService { PublishResult publish(CurrentUser teacher, TaskId taskId); void close(CurrentUser teacher, TaskId taskId); }`
  - `@Service class TaskPublishServiceImpl implements TaskPublishService`.

- [ ] **Step 1: Write the failing publish tests**

```java
@Test void publishCreatesAssignmentsSnapshotAndEvent() { ... }
@Test void zeroStudentsLeavesDraftAndNoAssignments() { ... TASK_NO_STUDENTS ... }
@Test void questionBankFailureLeavesDraftAndNoAssignments() { ... SERVICE_UNAVAILABLE ... }
@Test void disabledQuestionLeavesDraft() { ... QUESTION_DISABLED ... }
@Test void concurrentPublishHasOneWinner() { ... one success + TASK_ALREADY_PUBLISHED ... }
@Test void closePublishedTask() { ... }
@Test void closeDraftRejected() { ... TASK_NOT_DRAFT ... }
@Test void closeTwiceReturnsTaskClosed() { ... TASK_CLOSED ... }
```

- [ ] **Step 2: Run test to verify it fails**

```bash
docker compose --profile test run --rm test \
  gradle test --tests '*TaskPublishServiceTest' --no-daemon --console=plain
```

Expected: compilation failure.

- [ ] **Step 3: Implement the services**

`publish` is `@Transactional` and runs inside `taskRepository.withLock(taskId, task -> { ... })`:
1. `CourseInfo course = accessPolicy.requireTeacherCourse(teacher, task.courseId())`.
2. `Instant now = clock.now()`.
3. `List<CourseStudentInfo> students = courseQueryPort.activeStudents(task.courseId())`.
4. For interview, `List<PublishQuestion> questions = questionBankQueryPort.getForPublish(course.schoolId(), task.draft().questionConfig().questionIds())`; otherwise `List.of()`.
5. `policy.validateForPublish(task, course, questions, students, now)`.
6. `TaskSnapshot snapshot = snapshotFactory.create(task, course, questions, now)`.
7. Build `TaskAssignment` per student with `idGenerator.nextId()`, `classIdSnapshot = student.classId() == null ? null : ClassId.of(student.classId())`, `assignedAt = now`, `ASSIGNED`.
8. `assignmentRepository.saveAll(assignments)`; `task.publish(snapshot, now)`; `taskRepository.save(task)`.
9. `eventPublisher.publish(new TaskPublishedEvent(...))`; `SpringEventPublisher` defers until after commit.
10. Return `PublishResult`.

`close` is `@Transactional` and runs inside `withLock`: require teacher course; `task.close(clock.now())`; save; no event.

No partial write before validation: all reads/validation/snapshot happen before `saveAll`; the DB transaction rolls back on any later failure.

- [ ] **Step 4: Run tests**

Run the Step 2 command again. Expected: all pass, including the concurrency test.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/application/TaskPublishService.java \
  server/src/main/java/com/shijiangtai/m3classroom/application/TaskPublishServiceImpl.java \
  server/src/test/java/com/shijiangtai/m3classroom/application/TaskPublishServiceTest.java
git commit -m "[M3A] 发布事务、分配名单与关闭任务"
```

---

### Task 9: Material parsing, MinIO storage, and material service

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/material/{PdfMaterialTextExtractor,DocxMaterialTextExtractor,PlainTextMaterialTextExtractor,CompositeMaterialTextExtractor}.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/storage/{MinioMaterialObjectStorage,MinioProperties,InMemoryMaterialObjectStorage}.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/application/{TaskMaterialService,TaskMaterialServiceImpl,TaskMaterialTxService}.java`
- Test: `server/src/test/java/com/shijiangtai/m3classroom/infrastructure/material/MaterialTextExtractorTest.java`
- Test: `server/src/test/java/com/shijiangtai/m3classroom/application/TaskMaterialServiceTest.java`
- Test support: `server/src/test/java/com/shijiangtai/m3classroom/support/{PdfTestFiles,DocxTestFiles}.java`
- Modify: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/config/M3Config.java` (extractor beans)

**Interfaces:**
- Consumes: `MaterialTextExtractor`, `MaterialObjectStorage`, `MaterialExtractionException`, `TaskAccessPolicy`, `TaskRepository`.
- Produces:
  - `interface TaskMaterialService`:
    ```java
    MaterialView upload(CurrentUser teacher, TaskId taskId, String filename, String mime, byte[] content);
    MaterialView get(CurrentUser teacher, TaskId taskId);
    void delete(CurrentUser teacher, TaskId taskId);
    ```
  - `@Service class TaskMaterialServiceImpl implements TaskMaterialService` (non-transactional; delegates DB writes to `TaskMaterialTxService`).
  - `@Service class TaskMaterialTxService` with `@Transactional` methods:
    ```java
    ReferenceMaterial attach(TaskId taskId, ReferenceMaterial material);
    ReferenceMaterial clear(TaskId taskId);
    ```
  - `MinioMaterialObjectStorage` (`@ConditionalOnProperty(name="m3.storage", havingValue="minio")`) using `MinioClient`; lazily creates the bucket.
  - `InMemoryMaterialObjectStorage` (`@ConditionalOnProperty(name="m3.storage", havingValue="memory", matchIfMissing=true)`); also used by tests.
  - `@ConfigurationProperties(prefix="m3.minio") record MinioProperties(String endpoint, String accessKey, String secretKey, String bucket)`.
  - Extractors:
    - `PdfMaterialTextExtractor`: PDFBox `Loader.loadPDF`, `PDFTextStripper`.
    - `DocxMaterialTextExtractor`: POI `XWPFDocument` + `XWPFWordExtractor`.
    - `PlainTextMaterialTextExtractor`: `.txt`/`.md`, strict UTF-8 then GB18030 fallback.
    - `CompositeMaterialTextExtractor`: selects by extension; unsupported -> `MaterialExtractionException(UNSUPPORTED_TYPE)`.

- [ ] **Step 1: Write the failing extractor tests**

Generate fixtures in code (no binary files committed):

```java
@Test void extractsTextFromPdf() { assertThat(pdfExtractor.extract(pdf("课堂导入"), "a.pdf", "application/pdf")).contains("课堂导入"); }
@Test void blankPdfFails() { ... NO_TEXT ... }
@Test void extractsTextFromDocx() { ... }
@Test void plainTextFallsBackToGb18030() { ... }
@Test void markdownIsSupported() { ... }
@Test void unsupportedExtensionFails() { ... UNSUPPORTED_TYPE ... }
```

- [ ] **Step 2: Write the failing material-service tests**

```java
@Test void uploadStoresObjectAndAttachesMaterial() { ... }
@Test void uploadRejectsInterviewMode() { ... MATERIAL_NOT_APPLICABLE ... }
@Test void uploadRejectsOversizeAndUnsupportedType() { ... }
@Test void parseFailureKeepsOldMaterialAndOldObject() { ... }
@Test void replacementDeletesOldObjectAfterAttach() { ... }
@Test void deleteRemovesTaskMaterialAndObject() { ... }
@Test void getReturnsFullParsedTextOnlyForTeacherGroup() { ... }
@Test void secondTeacherOutsideGroupGetsForbidden() { ... }
```

- [ ] **Step 3: Implement extractors, storage adapters, and the material service**

Rules:
- Validate extension first: `.pdf`, `.docx`, `.txt`, `.md`; otherwise `MATERIAL_TYPE_UNSUPPORTED`.
- Size ≤ 20 MB; otherwise `MATERIAL_TOO_LARGE`.
- Parse synchronously. Map extraction errors: `UNSUPPORTED_TYPE` -> `MATERIAL_TYPE_UNSUPPORTED`; `TOO_LARGE` -> `MATERIAL_TOO_LARGE`; `NO_TEXT`/`TOO_SHORT`/`CORRUPT` -> `MATERIAL_PARSE_FAILED`.
- Extracted text length: <20 -> `MATERIAL_PARSE_FAILED`; >100,000 -> `MATERIAL_TOO_LARGE`.
- Object key: `materials/{schoolId}/{courseId}/{taskId}/{materialId}.{ext}` using the course returned by `requireTeacherCourse`.
- `upload` flow (no DB transaction while talking to MinIO):
  1. Load task, require teacher, assert draft, reject interview mode.
  2. Validate/parse.
  3. `storage.put(newKey, ...)`.
  4. `old = txService.attach(taskId, material)`; on failure delete `newKey` and rethrow.
  5. Delete `old.objectKey()` best-effort after the attach transaction commits.
- `delete` flow: load/validate; `old = txService.clear(taskId)`; delete `old.objectKey()` best-effort.
- `TaskMaterialTxService.attach`/`clear` load the task, re-check `assertDraft()` (and `mode != INTERVIEW` for attach), mutate, and save in a transaction; they do not call MinIO.
- `get`: require teacher; missing material -> `RESOURCE_NOT_FOUND`; includes full `parsed_text`.
- `upload` response `parsed_text` is null; it includes `preview` (first 500 chars).
- `MinioMaterialObjectStorage` maps MinIO failures to `DomainException(SERVICE_UNAVAILABLE)`; `InMemoryMaterialObjectStorage` stores defensive byte copies.

- [ ] **Step 4: Run tests**

```bash
docker compose --profile test run --rm test \
  gradle test --tests '*MaterialTextExtractorTest' --tests '*TaskMaterialServiceTest' \
  --no-daemon --console=plain
```

Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/infrastructure/material \
  server/src/main/java/com/shijiangtai/m3classroom/infrastructure/storage \
  server/src/main/java/com/shijiangtai/m3classroom/infrastructure/config/M3Config.java \
  server/src/main/java/com/shijiangtai/m3classroom/application/TaskMaterialService.java \
  server/src/main/java/com/shijiangtai/m3classroom/application/TaskMaterialServiceImpl.java \
  server/src/main/java/com/shijiangtai/m3classroom/application/TaskMaterialTxService.java \
  server/src/test/java/com/shijiangtai/m3classroom/infrastructure/material \
  server/src/test/java/com/shijiangtai/m3classroom/application/TaskMaterialServiceTest.java \
  server/src/test/java/com/shijiangtai/m3classroom/support
git commit -m "[M3A] 参考材料解析、MinIO 存储与清理"
```

---

### Task 10: Flyway migration, JPA entities, repository adapters, and dev data

**Files:**
- Prerequisite (already created by the all-module migration plan): `server/src/main/resources/db/migration/V4__m3_classroom.sql`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence/jpa/{TaskEntity,VirtualStudentEntity,TaskAssignmentEntity,TaskJpaRepository,VirtualStudentJpaRepository,TaskAssignmentJpaRepository,TaskJsonCodec,TaskRepositoryAdapter,TaskAssignmentRepositoryAdapter}.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/crossmodule/{InMemoryCourseQueryAdapter,InMemoryQuestionBankQueryAdapter}.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/infrastructure/config/DevDataConfig.java`
- Create: `server/src/test/resources/db/test-clean.sql`
- Create: `server/src/test/java/com/shijiangtai/m3classroom/persistence/PostgresIntegrationTest.java`
- Create: `server/src/test/java/com/shijiangtai/m3classroom/persistence/TaskRepositoryIT.java`
- Modify: `server/src/test/java/com/shijiangtai/ServerApplicationTests.java` (real context test with `@ActiveProfiles("test")`)

**Interfaces:**
- Produces:
  - The prerequisite `V4__m3_classroom.sql` and `V10__foreign_keys.sql` provide the M3 tables and all foreign keys; this task only adds the JPA mapping.
  - `TaskRepositoryAdapter implements TaskRepository`, `TaskAssignmentRepositoryAdapter implements TaskAssignmentRepository`.
  - `TaskJsonCodec` with methods:
    ```java
    String writeTaskSnapshot(TaskSnapshot snapshot); TaskSnapshot readTaskSnapshot(String json);
    String writeReferenceMaterial(ReferenceMaterial material); ReferenceMaterial readReferenceMaterial(String json);
    String writeRubricWeights(RubricWeights weights); RubricWeights readRubricWeights(String json);
    String writeQuestionConfig(QuestionConfig config); QuestionConfig readQuestionConfig(String json);
    String writeScenarioConfig(ScenarioConfig scenario); ScenarioConfig readScenarioConfig(String json);
    ```
  - `DevDataConfig` (`@Profile({"dev","test","docker"})`) exposing `CourseQueryPort`/`QuestionBankQueryPort` beans with fixed dev data:
    - School `00000000-0000-0000-0000-000000000001`
    - Teacher `...0010`, other teacher `...0011`, students `...0020`/`...0021`
    - Course `...0100`, class `...0200`
    - Questions `...0301`/`...0302` in category `...0400`
    - Course teacher group = teacher `...0010`; active students = both students.
  - `PostgresIntegrationTest` base class:
    ```java
    @SpringBootTest
    @ActiveProfiles("test")
    @Sql(scripts = "/db/test-clean.sql", executionPhase = BEFORE_TEST_METHOD)
    abstract class PostgresIntegrationTest {}
    ```

- [ ] **Step 1: Verify the all-module migrations are present**

Do **not** create a new migration. The all-module plan already provides:
- `V4__m3_classroom.sql` with all 10 M3 tables.
- `V10__foreign_keys.sql` with all cross-module FKs (including M1/M2).
- `V11__indexes.sql` with the M3 indexes and partial unique indexes.

Run `docker compose --profile migrate run --rm flyway` and assert `m3_classroom` has 10 tables and the expected FK/index names. If V4/V10/V11 are missing, stop and execute the prerequisite plan first.

- [ ] **Step 2: Write the entities and repository adapters**

Implementation notes:
- `TaskEntity` maps the `tasks` table. JSON columns are `String` with `@Column(columnDefinition = "jsonb")`.
- `VirtualStudentEntity` maps the `virtual_students` table.
- `TaskAssignmentEntity` maps `task_assignments`; `class_id_snapshot` is nullable.
- `TaskJpaRepository` includes:
  ```java
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from TaskEntity t where t.id = :id")
  Optional<TaskEntity> findByIdForUpdate(@Param("id") UUID id);
  List<TaskEntity> findByCourseIdOrderByUpdatedAtDesc(UUID courseId);
  ```
- `TaskRepositoryAdapter`:
  - `findById`: load entity + virtual students, map via `Task.rehydrate`.
  - `findByCourse`: load entities, virtual students, map.
  - `withLock`: `findByIdForUpdate`, map, run the action; the caller’s `@Transactional` holds the row lock.
  - `save`: map to entity, `taskJpaRepository.save`, replace virtual students (`deleteByTaskId` + `saveAll`).
  - `delete`: `taskJpaRepository.deleteById`; DB cascade removes children.
- `TaskAssignmentRepositoryAdapter` maps assignments and implements counts/finds.
- `TaskJsonCodec` uses the injected Jackson 3 `ObjectMapper`. `QuestionConfig` is encoded as `{"question_ids":[...],"total_duration_seconds":...}` via a small `QuestionConfigJson` record. `RubricWeights` is encoded as the flat map (`writeValueAsString(weights.asMap())`); read it as a `Map<String,Integer>` and construct `new RubricWeights(map)` (do not deserialize the wrapper record). Wrap codec errors in `DomainException(INTERNAL_ERROR, ...)`.
- `Task.rehydrate(...)` (Task 4) is used to rebuild the domain object; do not re-run draft validation for persisted rows.
- `DevDataConfig` populates the in-memory M2/M5 adapters; it is a temporary bridge until those modules exist. `InMemoryQuestionBankQueryAdapter.getForPublish` returns questions in the requested order and omits missing IDs; it throws `DomainException(SERVICE_UNAVAILABLE)` when `available=false`. `InMemoryCourseQueryAdapter.activeStudents` returns the configured active students and supports adding a course with an empty student list.

- [ ] **Step 3: Write the repository integration test and context test**

`TaskRepositoryIT` extends `PostgresIntegrationTest`, is annotated `@Transactional` (test-managed rollback), and asserts:
- Saving a task with material/scenario/virtual students/question config and reading it back preserves all fields.
- The `config_snapshot` JSONB column is readable via `JdbcTemplate` (`select config_snapshot::text ...`) and contains `"schema_version":1`.
- Virtual students are replaced, not duplicated, on update.
- Deleting a draft removes task assignments and virtual students via cascade.

`ServerApplicationTests` becomes:

```java
@SpringBootTest
@ActiveProfiles("test")
class ServerApplicationTests {
    @Test void contextLoads() {}
}
```

`test-clean.sql`:

```sql
TRUNCATE TABLE m3_classroom.task_assignments, m3_classroom.virtual_students, m3_classroom.tasks CASCADE;
```

- [ ] **Step 4: Run tests**

```bash
docker compose --profile test run --rm test \
  gradle test --tests '*TaskRepositoryIT' --tests 'ServerApplicationTests' \
  --no-daemon --console=plain
```

Expected: Flyway applies `V4`, the context loads, and the repository tests pass. If Postgres rejects `varchar -> jsonb`, verify the JDBC URL contains `stringtype=unspecified`; do not change the schema or add a second migration.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/resources/db/migration \
  server/src/main/java/com/shijiangtai/m3classroom/infrastructure/persistence \
  server/src/main/java/com/shijiangtai/m3classroom/infrastructure/crossmodule \
  server/src/main/java/com/shijiangtai/m3classroom/infrastructure/config/DevDataConfig.java \
  server/src/test/resources/db/test-clean.sql \
  server/src/test/java/com/shijiangtai/m3classroom/persistence \
  server/src/test/java/com/shijiangtai/ServerApplicationTests.java
git commit -m "[M3A] Flyway 迁移、JPA 适配器与开发数据"
```

---

### Task 11: Persistence integration tests for JSONB, rollback, concurrency, and after-commit events

**Files:**
- Create: `server/src/test/java/com/shijiangtai/m3classroom/persistence/TaskJsonCodecIT.java`
- Create: `server/src/test/java/com/shijiangtai/m3classroom/persistence/TaskPublishPersistenceIT.java`
- Test support: `server/src/test/java/com/shijiangtai/m3classroom/persistence/PublishedEventRecorder.java`

**Interfaces:**
- Consumes: Task 10 adapters/codec, Task 7/8 services, `DevDataConfig`.
- Produces: verified persistence behavior; no new production types.

- [ ] **Step 1: Write the failing JSONB round-trip test**

```java
@Test void snapshotJsonUsesSnakeCaseAndRoundTrips() {
    String json = codec.writeTaskSnapshot(snapshot);
    assertThat(json).contains("\"schema_version\":1", "\"published_at\"", "\"question_count\"");
    assertThat(codec.readTaskSnapshot(json)).isEqualTo(snapshot);
}
@Test void questionConfigRoundTripsWithQuestionIds() { ... }
@Test void referenceMaterialRoundTrips() { ... }
```

- [ ] **Step 2: Write the failing publish persistence tests**

```java
@Test void publishPersistsAssignmentsAndSnapshot() { ... }
@Test void zeroStudentsRollsBackTaskAndAssignments() {
    assertThatThrownBy(...).extracting("code").isEqualTo(ErrorCode.TASK_NO_STUDENTS);
    assertThat(taskRepository.findById(taskId).orElseThrow().status()).isEqualTo(TaskStatus.DRAFT);
    assertThat(assignmentRepository.findByTask(taskId)).isEmpty();
}
@Test void questionBankFailureRollsBack() { ... SERVICE_UNAVAILABLE ... }
@Test void concurrentPublishHasOneWinner() throws Exception {
    // two threads; one success, one TASK_ALREADY_PUBLISHED; exactly one assignment set
}
@Test void publishedEventArrivesAfterCommit() {
    // PublishedEventRecorder receives exactly one TaskPublishedEvent after publish returns
}
```

- [ ] **Step 3: Implement the test support**

- `PublishedEventRecorder` is a `@TestConfiguration` bean implementing `ApplicationListener<TaskPublishedEvent>` with a `CopyOnWriteArrayList` and `clear()`.
- `TaskPublishPersistenceIT` extends `PostgresIntegrationTest`, autowires `TaskApplicationService`, `TaskPublishService`, `TaskRepository`, `TaskAssignmentRepository`, `InMemoryCourseQueryAdapter`, `InMemoryQuestionBankQueryAdapter`, and the recorder.
- Create tasks through `TaskApplicationService` with the teacher from `DevDataConfig`.
- For zero students, add a course with no active students to `InMemoryCourseQueryAdapter`.
- For M5 failure, set `InMemoryQuestionBankQueryAdapter.setAvailable(false)` before publishing an interview task.
- For the concurrency test, use an `ExecutorService` with two tasks and a `CountDownLatch`; collect results/exceptions; assert one success and one `TASK_ALREADY_PUBLISHED`.
- Do not annotate the class `@Transactional`; commits must be real. `@Sql` cleanup runs before each test.

- [ ] **Step 4: Run tests**

```bash
docker compose --profile test run --rm test \
  gradle test --tests '*TaskJsonCodecIT' --tests '*TaskPublishPersistenceIT' \
  --no-daemon --console=plain
```

Expected: all pass. The concurrency test must show exactly one published task and one assignment set.

- [ ] **Step 5: Commit**

```bash
git add server/src/test/java/com/shijiangtai/m3classroom/persistence
git commit -m "[M3A] 持久化 JSONB、回滚与并发发布验证"
```

---

### Task 12: Task/student REST API, error handling, and controller integration tests

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/api/security/HeaderCurrentUserProvider.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/api/error/{ApiErrorResponse,GlobalExceptionHandler}.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/api/dto/{CreateTaskRequest,VirtualStudentRequest,QuestionConfigRequest,ScenarioRequest,TaskRequestMapper}.java`
- Create: `server/src/main/java/com/shijiangtai/m3classroom/api/{TaskController,StudentTaskController}.java`
- Test: `server/src/test/java/com/shijiangtai/m3classroom/api/{TaskRequestMapperTest,TaskControllerIT,StudentTaskControllerIT}.java`

**Interfaces:**
- Produces:
  - `@Component HeaderCurrentUserProvider implements CurrentUserProvider`, reading `X-Account-Id`, `X-School-Id`, `X-Account-Type`; missing/invalid -> `DomainException(UNAUTHORIZED)`.
  - `record ApiErrorResponse(String code, String message, Map<String,Object> details, String traceId)`.
  - `@RestControllerAdvice GlobalExceptionHandler`:
    - `DomainException` -> `code.httpStatus()`.
    - `MethodArgumentNotValidException` -> 400 `VALIDATION_ERROR` with `fields`.
    - `MaxUploadSizeExceededException` -> 422 `MATERIAL_TOO_LARGE`.
    - other `Exception` -> 500 `INTERNAL_ERROR` (log; never expose the stack).
  - `record CreateTaskRequest(String mode, String topic, String textbookVersion, Integer durationRecommendedSeconds, Integer durationMaxSeconds, Map<String,Integer> rubricWeights, ScenarioRequest scenario, List<VirtualStudentRequest> virtualStudents, QuestionConfigRequest questionConfig)`.
  - `record VirtualStudentRequest(UUID virtualStudentId, String name, String avatar, String personalityText, BigDecimal activity, BigDecimal questionRate, BigDecimal ability, BigDecimal attentionBaseline, BigDecimal disciplineProbability, int seatNo, int sortOrder)`.
  - `record QuestionConfigRequest(List<UUID> questionIds, int totalDurationSeconds)`.
  - `record ScenarioRequest(String role, String situation)`.
  - `@Component TaskRequestMapper`:
    ```java
    CreateTaskCommand toCommand(UUID courseId, CreateTaskRequest request);
    UpdateTaskCommand toCommand(tools.jackson.databind.JsonNode body);
    ```
  - Endpoints:
    - `POST /api/v1/courses/{course_id}/tasks` -> `TaskView`
    - `GET /api/v1/courses/{course_id}/tasks?page=&size=&status=&mode=` -> `PageResult<TaskCardView>`
    - `GET /api/v1/tasks/{task_id}` -> `TaskView` for teachers, `StudentTaskDetailView` for assigned students
    - `PATCH /api/v1/tasks/{task_id}` -> `TaskView`
    - `POST /api/v1/tasks/{task_id}/publish` -> `PublishResult`
    - `POST /api/v1/tasks/{task_id}/close` -> `TaskView`
    - `DELETE /api/v1/tasks/{task_id}` -> 204
    - `GET /api/v1/tasks/{task_id}/assignments?page=&size=` -> `PageResult<TaskAssignmentView>`
    - `GET /api/v1/student/tasks?page=&size=` -> `PageResult<StudentTaskView>`
    - `GET /api/v1/student/tasks/{task_id}` -> `StudentTaskDetailView`

- [ ] **Step 1: Write the failing mapper and error-handler tests**

```java
@Test void mapsSnakeCaseCreateRequest() { ... }
@Test void updateMapperDistinguishesMissingFromExplicitNull() {
    JsonNode body = objectMapper.readTree("{\"topic\":\"新课题\",\"textbook_version\":null}");
    UpdateTaskCommand command = mapper.toCommand(body);
    assertThat(command.topic().present()).isTrue();
    assertThat(command.textbookVersion().present()).isTrue();
    assertThat(command.textbookVersion().value()).isNull();
    assertThat(command.mode().present()).isFalse();
}
@Test void domainExceptionUsesCodeAndHttpStatus() { ... }
@Test void unknownExceptionReturnsInternalErrorWithoutStackTrace() { ... }
```

- [ ] **Step 2: Write the failing controller integration tests**

`TaskControllerIT` extends `PostgresIntegrationTest`, adds `@AutoConfigureMockMvc`, and uses headers:

```java
MockHttpServletRequestBuilder asTeacher = post("/api/v1/courses/{id}/tasks", COURSE_ID)
    .header("X-Account-Id", TEACHER_ID)
    .header("X-School-Id", SCHOOL_ID)
    .header("X-Account-Type", "TEACHER")
    .contentType(APPLICATION_JSON)
    .content(json);
```

Assertions:
- Create free/interview drafts; response uses snake_case (`$.task_id`, `$.rubric_weights.content`).
- Publish returns `assigned_count`.
- Student task detail omits `rubric_weights`, `reference_material`, and `published_questions`.
- Other teacher 403 `TEACHER_NOT_IN_GROUP`; admin 403; missing headers 401.
- `PATCH` with explicit `textbook_version: null` clears the field; absent fields remain.

- [ ] **Step 3: Implement API infrastructure and controllers**

- Header provider parses UUIDs/enums; admin authenticates but the service denies M3.
- `GlobalExceptionHandler` uses `X-Trace-Id` or a generated UUID; response body `trace_id`.
- `TaskRequestMapper` uses the injected Jackson 3 `ObjectMapper`; for `PATCH`, `body.has("field")` distinguishes absent from explicit null; nested request records are converted with `treeToValue`.
- `TaskController` delegates; `GET /tasks/{id}` returns the teacher or student view based on `CurrentUser.type()`. `POST /close` calls `publishService.close` then `taskService.get`. `DELETE` returns 204.
- `StudentTaskController` delegates to `listStudentTasks`/`getStudentTask`.
- No business logic in controllers; no `@PreAuthorize`.

- [ ] **Step 4: Run tests**

```bash
docker compose --profile test run --rm test \
  gradle test --tests '*TaskRequestMapperTest' --tests '*TaskControllerIT' \
  --tests '*StudentTaskControllerIT' --no-daemon --console=plain
```

Expected: all pass against the Compose PostgreSQL database.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/api \
  server/src/test/java/com/shijiangtai/m3classroom/api
git commit -m "[M3A] 任务与学生任务 REST 接口"
```

---

### Task 13: Material REST API and controller integration tests

**Files:**
- Create: `server/src/main/java/com/shijiangtai/m3classroom/api/TaskMaterialController.java`
- Test: `server/src/test/java/com/shijiangtai/m3classroom/api/TaskMaterialControllerIT.java`

**Interfaces:**
- Produces:
  - `POST /api/v1/tasks/{task_id}/material` (`multipart/form-data`, field `file`) -> `MaterialView` (200).
  - `GET /api/v1/tasks/{task_id}/material` -> `MaterialView` with full `parsed_text`.
  - `DELETE /api/v1/tasks/{task_id}/material` -> 204.

- [ ] **Step 1: Write the failing material controller test**

```java
@Test void uploadTxtThenGetThenDelete() throws Exception {
    mockMvc.perform(multipart("/api/v1/tasks/{id}/material", taskId)
            .file(new MockMultipartFile("file", "教案.txt", "text/plain", textBytes))
            .headers(teacherHeaders))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.parse_status").value("parsed"))
        .andExpect(jsonPath("$.preview").isString());
    mockMvc.perform(get(...).headers(teacherHeaders))
        .andExpect(jsonPath("$.parsed_text").isString());
    mockMvc.perform(delete(...).headers(teacherHeaders)).andExpect(status().isNoContent());
}
@Test void interviewMaterialUploadReturns422() { ... MATERIAL_NOT_APPLICABLE ... }
@Test void studentCannotReadMaterial() { ... 403 ... }
```

- [ ] **Step 2: Run test to verify it fails**

```bash
docker compose --profile test run --rm test \
  gradle test --tests '*TaskMaterialControllerIT' --no-daemon --console=plain
```

Expected: compilation failure.

- [ ] **Step 3: Implement the controller**

- Read `MultipartFile.getBytes()`, `getOriginalFilename()`, `getContentType()`; delegate to `TaskMaterialService`. Empty file -> `VALIDATION_ERROR`.
- `DELETE` returns 204 even if the material is already absent (idempotent).
- The test profile uses `InMemoryMaterialObjectStorage`; the Docker app profile uses MinIO.
- No business logic in the controller.

- [ ] **Step 4: Run tests**

Run the Step 2 command again. Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/shijiangtai/m3classroom/api/TaskMaterialController.java \
  server/src/test/java/com/shijiangtai/m3classroom/api/TaskMaterialControllerIT.java
git commit -m "[M3A] 材料 REST 接口"
```

---

### Task 14: Architecture tests, full Docker suite, Compose smoke, and progress evidence

**Files:**
- Create: `server/src/test/java/com/shijiangtai/m3classroom/ArchitectureTest.java`
- Create: `docs/team/evidence/2026-11-04-m3a-docker-postgres.md`
- Modify: `docs/team/02-进度看板.md` (M3A row “阻塞 / 下一步” and §5 update log only)
- Modify: `server/README.md` (run/test/smoke commands)

**Interfaces:**
- Consumes: all prior tasks.
- Produces: verified full suite, Compose smoke evidence, progress-board note. No new production types.

- [ ] **Step 1: Write the architecture test**

```java
@AnalyzeClasses(packages = "com.shijiangtai")
class ArchitectureTest {
    @ArchTest static final ArchRule domainHasNoFrameworkImports =
        noClasses().that().resideInAPackage("..m3classroom.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..", "tools.jackson..", "jakarta.persistence..",
                "..m3classroom.api..", "..m3classroom.application..", "..m3classroom.infrastructure..");
    @ArchTest static final ArchRule applicationDoesNotDependOnApiOrInfrastructure = ...;
    @ArchTest static final ArchRule apiDoesNotDependOnInfrastructure = ...;
}
```

Run it; if it fails, fix the dependency direction (do not weaken the rule).

- [ ] **Step 2: Run the full suite in the Docker test service**

```bash
docker compose --profile test run --rm test
```

Expected: `BUILD SUCCESSFUL`; all unit and integration tests pass against `shijiangtai_test`. Record the test count.

- [ ] **Step 3: Build and smoke the full Compose stack**

```bash
docker compose up -d --build
docker compose ps
curl -fsS http://localhost:8080/actuator/health
```

Then, with teacher headers:
1. Create a `free` task with `textbook_version`, a `.txt` reference material (at least 20 characters), 1 virtual student, and weights summing to 100.
2. Upload the material via `POST /api/v1/tasks/{task_id}/material`.
3. Publish the task.
4. Fetch the student task list with student headers.
5. Verify the MinIO object:
   ```bash
   docker compose exec minio mc ls --recursive local/shijiangtai/materials
   ```
Expected: health `UP`, publish returns `assigned_count`, the student list contains the task, and MinIO lists the material object.

Stop the app but keep volumes:

```bash
docker compose down
```

- [ ] **Step 4: Write the evidence and update the progress board**

Evidence file must list:
- Docker Desktop/Compose versions; images used (including the MinIO SWR mirror).
- Commands run, full test count, Compose smoke result, MinIO object check.
- What is covered and what is deferred (M3B/C, frontend, M1 JWT, M2/M5 real implementations).
- Cross-module authorization: `server/Dockerfile`, `server/.dockerignore`, root `docker-compose.yml`, `server/docker/postgres/init/**`; user approval on 2026-11-04.
- All M1/M2 foreign keys are present in `V10__foreign_keys.sql` from the prerequisite migration plan.

Progress board:
- Update only the M3A row’s “阻塞 / 下一步” to: `Docker + PostgreSQL 垂直切片已实现；待 G2 设计冻结与 E2E`.
- Add one §5 update row: `2026-11-04 | M3A Owner | M3A | Docker 全栈 + PostgreSQL/Flyway/JPA 垂直切片；证据见 evidence/2026-11-04-m3a-docker-postgres.md | 10%`.
- Do **not** mark G3/G4/G5 ✅ and do not change other rows; G2 is still pending, so completion stays 10%.

- [ ] **Step 5: Commit**

```bash
git add server/src/test/java/com/shijiangtai/m3classroom/ArchitectureTest.java \
  server/README.md docs/team/evidence/2026-11-04-m3a-docker-postgres.md docs/team/02-进度看板.md
git commit -m "[M3A] 架构测试、Docker 全量验证与进度证据"
```

---

## Out of Scope / Follow-up Plan

The following are deliberately **not** in this plan; do not implement them or fake them:

- M1 JWT authentication, URL-level permission codes, `CurrentUser` from JWT. The header provider is dev-only.
- M2 course implementation and M5 question-bank implementation. Ports + in-memory dev adapters only.
- None for schema: all M1/M2 foreign keys are added by `V10__foreign_keys.sql` in the prerequisite migration plan.
- M3B session/WS/interview room, M3C recording/replay, frontend pages, E2E.
- Redis-backed features; Redis is provisioned but unused by M3A.
- MinIO multipart/presigned URLs for recording; M3A only stores material objects via put/delete.
- `Idempotency-Key`, task version conflict, assignment `submitted` transitions (M3B).
- Scanned-PDF OCR, LLM question generation, task copy/templates.
- Testcontainers: integration tests use the Compose `postgres` service instead; document this in evidence.

## Plan Self-Review

**Spec coverage:** M3A §3 (task model, modes, fields), §4.1 (materials), §4.2 (virtual students), §4.3 (scenario), §4.4 (weights), §4.5 (question selection/snapshot), §4.6 (config snapshot), §5 (publish/close), §7.1–§7.2 (task/material APIs), §8 (permissions/visibility), and `10` §6.4 M3-T01–T08 are mapped to Tasks 2–14. M3B/C sections are intentionally excluded.

**Step scan:** Each task has a failing test, an implementation step with exact signatures/rules, a pass command, and a commit. The persistence tasks explicitly cover JSONB, rollback, concurrency, and after-commit events.

**Type consistency:** `CourseInfo`/`CourseStudentInfo` live in `domain/task`, so the domain policy does not depend on application ports. `PublishQuestion` is the policy/factory input; `QuestionSnapshot` is the snapshot output. `Task.rehydrate` is the single persistence reconstruction path. `TaskRepository.withLock` is implemented by the JPA adapter with `PESSIMISTIC_WRITE`, and by the test double with a per-task lock.

**Review Focus coverage:** mode switch/material deletion (Task 7), publish failure rollback (Tasks 8, 11), concurrent publish (Tasks 8, 11), student visibility (Tasks 7, 12), JSONB/transaction correctness (Tasks 10, 11).
