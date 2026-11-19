# 2026-11-16 全站 UI 重构静态原型 V1.5

> 版本：V1.5（2026-11-17 迭代；文件沿用首次证据日期）
> 授权：用户于 2026-11-16 明确作为全项目整合负责人，拥有修改各模块的权利；本轮按用户要求“根据文档和当前前端代码重新设计网站样式和编排，在 docs 里编写静态网站原型，供后续调整迭代”执行。
> 范围：仅 `docs/prototype/**` 与根目录 `DESIGN.md` / 文档索引；**未修改 `frontend/`、`server/`、`ai/` 代码**。所有页面是视觉与交互提案，落地为 Vue 前需各模块 Owner 确认。

## 1. 交付物

- `DESIGN.md`（仓库根目录）：目标设计契约，含 stack、tokens、决策、组件清单、非目标。
- `docs/prototype/`：
  - `README.md`：查看方式、页面清单、与现有路由 / 组件的映射、迭代说明、验证记录、待确认事项。
  - `index.html`：设计命题、改了什么、页面地图、迭代方式。
  - `design-system.html`：颜色 / 字体 / 间距 / 按钮 / 表单 / 状态 / 抽屉 / 舞台组件。
  - 32 个业务页面：登录、注册、账号安全、403/404、学生工作台、课程（学生 / 教师 / 管理员三视图）、课程详情（学生 / 教师 / 管理员）、教师创建课程、学生任务、课前检查、课堂、面试、回看提交、AI 报告、我的成绩（按课程）、课程成绩详情、训练、训练配置、学生模板创建 / 编辑、教师课程任务、任务编辑、任务评分、任务统计、题库、题库类别管理、教师训练模板、模板查看、模板创建 / 编辑、教师课程学生管理、学校组织、教师管理。
  - `assets/tokens.css`：浅色学术主题 + 墨夜舞台双 token。
  - `assets/site.css`：共享组件与响应式规则。
  - `assets/site.js`：图标注入、顶部导航、原型地图、tab、抽屉、弹窗、Toast、角色切换辅助。

## 2. 视觉与编排决策

1. **主题归位**：普通页面用浅色学术主题（米白 / 墨绿 / 朱砂 / 衬线标题），与现有 `frontend/src/style.css` 一致；课堂 / 面试 / 回看用墨夜 + 琥珀舞台。`docs/design/09` §1.1 已写“浅色 A + 深色沉浸舞台”，但 §5 / §16 仍是旧的深色 B token，需 Score/QA Owner 后续同步。
2. **顶部导航 + 课程上下文条**：保留 09 §3 的顶部导航和头像在右；新增课程内面包屑 / 二级导航，解决任务、评分、统计页缺少空间上下文的问题。
3. **一个页面一个主行动**：任务详情 → 开始试讲；课堂 → 结束试讲；回看 → 提交；报告 → 定位回放；评分 → 保存。
4. **学生工作台是提案**：当前 `/student` 重定向到 `/student/tasks`；原型增加工作台入口，是否新增正式路由需产品与 M3/M6/M7 Owner 确认。
5. **课程三角色视图**：同一 `/courses` 路由下，学生卡片、教师工作台、管理员表格；原型页内切换仅用于对比设计。
6. **无外部依赖**：纯 HTML/CSS/JS，内联 SVG 图标，不加载 CDN / 字体 / 图片；静态服务器或 `file://` 均可查看。

## 3. 验证

### 3.1 链接与结构

- 静态服务器：`python3 -m http.server 4173 --directory docs/prototype`，`/index.html` 返回 200。
- 34 个 HTML 的本地 `href` / `src` 检查：缺失 0。
- 重复 `id`：0；`<button>` 开 / 闭数量一致。

### 3.2 Headless Chrome 截图

- 浏览器：Windows Chrome headless（WSL 环境通过 `localhost:4173` 访问静态服务器）。
- 尺寸：1440 × 1200、1440 × 900、1024 × 900。
- 覆盖：`index`、`login`、`register`、`student-home`、`courses`、`course-detail`、`student-tasks`、`student-task-detail`、`classroom`、`interview`、`review`、`report`、`grading`、`question-bank`、`question-categories`、`design-system`、`admin-org`、`admin-teachers`、`stats`、`teacher-tasks`、`teacher-course-create`、`teacher-training`、`teacher-template-detail`、`teacher-template-editor`、`student-training`、`training-config`、`student-template-editor`、`student-results`、`student-course-results`、`teacher-course-students`、`admin-course-detail`、`errors`。
- 结果：未发现阻断性布局问题；修复了页面入场动画在 headless 截图中的透明残影、搜索图标定位、学生工作台票据重叠、课堂侧栏空白等问题。
- 课程页 `?role=teacher` / `?role=admin` 预选视图：顶部导航、角色切换与内容面板一致，已截图验证。

