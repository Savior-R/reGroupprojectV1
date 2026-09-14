# 师范专业 AI 试讲台系统

面向师范类专业(师范生)的 AI 虚拟试讲训练与考核平台。用「虚拟学生 + AI 多维度评测」替代 / 辅助真实课堂试讲,实现课前磨课、技能训练、教学评价一体化。

> **当前阶段:MVP 可行性验证(可演示的课程 / 毕设项目)。**
> 核心闭环 = **虚拟课堂试讲 → 语音评测 → 报告**,其余模块逐步扩展。
> 设计文档见 `docs/superpowers/specs/2026-09-14-shifan-ai-shijiangtai-design.md`。

## 目录

- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [启动流程](#启动流程)
- [MVP 范围](#mvp-范围)
- [待定项](#待定项)

## 技术栈

### 后端

| 层 | 选型 | 状态 |
|---|---|---|
| 语言 / 包管理 | Python 3.12 + [uv](https://docs.astral.sh/uv/) | 已有 |
| Web 框架 | FastAPI 0.141 | 已有 |
| ASGI 服务器 | Uvicorn | 已有 |
| LLM 编排 | LangGraph 1.2(评测图) | 已有 |
| 大模型 | DeepSeek(OpenAI 兼容,经 `langchain-openai`) | 已有 |
| 可观测 | LangSmith tracing | 已有 |
| ORM | SQLModel + SQLAlchemy 2.0(async) | 已有 |
| 数据库 | PostgreSQL 16(Docker) | 已有 |
| DB 驱动 | asyncpg | 已有 |
| 迁移 | Alembic | 已有 |
| 鉴权 | python-jose + bcrypt | 已有 |
| 配置 | pydantic-settings | 已有 |
| 文件上传 | python-multipart | 已有 |
| 后台任务 | FastAPI `BackgroundTasks`(内置) | 已有 |
| ASR | 适配层 + Fake 实现(云端厂商待定) | 已有 |

### 前端

| 层 | 选型 | 状态 |
|---|---|---|
| 框架 | Vue 3.5 + TypeScript 6 | 已有 |
| 构建 | Vite 8 | 已有 |
| 路由 / 状态 | Vue Router + Pinia | 已有 |
| UI 组件库 | Element Plus(自动导入) | 已有 |
| 图表 | ECharts(`vue-echarts`,雷达图) | 已有 |
| HTTP 客户端 | axios | 已有 |
| 录制 | 浏览器原生 `MediaRecorder` | 已有 |
| 虚拟学生语音 | 浏览器 Web SpeechSynthesis | 已有 |

### 基础设施

- **Docker Compose**:仅用于拉起 `postgres:16`。
- **本地文件存储**:录制音视频落 `backend/storage/`(运行时目录,已 gitignore)。

### 环境要求

- Python 3.12、[uv](https://docs.astral.sh/uv/)
- Node ≥ 20.19 或 ≥ 22.12(Vite 8 硬性要求)
- Docker Desktop

## 项目结构

```
project_school_temp/
├── docker-compose.yml            # postgres:16
├── README.md                     # 本文件
├── CODE_STANDARDS.md             # 代码规范
├── docs/superpowers/             # 设计文档 + 实现计划
├── backend/                      # FastAPI + LangGraph
│   ├── pyproject.toml
│   ├── .env.example
│   ├── alembic/                  # 数据库迁移
│   ├── storage/                  # 录制音视频(运行时)
│   └── app/
│       ├── main.py               # 入口:路由挂载 + CORS + /media
│       ├── core/                 # config / db / security / storage
│       ├── models/               # SQLModel:user / lesson / attempt / transcript / evaluation
│       ├── schemas/              # 请求响应 DTO
│       ├── api/v1/               # auth / lessons / attempts
│       ├── agents/               # llm.py / evaluation_graph.py
│       └── services/             # asr / metrics / classroom / seed / evaluation
└── frontend/                     # Vue 3 + TS + Vite
    └── src/
        ├── router/  stores/  api/  types/
        ├── views/                # Login / LessonList / Classroom / Report / History
        ├── components/           # ClassroomBoard / StudentPanel / RecorderControls /
        │                         #   SegmentTimeline / RadarChart / ReportCard
        └── composables/          # useRecorder / useClassroomScript / useAttemptPolling
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
uv sync
uv run alembic upgrade head   # 建表
uv run python -m app.services.seed   # 灌入试讲题目种子数据
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
- `/api` 请求由 Vite 代理到 `http://localhost:8000`

### 环境变量(`backend/.env`)

| 变量 | 说明 |
|---|---|
| `DEEPSEEK_API_KEY` | DeepSeek API 密钥 |
| `DEEPSEEK_BASE_URL` | 兼容端点,默认 `https://api.deepseek.com` |
| `DEEPSEEK_MODEL` | 模型名,默认 `deepseek-chat` |
| `DATABASE_URL` | PostgreSQL 连接串(`postgresql+asyncpg://...`) |
| `JWT_SECRET` | JWT 签名密钥 |
| `STORAGE_DIR` | 录制文件根目录,默认 `storage` |
| `ASR_PROVIDER` | ASR 实现,`fake`(默认)/ 厂商名 |
| `ASR_API_KEY` | 云端 ASR 密钥(厂商确定后使用) |
| `LANGSMITH_API_KEY` | 可选,LangSmith tracing |

## MVP 范围

**MVP 内**:

1. 注册 / 登录(师范生,含专业、年级)
2. 试讲题目浏览与选择(内置种子数据)
3. 虚拟课堂(三栏布局 + 脚本化虚拟学生 + 浏览器 TTS)
4. 录制(音频 + 摄像头)、上传、回放
5. 评测流水线 + 报告页(雷达图 + 逐句建议)
6. 我的试讲历史

**MVP 外(后续阶段)**:教师端(班级 / 任务 / 批阅 / 看板)、资源库、训练阶梯(跟读 / 情景 / 模拟考核)、教姿教态(CV)、板书 PPT(OCR)、达标认证、实时直播观摩、教材语义检索(pgvector)、课堂互动维度。

## 待定项

- **云端 ASR 厂商**:候选 讯飞 / 阿里云 / 腾讯云。MVP 用适配层 + `FakeAsrClient` 跑通,厂商确定后新增一个 `AsrClient` 实现即可(插入点见 `app/services/asr.py`)。
- **云端 TTS**:厂商确定后,把前端 Web SpeechSynthesis 换成后端 TTS 接口。
- **波形回放**:是否引入 wavesurfer.js。
- **「课堂互动」维度**:脚本学生的举手时间点已知,加「响应率」成本极低,可后续补。
