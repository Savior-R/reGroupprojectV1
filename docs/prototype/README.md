# 三尺讲台 · 静态网站原型 V1.5

> 日期：2026-11-17　状态：设计提案，待评审
> 设计契约：[仓库根目录 DESIGN.md](../../DESIGN.md)
> 范围：普通页面（讲义）+ 课堂 / 面试 / 回看（舞台）+ 登录 / 注册 / 管理端
> 说明：所有数据为演示数据；不接后端、不发请求、不修改 `frontend/` 代码。

## 1. 这是什么

这是一套**可点击的静态 HTML 原型**，用于在进入 Vue / Element Plus 实现前，先确认：

- 视觉方向：米白纸感 + 墨绿主色 + 朱砂批注，普通页浅色、沉浸页深色；
- 信息编排：顶部导航 + 课程上下文条；列表、表单、报告、课堂各自的主行动；
- 关键流程：学生任务 → 课前检查 → 课堂 / 面试 → 回看提交 → AI 报告；教师课程 → 任务编辑 → 评分 → 统计；管理员组织 → 教师权限。

原型不是最终视觉稿：它把**结构、层级、状态和交互**先落成可讨论的页面，方便逐页调整后再映射回 Vue。

## 2. 如何查看

推荐用静态服务器（`file://` 直接打开也能看，但少数浏览器对本地资源更严格）：

```bash
# 仓库根目录
python3 -m http.server 4173 --directory docs/prototype
# 打开 http://localhost:4173/
```

入口：

- `index.html` — 设计命题、改了什么、页面地图、迭代方式；
- `design-system.html` — 颜色 / 字体 / 间距 / 按钮 / 表单 / 状态 / 抽屉 / 舞台组件；
- 任意页面右下角「原型地图」可跳转全部页面，按 `?` 打开 / 关闭。

> 顶部导航、账号菜单与原型地图由本地 `assets/site.js` 注入；请启用 JavaScript。内容与页面本身不依赖网络。

## 3. 页面清单

