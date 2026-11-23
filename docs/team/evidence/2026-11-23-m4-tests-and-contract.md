# 2026-11-23 M4 评测测试补齐与契约对齐

> 分支：`feat/m4-eval`（基于 `main`）
> 依据：`docs/team/prompts/03-ai-m4.md`、`docs/design/06-M4-AI评测与反馈.md`、`docs/design/10-测试与验收.md` §6.7
> 执行人：AI Owner（当前使用者）
> 环境：Windows 11 + Git Bash；JDK 25.0.1 + Gradle 9.5.0（临时 init script 覆盖 toolchain 21，未改 `build.gradle.kts`）；Python 3.13.15（`ai/.venv`）；Node 24 / npm 11；**无 Docker、无 PostgreSQL / Redis / MinIO 运行中**。
> 结论：补齐 M4 Java 应用层、Python worker、前端报告页测试，并修复四处契约偏差。M4 Java **100 条**、Python **53 条**、前端报告页 **16 条**（全量 159 条）通过；前端生产构建通过。**PostgreSQL / Redis / MinIO IT、真实 Provider、真实浏览器 E2E 与真实模型性能仍未执行，本文不声称全链路验收通过。**

## 1. 分支与提交

| 提交 | 内容 |
|---|---|
| `9fe0c1a` | `test(m4)` 应用层编排、回调、重试、权限用例；失败终态保留 `partial_reasons` |
| `a2fcf4c` | `test(m4-ai)` worker 输入 / 回调 / 异常映射 / 3 并发 mock 预算 |
| `99f9dee` | `test(m4-web)` 报告页状态、雷达图、文案与定位跳转 |
| `b74914d` | `fix(m4)` 缺失参考材料 / 面试答案要点的结果校验与置信度契约 |
| `656ee6b` | `fix(m4)` 输入清单严格按快照录像 ID 过滤，补充 manifest / 分页用例 |
| `70f23f0` | `test(m4-api)` 回调 DTO、服务令牌、错误码映射契约用例 |

## 2. 新增覆盖

### 2.1 Java（`server/src/test/java/com/shijiangtai/m4eval/application/**`）

新增内存端口替身 `M4TestDoubles`（仓储、队列、事件、时钟、ID、输入查询），覆盖：

| 测试类 | 覆盖点 | 对应测试项 |
|---|---|---|
| `EvaluationApplicationServiceTest` | 提交 / 训练触发、`(source_type, source_id)` 幂等、快照 ID 与 flags、配置版本、触发后投递、投递失败保留 queued、对账入口 | M4-T01 / T02 / T11 |
| `EvaluationCallbackServiceTest` | start / result / fail、attempt 旧值忽略、新值 409、终态拒绝、总分合成与一位小数、partial 无总分、空结果 failed、非法结果不落库、建议排序、失败原因保留 | M4-T03 / T04 / T05 / T11 |
| `EvaluationJobSchedulerTest` | 15 / 60 / 180 秒退避、重试上限、死信、已完成维度保留 partial、worker 10 分钟 reclaim、终态收敛、投递异常 | M4-T06 |
| `EvaluationAccessPolicyTest` | 学生本人、课程教师组、非本组教师、管理员 403、跨校、训练仅本人、无课程跳过学校校验 | M4-T09 |
| `EvaluationReportQueryServiceTest` | completed 报告 / 雷达、partial 缺失维度与无总分、pending 409、failed 422、建议 3—5 与排序、issues 排序、训练过期 / 删除 404、无权限 | M4-T05 / T09 |
| `EvaluationResultValidatorTest` | 缺失 accuracy 可接受、空子项与未知子项拒绝 | M4-T04 / T05 |
| `EvaluationInputServiceTest` | manifest 配置 / counts / flags、仅快照录像、迟到上传不进入、未完成流排除、分页上限 | M4-T02 |
| `InternalEvaluationControllerTest` | start / result / fail DTO 转换、Idempotency-Key、缺字段拒绝 | M4-T03 / G6 |
| `M4ServiceTokenFilterTest` | 服务令牌 fail-closed、空配置拒绝 | M4-T03 / T09 |
| `M4ExceptionHandlerTest` | 404 / 409 / 422 / 502 / 503 映射与 trace_id | G6 |

### 2.2 Python（`ai/tests/evaluation/**`）

| 文件 | 覆盖点 | 对应测试项 |
|---|---|---|
| `test_worker_input_client.py` | manifest、文本 / 帧 / 事件分页游标、上限、服务令牌、非 200 / 非 JSON / 非对象 / 网络异常 | M4-T02 / T03 |
| `test_worker_callback.py` | start / result / fail 载荷、`Idempotency-Key`、409 放弃、4xx / 5xx / 网络失败、错误截断 | M4-T03 |
| `test_worker_engine.py`（扩展） | manifest 不匹配、ProviderError、未知异常、维度超时映射 | M4-T07 |
| `test_worker_concurrency.py` | 3 个 mock 评测并发完成于 90 s 内、返回维度且不含总分、token 预算回归 | M4-T10（mock 口径） |
| `test_evaluation_capability.py`（扩展） | 参考材料缺失删除 accuracy、降低置信度、不 partial；面试缺答案要点 → partial | M4-T05 / T07 |

