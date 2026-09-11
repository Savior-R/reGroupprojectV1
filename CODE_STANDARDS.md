# 代码规范

> 适用:大学生考研 AI 导学系统(backend: Python 3.12 / FastAPI / LangGraph;frontend: Vue 3 + TS + Vite)。
> 原则:**一致性优先于个人偏好**。与本文冲突时,先在 PR 里讨论规范,再改代码。

## 目录

- [1. 通用原则](#1-通用原则)
- [2. 目录与分层](#2-目录与分层)
- [3. 后端(Python)](#3-后端python)
- [4. 前端(Vue 3 + TS)](#4-前端vue-3--ts)
- [5. 数据库与迁移](#5-数据库与迁移)
- [6. Git 提交规范](#6-git-提交规范)
- [7. 注释与文档](#7-注释与文档)
- [8. 测试](#8-测试)
- [9. 安全红线](#9-安全红线)

## 1. 通用原则

- **KISS**:能简单实现就不引入抽象。三行重复代码优于一个过早的公共工具。
- **YAGNI**:只为当前需求写代码,不为「以后可能用到」预留参数、开关、接口。
- **单一职责**:一个函数/组件只做一件事。函数超过约 50 行、嵌套超过 3 层,考虑拆分。
- **显式优于隐式**:避免魔法数字、隐式全局状态、靠副作用传递数据。
- **不留死代码**:确认无用的代码直接删,不要注释掉或改名为 `_unused` 保留。
- **不做无谓兼容**:MVP 阶段可以自由改接口,不加只为兼容旧调用的分支。
- 编码统一 **UTF-8**,换行统一 **LF**(`.gitattributes` 约束),缩进后端 4 空格、前端 2 空格。

## 2. 目录与分层

依赖方向**单向**,禁止反向引用:

```
api/v1  →  services  →  agents(LangGraph)
                    ↘  models / core(db, security, config)
```

- `api/v1/`:只做 HTTP 层的事——参数校验、鉴权、调用 service、返回 DTO。**不写业务逻辑、不直连 ORM**。
- `services/`:业务逻辑的唯一归属地。可以调用 `models` 和 `agents`。
- `agents/`:LangGraph 图与 LLM 客户端。**不感知 HTTP**(不 import FastAPI 的类型)。
- `models/`:`SQLModel` 表定义,同时服务 ORM 与 pgvector 检索。
- `schemas/`:请求/响应 DTO,禁止把 `models` 直接当响应体返回(避免泄露字段)。
- `core/`:`config` / `security` / `db`,被所有层依赖,自己不依赖业务层。

前端同理:`views` → `components` / `stores` → `api`,`api` 只负责请求封装。

## 3. 后端(Python)

### 3.1 格式与静态检查

- 遵循 **PEP 8**。
- 使用 **Ruff** 做 lint + format(行宽 100),**mypy** 做类型检查。
- 所有函数(含私有)写**类型注解**,返回值用 `-> None` 显式标出。
- 禁止 `from x import *`;禁止裸 `except:`。

### 3.2 命名

| 对象 | 规范 | 示例 |
|---|---|---|
| 模块 / 包 | `snake_case` | `tutor_graph.py` |
| 类 | `PascalCase` | `TutorState` |
| 函数 / 变量 | `snake_case` | `build_tutor_graph` |
| 常量 | `UPPER_SNAKE` | `MAX_RETRY` |
| 私有 | 前缀 `_` | `_normalize(text)` |

- 布尔量以 `is_` / `has_` / `can_` 开头。
- 集合类变量用复数(`questions`、`mistakes`)。

### 3.3 异步

- 全链路 **async**:路由 `async def`,DB 用 SQLAlchemy 2.0 async session。
- **禁止**在 async 函数里做阻塞调用(同步 IO、`time.sleep`、同步 DB)。
- CPU 密集或确需同步的场景用 `run_in_threadpool`。

### 3.4 FastAPI

- 路由按业务域拆分到 `app/api/v1/<domain>.py`,用 `APIRouter`,统一挂 `prefix="/api/v1"`。
- 请求体/响应体一律用 Pydantic(`schemas/`),不接收 `dict`。
- 依赖注入(鉴权、DB session)用 `Depends`,不要在函数里自己 new。
- 每个路由声明 `response_model` 与状态码;错误用 `HTTPException`,不返回 `{"error": ...}` 裸结构。
- 新接口必须能在 `/docs` 里自解释:补 `summary`、`description`、参数说明。

### 3.5 SQLModel / DB 会话

- 表模型集中在 `models/`,一个文件一张表或一组强相关表。
- 会话生命周期交给 `Depends`,不要全局共享 session。
- 查询避免 N+1;关联数据按需 `selectinload` / 显式 join。
- 不在事务里夹带外部 IO(LLM 调用、HTTP 请求),防止长事务锁表。

### 3.6 LangGraph / LLM

- 图构建集中在 `agents/`,节点函数保持**纯**:输入 state,输出 state 的增量,不读全局。
- LLM 客户端集中在 `agents/llm.py`,模型名、`base_url` 从 `core/config` 读取,**不硬编码**。
- Prompt 作为模块级常量,便于 review 与版本对比;不要散落在节点函数体内。
- 流式输出统一走 `astream_events` + SSE,前端对接 `/api/v1/chat/stream`。
- 不在节点里吞异常:LLM 失败要能区分「可重试」与「参数/配额错误」。

### 3.7 配置

- 一切配置走 `pydantic-settings`(`core/config.py`),从环境变量读取。
- **禁止**硬编码密钥、连接串、模型名。新增配置项同步更新 `.env.example`。

## 4. 前端(Vue 3 + TS)

### 4.1 格式与类型

- 使用 **ESLint + Prettier**,组件单文件 `<script setup lang="ts">`。
- **禁止 `any`**;确实未知用 `unknown` 并收窄,或定义明确类型。
- `tsconfig` 保持 `strict: true`,不靠 `@ts-ignore` 绕过。提交前跑 `npm run build`(`vue-tsc`)确认类型通过。
- 接口返回类型定义在 `api/` 层,组件不重复声明。

### 4.2 命名

| 对象 | 规范 | 示例 |
|---|---|---|
| 组件文件 | `PascalCase.vue` | `ChatBubble.vue` |
| 页面 | `PascalCase.vue`(放 `views/`) | `PracticeView.vue` |
| 组合式函数 | `useXxx` | `useChatStream` |
| 变量 / 函数 | `camelCase` | `fetchMistakes` |
| Store | `useXxxStore` | `useUserStore` |
| 常量 | `UPPER_SNAKE` | `MAX_INPUT_LEN` |

- 自定义事件名用 `kebab-case`(`@submit-answer`),props 用 `camelCase` 定义、模板里 `kebab-case` 传递。
- 组件名必须多单词,避免与 HTML 标签冲突。

### 4.3 组件与状态

- 优先**组合式 API**(`<script setup>`),不用 Options API 写新组件。
- 组件保持「展示」与「逻辑」分离:纯展示组件只收 props / 发事件,不直接调 API。
- 跨组件共享状态放 **Pinia store**,不靠 `provide/inject` 或全局变量传递业务状态。
- 派生数据用 `computed`,不手动维护冗余状态。

### 4.4 请求与 SSE

- 所有 HTTP 请求经 `api/` 下的 axios 实例,**统一拦截器**处理 baseURL、鉴权头、错误提示。
- 组件里不直接写 `axios.get(...)`,只调 `api/` 暴露的函数。
- SSE 用 `fetch` + `ReadableStream` 逐字渲染;组件卸载时**必须中断连接**(`AbortController`)。
- 异步操作处理 loading / error / empty 三种状态,不出现「白屏无反馈」。

### 4.5 样式

- 优先 `<style scoped>`,避免全局污染。
- 复用 UI 用 Element Plus 组件,不自造重复的按钮/表单。
- 类名语义化;主题色、间距等抽成 CSS 变量,不散落魔法值。

## 5. 数据库与迁移

- 表结构变更**一律通过 Alembic**,禁止手动改库后不同步迁移。
- 迁移脚本命名清晰(`add_question_index`),`upgrade` / `downgrade` 成对实现。
- 涉及 pgvector 的迁移显式写 `CREATE EXTENSION IF NOT EXISTS vector;`。
- 加字段给默认值或允许 null,避免线上迁移失败;删字段/改类型需评估数据影响。
- 索引:外键、高频查询字段、向量列(`ivfflat` / `hnsw`)必须建索引,并在迁移中体现。

## 6. Git 提交规范

- 分支:`main` 保持可运行;功能分支 `feat/<简述>`、修复 `fix/<简述>`。
- 提交信息遵循 **Conventional Commits**:

  ```
  <type>(<scope>): <简述>

  <可选正文:为什么这么改>

  <可选 footer>
  ```

  常用 type:`feat` / `fix` / `refactor` / `docs` / `test` / `chore` / `perf`。
  scope 用模块名:`auth` / `chat` / `mistakes` / `plans` / `frontend` / `db`。

  示例:
  ```
  feat(chat): 支持多轮追问的 SSE 流式答疑
  fix(auth): 修正 JWT 过期时间单位错误
  ```

- 一次提交只做一件事,不混入无关的格式化改动。
- 提交前确保:lint 通过、类型检查通过、相关测试通过。
- **禁止**提交 `.env`、密钥、大体积二进制;确认 `.gitignore` 生效。

## 7. 注释与文档

- 注释解释**为什么**,不复述代码在做什么。
- 复杂算法、非显然的边界处理、临时妥协(带 TODO + 原因)才写注释。
- 公共函数/接口写 docstring(一句话说明用途、参数、返回值、异常)。
- 不写「变更日志式」注释(如 `// 2026-09-11 改成 xxx`),历史交给 git。
- 接口、表结构等对外契约变更,同步更新 `README.md` / `docs/`。

## 8. 测试

- 后端用 `pytest` + `httpx.AsyncClient`;前端用 `vitest`。
- 优先覆盖:核心业务流程(答疑闭环、错题归集、计划生成)、边界条件、异常分支。
- 测试命名描述行为:`test_reject_expired_token`。
- LLM 调用在测试中 **mock**,不打真实 API(避免费用与不稳定)。
- 修 bug 时先补一个能复现的失败测试,再修。

## 9. 安全红线

- 密钥、Token 只从环境变量读取,**永不**写进代码或提交到仓库。
- 所有 SQL 通过 ORM/参数化,禁止字符串拼接。
- 用户输入必须校验;涉及富文本/HTML 渲染时做转义,防 XSS。
- 鉴权在服务端强制校验,不信任前端传来的用户标识。
- 错误响应不泄露堆栈、SQL、内部路径等敏感信息。
