# Design System — 三尺讲台

> 版本：V1.5 原型提案　日期：2026-11-17　状态：待评审
> 静态原型：`docs/prototype/`；设计契约以本文件为准
> 说明：本文件描述**目标设计语言**，不是对现有 `frontend/` 的已实现状态声明。落地前需按 `docs/team/01` 与各模块 owner 确认。

## Stack

- framework: Vue 3.5 + Vite 8 + TypeScript（现有前端）
- styling: 原生 CSS 变量 + Element Plus 深度定制（原型用纯 CSS，无构建）
- components: Element Plus（现有）；原型中用语义化 HTML/CSS 重绘
- animation: CSS transitions / keyframes；不使用动画库
- icons: 内联 SVG sprite（`docs/prototype/assets/site.js`）
- charts: 原型用内联 SVG / CSS；落地继续用 ECharts（按需引入）

## 设计命题

**“教案 / 讲义”式的学术界面。** 不是 SaaS 仪表盘，也不是校园信息门户，而是一套让师范生愿意长时间停留、让教师快速判断的训练工具。

四个关键词：

1. **纸感** — 米白纸色、细纹理、低对比边界；靠留白和字距建立秩序。
2. **墨色** — 墨绿为主色，朱砂为批注 / 重点，赭石为提醒；不用大面积高饱和色。
3. **双舞台** — 普通页面是明亮的“讲义”，课堂 / 面试 / 回看是深色的“舞台”。两者共享同一套语义 token。
4. **节奏** — 页面标题、任务卡、表单分节、评测建议都用明确的层级与编号，像一份可读的教案。

## Tokens

完整 token 见 `docs/prototype/assets/tokens.css`。

### 色彩

| 角色 | Light | Stage（沉浸页） |
|---|---|---|
| 页面底 | `#F5F1E8` 米白纸 | `#07100E` 墨夜深色 |
| 表面 | `#FFFDF8` | `#0D1916` |
| 主色 / 主按钮 | `#2A6758` 墨绿 | `#E0A44A` 暖琥珀 |
| 强调 / 批注 | `#A8442F` 朱砂 | `#D95F4A` 录制红 |
| 正文 | `#182622` | `#E9EEE9` |
| 次文 | `#68766F` | `#93A49D` |
| 分隔线 | `#E4DFD4` | `rgba(233,238,233,.10)` |
| 成功 / 警告 / 危险 / 信息 | `#367254` / `#A9782F` / `#A8442F` / `#4B7088` | 提亮版本 |

- 主按钮：墨绿底 + 米白字；沉浸页主按钮：琥珀底 + 深墨字。
- 朱砂只用于：品牌章、当前导航下划线、重点数字、批注、危险语义。不要整屏使用。
- 状态不能只靠颜色：必须有文字 + 圆点 / 图标。
- 对比度目标：正文 ≥ 4.5:1，大字 ≥ 3:1。

### 字体

```css
--font-serif: "Noto Serif SC", "Source Han Serif SC", "Songti SC", "STSong", "SimSun", serif;
--font-sans: "Inter", "Noto Sans SC", "PingFang SC", "Microsoft YaHei", system-ui, sans-serif;
--font-mono: "JetBrains Mono", "SFMono-Regular", Consolas, monospace;
```

| 用途 | 字号 / 行高 | 字族 |
|---|---|---|
| 封面 / 登录大标题 | 38—54 / 1.3 | serif，600 |
| 页面标题 | 32—42 / 1.25 | serif，600 |
| 区块标题 | 22—26 / 1.35 | serif，600 |
| 卡片标题 | 17—20 / 1.4 | serif 或 sans 600 |
| 正文 | 14.5 / 1.72 | sans，400 |
| 辅助 | 12.5 / 1.6 | sans，400 |
| 标签 / eyebrow | 10.5—11 / 1.4，字距 .14em | sans，600，大写 |
| 分数 / 计时 | tabular numerals / mono | serif 或 mono |

- 中文正文最小 12px；不要用过细字重。
- 英文只做 eyebrow 与短标签，不替代中文主标题。

### 间距、圆角、阴影

- 间距：4 / 8 / 12 / 16 / 24 / 32 / 48 / 64 / 80 / 96。
- 圆角：控件 6—8px，卡片 14px，弹窗 16px，标签 3px，胶囊 999px。
- 阴影两层：近距离细阴影 + 大范围极淡环境阴影；沉浸页用更深的舞台阴影。
- 卡片以 1px 线 + 纸面反差为主，阴影只做轻微托起。

### 布局

- 顶部导航 64px，固定；内容最大 1360px，左右 40px；表单页 1120px。
- 普通页面：单列或 12 栏网格；列表页可加右侧“待办 / 摘要”栏。
- 课堂 / 面试：全屏 Stage，顶部 64px 状态条，右侧 320—360px 信息栏，底部控制坞。
- 桌面优先：1024—1920；小于 1024 提示使用桌面浏览器（与 09 §13 一致）。原型页面在 1180px 以下做有限收窄，不承诺移动端。

## Decisions