| # | 文件 | 角色 | 对应现有路由 / 页面 | 说明 |
|---|---|---|---|---|
| 00 | `index.html` | 公共 | — | 原型说明与页面地图 |
| 01 | `design-system.html` | 公共 | — | 设计系统与组件状态 |
| 02 | `login.html` | 公开 | `/login` · `LoginView.vue` | 密码 / 邮箱 / 微信三 tab；品牌封面 |
| 03 | `register.html` | 公开 | `/register` · `RegisterView.vue` | 两步注册合并为分节表单；账号类型切换 |
| 04 | `security.html` | 登录用户 | `/settings/security` · `SecurityView.vue` | 账号信息、邮箱 / 微信绑定、登录记录 |
| 05 | `errors.html` | 公共 | `/403`、`/404` | 错误页与状态示例 |
| 06 | `student-home.html` | 学生 | **提案**（当前 `/student` 重定向任务） | 学生工作台：下一件事、本周节奏、能力画像 |
| 07 | `courses.html` | 学生 / 教师 / 管理员 | `/courses` · `CourseListPage.vue` | 同一路由三种视图，页内可切换角色 |
| 08 | `course-detail.html` | 学生 / 教师 | `/courses/:courseId` · `CourseDetailPage.vue` | 课程详情：当前课程任务、老师组、我的成绩；`?role=student / teacher`；页内不提供角色 / 课程切换 |
| 09 | `student-tasks.html` | 学生 | `/student/tasks` · `StudentTasksPage.vue` | 任务卡片、状态筛选、截止提醒 |
| 10 | `student-task-detail.html` | 学生 | `/student/tasks/:taskId` · `StudentTaskDetailPage.vue` | 课前检查：设备、共享窗口、任务说明 |
| 11 | `classroom.html` | 学生 | `/student/sessions/:id/classroom` · `ClassroomPage.vue` | 深色课堂舞台：共享画面、虚拟学生、对话、控制坞 |
| 12 | `interview.html` | 学生 | `/student/sessions/:id/interview` · `InterviewPage.vue` | 深色面试舞台：摄像头、题干、倒计时、转写 |
| 13 | `review.html` | 学生 | `/student/sessions/:id/review` · `ReviewPage.vue` | 回放 + 事件时间线 + 提交 / 重做 |
| 14 | `report.html` | 学生 / 教师 | `/evaluations/:id/report` · `EvaluationReportPanel.vue` | 总分 + 雷达 + 维度 + 建议 + 问题时间线 |
| 15 | `student-results.html` | 学生 | `/student/results` · `StudentResultsPage.vue` | **按课程分类**的成绩总览 + 最近任务成绩 |
| 16 | `student-course-results.html` | 学生 | `/student/results` 课程视图（新增） | 课程成绩详情：任务级 AI / 教师 / 最终分；`?course=chinese / micro / speak` 决定课程，页内不提供课程切换条 |
| 17 | `student-training.html` | 学生 | `/student/training` · `TrainingHomePage.vue` | 进阶 / 模拟考核、教师模板、我的模板（创建 / 复制 / 删除）、自由训练 |
| 18 | `training-config.html` | 学生 | `/student/training/...`（新增） | 训练配置：模板训练 / 自由训练；模板选择窗口；自由训练可引用我的模板带入并自由修改；`?mode=template / free` |
| 18A | `student-template-editor.html` | 学生 | `/student/training/templates/new`（新增） | 学生模板创建 / 编辑：基本信息、训练侧重、虚拟学生、参考材料；支持从教师模板复制 |
| 19 | `teacher-tasks.html` | 教师 | `/teacher/courses/:id/tasks` · `TeacherTaskListPage.vue` | 课程上下文条 + 任务表 + 待办；任务行支持删除 |
| 20 | `task-editor.html` | 教师 | `/teacher/courses/:id/tasks/new` · `TeacherTaskEditorPage.vue` | 布局 B：左侧 1—4 分节，右侧 sticky 摘要 |
| 21 | `grading.html` | 教师 | `/teacher/courses/:id/tasks/:id/grading` · `TaskGradingPage.vue` | 摘要、名单、评分抽屉、批量模式 |
| 22 | `stats.html` | 教师 | `/teacher/courses/:id/tasks/:id/stats` · `TaskStatsPage.vue` | 维度平均分、完成情况、排行榜 |
| 23 | `question-bank.html` | 教师 | `/teacher/question-bank` · `QuestionBankPage.vue` | 类别栏 + 题目列表 + 答案要点预览 |
| 24 | `teacher-training.html` | 教师 | `/teacher/training/templates` · `TeacherTrainingTemplatesPage.vue` | 模板表、额度、发布课程抽屉；模板行支持删除 |
| 25 | `teacher-template-detail.html` | 教师 | `/teacher/training/templates/:id`（新增查看） | 模板查看：配置、权重、虚拟学生、发布记录、关联任务 |
| 26 | `teacher-template-editor.html` | 教师 | `/teacher/training/templates/new`（新增创建 / 编辑） | 模板创建 / 编辑：基本信息、权重、虚拟学生、材料 |
| 27 | `admin-org.html` | 管理员 | `/admin/org` · `OrgView.vue` | 学校信息、专业 / 班级；新增与编辑都在弹窗内完成；导入与错误 |
| 28 | `admin-teachers.html` | 管理员 | `/admin/teachers` · `TeachersView.vue` | 教师专业范围、附加角色、**专业搜索栏 + 可滚动列表** |
| 29 | `teacher-course-create.html` | 教师 | `/courses/new`（新增） | 创建学生课程：基本信息、专业范围、老师组、课程预览 |
| 30 | `question-categories.html` | 教师 | `/teacher/question-bank/categories`（新增） | 题库类别仓库：搜索、增改、停用、分页 |
| 32 | `teacher-course-students.html` | 教师 | `/teacher/courses/:id/students`（新增） | 课程学生管理：名单、班级分布、添加 / 移出学生、学生成绩抽屉 |
| 33 | `admin-course-detail.html` | 管理员 | `/admin/courses/:id`（新增） | 管理员课程详情：概览、学生、老师组、编辑元数据、归档 |
| — | `student-training.html#records` | 学生 | `/student/training/records` · `TrainingRecordsPage.vue` | 在训练页右侧展示最近记录，未单独建页 |

