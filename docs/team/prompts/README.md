# 各 Owner 并行开发提示词

> 日期：2026-11-07　基线：`main`（开始前 `git fetch origin`，以最新 main 为准）
> 用途：给各模块 Owner（或代其执行的实现 Agent）一份并行开发上下文。
> 人员分配：M3 已完成；**M5 + M6 合并给同一人**；其余模块分给不同成员；完整测试后置，先交付可运行实现 + smoke。

## 1. 使用方式

1. 每个成员直接使用 §3 对应提示词，不要复制模板再手工替换。
2. 提示词中的“必读文档”“允许路径”“禁止路径”和跨模块规则不得省略。
3. 各模块从 `main` 新建分支并行开发；不得在 M3 的 `feat/m3a-task-config` 或其他进行中分支上继续。
4. 任何跨模块修改仍需按 `docs/team/01-模块归属与修改规则.md` 单独确认；提示词不构成跨模块授权。

## 2. Owner 与模块表

| # | Owner | 模块 / 分支建议 | 必读文档 | 主要允许路径 | 并行依赖 |
|---|---|---|---|---|---|
| 1 | Foundation | M1 / `feat/m1-auth` | `requirements/M1`；`design/03`、`02`、`01`、`12`；`implementation/01` | `m1auth/**`、`V1*` / `V13*__m1_*.sql`、`frontend/src/views/auth/**`、`views/admin/**` | 无；可立即开始 |
| 2 | Course | M2 / `feat/m2-course` | `requirements/M2`；`design/03`；`implementation/02` | `m2course/**`、`V2*`、`frontend/src/views/course/**` | 按 M1 契约 + fake 并行，不等 M1 完成 |
| 3 | AI | M4 / `feat/m4-eval` | `requirements/M4`；`design/06`；`implementation/05`、`08` | `m4eval/**`、`ai/app/capabilities/evaluation.py`、`media.py`、`worker/**`、`V5*`、`frontend/src/views/report/**` | M3 提交契约已具备；M4 manifest / callback 用文档契约并行 |
| 4 | Media/Training | **M5 + M6** / `feat/m5-m6` | `requirements/M5`、`requirements/M6`；`design/04`、`08`；`implementation/03`、`07` | `m5question/**`、`m6training/**`、`V3*`、`V7*`、`frontend/src/views/question/**`、`views/training/**` | M5 用 M1 契约；M6 用 M3 训练 session / M4 报告契约 + fake，并行 |
| 5 | Score | M7 / `feat/m7-score` | `requirements/M7`；`design/07`、`09`；`implementation/06` | `m7teacher/**`、`V6*`、`frontend/src/views/grading/**`、`views/stats/**` | M4 terminal / M3 提交契约 + fixture，并行 |
| 6 | QA | E2E / `feat/e2e` | `design/10`、`09`、M1—M7 需求 / 设计只读 | `e2e/**` | 可先搭骨架；随模块可用逐步启用 E2E-01—06 |
| 7 | Architecture / Foundation | G2 设计冻结 / 无业务分支 | `design/11`、`00`、`02`、`01`、`12`、`13`、`14`、`15` | 仅评审 / 契约文档 | 与 1—6 并行；不阻塞编码，但契约变更需其确认 |

> 所有 Owner 另需读：`docs/prototype/README.md` V1.5、根目录 `DESIGN.md`、`docs/team/02-进度看板.md` §9（本模块前端原型任务）；详见 §7。

## 3. 已生成提示词

| Owner | 文件 |
|---|---|
| Foundation / M1 | [`01-foundation-m1.md`](01-foundation-m1.md) |
| Course / M2 | [`02-course-m2.md`](02-course-m2.md) |
| AI / M4 | [`03-ai-m4.md`](03-ai-m4.md) |
| Media/Training / M5 + M6 | [`04-media-m5-m6.md`](04-media-m5-m6.md) |
| Score / M7 | [`05-score-m7.md`](05-score-m7.md) |
| QA / E2E | [`06-e2e.md`](06-e2e.md) |
| Architecture / Foundation / G2 | [`07-architecture-g2.md`](07-architecture-g2.md) |

## 4. 并行开发约定