### 3.3 临时交互测试（测试文件已删除）

| 页面 | 验证点 | 结果 |
|---|---|---|
| `courses.html` | 学生 → 教师角色切换后 `body[data-role]`、顶部导航、教师面板同步 | 通过 |
| `grading.html` | 点击不同行后评分抽屉姓名 / AI 分更新；批量模式显示编辑列与批量条 | 通过 |
| `classroom.html` | 学生卡片选中、对话 tab 切换、结束确认弹窗 | 通过 |
| `student-tasks.html` | 状态筛选只显示对应任务 | 通过 |
| `question-bank.html` | 点击题目更新右侧预览题干 / 类别 | 通过 |
| `task-editor.html` | 权重滑杆联动合计、摘要权重与超 100 警告 | 通过 |
| `register.html` | 账号类型切换后右侧注册摘要同步 | 通过 |
| `student-results.html` | 成绩总览按课程聚合，课程卡片进入课程成绩 | 通过 |
| `teacher-tasks.html` | 已发布筛选显示 5 行，草稿状态已移除 | 通过 |
| `course-detail.html` | `?role=student / teacher` 预选面板；页内角色切换条已移除；教师任务删除后卡片与页脚更新 | 通过 |
| `teacher-tasks.html` | 删除任务后表格行、任务总数与 tab 计数同步 | 通过 |
| `teacher-training.html` | 删除模板后行、活跃模板数、额度与页脚同步 | 通过 |
| `student-training.html` | 删除我的模板后卡片、额度与模板计数同步 | 通过 |
| `teacher-template-detail.html` / `student-template-editor.html` | 删除模板确认弹窗打开；编辑态显示删除入口、新建态隐藏 | 通过 |
| `student-course-results.html` | 切换课程后标题、平均分、任务行数同步 | 通过 |
| `training-config.html` | `?mode=free` 预选自由训练；切换模拟考核后开始卡片档位 / 时长同步；模板窗口按来源筛选 / 搜索 / 选择 | 通过 |
| `training-config.html` | 自由训练引用 `mine-2` 后带入课题、档位、时长、训练侧重与虚拟学生；修改课题 / 时长不回写模板 | 通过 |
| `student-template-editor.html` | `?source=copy` 预填复制来源；`?source=copy&from=teacher-3` 预填模拟考核副本；`?id=mine-1` 进入编辑态；档位与训练侧重联动摘要 | 通过 |
| `student-training.html` | 档位切换后教师 / 我的模板计数与可见卡片一致（进阶 2 / 1，模拟考核 1 / 1） | 通过 |
| `teacher-template-editor.html` | 权重滑杆联动合计与超 100 警告 | 通过 |
| `admin-teachers.html` | 抽屉打开；专业搜索 “数学” 只显示 1 条；已选计数 2，清空后 0 | 通过 |
| `teacher-template-detail.html` | 发布到课程弹窗打开 | 通过 |
| `task-editor.html` | 模板选择窗口搜索 “荷塘” 只显示 1 条；选择后回填来源模板 | 通过 |
| `question-bank.html` | 类别选择窗口叠在题目抽屉上；搜索 “教育” 只显示 1 条；选择后回填类别 | 通过 |
| `teacher-course-create.html` | 创建确认弹窗打开 | 通过 |
| `admin-org.html` | 新增专业 / 班级弹窗打开；编辑专业 / 班级复用同一弹窗并预填 | 通过 |
| `admin-course-detail.html` | 概览 / 学生 / 老师组展示；编辑元数据与归档弹窗打开；无任务 / 评分 / 统计入口 | 通过 |
| `teacher-course-students.html` | 搜索 / 班级 / 状态筛选；学生详情抽屉按行更新；添加候选多选计数；移出学生后人数与页脚同步 | 通过 |
| `student-course-results.html` | `?course=` 预选；页内课程切换条已移除 | 通过 |
| `question-categories.html` | 类别搜索 “应急” 只显示 1 行；状态筛选联动；分页控件正常 | 通过 |

### 3.4 未执行

- 正式无障碍审计、键盘全流程走查；
- Safari / Firefox 验证（按 09 §13 录制 / 回放仅 Chrome / Edge）；
- 真实 API / 权限 / 上传 / WebSocket / AI 评测联调；
- 移动端 / 平板适配（按 09 §13 不做）。

## 4. 与当前实现的映射