> 说明：原型地图中「课程详情（学生）」与「课程详情（教师）」指向同一 `course-detail.html`，仅以 `?role=` 区分；因此地图条目数比实际文件数多 1。


## 4. 这版做了什么

### 4.1 主题归位

- 普通页面采用 **浅色学术主题 A**：米白纸、墨绿、朱砂、衬线标题；与现有 `frontend/src/style.css` 一致；
- 课堂 / 面试 / 回看采用 **深色舞台**：墨夜底 + 琥珀主按钮 + 录制红；比现有深蓝更贴近品牌；
- 所有颜色只来自 `assets/tokens.css`，页面局部 `<style>` 不写死 hex。

### 4.2 编排升级

- 顶部导航保留（符合 09 §3），新增**课程上下文条**（面包屑 + 课程内二级导航），解决任务 / 评分 / 统计“不知道自己在哪一层”的问题；
- 每个页面有且只有一个主行动：任务详情 → 开始试讲；课堂 → 结束试讲；回看 → 提交；报告 → 定位回放；评分 → 保存；
- 列表页统一“标题区 → 摘要条 → 筛选 → 内容 → 空 / 错 / 加载”的节奏；
- 表单页统一“左侧分节 + 右侧 sticky 摘要 / 发布检查”；
- 沉浸页统一“顶部状态条 + 主舞台 + 右侧信息栏 + 底部控制坞”。

### 4.3 新增 / 提案

- **学生工作台**（`student-home.html`）：当前 `/student` 重定向到任务；原型提案增加工作台。是否落地需产品与 M3/M6/M7 Owner 确认；
- **课程页角色视图**（`courses.html`）：同一 `/courses` 路由，学生卡片、教师工作台、管理员表格三种呈现；可用 `?role=student|teacher|admin` 预选视图，页内切换仅用于原型对比；
- **课程详情**（`course-detail.html`）：学生 / 教师共用同一课程详情页，包含当前课程任务、课程信息、老师组与本人成绩 / 教师待办；`?role=student / teacher`；
- **成绩按课程分类**（`student-results.html` + `student-course-results.html`）：总览按课程聚合，点进课程查看任务级 AI / 教师 / 最终分；课程成绩详情用 `?course=chinese / micro / speak` 切换；
- **训练模板查看 / 创建**（`teacher-template-detail.html` + `teacher-template-editor.html`）：模板列表不再只靠抽屉，提供独立查看、创建 / 编辑与发布记录；
- **训练配置**（`training-config.html`）：模板训练 / 自由训练两种模式、虚拟学生参数、开始前规则确认；`?mode=template / free`；
- **教师创建课程**（`teacher-course-create.html`）：独立的创建学生课程页面，含基本信息、专业范围、老师组、课程预览与创建确认；
- **任务引用模板**（`task-editor.html`）：从训练模板填充改为独立窗口，提供搜索栏、模板列表、选择与分页 / 懒加载提示；任务不再保存草稿，编辑后直接发布；
- **题库类别管理**（`question-categories.html` + `question-bank.html`）：新增类别仓库页面；新增 / 编辑题目时，类别引用改为独立窗口，提供搜索栏、选择列表与分页；
- **分页与懒加载**：可能增长到多页的列表统一使用分页；模板 / 类别引用使用搜索 + 分页列表，保留“滚动加载更多”提示；
- **数据展示**：移除进度条，改用数字、表格、雷达 / 趋势图；训练模板不提供归档；
- **学生模板**（`student-template-editor.html` + `student-training.html`）：学生可创建模板，也可从教师模板复制；「我的模板」展示自己创建与复制的全部模板，可编辑、开始模板训练或引用配置；
- **自由训练引用模板**（`training-config.html`）：训练配置支持模板训练 / 自由训练；自由训练可打开「引用模板填写配置」窗口，选择自己创建或复制的模板，带入课题、档位、时长、训练侧重与虚拟学生，带入后所有字段仍可自由修改；
- **学生模板额度**：创建与复制共用「我的模板」额度，教师模板发布后仍归教师所有；
- **课程详情去掉页内切换**（V1.4）：移除课程详情顶部的角色切换条；学生 / 教师视图由 `?role=student / teacher` 预选，实际应用由登录角色与路由权限决定；
- **课程任务删除**（V1.4 / V1.5 契约口径）：教师课程详情与课程任务列表都提供「删除」；二次确认后软删除，任务从列表隐藏，已提交回放、AI 报告、教师评分与最终分保留只读；
- **训练模板删除**（V1.4 / V1.5 契约口径）：教师模板列表 / 详情、学生「我的模板」、学生模板编辑页都提供删除；已发布教师模板先下架，释放额度，并清理该模板关联的训练记录、录像 / 报告与参考材料；学生副本与关联任务快照不受影响；
- **组织弹窗**（V1.5）：管理员新增专业 / 班级不再使用行内输入，改为独立弹窗；编辑同专业 / 班级也复用同一弹窗，支持修改名称、代码、专业、年级与状态；
- **管理员课程详情**（`admin-course-detail.html`）：只读课程概览、学生名单、老师组与元数据变更；可编辑课程元数据、归档课程；不展示任务、评分、报告与统计入口；
- **教师课程学生管理**（`teacher-course-students.html`）：课程学生名单、班级分布、待处理申请；支持搜索 / 班级 / 状态筛选、添加学生弹窗、移出课程、学生成绩抽屉；
- **课程成绩去掉切换条**（`student-course-results.html`）：课程由 `?course=` 决定；移除页内语文 / 微格 / 口语切换条；
- **管理员专业范围**（`admin-teachers.html` 抽屉）：新增搜索栏、可滚动专业列表、分页 / 懒加载提示、已选计数与清空；停用专业不可选；
- **设计系统页**：把组件状态集中在一页，便于后续拆分为 `tokens.css` / `base.css` / `element-overrides.css`。

