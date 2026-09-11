# 大学生考研 AI 导学系统

面向考研学生的 AI 陪伴式备考数字人助教。产品愿景覆盖择校、公共课 / 专业课、刷题、模考、复试全流程。

> **当前阶段:MVP 可行性验证。**
> 核心闭环 = **AI 答疑 + 刷题 / 错题本 + 个性化规划**,用于验证 LangGraph 能否跑通答疑闭环,再逐步扩展其余模块。

## 目录

- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [启动流程](#启动流程)
- [架构说明](#架构说明)
- [MVP 范围](#mvp-范围)
- [待定与规划](#待定与规划)

## 技术栈

### 后端

| 层 | 选型 | 状态 |
|---|---|---|
| 语言 / 包管理 | Python 3.12 + [uv](https://docs.astral.sh/uv/) | 已有 |
| Web 框架 | FastAPI 0.141 | 已有 |
| ASGI 服务器 | Uvicorn | 待加 |
| Agent 编排 | LangGraph 1.2(内置 `MemorySaver`) | 已有 |
| 大模型 | DeepSeek(OpenAI 兼容接口,经 `langchain-openai`) | 待加 |
| 可观测 | LangSmith tracing | 已有 |
| ORM | SQLModel + SQLAlchemy 2.0(async) | 已有 |
| 数据库 | PostgreSQL 16 + pgvector(Docker) | 待加 |
| DB 驱动 | asyncpg | 待加 |
| 数据库迁移 | Alembic | 待加 |
| 鉴权 | python-jose + passlib[bcrypt] | 待加 |
| 配置管理 | pydantic-settings | 待加 |
| 流式输出 | SSE(`StreamingResponse`) | 待加 |

### 前端

| 层 | 选型 | 状态 |
|---|---|---|
| 框架 | Vue 3.5 + TypeScript 6 | 已有 |
| 构建 | Vite 8 | 已有 |
| 路由 | Vue Router | 待加 |
| 状态管理 | Pinia | 待加 |
| UI 组件库 | Element Plus(自动导入) | 待加 |
| 图表 | ECharts(`vue-echarts`) | 待加 |
| HTTP 客户端 | axios | 待加 |

### 基础设施

- **Docker Compose**:仅用于拉起 `pgvector/pgvector:pg16` 数据库。

### 环境要求

- Python 3.12
- [uv](https://docs.astral.sh/uv/)
- Node ≥ 20.19 或 ≥ 22.12(Vite 8 硬性要求)
- Docker Desktop

## 项目结构

```
project_school_temp/
├── docker-compose.yml            # pgvector/pgvector:pg16 数据库
├── README.md                     # 本文件
├── backend/                      # FastAPI + LangGraph
│   ├── pyproject.toml            # 依赖声明(uv)
│   ├── .env.example              # 环境变量模板
│   ├── alembic/                  # 数据库迁移脚本
│   └── app/
│       ├── main.py               # FastAPI 入口:路由挂载 + CORS
│       ├── core/                 # config / security(JWT) / db(engine+session)
│       ├── models/               # SQLModel 表:user / question / mistake / plan
│       ├── schemas/              # 请求响应 DTO
│       ├── api/v1/               # 路由:auth / questions / mistakes / plans / chat
│       ├── agents/               # LangGraph 图
│       │   ├── llm.py            #   DeepSeek 客户端
│       │   ├── tutor_graph.py    #   答疑图(引导式,不直接给答案)
│       │   └── planner_graph.py  #   规划图(日 / 周 / 月计划)
│       └── services/             # 业务逻辑(错题归类、同类题检索)
└── frontend/                     # Vue 3 + TS + Vite
    ├── vite.config.ts            # /api 代理 → localhost:8000
    └── src/
        ├── main.ts               # 应用入口
        ├── App.vue
        ├── router/               # 路由定义
        ├── stores/               # Pinia stores
        ├── api/                  # axios 封装 + SSE 客户端
        ├── views/                # 页面:Chat / Practice / Mistake / Plan / Login
        ├── components/           # 复用组件
        └── assets/               # 静态资源
```

## 启动流程

### 1. 启动数据库

```bash
docker compose up -d db
```

### 2. 启动后端

```bash
cd backend
cp .env.example .env          # 填入 DEEPSEEK_API_KEY
uv sync                       # 安装依赖
uv run alembic upgrade head   # 建表 + 启用 pgvector 扩展
uv run uvicorn app.main:app --reload --port 8000
```

- API 文档:<http://localhost:8000/docs>
- 健康检查:`GET /api/v1/health`

### 3. 启动前端

```bash
cd frontend
npm install
npm run dev
```

- 前端地址:<http://localhost:5173>
- `/api` 请求由 Vite 代理到 `http://localhost:8000`,无需额外配置跨域

### 环境变量(`backend/.env`)

| 变量 | 说明 |
|---|---|
| `DEEPSEEK_API_KEY` | DeepSeek API 密钥 |
| `DEEPSEEK_BASE_URL` | 兼容端点,默认 `https://api.deepseek.com` |
| `DEEPSEEK_MODEL` | 模型名,默认 `deepseek-chat` |
| `DATABASE_URL` | PostgreSQL 连接串(pgvector) |
| `JWT_SECRET` | JWT 签名密钥 |
| `LANGSMITH_API_KEY` | 可选,LangSmith tracing |


**待定决策**:

- **数字人技术路线**(候选:2D 形象 + TTS/ASR、Live2D 可动形象、3D + three.js、商用虚拟人 API)待专项讨论后锁定。

