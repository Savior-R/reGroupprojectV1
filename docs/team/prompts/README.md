# 各模块 Owner 启动提示词

> 日期：2026-11-07　基线：`main` / `e9da2f8`
> 用途：给各模块 Owner（或代其执行的实现 Agent）一份统一的启动上下文；先复制 `template.md`，按本表替换占位符。
> 约束：所有模块从 `main` 新建分支，不得在 M3 的 `feat/m3a-task-config` 或其他进行中分支上继续开发。

## 1. 使用方式

1. 复制 `docs/team/prompts/template.md` 为 `docs/team/prompts/<序号>-<owner>-<module>.md`。
2. 按 §2 的 Owner 表替换 `<...>` 占位符。
3. 把生成后的提示词交给对应 Owner / 实现 Agent；提示词中的“必读文档”和“测试门禁”不得省略。
4. 任何跨模块修改仍需按 `docs/team/01-模块归属与修改规则.md` 单独确认；提示词不构成跨模块授权。

## 2. Owner 与模块表

| # | Owner | 模块 / 分支建议 | 必读需求 / 设计 / 实现 | 主要允许路径 | 上游依赖 | 当前状态 / 启动前置 |
|---|---|---|---|---|---|---|
| 1 | Foundation | M1 账号 / 组织 / 认证 / `feat/m1-auth` | `requirements/M1-账号管理与身份认证.md`；`design/03`；`implementation/01-M1-实现设计.md`；`design/02`、`01`、`12` | `server/src/main/java/com/shijiangtai/m1auth/**`、`server/src/test/java/com/shijiangtai/m1auth/**`、`V1*` / 新增 `V13*__m1_*.sql`、`frontend/src/views/auth/**` | 无（M1 是基础） | 设计待 G2 复审；2026-11-07 已扩展邮箱验证码 / 微信扫码文档；先冻结认证契约，再实现 |
| 2 | Course/Question | M2 课程 + M5 题库 / `feat/m2-m5` | `requirements/M2-课程操作与管理.md`、`requirements/M5-结构化面试题库.md`；`design/03`、`04`；`implementation/02-M2-实现设计.md`、`03-M5-实现设计.md` | `server/src/main/java/com/shijiangtai/m2course/**`、`m5question/**`、`V2*` / `V3*`、`frontend/src/views/course/**`、`views/question/**` | M1 账号 / 学校 / 专业 / 班级 | 依赖 M1 契约；M2 删除 / 归档、M5 种子题库契约先确认 |
| 3 | AI | M4 评测 / `feat/m4-eval` | `requirements/M4-AI评测与反馈.md`；`design/06`；`implementation/05-M4-实现设计.md`、`08-Python-AI-实现设计.md` | `server/src/main/java/com/shijiangtai/m4eval/**`、`ai/app/capabilities/evaluation.py`、`media.py`、`ai/app/worker/**`、`prompts/evaluation/**`、`V5*` | M1 / M2 / M3 提交契约、M3C 录像 | M3 提交契约已具备；M4 `EvaluationInputManifest` / 回调契约先冻结；M1/M2 未完成时用文档端口 |
| 4 | Media/Training | M6 训练 / `feat/m6-training` | `requirements/M6-训练模式.md`；`design/08`；`implementation/07-M6-实现设计.md` | `server/src/main/java/com/shijiangtai/m6training/**`、`V7*`、`frontend/src/views/training/**` | M3 训练 session、M4 训练报告；M3C 训练保留 1 个月 | M3/M4 契约未冻结前只做模板 / 只读快照；不得改 M3 |
| 5 | Score/QA | M7 统计 + E2E / `feat/m7-e2e` | `requirements/M7-教师评测与数据统计.md`；`design/07`、`09`、`10`；`implementation/06-M7-实现设计.md` | `server/src/main/java/com/shijiangtai/m7teacher/**`、`V6*`、`frontend/src/views/grading/**`、`views/stats/**`、`e2e/**` | M4 `EvaluationTerminalEvent`、M3 提交件 | E2E 骨架可先搭；M7 最终分 / 统计依赖 M4；跨模块 E2E fixture 由各 Owner 提供 |
| 6 | Architecture / Foundation | G2 设计冻结（非业务模块） | `design/11-概要设计评审.md`、`design/00`、`02`、`12`、`12`、`13`、`14`、`15` | 仅评审 / 契约文档，按评审结论由各 Owner 改实现 | 各模块设计 | 先关闭 02 / 03 / 10 / 12 的契约缺口；冻结后才允许各模块 G3 |

## 3. 已生成提示词

| Owner | 文件 |
|---|---|
| Foundation / M1 | [`01-foundation-m1.md`](01-foundation-m1.md) |
| Course/Question / M2 + M5 | [`02-course-m2-m5.md`](02-course-m2-m5.md) |
| AI / M4 | [`03-ai-m4.md`](03-ai-m4.md) |
| Media/Training / M6 | [`04-media-m6.md`](04-media-m6.md) |
| Score/QA / M7 + E2E | [`05-score-m7-e2e.md`](05-score-m7-e2e.md) |
| Architecture / Foundation / G2 | [`06-architecture-g2.md`](06-architecture-g2.md) |

## 4. 各 Owner 的启动前置（按顺序）

1. **G2 设计复审**：Architecture/Foundation 主持；M1 新增邮箱 / 微信登录、M2/M5、M3 提交契约、M4 manifest / 回调、M6 训练、M7 终态事件逐项确认。
2. **Foundation / M1**：实现邮箱验证码、微信扫码、OAuth 引导注册；先落 `V13__m1_login_methods.sql` 与认证契约；再补组织 / 建校。
3. **Course/Question / M2+M5**：在 M1 账号 / 学校 / 专业 / 班级可用后开发；M5 种子题库与 M2 课程 / 成员契约同步冻结。
4. **AI / M4**：使用 M3 已完成的提交件 / 录像契约和 `EvaluationInputManifest`；Java worker 与 Python capability 分层。
5. **Media/Training / M6**：在 M3 训练 session 与 M4 训练报告可用后开发；训练保留 1 个月由 M6 负责。
6. **Score/QA / M7+E2E**：先搭 Playwright / 测试骨架与种子数据；`EvaluationTerminalEvent` 冻结后实现评分 / 最终分 / 统计 / CSV。

## 5. 通用禁止项

- 不得改其他 Owner 的模块、迁移、测试或 owner 表。
- 不得在 main 直接开发；不得 force push；不得把 secrets / 真实 key 写入仓库。
- 不得用“测试失败 / 只改一行 / 只改注释”为由跳过跨模块确认。
- 不得伪造测试结果；测试失败必须报告命令与输出。
- 不得把本次 M1 文档授权推广到 M2—M7 的实现授权。

## 6. 生成后的检查

每份提示词发送前确认：

- [ ] Owner / 模块 / 分支 / 允许路径已填写；
- [ ] 必读文档路径存在且版本日期正确；
- [ ] 上游依赖与未冻结契约已列明；
- [ ] 测试命令与证据路径已填写；
- [ ] 跨模块确认规则保留；
- [ ] 没有把其他模块的文件列入允许编辑范围。