- **契约优先**：API / DTO / 错误码 / WS 事件 / 表 / 外键 / 集成事件 / 时间单位 / 权限码以最新文档为准；发现文档冲突时停下记录，向 Architecture / 对方 Owner 确认，不得自行改契约。
- **不等待上游实现**：上游未完成时用文档端口 / fake / fixture / seed 开发；不得把 stub 假设写进正式接口。
- **迁移独立**：M1 `V13*`、M2 `V2*`、M5 `V3*`、M4 `V5*`、M7 `V6*`、M6 `V7*`；不得修改他人迁移；版本冲突先找 Architecture。
- **模块边界**：不得改他人模块代码 / 测试 / owner 表；跨模块缺陷记录场景交对应 Owner。
- **测试后置**：本阶段先交付可运行实现 + 受影响路径 smoke + 编译；完整 UT / IT / 契约 / E2E 由各 Owner / QA 在后续测试阶段补齐。不得把“测试后置”当成“可以不做测试”或伪造结果。
- **E2E 独立**：QA 维护 `e2e/**`；可先搭 Playwright 骨架、fixture、seed，随模块可用逐步启用 E2E-01—06，不阻塞业务模块。
- **G2 并行**：G2 评审与模块编码并行；G2 通过不是开始编码的硬门禁，但**契约变更**必须确认。各模块不得把未冻结的跨模块假设写进正式实现。
- **合并**：各模块独立分支、独立提交；默认不合并 / 不推送，等用户选择。
- **提交信息**：必须按 §7.3 详细模板（范围 / 变更 / 测试 / 证据 / 跨模块 / 待办风险）；禁止只写 `[Mx] 更新`。

## 5. 通用禁止项

- 不得改其他 Owner 的模块、迁移、测试或 owner 表。
- 不得在 main 直接开发；不得 force push；不得把 secrets / 真实 key 写入仓库。
- 不得用“测试失败 / 只改一行 / 只改注释”为由跳过跨模块确认。
- 不得伪造测试结果；测试失败必须报告命令与输出。
- 不得把本次 M1 文档授权推广到 M2—M7 的实现授权。

## 6. 发送前检查

- [ ] Owner / 模块 / 分支 / 允许路径已填写；
- [ ] 必读文档路径存在且版本日期正确；
- [ ] 并行依赖与未冻结契约已列明；
- [ ] 编译 / smoke 命令与证据路径已填写；
- [ ] 跨模块确认规则保留；
- [ ] 没有把其他模块的文件列入允许编辑范围；
- [ ] 已认领本模块前端原型任务（见 §7 / 进度看板 §9）；
- [ ] 提交信息按 §7.3 详细模板编写。

## 7. 前端原型落地与详细提交（2026-11-25）

### 7.1 必读

所有 Owner / 实现 Agent 在开始前端工作前必须读：

1. `docs/prototype/README.md` V1.5：页面清单、对应路由 / 页面、新增 / 提案、差异说明；
2. 根目录 `DESIGN.md` 与 `docs/prototype/assets/tokens.css`：颜色、字体、间距、组件、舞台；
3. `docs/design/09-前端设计.md`：路由、布局、组件、页面模板；
4. `docs/team/02-进度看板.md` **§9 全模块前端原型落地任务**：本模块任务 ID / 状态 / 验收。

### 7.2 各 Owner 的前端任务

| Owner | 任务 ID | 重点 |
|---|---|---|
| Foundation / M1 | M1-F-01—04 | 登录 / 注册 / 安全 / 组织 / 教师管理 / 错误页 |
| Course / M2 | M2-F-01—05 | 课程三角色视图、创建课程独立页、课程学生、管理员课程详情 |
| AI / M4 | M4-F-01—03 | 报告页、雷达、问题时间线、状态 |
| Media/Training / M5+M6 | M5-F-01—03、M6-F-01—05 | 题库类别页、训练首页 / 配置 / 学生 / 教师模板 |
| Score / M7 | M7-F-01—05 | 评分、统计、学生成绩按课程分类、课程成绩详情、回放入口 |
| QA / E2E | FD-05—06 + 全模块 E2E | 无障碍、原型对照表、逐页 E2E |
| Architecture | 评审 / 契约 | 原型与 09 / 02 / DTO 冲突裁决；不直接实现业务页 |
| M3 Owner（参考） | M3A-F / M3B-F / M3C-F | 任务配置、课堂 / 面试、回看；M3B 细则见看板 §8.3 |

- 原型是设计提案：落地前由模块 Owner + Score/QA 确认；共享路由 / layouts / stores / services 仍归 Score/QA。
- 不得用静态原型数据替换真实 API；不得把原型演示交互当成已接后端。
- 任务状态变化只改本模块行 / 本模块任务，不修改他人行的 Gate / 百分比。

### 7.3 详细提交信息（强制）

提交必须让 reviewer 不打开 diff 也能理解范围。禁止只写 `[Mx] 更新`。推荐格式：

```text
[Mx] <动词> <对象>

范围：
- 模块 / 页面 / 接口 / 表 / 契约

变更：
- 关键实现点 1
- 关键实现点 2

测试：
- 命令：
- 结果：classes / tests / failures / skipped
- 未运行及原因：

证据：
- docs/team/evidence/<file>.md
- 或 CI / Playwright / 手工验收链接

跨模块：
- 无
- 或：已获 <Owner> 确认，影响 <API/DTO/事件/表/前端共享层>

待办 / 风险：
- 未完成项 / 已知限制
```

- 一个提交只做一件事；不要把无关重构、格式化、他人文件混入。
- 使用 `git commit --only <显式路径>`；提交前 `git diff --check`。
- 未跑全量测试必须写“测试后置”与原因，不得伪造结果。
