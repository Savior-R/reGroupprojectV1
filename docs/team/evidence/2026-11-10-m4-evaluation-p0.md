# 2026-11-10 M4 AI 评测与反馈 P0 实现证据

> 分支：`feat/m4-eval`（基于 `main`；尚未提交 / 推送）
> 计划 / 授权：`docs/team/prompts/03-ai-m4.md`（AI Owner）
> 执行人：AI Owner（当前使用者）
> 环境：Windows 11 + Git Bash；JDK 25.0.1（项目 toolchain 21 的临时覆盖）、Gradle 9.5.0；Python 3.13.15（临时 venv）、pytest 8.x；Node / npm + Vite 8.2.2；**无 Docker、无 PostgreSQL / Redis / MinIO，未启动 Java 服务**。
> 结论：M4 Java 编排 / 持久化、Python evaluation / media / worker、报告前端切片已实现；Java 编译与 **22 条 M4 领域单测**通过，Python **26 条 mock 测试**通过，前端生产构建通过。**未执行 IT / 契约 / E2E / 性能测试，`docs/design/10` 的 M4-T01—T11 大部分仍待环境验证；本文件不声称 P0 全链路验收通过。**

## 1. 范围与授权

### 1.1 授权路径

按 `03-ai-m4.md` 允许路径执行：

- `server/src/main/java/com/shijiangtai/m4eval/**`
- `server/src/test/java/com/shijiangtai/m4eval/**`
- `ai/app/capabilities/evaluation.py`、`ai/app/capabilities/media.py`
- `ai/app/worker/**`、`ai/app/prompts/evaluation/**`、`ai/app/schemas/evaluation.py`
- `ai/tests/evaluation/**`
- `frontend/src/views/report/**`
- `docs/team/evidence/*m4*`

### 1.2 明确未修改（跨模块）

- 未修改 `shared/**`、`m1auth/**`、`m2course/**`、`m3classroom/**`、`m5question/**`、`m6training/**`、`m7teacher/**`。
- 未修改 `ai/app/main.py`、`ai/app/config.py`、`ai/app/providers/**`。
- 未修改 `frontend/src/app/router.ts`、`layouts/**`、`stores/auth.ts`、`services/**`、`frontend/src/api/**`、`e2e/**`（Score/QA Owner）。
- 未修改 `ai/pyproject.toml`、`docker-compose.yml`、`application*.yml`、`build.gradle.kts`、`docs/team/01`、owner 表、共享 API 契约。
- M2 / M6 尚未实现：M4 通过只读 JDBC 查询其**已发布表**（`m2_course.courses` / `course_teachers`，`m6_training.training_records` / `training_session_meta`），不写对方表。
- `TrainingRecordSelectedEvent` 按 `docs/design/11` §B7 的已文档化契约在 M4 定义并监听；M6 实现时需按同名字段发布。
- 数据库：`V5__m4_eval.sql` 已包含 5 张 M4 表，本次**未新增迁移**。

### 1.3 需跨模块确认（尚未执行）

| 项 | 文件 / 目录 | 归属 | 原因 | 最小方案 | 需要确认 |
|---|---|---|---|---|---|
| A | `ai/pyproject.toml` | AI Owner 共享入口（允许清单未列） | worker 需要 `redis>=5` 的 Streams 客户端；当前代码可选导入，缺包时 worker 启动失败 | 增加可选依赖组 `worker = ["redis>=5"]`，Web 镜像不受影响 | 用户 / 架构 owner |
| B | `frontend/src/app/router.ts` | 前端共享层（Score/QA Owner） | 三个报告页尚无路由，无法在浏览器访问 | 增加 `/evaluations/:evaluationId/report`、`/submissions/:submissionId/report`、`/training/records/:recordId/report` 三条懒加载路由 | Score/QA Owner |
| C | 真实 provider 手测 | AI 共享入口 | 本次仅使用确定性 mock，未调用 DeepSeek | 在有密钥的 staging 对一份已完成课堂执行 `AI_MOCK=false` 手工 smoke | 用户 / AI Owner |

## 2. 实现摘要

### 2.1 Java（M4 编排 / 持久化）

