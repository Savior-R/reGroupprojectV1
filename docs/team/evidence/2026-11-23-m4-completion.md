# 2026-11-23 M4 评测补齐：零权重语义、截止时间保留、互动/时间指标与报告联动

> 分支：`feat/m4-eval`（基于 `main`）
> 依据：`docs/team/prompts/03-ai-m4.md`、`docs/design/06-M4-AI评测与反馈.md`、`docs/design/10-测试与验收.md` §6.7
> 执行人：AI Owner（当前使用者）
> 环境：Windows 11 + Git Bash；JDK 25.0.1 + Gradle 9.5.0（临时 init script 覆盖 toolchain，未改 `build.gradle.kts`）；Python 3.13（`ai/.venv`）；Node 24 / npm 11；**无 Docker、无 PostgreSQL / Redis / MinIO 运行中**。
> 结论：修复两处契约偏差，补全互动统计、时间维度 LLM 评测、建议选择/改写与前端问题时间线；新增 Java / Python 跨语言契约 fixture。M4 Java **124 条**、Python **68 条**、前端报告页 **21 条**（全量 164 条）通过，前端生产构建通过。**PostgreSQL / Redis / MinIO IT、真实 Provider、真实浏览器 E2E 与真实模型性能仍未执行。**

## 1. 提交

| 提交 | 内容 |
|---|---|
| `fix(m4)` | 零权重维度缺失 / 失败只记 warning，不再产生 partial；Java 按维度过滤 partial reason |
| `fix(m4-api)` | 问题数量按维度 ≤5、总数 ≤20、建议 ≤5 校验（建议副本不重复计数） |
| `test(m4-api)` | Redis Streams 载荷与死信、对账器、JSONB codec、报告 / manifest DTO snake_case 契约 |
| `feat(m4-ai)` | 90 s 截止时间保留已完成维度；零权重语义对齐；互动事件统计补全 |
| `feat(m4-ai)` | 时间维度阶段 / 面试题目窗口规则 + LLM 评测；建议 3—5 条 LLM 选择与改写 + fallback |
| `test(m4-ai)` | Java ↔ Python 输入 / 队列 / 回调契约 fixture 与分页、字节字段契约测试 |
| `feat(m4-web)` | 报告问题时间线（`/issues`）、维度置信度与低置信标记、缺失维度「未完成」、训练报告 409 轮询 |
| `docs(m4)` | 本证据与进度看板 |

## 2. 修复的契约偏差

1. **零权重维度错误触发 partial（Java + Python）**：`06` §3.2 / §6.7 要求 `weight=0` 的维度缺失只记 warning。原先 worker 只要返回 `FRAMES_MISSING` 等 reason，Java `EvaluationPolicy.decide` 与 Python `evaluate` 都会标 partial。现在：
   - Python 按维度权重过滤 `partial_reasons`；零权重维度缺失追加 `ZERO_WEIGHT_DIMENSION_MISSING` warning；
   - Java 将 reason 映射到受影响维度（`FRAMES_MISSING→board`、`AUDIO_MISSING→expression`、`TRANSCRIPT_MISSING→content/expression/time`、`ANSWER_POINTS_MISSING→content`），只有对应维度 `weight>0` 才 gate；`DIMENSION_*` 依赖缺失维度检查。
2. **90 s 截止时间丢弃已完成维度（Python）**：`EvaluationEngine` 把剩余预算传给 capability；`EvaluationCapability` 用 `asyncio.wait` 收集已完成维度，未完成维度标 `DIMENSION_TIMEOUT`，返回 partial 而不是整体 failed。仍有维度时回调 result，全部缺失才 fail `AI_TIMEOUT`。
3. **回调问题数量无上限（Java）**：按 `06 §7.3` 校验每维度问题 ≤5、问题总数 ≤20、建议 ≤5；建议副本不计入问题上限。

## 3. 补全的 P0 行为

| 项 | 说明 | 文件 |
|---|---|---|
| 互动统计 | 补 `teacher.called_on`、`teacher.marked` 正确性分布、`teacher.management_action`、`dialogue.started` 轮次、点名 / 提问到回答的响应时间；`classroom.question_asked` 仅 `intent=answer` 计为提问 | `ai/app/capabilities/evaluation.py` |
| 时间维度 | 自由 / 情景按 60 s 间隔切分阶段并计算阶段时长与分配分；面试按 `interview.question_shown/answer_ended` 生成题目窗口、统计未作答并产生问题；由 LLM 输出四个子项，失败时回退规则分并标 `TIME_RULE_ONLY` | `ai/app/capabilities/evaluation.py`、`ai/app/prompts/evaluation/v1.py` |
| 改进建议 | 新增 suggestions prompt：从排序后的问题中选 3—5 条并改写建议文本，保留原维度 / 严重度 / 时间；非法输出或 Provider 失败回退到原有建议 + 模板补齐 | 同上、`ai/app/worker/mock_provider.py` |
| 契约 fixture | `ai/tests/evaluation/fixtures/contract/` 固化 manifest、文本 / 帧 / 事件分页、Redis 队列消息、result / fail 回调；覆盖游标分页与 Redis 字节字段 | 新增 `test_contract_fixtures.py` |
| 前端问题时间线 | 报告页调用 `/issues`，按 `start_ms` 折叠展示全部问题（不含建议副本），可跳转回放 | `frontend/src/views/report/EvaluationReportPanel.vue` |
| 置信度 | 维度卡展示置信度；`< 0.7` 标记「低置信度」 | 同上 |
| 缺失维度 | `partial` 报告为缺失维度渲染灰显「未完成」卡片 | 同上 |
| 训练报告轮询 | `GET /training/records/{id}/evaluation` 返回 409 时每 3 s 重试，不再直接展示错误 | `TrainingRecordReportPage.vue` |