## 5. 如何迭代

### 5.1 改全局气质

编辑 `assets/tokens.css`：

- 纸色：`--paper-*` / `--surface-*`；
- 墨色：`--ink-*`；
- 主色 / 强调 / 语义：`--green-*` / `--cinnabar-*` / `--gold-*`；
- 字体：`--font-serif` / `--font-sans` / `--font-mono`；
- 圆角、阴影、间距、动效。

### 5.2 改共享组件

编辑 `assets/site.css`：按钮、badge、表格、表单、卡片、抽屉、舞台、原型地图。改这里会影响所有页面。

### 5.3 改单页编排

每个页面顶部有一个局部 `<style>`，只负责该页的网格、特殊卡片和视觉。优先改局部，不要把单页特例塞进共享层。

### 5.4 改数据 / 文案

页面均为静态 HTML，直接改文本即可。演示数据约定：

- 学生：林小雨 / `student.a01` / 汉语言文学（师范）/ 语文2401；
- 教师：王宁 / `teacher.a1` / 语文教学论；
- 管理员：陈明 / `admin.a` / 教务处；
- 课程：语文教学论、微格教学实训、教师口语；
- 任务：荷塘月色、教师资格证结构化面试、少年闰土、古诗二首。

### 5.5 记录决策

每次调整后，在本文件末尾追加“迭代记录”：日期、改了什么、为什么、影响页面、待确认事项。这样后续映射到 Vue 时不会丢失设计意图。

## 6. 与现有前端 / 文档的差异

1. **09 §5 / §16 与 §1.1 冲突**：09 §1.1 写的是“浅色学术主题 A + 沉浸式深色舞台”，但 §5 / §16 仍是旧的深色 B token；现有 `style.css` 已是浅色 A。原型采用 A，建议 Score/QA Owner 同步修订 09 的 §5 / §16。
2. **顶部导航保留**：没有改成左侧栏，减少路由 / 布局层改动；课程内导航用上下文条解决。
3. **学生工作台是新增提案**：当前 `/student` 直接重定向；原型把它当作工作台入口，路由是否新增需确认。
4. **课程页三角色视图默认不存在**：真实应用由角色 / 路由裁剪；原型的角色切换仅用于对比设计。
5. **部分交互为演示**：批量评分、导入、模板发布、评分保存等按钮以 Toast 表示，不产生真实数据；实际实现需接 API / 权限 / 校验。

## 7. 未覆盖 / 待确认