| 区域 | 内容 |
|---|---|
| 触发 / 幂等 | `SubmissionCreatedEvent`、`TrainingRecordSelectedEvent` 监听；`(source_type, source_id)` 查询 + DB 唯一键；`EvaluationReconciler` 每 60 s 补偿丢失触发 |
| 输入快照 | `InputSnapshot` 固化 recording / text / frame / event ID、task / training 配置版本、flags；分页输入按快照 ID 过滤，迟到数据不改变已有证据 |
| 队列 | Redis Streams `evaluation:jobs`，consumer group `eval-workers`，消息只带 ID；`XADD` 失败保持 queued 由调度器重投 |
| 状态机 | `pending → running → completed / partial / failed`；job `queued / running / completed / partial / failed` |
| 回调 | `/internal/evaluations/{id}/start|result|fail`，`X-Service-Token` + `Idempotency-Key: job_id:attempt`；尝试号守卫（旧 200 ignored / 高 409 / 终态拒绝） |
| 评分 | 适用维度 free / scenario 5 维、interview 3 维；固定子项权重；`total = Σ(score × weight)/100`，HALF_UP 一位小数；Python 不返回总分 |
| partial | weight>0 维度缺失或致命 `partial_reasons` → partial 且无总分；零权重缺失只告警 |
| 重试 | 15 / 60 / 180 s，最多 3 次；`running` 超过 10 min 视为 worker 失联；死信 `evaluation:dead`，保留已完成维度 |
| 终态事件 | `EvaluationTerminalEvent`（v1）在事务提交后发布；partial / failed 同样终态 |
| 权限 | 学生本人；教师须在课程组；管理员一律 403；训练报告仅本人；跨校 403 |
| 报告 | `/evaluations/{id}`、`/report`、`/issues`、`/submissions/{id}/evaluation`、`/training/records/{id}/evaluation`；pending / running → 409，failed → 422 |

### 2.2 Python（capability / media / worker）

| 文件 | 内容 |
|---|---|
| `ai/app/capabilities/evaluation.py` | 5 / 3 维并行评测；内容 / 表达 / 互动 / 板书走 LLM JSON，时间维度本地规则；子项加权成维度分；issue 时间裁剪、3—5 条建议；audio 缺失 / 转写缺失 / 帧缺失 / 维度超时映射为 partial_reasons；不计算总分 |
| `ai/app/capabilities/media.py` | WAV PCM 静音比例、长停顿、低音量比例；关键帧选择（≤4）；不向 LLM 发送原始音视频 |
| `ai/app/prompts/evaluation/v1.py` | `PROMPT_VERSION=m4-eval-v1`，`RUBRIC_VERSION=m4-rubric-v1`，逐维度 JSON 契约 |
| `ai/app/schemas/evaluation.py` | manifest / 分页输入 / 回调 DTO，snake_case 与 Java 一致 |
| `ai/app/worker/input_client.py` | manifest + texts / frames / events 分页；`after_id` / `after_ts` / `after_seq` 游标；不记录预签名 URL |
| `ai/app/worker/callback.py` | start / result / fail，`Idempotency-Key`；409 视为过期 / 终态并放弃消息 |
| `ai/app/worker/engine.py` | start → manifest → 并行拉取 → 音频特征 → 90 s 整体超时 → result / fail；回调失败不吞异常 |
| `ai/app/worker/consumer.py` | `XREADGROUP`、`XAUTOCLAIM`（>10 min）、成功 / 过期才 `XACK`、回调失败保持 pending |
| `ai/app/worker/mock_provider.py` | CI / 本地确定性 mock，不冒充真实 provider |
| `ai/app/worker/main.py` | 独立进程入口；worker 并发 3、LLM 并发 8；无 DB |

### 2.3 前端（`frontend/src/views/report/**`）

- `EvaluationReportPanel.vue`：pending / running 每 3 s 轮询；completed / partial 渲染总分、雷达、维度卡、建议、提示、元信息；failed / 403 / 404 独立状态。
- `RadarChart.vue`：无图表库的内联 SVG 单序列雷达；缺失维度标注“未提供”，数值与轴标签使用文本色。
- `EvaluationReportPage.vue`、`SubmissionReportPage.vue`、`TrainingRecordReportPage.vue`：三种入口，路由注册待 Score/QA Owner（见 §1.3 B）。
- 建议卡展示维度、严重度、时间区间，并可跳转 `/student/sessions/{sessionId}/review?t={ms}`。

### 2.4 契约版本

| 契约 | 版本 / 位置 |
|---|---|
| `EvaluationInputManifest` | v1，字段见 `EvaluationInputManifestView` / `docs/design/06` §9.3 |
| `EvaluationTerminalEvent` | v1：`evaluation_id, source_type, source_id, session_id, status, completed_at, attempt` |
| 回调 | `docs/design/06` §5.3（start / result / fail，snake_case） |
| 评分规则 | `m4-rubric-v1` |
| Prompt | `m4-eval-v1` |

## 3. 验证结果（2026-11-10）

### 3.1 Java 编译与单测

```text
cd server
gradle test --tests "com.shijiangtai.m4eval.*" \
  --init-script /tmp/m4build/toolchain.gradle \
  -Dorg.gradle.java.installations.paths="C:/Users/60540/.jdks/openjdk-25.0.1"

BUILD SUCCESSFUL in 7s
22 tests completed, 0 failed
```