### 2.3 前端（`frontend/src/views/report/**`）

| 文件 | 覆盖点 |
|---|---|
| `EvaluationReportPanel.spec.ts` | completed 渲染、partial 无总分与缺失维度、failed、pending 3 s 轮询、403 / 404 / 重试、建议回看时间点 |
| `RadarChart.spec.ts` | 缺失维度标「未提供」、全缺失空态、少于三个轴不绘制、越界值钳制 |
| `labels.spec.ts` | 状态 / 维度 / 原因 / 子项文案、时间与日期格式化 |

## 3. 修复的契约偏差

1. **失败终态丢失 `partial_reasons`**：`Evaluation.fail` 原先只写 `error_code`，fail 回调与死信原因被丢弃。新增带 reasons 的重载，fail 回调、空结果与死信均保留原因，报告页与 M7 可读取。
2. **Java 校验强制全部子项**：`06` §6.1 / §6.9 规定参考材料缺失时 `accuracy` 置空、其余子项计分；原校验会拒绝整份结果。现允许缺失子项，但拒绝空子项与未知子项。
3. **Python 参考材料 / 答案要点**：参考材料缺失时删除 `accuracy` 并将内容维度置信度上限设为 0.75；面试缺答案要点时内容维度缺失并返回 `ANSWER_POINTS_MISSING`，按规则进入 partial。
4. **输入清单可复现性**：`recordings()` 原先在快照录像 ID 为空时回退到实时全部流，迟到上传会改变 manifest；现严格只取快照内 ID。

## 4. 验证结果

| 项 | 命令 | 结果 |
|---|---|---|
| Java M4 | `gradle test --tests "com.shijiangtai.m4eval.*"` | **100 passed / 0 failed** |
| Java 全量 | `gradle test` | 947 tests，255 failed：均为其他模块 Spring 上下文 / 外部依赖不可用（无 PostgreSQL / Redis / MinIO）；**M4 100 条全部通过** |
| Python M4 | `.venv/Scripts/python -m pytest tests/evaluation` | **53 passed** |
| 前端报告页 | `npx vitest run src/views/report` | **16 passed** |
| 前端全量 | `npx vitest run` | **21 files / 159 tests passed** |
| 前端构建 | `npm run build` | 通过（仅既有 >500 kB chunk 警告） |

## 5. M4-T01—T11 状态

| 项 | 状态 | 说明 |
|---|---|---|
| M4-T01 触发 / 幂等 | ◐ | 单元覆盖；无 PostgreSQL 唯一键 IT |
| M4-T02 输入快照 / flags / 分页 | ◐ | Java 应用层 + Python 分页游标单测；无实库 IT |
| M4-T03 队列 / 回调 / attempt | ◐ | 应用层 + worker 回调 / 消费单测；无 Redis IT |
| M4-T04 评分 / 权重 / 精度 | ✅ | 领域 UT + 应用层合成 |
| M4-T05 partial / 零权重 / 无总分 | ✅ | 领域、应用层、Python capability、报告页 |
| M4-T06 重试 / 退避 / 死信 / reclaim | ◐ | 调度器单测；无真实 worker 崩溃并发 IT |
| M4-T07 LLM 异常 / 建议 fallback | ◐ | ProviderError / 未知异常 / 非法输出分支；无真实 429 / 超时注入 |
| M4-T08 音频特征 / 关键帧 | ✅ | `test_media.py` 既有 |
| M4-T09 报告权限 | ◐ | 权限策略单测；无安全 IT |
| M4-T10 性能 | ◐ | 3 并发 mock <90 s 与 token 预算回归；真实 Provider 性能未测 |
| M4-T11 终态事件 / 对账 | ◐ | 事件发布 / 失败终态 / 对账单测；无事件总线 IT |

## 6. 未完成 / 阻塞

1. PostgreSQL / Redis / MinIO 集成测试：本机无 Docker，且新增 Testcontainers 依赖需跨模块修改 `build.gradle.kts`，未执行。
2. 真实 Provider smoke：`DEEPSEEK_API_KEY` 未配置，未调用真实模型；mock 结果不作为模型质量证据。
3. 真实浏览器 E2E：`e2e/**` 属 Score/QA Owner，未修改；本轮只做 Vitest 组件测试。
4. 真实性能：T10 仅 mock 口径，真实模型 3 并发 <90 s 与 token 预算待 staging 验证。
5. 训练配置版本：`m6_training` 表暂无训练配置版本列，M4 读数为 0；需 M6 Owner 在快照中提供版本后再对齐。