- [ ] 学生工作台是否作为正式路由；
- [ ] 老师组独立页面（课程详情内已展示成员）；
- [ ] 训练记录列表 / 详情 / 回看选中页面的完整页面（当前在训练页右侧展示最近记录）；
- [ ] 移动端 / 平板（按 09 §13 不做）；
- [ ] 真实图表库（ECharts）与雷达 / 柱状图落地方案；
- [ ] 无障碍审计与键盘全流程走查（原型已有 focus-visible / reduced-motion / 语义标签，但未做正式审计）；
- [ ] 原型中的静态数据与 09 / 02 的 DTO 字段逐项对齐（尤其报告、评分、训练记录）。

## 8. 归属与评审

- 本目录是**设计提案**，不修改 `frontend/` 代码；
- 涉及 M1—M7 的页面视觉与编排，落地前需各模块 Owner 确认；前端共享层（路由 / layouts / stores / services）由 Score/QA Owner 评审；
- 设计契约以根目录 `DESIGN.md` 为准；本文件只记录原型使用与迭代说明；
- 本原型由 2026-11-16 全项目整合授权下创建，所有权与最终设计决策仍归对应模块 Owner。

## 9. 验证记录（2026-11-17）

- 静态服务器：`python3 -m http.server 4173 --directory docs/prototype`，入口返回 200。
- 链接检查：34 个 HTML 页面与 `assets/` 下全部本地引用，缺失 0。
- 结构检查：无重复 `id`；`<button>` 开闭标签数量一致。
- Headless Chrome 截图（1440 × 1200 / 1440 × 900 / 1024 × 900）：覆盖 `index`、`login`、`register`、`student-home`、`courses`、`course-detail`、`student-tasks`、`student-task-detail`、`classroom`、`interview`、`review`、`report`、`grading`、`question-bank`、`question-categories`、`design-system`、`admin-org`、`admin-teachers`、`stats`、`teacher-tasks`、`teacher-course-create`、`teacher-training`、`teacher-template-detail`、`teacher-template-editor`、`student-training`、`training-config`、`student-template-editor`、`student-results`、`student-course-results`、`teacher-course-students`、`admin-course-detail`、`errors`；未发现阻断性布局问题。
- 课程页 `?role=student|teacher|admin` 预选视图：顶部导航、角色切换与内容面板一致（teacher / admin 已截图验证）。
- 临时交互测试（测试文件已删除）：
  - `courses.html`：学生 → 教师视图切换后顶部导航与面板同步；
  - `grading.html`：评分抽屉按行更新姓名 / AI 分；批量模式显示编辑列；
  - `classroom.html`：学生卡片选中、对话 tab 切换、结束确认弹窗；
  - `student-tasks.html`：状态筛选只显示对应任务；
  - `question-bank.html`：点击题目更新右侧预览；
  - `task-editor.html`：权重滑杆联动合计与警告；
  - `register.html`：账号类型切换后右侧摘要同步；
  - `student-results.html`：成绩总览按课程聚合，课程卡片进入课程成绩；
  - `teacher-tasks.html`：已发布筛选显示 5 行，草稿状态已移除；
  - `course-detail.html`：`?role=student / teacher` 预选面板；页内角色切换条已移除；教师任务「删除」二次确认后移除任务卡片并更新页脚；
  - `student-course-results.html`：`?course=` 预选课程后标题、平均分、任务行数同步；页内课程切换条已移除；
  - `training-config.html`：`?mode=free` 预选自由训练；开始卡片标题与时长同步；模板选择窗口可按来源筛选 / 搜索 / 选择；自由训练引用 `mine-2` 后带入课题、档位、时长与虚拟学生，修改课题与时长不会回写原模板；
  - `student-template-editor.html`：`?source=copy` 预填复制来源；`?source=copy&from=teacher-3` 预填模拟考核副本；`?id=mine-1` 进入编辑态；档位与训练侧重变化联动摘要；
  - `student-training.html`：档位切换后教师 / 我的模板计数与可见卡片一致（进阶 2 / 1，模拟考核 1 / 1）；删除模板后卡片、额度与「共 N 个我的模板」同步；
  - `teacher-tasks.html`：删除任务后表格行、任务总数与 tab 计数同步；删除确认文案明确回放 / 报告 / 评分保留只读；
  - `teacher-training.html`：删除模板后行、活跃模板数、额度与页脚同步；删除确认文案明确清理训练记录 / 材料；
  - `teacher-template-detail.html` / `student-template-editor.html`：删除模板确认弹窗打开，编辑态显示删除入口、新建态隐藏；
  - `admin-org.html`：新增专业 / 班级打开弹窗并清空字段；编辑专业 / 班级打开同一弹窗并预填名称、代码、专业、年级与状态；
  - `admin-course-detail.html`：概览 / 学生 / 老师组展示；编辑课程信息与归档课程弹窗打开；页面无任务 / 评分 / 统计入口；
  - `teacher-course-students.html`：搜索 / 班级 / 状态筛选联动；学生详情抽屉按行更新姓名、班级、学号与平均分；添加学生候选搜索与多选计数；移出学生后行、人数与页脚同步；
  - `teacher-template-editor.html`：权重滑杆联动合计与超 100 警告；
  - `teacher-template-detail.html`：发布到课程弹窗打开；
  - `admin-teachers.html`：抽屉打开后专业搜索 “数学” 只显示 1 条，已选计数为 2；清空后为 0；
  - `task-editor.html`：训练模板窗口搜索 “荷塘” 只显示 1 条；选择后回填来源模板；
  - `question-bank.html`：类别选择窗口叠在题目抽屉上，搜索 “教育” 只显示 1 条；选择后回填类别；
  - `teacher-course-create.html`：创建确认弹窗打开；
  - `question-categories.html`：类别搜索 “应急” 只显示 1 行；状态筛选联动；分页控件显示正常。