覆盖：`EvaluationPolicyTest`（加权总分、一位小数、required 缺失、零权重告警、partial_reasons、failed、dead-letter）、`EvaluationResultValidatorTest`（适用维度、分数范围 / 精度、子项完整性、建议时间锚点）、`WeightDefaultsTest`（默认权重、interview 维度、归一化 / 负数 / 总和）。

> 环境说明：项目 `build.gradle.kts` 要求 JDK 21 toolchain，本机仅安装 JDK 25；使用 `/tmp/m4build/toolchain.gradle` 临时覆盖，不修改仓库构建文件。

### 3.2 Python mock 测试

```text
cd ai
python -m pytest tests/evaluation -q
.......................... [100%]
26 passed
```

覆盖：free / scenario / interview 维度集合、子项契约、audio 缺失 → partial、无输入 → failed、issue 时间裁剪、3—5 条建议；WAV 特征 / 非法音频 / mock URL；engine 成功、stale start、输入失败、90 s 超时、无维度、回调失败不 ack；consumer ack / 不 ack / 非法消息 / bytes 解码。

`python -m compileall -q app` 通过。

### 3.3 前端构建

```text
cd frontend
npm install --no-audit --no-fund   # 173 packages
npm run build                      # vue-tsc -b && vite build
✓ 1775 modules transformed
✓ built in 915ms
```

仅保留既有 Element Plus 主 chunk >500 kB 警告；报告页尚未接入路由，因此不产生独立 chunk。

### 3.4 未执行 / 测试后置

- **M4-T01 / T02 / T03 / T06 / T09 / T11**：代码已实现，但无 PostgreSQL / Redis / MinIO / 真实 event bus，未跑 IT。
- **M4-T07**：仅有 mock provider 与失败分支单测；未做真实 LLM 超时 / 429 / 非法 JSON 注入。
- **M4-T08**：有 WAV / 关键帧单测；未跑真实 OCR 帧与参考材料检索。
- **M4-T10**：未执行 3 并发 <90 s 与 token 预算性能测试。
- **E2E / 浏览器 smoke**：未启动后端，未执行。
- **真实 provider**：未调用 DeepSeek / OCR / ASR，不声称真实模型通过。

## 4. M4 测试项状态

| 项 | 状态 | 说明 |
|---|---|---|
| M4-T01 触发 / 幂等 | ◐ | 代码 + 唯一键设计；无 IT |
| M4-T02 输入快照 / flags / 分页 | ◐ | 代码；分页游标已在 SQL 层审查；无 IT |
| M4-T03 队列 / 回调 / attempt | ◐ | Java 代码 + Python engine / consumer 单测；无 Redis IT |
| M4-T04 评分 / 权重 / 精度 | ✅ | Java 22 条 UT + Python 26 条 mock UT |
| M4-T05 partial / 零权重 / 无总分 | ✅ | `EvaluationPolicyTest`、`test_evaluation_capability.py` |
| M4-T06 重试 / 退避 / 死信 / reclaim | ◐ | 代码；无并发 / 崩溃 IT |
| M4-T07 LLM 异常 / 建议 fallback | ◐ | mock 与维度失败分支；无真实故障注入 |
| M4-T08 音频特征 / 关键帧 | ✅ | `test_media.py` |
| M4-T09 报告权限 | ◐ | 代码；无安全 IT |
| M4-T10 性能 | ⬜ | 未执行 |
| M4-T11 终态事件 / 对账 | ◐ | 代码；无事件总线 IT |

## 5. 风险与阻塞

1. **worker 依赖未落库**：`redis` 未写入 `ai/pyproject.toml`（跨模块确认项 A），当前 worker 只能在一个已安装 `redis` 的环境中运行。
2. **报告页不可路由**：`router.ts` 属 Score/QA（确认项 B），未改；页面代码与构建已就绪。
3. **M2 / M6 未实现**：M4 读取其表与监听约定事件；真实联调需等对应模块落地。
4. **真实 provider 未验证**：mock 结果不能作为真实模型质量证据。
5. **迁移**：`V5__m4_eval.sql` 在基线已存在，本次未改；若 M3 后续调整表结构，需双方确认后同步。
6. **SQL 修正**：文本分页游标由 `id > after_id` 改为 `(at_ms, id) > (after_id 对应行的 at_ms, id)`，避免 `ORDER BY at_ms, id` 与 UUID 游标不一致导致漏页；属 M4 自有查询。

## 6. 复现命令

```bash
# Java
cd server
~/.gradle/wrapper/dists/gradle-9.5.0-bin/*/gradle-9.5.0/bin/gradle test \
  --tests "com.shijiangtai.m4eval.*" --init-script /tmp/m4build/toolchain.gradle \
  -Dorg.gradle.java.installations.paths="$HOME/.jdks/openjdk-25.0.1"

# Python
cd ai
python -m pytest tests/evaluation -q

# Frontend
cd frontend
npm ci
npm run build
```