| 原型 | 现有前端 | 备注 |
|---|---|---|
| `login.html` / `register.html` | `views/auth/LoginView.vue` / `RegisterView.vue` | 三 tab 登录、两步注册 |
| `security.html` | `views/admin/SecurityView.vue` | 邮箱 / 微信绑定、登录记录 |
| `courses.html` | `views/course/CourseListPage.vue` | 需按角色拆视图 |
| `course-detail.html` | `views/course/CourseDetailPage.vue` | 课程详情：当前任务 / 老师组 / 成绩；角色由登录或 `?role=` 决定，不在页内切换 |
| `student-results.html` / `student-course-results.html` | `views/grading/StudentResultsPage.vue` | 按课程聚合 + 课程任务分数详情 |
| `training-config.html` | `views/training/*ConfigPage.vue` | 模板训练 / 自由训练；自由训练可引用我的模板带入配置 |
| `student-template-editor.html` | 无（新增） | 学生模板创建 / 编辑；支持从教师模板复制 |
| `teacher-template-detail.html` / `teacher-template-editor.html` | `views/training/TeacherTrainingTemplatesPage.vue` | 模板查看 / 创建 / 编辑 |
| `admin-teachers.html` 专业抽屉 | `views/admin/TeachersView.vue` | 专业搜索 + 可滚动列表 |
| `teacher-course-create.html` | 无（新增） | 教师创建学生课程 |
| `teacher-course-students.html` | 无（新增） | 教师课程学生管理：名单 / 添加 / 移出 / 成绩抽屉 |
| `admin-course-detail.html` | 无（新增） | 管理员课程详情：概览 / 学生 / 老师组 / 编辑元数据 / 归档 |
| `question-categories.html` | 无（新增） | 题库类别管理仓库 |
| `task-editor.html` 模板窗口 | `views/task/TeacherTaskEditorPage.vue` | 模板搜索 + 列表选择 |
| `question-bank.html` 类别窗口 | `views/question/QuestionBankPage.vue` | 类别搜索 + 列表选择 |
| `student-home.html` | 无（`/student` 重定向） | 新增提案 |
| `student-tasks.html` / `student-task-detail.html` | `views/task/StudentTasksPage.vue` / `StudentTaskDetailPage.vue` | 卡片 / 课前检查 |
| `classroom.html` / `interview.html` | `views/classroom/ClassroomPage.vue` / `InterviewPage.vue` | 深色舞台 |
| `review.html` | `views/replay/ReviewPage.vue` + `ReplayPage.vue` | 回看 + 提交 |
| `report.html` | `views/report/EvaluationReportPanel.vue` | 报告组件 |
| `student-results.html` | `views/grading/StudentResultsPage.vue` | 成绩档案 |
| `student-training.html` | `views/training/TrainingHomePage.vue` | 训练首页 |
| `teacher-tasks.html` / `task-editor.html` | `views/task/TeacherTaskListPage.vue` / `TeacherTaskEditorPage.vue` | 任务列表（含删除）/ 布局 B |
| `grading.html` / `stats.html` | `views/grading/TaskGradingPage.vue` / `views/stats/TaskStatsPage.vue` | 评分 / 统计 |
| `question-bank.html` | `views/question/QuestionBankPage.vue` | 三栏题库 |
| `teacher-training.html` | `views/training/TeacherTrainingTemplatesPage.vue` | 模板管理（含删除） |
| `admin-org.html` / `admin-teachers.html` | `views/admin/OrgView.vue` / `TeachersView.vue` | 组织（专业 / 班级弹窗新增与编辑）/ 教师权限 |
| `errors.html` | `views/errors/ForbiddenPage.vue` / `NotFoundPage.vue` | 状态页 |

## 5. 待确认

1. 浅色学术主题是否作为唯一普通页面主题（现有代码已是，09 §5 待同步）；
2. 学生工作台是否新增正式路由；
3. 课程详情已补；学生管理、老师组、训练记录列表 / 详情是否补齐独立原型；
4. 是否将顶部导航 + 课程上下文条作为最终导航方案，或改左侧栏；
5. 沉浸页深色由深蓝 + 琥珀改为墨夜 + 琥珀是否被接受；
6. 落地优先级：建议先做登录 / 课程 / 课程详情 / 学生任务 / 课前检查 / 课堂 / 报告，再做成绩按课程、训练配置、模板与评分统计。

## 6. 结论

- V1.5 在 V1.4 基础上把管理员专业 / 班级新增与编辑改为弹窗，补齐管理员课程详情、教师课程学生管理，移除学生课程成绩页内课程切换条；原型可作为下一轮视觉与信息架构讨论的基线；
- 未改任何业务代码，不改变现有构建 / 测试结果；
- 按原型同步设计文档的记录见 [2026-11-17-ui-doc-sync](2026-11-17-ui-doc-sync.md)；M3 / M6 契约同步见 [2026-11-18-contract-sync](2026-11-18-contract-sync.md)。
- 落地前需按 `docs/team/01` 的模块归属逐页确认，并先同步 09 §5 / §16 的主题描述。