- 未做：正式无障碍审计、跨浏览器（Safari / Firefox）验证、真实 API / 权限 / 上传 / 评测联调。
- 说明：密集元信息在原型中使用 9—11px 的字号；落地到 Vue 时需按 09 §5.2「中文正文最小 12px」复核，必要时增大行高或减少列。

## 10. 迭代记录

| 日期 | 修改人 | 内容 | 影响页面 |
|---|---|---|---|
| 2026-11-16 | 当前使用者（整合授权） | 建立 V1.0：24 页静态原型、tokens、共享组件、原型地图、设计系统页 | 全部 |
| 2026-11-16 | 当前使用者（整合授权） | V1.1：新增课程详情、课程成绩详情、训练配置、模板查看 / 创建；成绩按课程分类；管理员专业范围增加搜索与列表 | 课程 / 成绩 / 训练 / 模板 / 管理员 |
| 2026-11-16 | 当前使用者（整合授权） | V1.2：补全教师创建课程；任务模板引用改为搜索窗口；删除草稿；移除数据进度条；题库类别管理 + 独立类别选择窗口；列表分页 / 懒加载；训练模板去掉归档 | 课程 / 任务 / 题库 / 训练 / 管理员 |
| 2026-11-17 | 当前使用者（整合授权） | V1.3：新增学生模板创建 / 编辑；学生可从教师模板复制；训练配置增加我的模板选择窗口；自由训练可引用模板带入课题、档位、时长、训练侧重与虚拟学生，带入后自由修改 | 训练 / 我的模板 / 训练配置 |
| 2026-11-17 | 当前使用者（整合授权） | V1.4：课程详情移除页内角色 / 课程切换；教师课程详情与任务列表增加删除任务；教师模板列表 / 详情、学生模板卡片 / 编辑页增加删除模板 | 课程详情 / 课程任务 / 训练模板 |
| 2026-11-17 | 当前使用者（整合授权） | V1.5：管理员专业 / 班级新增与编辑改为弹窗；新增管理员课程详情、教师课程学生管理；移除学生课程成绩页内课程切换条 | 管理员组织 / 课程 / 学生管理 / 成绩 |
| 2026-11-18 | 当前使用者（整合授权） | V1.5 契约口径：任务软删除保留回放 / 报告 / 评分；模板删除软删除、自动下架、释放额度并清理训练记录 / 材料；学生副本与任务快照保留 | 课程任务 / 训练模板 |