- **2026-11-16 — 主题归位。** `docs/design/09` 的 §1.1 已写明“浅色学术主题 A + 沉浸式深色舞台”，但 §5 / §16 仍保留旧的深色 B token；现有 `frontend/src/style.css` 已实现浅色 A。原型采用 A（浅色）作为普通页面基线，B 仅作为课堂 / 面试 / 回看的深色舞台，并把它改写为与品牌一致的“墨夜 + 琥珀”。09 的 §5 / §16 需 Score/QA Owner 后续同步。
- **2026-11-16 — 保留顶部导航。** 继续使用顶部导航 + 头像在右，减少路由与布局层改动；新增“课程上下文条”（面包屑 + 课程内二级导航），解决当前课程 / 任务 / 评分页面缺少空间上下文的问题。
- **2026-11-16 — 学生工作台为提案。** 当前 `/student` 重定向到 `/student/tasks`；原型增加 `student-home` 工作台，用于展示“下一件事 / 本周节奏 / 能力画像”。是否新增该路由需产品与 M3/M6/M7 Owner 确认。
- **2026-11-16 — 课程页按角色分视图。** 同一个 `/courses` 路由，学生看卡片式课程发现，教师看“我的课程”工作台 + 全校只读表，管理员看归档管理。原型用同一个 `courses.html` 的角色切换演示。
- **2026-11-16 — V1.1 信息架构补充。** 课程列表可进入课程详情（学生 / 教师共用 `course-detail.html`，`?role=` 区分）；学生成绩先按课程聚合，再进入课程成绩详情查看任务级分数；训练从模板卡片进入独立训练配置页；教师训练模板增加独立的查看与创建 / 编辑页；管理员教师权限抽屉的专业范围改为“搜索 + 可滚动列表 + 已选计数”。
- **2026-11-16 — V1.2 交互规则。** 任务不做草稿，编辑后直接发布；数据展示不再使用进度条，改为数字 + 表格 / 雷达 / 趋势图；所有可能增长到多页的列表使用分页；模板引用与题库类别引用使用“搜索 + 分页列表”的独立窗口；列表优先懒加载 / 加载更多；训练模板不提供归档。
- **2026-11-17 — V1.3 学生模板与自由训练引用。** 学生可创建训练模板，也可从教师模板复制；训练首页「我的模板」汇总自己创建与复制的全部模板。训练配置保留“模板训练 / 自由训练”两种入口；模板训练可在独立窗口中选择我的模板；自由训练可引用任一我的模板，带入课题、档位、时长、训练侧重与虚拟学生，带入后所有字段仍可自由修改，且不回写原模板。学生模板不展示教师评分权重。
- **2026-11-17 — V1.4 课程详情与删除操作。** 课程详情不再提供页内课程 / 角色切换，学生 / 教师视图由路由权限与 `?role=` 预选决定。教师课程任务支持删除，删除前二次确认；教师与学生的训练模板支持删除，已发布教师模板删除前先下架；删除语义以 2026-11-18 M3 / M6 契约同步为准。
- **2026-11-17 — V1.5 组织弹窗与课程管理补齐。** 管理员专业 / 班级的新增与编辑统一在弹窗内完成；新增管理员课程详情（只读概览 / 学生 / 老师组 + 编辑元数据 / 归档），新增教师课程学生管理（筛选、添加、移出、学生成绩抽屉）；学生课程成绩页移除课程切换条，课程由路由 / `?course=` 决定。
- **2026-11-18 — M3 / M6 契约同步。** 任务彻底移除草稿，创建即发布；任务删除为软删除，保留回放 / 报告 / 评分。训练模板删除为软删除，自动下架、释放额度，并清理关联 TrainingRecord / 录像 / 报告 / 参考材料；学生副本与任务快照保留。
- **2026-11-16 — 无外部依赖。** 原型不加载 CDN、字体文件或图片；所有图标为内联 SVG，数据为静态演示数据，直接双击 HTML 或起静态服务器即可查看。

## Components

原型已建立以下组件样式（`docs/prototype/assets/site.css`）：

- AppHeader / BrandSeal / MainNav / AccountMenu
- ContextBar / Breadcrumb / PageHead / Eyebrow
- Button（primary / secondary / ghost / accent / danger / sizes / loading / disabled）
- Badge / StatusBadge / Chip / Count
- Card / CardHead / StatStrip / Stat
- DataTable / DataInline（数值） / Avatar / AvatarStack / Pagination / LazyFoot
- TemplatePicker（搜索 + 来源筛选 + 分页 / 懒加载）/ FreeTemplateBar（已引用提示 + 清除）
- ConfirmDialog（删除任务 / 模板 / 移出学生二次确认，危险按钮）
- OrgFormModal（专业 / 班级新增与编辑弹窗）/ StudentManagementTable（筛选 + 添加 / 移出 + 成绩抽屉）/ AdminCourseDetail
- Field / Input / Select / Textarea / Segmented / TabsLine / Checkbox / RadioCard
- Alert / EmptyState / Skeleton / Toast / Drawer / Modal
- StageBar（沉浸页）/ StagePanel / ControlDock
- PrototypeMap（悬浮原型导航）

## Non-Goals

- 不引入 Figma 同步、图片生成或设计工具依赖。
- 不在本轮做明暗主题切换；深色仅用于沉浸页。
- 不做移动端 / 平板适配（与 09 §13 一致）。
- 不修改现有 API、DTO、数据库或业务规则；原型只表达界面与交互。
- 不把原型样式直接复制进 `frontend/`；落地需按模块归属拆分为 `tokens.css` / `base.css` / `element-overrides.css` 并评审。

## 查看方式

```bash
# 在仓库根目录
python3 -m http.server 4173 --directory docs/prototype
# 打开 http://localhost:4173/
```

入口：`docs/prototype/index.html`。迭代说明与页面清单见 `docs/prototype/README.md`。