## 4. 新增测试

| 层 | 文件 | 覆盖 |
|---|---|---|
| Java | `EvaluationPolicyTest`（+3） | 零权重 reason、零权重维度失败、跨维度 reason |
| Java | `EvaluationResultValidatorTest`（+4） | 每维度 / 总数 / 建议上限，建议不重复计数 |
| Java | `RedisEvaluationQueueTest`（4） | 队列字段、maxlen、死信 reason、30 天过期 |
| Java | `EvaluationReconcilerTest`（4） | 两类来源扫描、单条失败隔离、空批次 |
| Java | `M4JsonCodecTest`（7） | JSONB 读写、空值、非法 JSON、int / decimal / string list |
| Java | `EvaluationDtoContractTest`（2） | `ReportView` / `EvaluationInputManifestView` snake_case 字段与嵌套结构 |
| Python | `test_evaluation_metrics.py`（6） | 互动统计、零权重 completed、截止时间保留、面试未作答、建议选择 / fallback |
| Python | `test_contract_fixtures.py`（8） | manifest / 分页 / 队列 / 回调 fixture 与游标分页 |
| Python | `test_worker_engine.py`（+1） | 剩余预算传入 capability |
| 前端 | `EvaluationReportPanel.spec.ts`（+2） | 置信度与低置信标记、问题时间线去重 |
| 前端 | `TrainingRecordReportPage.spec.ts`（3） | 409 轮询、403、404 |

## 5. 验证结果

| 项 | 命令 | 结果 |
|---|---|---|
| Java M4 | `gradle test --tests "com.shijiangtai.m4eval.*"` | **124 passed / 0 failed** |
| Python M4 | `.venv/Scripts/python -m pytest tests/evaluation` | **68 passed** |
| 前端报告页 | `npx vitest run src/views/report` | **21 passed** |
| 前端全量 | `npx vitest run` | **22 files / 164 tests passed** |
| 前端构建 | `npm run build` | 通过（仅既有 >500 kB chunk 警告） |

## 6. M4-T01—T11 状态

| 项 | 状态 | 说明 |
|---|---|---|
| M4-T01 触发 / 幂等 | ◐ | 单元覆盖；无 PostgreSQL 唯一键 IT |
| M4-T02 输入快照 / flags / 分页 | ◐ | Java 应用层 + Python 分页 / 契约 fixture；无实库 IT |
| M4-T03 队列 / 回调 / attempt | ◐ | Redis 载荷 + worker 回调 / 消费单测；无 Redis IT |
| M4-T04 评分 / 权重 / 精度 | ✅ | 领域 UT + 应用层合成 |
| M4-T05 partial / 零权重 / 无总分 | ✅ | Java / Python 零权重语义、应用层、报告页 |
| M4-T06 重试 / 退避 / 死信 / reclaim | ◐ | 调度器单测；无真实 worker 崩溃并发 IT |
| M4-T07 LLM 异常 / 建议 fallback | ◐ | ProviderError / 非法 JSON / 规则回退 / 建议 fallback；无真实 429 / 超时注入 |
| M4-T08 音频特征 / 关键帧 | ◐ | WAV 静音统计单测；webm→ffmpeg 解码未实现 |
| M4-T09 报告权限 | ◐ | 权限策略单测；无安全 IT |
| M4-T10 性能 | ◐ | 3 并发 mock <90 s 与 token 预算回归；真实 Provider 未测 |
| M4-T11 终态事件 / 对账 | ◐ | 事件发布、对账器、失败终态单测；无事件总线 IT |

## 7. 未完成 / 阻塞

1. **关键帧视觉 LLM**：当前只把 OCR 文本与关键帧元信息交给文本 LLM；`MediaCapability.select_keyframes` 未被调用，帧分页也不返回预签名 URL。需要 Provider 视觉入口与 M3 帧预签名契约，属共享入口 / 跨模块变更。
2. **音频 webm 解码**：`analyze_wav` 只接受 PCM WAV；正常录像为 `audio.webm`。需要 ffmpeg 与 `ai/Dockerfile` 运行环境变更（不在 M4 允许路径）。
3. **长参考材料检索**：参考材料目前整段截断到 20,000 字，未实现 2,000 字分块 / top-8 检索；面试答案要点仍整体传入，未逐题对照。
4. **worker 投递上限**：`max_deliveries` / `dead_stream` 配置未使用；重试与死信仍由 Java 调度器负责。
5. **Java 回调时间边界**：Python 已按 session 时长 clamp；Java 未按 `session` 时长再次 clamp（需要把 `EvaluationInputQuery` 注入回调服务）。
6. **训练配置版本**：`m6_training` 表暂无训练配置版本列，M4 读数为 0；需 M6 Owner 提供。
7. **任务报告保留期清理**：仅训练记录在报告读取时校验过期；课程结束 + 1 年的任务报告清理未实现。
8. **环境项**：PostgreSQL / Redis / MinIO IT、真实 Provider smoke、浏览器 E2E、真实模型性能均未执行；`e2e/**` 属 Score/QA Owner。
