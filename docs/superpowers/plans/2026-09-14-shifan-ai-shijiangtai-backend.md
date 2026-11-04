# 师范 AI 试讲台 — 后端实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 MVP 后端:身份鉴权、试讲题目、试讲录制上传、规则+LLM 评测流水线,对外提供 REST API。

**Architecture:** FastAPI 模块化单体。`api/v1 → services → agents(LangGraph) / models / core` 单向依赖。评测作为 `BackgroundTasks` 在进程内异步执行,状态机驱动 `attempt.status`。

**Tech Stack:** Python 3.12 · uv · FastAPI · SQLModel + SQLAlchemy 2.0(async) · PostgreSQL 16 · Alembic · LangGraph · DeepSeek(经 langchain-openai) · python-jose + bcrypt

**Spec:** `docs/superpowers/specs/2026-09-14-shifan-ai-shijiangtai-design.md`

---

## 计划内的 spec 修正(YAGNI 缩减)

| # | spec 原文 | 本计划 | 原因 |
|---|---|---|---|
| 1 | `passlib[bcrypt]` | 直接用 `bcrypt` | passlib 1.7.4 与 bcrypt≥4.1 有已知不兼容 |
| 2 | 后端 `services/tts.py` + 云端 TTS | **MVP 不做后端 TTS**,虚拟学生语音由前端 Web SpeechSynthesis 播放 | 零依赖立即可用;厂商确定后再补 |
| 3 | `ffmpeg` 为硬依赖 | **MVP 不需要 ffmpeg** | Fake ASR 不解码音频;浏览器可回放 webm |
| 4 | `services/` 含 `tts.py` | 无 `tts.py` | 同 #2 |
| 5 | 教学表达含「音量」维度 | **MVP 不做音量** | 需音频解码(ffmpeg + numpy),spec 已标为可选 |

---

## 文件结构

```
backend/
├── pyproject.toml                     # [改] 补依赖
├── .env.example                       # [建]
├── alembic.ini                        # [建] alembic init -t async
├── alembic/env.py                     # [改] 接 SQLModel metadata
├── alembic/versions/                  # [建] 迁移
├── storage/                           # 运行时,gitignore
├── tests/
│   ├── conftest.py
│   ├── test_health.py
│   ├── test_security.py
│   ├── test_auth_api.py
│   ├── test_classroom.py
│   ├── test_metrics.py
│   ├── test_asr.py
│   ├── test_evaluation_graph.py
│   └── test_attempts_api.py
└── app/
    ├── __init__.py
    ├── main.py                        # FastAPI 入口 + 路由 + CORS + /media
    ├── core/
    │   ├── __init__.py
    │   ├── config.py                  # pydantic-settings
    │   ├── db.py                      # async engine + get_session
    │   ├── security.py                # bcrypt + JWT
    │   └── storage.py                 # 录制文件落盘/路径
    ├── models/
    │   ├── __init__.py                # 汇总导入,供 Alembic autogenerate
    │   ├── user.py
    │   ├── lesson.py
    │   ├── attempt.py
    │   ├── transcript.py
    │   └── evaluation.py
    ├── schemas/
    │   ├── __init__.py
    │   ├── auth.py
    │   ├── lesson.py
    │   ├── attempt.py
    │   └── evaluation.py
    ├── api/v1/
    │   ├── __init__.py
    │   ├── deps.py
    │   ├── auth.py
    │   ├── lessons.py
    │   └── attempts.py
    ├── agents/
    │   ├── __init__.py
    │   ├── llm.py                     # DeepSeek 客户端
    │   └── evaluation_graph.py        # 评测图
    └── services/
        ├── __init__.py
        ├── asr.py                     # AsrClient 协议 + Fake + 工厂
        ├── metrics.py                 # 规则计算(纯函数)
        ├── classroom.py               # 虚拟学生脚本
        ├── seed.py                    # lesson 种子数据
        └── evaluation.py              # 评测流水线编排
```

---

## Phase 0 — 项目文档与基座

### Task 1: 重写 README,更新 CODE_STANDARDS 定位

**Files:**
- Modify: `README.md`(整体重写)
- Modify: `CODE_STANDARDS.md:3`

- [ ] **Step 1: 重写 `README.md`**

内容按 spec 精简:产品定位、技术栈表、项目结构、启动流程、MVP 范围、待定项。完整替换为:

```markdown
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
```

- [ ] **Step 2: 更新 `CODE_STANDARDS.md` 第 3 行定位**

把:
```
> 适用:大学生考研 AI 导学系统(backend: Python 3.12 / FastAPI / LangGraph;frontend: Vue 3 + TS + Vite)。
```
改为:
```
> 适用:师范专业 AI 试讲台系统(backend: Python 3.12 / FastAPI / LangGraph;frontend: Vue 3 + TS + Vite)。
```

- [ ] **Step 3: 更新 `CODE_STANDARDS.md` 第 6 节的 scope 示例**

把 `scope 用模块名:`auth` / `chat` / `mistakes` / `plans` / `frontend` / `db`。` 改为 `scope 用模块名:`auth` / `lessons` / `attempts` / `evaluation` / `frontend` / `db`。`

- [ ] **Step 4: 提交**

```bash
git add README.md CODE_STANDARDS.md
git commit -m "docs: 重写 README 为师范 AI 试讲台定位"
```

---

### Task 2: 后端骨架(配置 / 数据库 / 健康检查)

**Files:**
- Modify: `backend/pyproject.toml`
- Create: `backend/.env.example`, `backend/app/__init__.py`, `backend/app/core/__init__.py`
- Create: `backend/app/core/config.py`, `backend/app/core/db.py`, `backend/app/main.py`
- Create: `backend/tests/__init__.py`, `backend/tests/conftest.py`, `backend/tests/test_health.py`
- Create: `docker-compose.yml`(仓库根)

- [ ] **Step 1: 补依赖**

在 `backend/` 下执行:

```bash
cd backend
uv add fastapi uvicorn[standard] sqlmodel "sqlalchemy[asyncio]" asyncpg alembic \
  "pydantic-settings" "python-jose[cryptography]" bcrypt python-multipart \
  langgraph langchain-openai langsmith httpx
uv add --dev pytest pytest-asyncio
```

预期:`pyproject.toml` 的 `dependencies` 出现上述包,`uv.lock` 更新。

- [ ] **Step 2: 建 `docker-compose.yml`(仓库根)**

```yaml
services:
  db:
    image: postgres:16
    environment:
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
      POSTGRES_DB: shijiangtai
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data

volumes:
  pgdata:
```

- [ ] **Step 3: 建 `backend/.env.example`**

```dotenv
DEEPSEEK_API_KEY=
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_MODEL=deepseek-chat

DATABASE_URL=postgresql+asyncpg://postgres:postgres@localhost:5432/shijiangtai

JWT_SECRET=dev-secret-change-me
ACCESS_TOKEN_EXPIRE_MINUTES=1440

STORAGE_DIR=storage

ASR_PROVIDER=fake
ASR_API_KEY=

LANGSMITH_API_KEY=
```

- [ ] **Step 4: 写失败测试 `backend/tests/test_health.py`**

```python
from fastapi.testclient import TestClient

from app.main import app


def test_health_returns_ok() -> None:
    response = TestClient(app).get("/api/v1/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}
```

- [ ] **Step 5: 运行,确认失败**

```bash
cd backend && uv run pytest tests/test_health.py -v
```

预期:FAIL,`ModuleNotFoundError: No module named 'app.main'`。

- [ ] **Step 6: 写 `backend/app/core/config.py`**

```python
from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """应用配置,全部从环境变量(.env)读取。"""

    model_config = SettingsConfigDict(
        env_file=".env", env_file_encoding="utf-8", extra="ignore"
    )

    deepseek_api_key: str = ""
    deepseek_base_url: str = "https://api.deepseek.com"
    deepseek_model: str = "deepseek-chat"

    database_url: str = (
        "postgresql+asyncpg://postgres:postgres@localhost:5432/shijiangtai"
    )

    jwt_secret: str = "dev-secret-change-me"
    jwt_algorithm: str = "HS256"
    access_token_expire_minutes: int = 1440

    storage_dir: str = "storage"

    asr_provider: str = "fake"
    asr_api_key: str = ""

    langsmith_api_key: str = ""


@lru_cache
def get_settings() -> Settings:
    return Settings()
```

- [ ] **Step 7: 写 `backend/app/core/db.py`**

```python
from collections.abc import AsyncGenerator

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.core.config import get_settings

settings = get_settings()

engine = create_async_engine(settings.database_url, future=True)
SessionLocal = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)


async def get_session() -> AsyncGenerator[AsyncSession, None]:
    """FastAPI 依赖:每个请求一个会话。"""
    async with SessionLocal() as session:
        yield session
```

- [ ] **Step 8: 写 `backend/app/main.py`**

```python
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

app = FastAPI(title="师范 AI 试讲台 API", version="0.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/api/v1/health", tags=["health"])
async def health() -> dict[str, str]:
    """健康检查。"""
    return {"status": "ok"}
```

- [ ] **Step 9: 建空 `__init__.py`**

创建空文件:`app/__init__.py`、`app/core/__init__.py`、`tests/__init__.py`。

- [ ] **Step 10: 运行,确认通过**

```bash
cd backend && uv run pytest tests/test_health.py -v
```

预期:PASS。

- [ ] **Step 11: 提交**

```bash
git add docker-compose.yml backend/pyproject.toml backend/uv.lock backend/.env.example \
  backend/app backend/tests
git commit -m "feat(backend): 补齐依赖与 FastAPI 骨架,含健康检查"
```

---

### Task 3: 数据模型与 Alembic 迁移

**Files:**
- Create: `backend/app/models/__init__.py`, `user.py`, `lesson.py`, `attempt.py`, `transcript.py`, `evaluation.py`
- Create: `backend/alembic.ini`, `backend/alembic/env.py`, `backend/alembic/versions/*`

- [ ] **Step 1: 写 `backend/app/models/user.py`**

```python
from datetime import datetime, timezone
from enum import Enum

from sqlmodel import Field, SQLModel


class UserRole(str, Enum):
    student = "student"
    teacher = "teacher"


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


class User(SQLModel, table=True):
    __tablename__ = "users"

    id: int | None = Field(default=None, primary_key=True)
    username: str = Field(index=True, unique=True)
    hashed_password: str
    display_name: str
    role: UserRole = Field(default=UserRole.student)
    major: str | None = Field(default=None)
    grade: str | None = Field(default=None)
    created_at: datetime = Field(default_factory=_utcnow)
```

- [ ] **Step 2: 写 `backend/app/models/lesson.py`**

```python
from datetime import datetime
from typing import Any

from sqlalchemy import JSON, Column
from sqlmodel import Field, SQLModel

from app.models.user import _utcnow


class Lesson(SQLModel, table=True):
    """试讲题目 / 资源。"""

    __tablename__ = "lessons"

    id: int | None = Field(default=None, primary_key=True)
    title: str
    subject: str          # 语文 / 数学 / 英语 / 思政 / 幼教
    stage: str            # 小学 / 初中 / 高中 / 学前
    textbook: str         # 教材版本
    content: str          # 题目描述或课文
    key_points: list[str] = Field(default_factory=list, sa_column=Column(JSON))
    suggested_duration_seconds: int = 600
    suggested_segments: dict[str, int] = Field(
        default_factory=dict, sa_column=Column(JSON)
    )
    created_at: datetime = Field(default_factory=_utcnow)
```

- [ ] **Step 3: 写 `backend/app/models/attempt.py`**

```python
from datetime import datetime
from enum import Enum

from sqlalchemy import JSON, Column
from sqlmodel import Field, SQLModel

from app.models.user import _utcnow


class AttemptMode(str, Enum):
    free = "free"
    scenario = "scenario"
    interview = "interview"


class AttemptStatus(str, Enum):
    draft = "draft"
    uploaded = "uploaded"
    transcribing = "transcribing"
    evaluating = "evaluating"
    done = "done"
    failed = "failed"


class Attempt(SQLModel, table=True):
    """一次试讲。"""

    __tablename__ = "attempts"

    id: int | None = Field(default=None, primary_key=True)
    user_id: int = Field(foreign_key="users.id", index=True)
    lesson_id: int = Field(foreign_key="lessons.id", index=True)
    mode: AttemptMode = Field(default=AttemptMode.free)
    status: AttemptStatus = Field(default=AttemptStatus.draft, index=True)
    media_path: str | None = Field(default=None)
    media_mime: str | None = Field(default=None)
    duration_seconds: float | None = Field(default=None)
    segment_marks: list[dict] = Field(default_factory=list, sa_column=Column(JSON))
    classroom_script: list[dict] = Field(default_factory=list, sa_column=Column(JSON))
    error_message: str | None = Field(default=None)
    created_at: datetime = Field(default_factory=_utcnow)
    updated_at: datetime = Field(default_factory=_utcnow)
```

- [ ] **Step 4: 写 `backend/app/models/transcript.py`**

```python
from datetime import datetime

from sqlalchemy import JSON, Column
from sqlmodel import Field, SQLModel

from app.models.user import _utcnow


class Transcript(SQLModel, table=True):
    """ASR 转写结果。"""

    __tablename__ = "transcripts"

    id: int | None = Field(default=None, primary_key=True)
    attempt_id: int = Field(foreign_key="attempts.id", index=True, unique=True)
    segments: list[dict] = Field(default_factory=list, sa_column=Column(JSON))
    full_text: str = ""
    created_at: datetime = Field(default_factory=_utcnow)
```

- [ ] **Step 5: 写 `backend/app/models/evaluation.py`**

```python
from datetime import datetime

from sqlalchemy import JSON, Column
from sqlmodel import Field, SQLModel

from app.models.user import _utcnow


class Evaluation(SQLModel, table=True):
    """评测报告。雷达图数据由 dimensions 在前端派生,不落库。"""

    __tablename__ = "evaluations"

    id: int | None = Field(default=None, primary_key=True)
    attempt_id: int = Field(foreign_key="attempts.id", index=True, unique=True)
    dimensions: dict = Field(default_factory=dict, sa_column=Column(JSON))
    total_score: float = 0.0
    suggestions: list[dict] = Field(default_factory=list, sa_column=Column(JSON))
    summary: str = ""
    created_at: datetime = Field(default_factory=_utcnow)
```

- [ ] **Step 6: 写 `backend/app/models/__init__.py`**

```python
"""集中导入所有表模型,供 Alembic autogenerate 发现。"""

from app.models.attempt import Attempt, AttemptMode, AttemptStatus
from app.models.evaluation import Evaluation
from app.models.lesson import Lesson
from app.models.transcript import Transcript
from app.models.user import User, UserRole

__all__ = [
    "Attempt",
    "AttemptMode",
    "AttemptStatus",
    "Evaluation",
    "Lesson",
    "Transcript",
    "User",
    "UserRole",
]
```

- [ ] **Step 7: 初始化 Alembic**

```bash
cd backend && uv run alembic init -t async alembic
```

预期:生成 `alembic.ini` 与 `alembic/`(`env.py` / `script.py.mako` / `versions/`)。

- [ ] **Step 8: 改 `backend/alembic/env.py` 接 SQLModel metadata**

在文件顶部 import 区加入:

```python
from sqlmodel import SQLModel

from app.core.config import get_settings
import app.models  # noqa: F401  确保所有表被导入
```

把 `config = context.config` 之后加入:

```python
config.set_main_option("sqlalchemy.url", get_settings().database_url)
```

把 `target_metadata = None` 改为:

```python
target_metadata = SQLModel.metadata
```

- [ ] **Step 9: 起库并生成迁移**

```bash
docker compose up -d db
cd backend && uv run alembic revision --autogenerate -m "initial schema"
```

预期:`alembic/versions/` 下生成一个迁移文件,`upgrade()` 含 `users` / `lessons` / `attempts` / `transcripts` / `evaluations` 五张表。检查文件确认五张表都在。

- [ ] **Step 10: 应用迁移并验证**

```bash
cd backend && uv run alembic upgrade head
docker compose exec db psql -U postgres -d shijiangtai -c "\dt"
```

预期:列出 5 张表 + `alembic_version`。

- [ ] **Step 11: 提交**

```bash
git add backend/app/models backend/alembic backend/alembic.ini
git commit -m "feat(db): 新增 user/lesson/attempt/transcript/evaluation 表与初始迁移"
```

---

### Task 4: 鉴权(JWT + bcrypt)

**Files:**
- Create: `backend/app/core/security.py`, `backend/app/schemas/__init__.py`, `backend/app/schemas/auth.py`, `backend/app/api/__init__.py`, `backend/app/api/v1/__init__.py`, `backend/app/api/v1/deps.py`, `backend/app/api/v1/auth.py`
- Test: `backend/tests/test_security.py`, `backend/tests/test_auth_api.py`
- Modify: `backend/app/main.py`

- [ ] **Step 1: 写失败测试 `backend/tests/test_security.py`**

```python
from app.core.security import (
    create_access_token,
    decode_access_token,
    hash_password,
    verify_password,
)


def test_hash_password_is_not_plaintext() -> None:
    hashed = hash_password("s3cret")
    assert hashed != "s3cret"
    assert verify_password("s3cret", hashed)
    assert not verify_password("wrong", hashed)


def test_access_token_roundtrip() -> None:
    token = create_access_token("42")
    assert decode_access_token(token) == "42"


def test_decode_invalid_token_returns_none() -> None:
    assert decode_access_token("not-a-token") is None
```

- [ ] **Step 2: 运行,确认失败**

```bash
cd backend && uv run pytest tests/test_security.py -v
```

预期:FAIL,`ModuleNotFoundError: No module named 'app.core.security'`。

- [ ] **Step 3: 写 `backend/app/core/security.py`**

```python
from datetime import datetime, timedelta, timezone

import bcrypt
from jose import JWTError, jwt

from app.core.config import get_settings

_MAX_BCRYPT_BYTES = 72


def _encode(raw: str) -> bytes:
    # bcrypt 只取前 72 字节,超出部分先截断,避免抛错
    return raw.encode("utf-8")[:_MAX_BCRYPT_BYTES]


def hash_password(raw: str) -> str:
    """生成 bcrypt 哈希。"""
    return bcrypt.hashpw(_encode(raw), bcrypt.gensalt()).decode("utf-8")


def verify_password(raw: str, hashed: str) -> bool:
    """校验明文与哈希是否匹配。"""
    return bcrypt.checkpw(_encode(raw), hashed.encode("utf-8"))


def create_access_token(subject: str) -> str:
    """签发 JWT,subject 为用户 id。"""
    settings = get_settings()
    expire = datetime.now(timezone.utc) + timedelta(
        minutes=settings.access_token_expire_minutes
    )
    payload = {"sub": subject, "exp": expire}
    return jwt.encode(payload, settings.jwt_secret, algorithm=settings.jwt_algorithm)


def decode_access_token(token: str) -> str | None:
    """解码 JWT,失败返回 None。"""
    settings = get_settings()
    try:
        payload = jwt.decode(
            token, settings.jwt_secret, algorithms=[settings.jwt_algorithm]
        )
    except JWTError:
        return None
    subject = payload.get("sub")
    return subject if isinstance(subject, str) else None
```

- [ ] **Step 4: 运行,确认通过**

```bash
cd backend && uv run pytest tests/test_security.py -v
```

预期:3 passed。

- [ ] **Step 5: 写 `backend/app/schemas/auth.py`**

```python
from pydantic import BaseModel

from app.models.user import UserRole


class RegisterRequest(BaseModel):
    username: str
    password: str
    display_name: str
    major: str | None = None
    grade: str | None = None


class LoginRequest(BaseModel):
    username: str
    password: str


class UserOut(BaseModel):
    id: int
    username: str
    display_name: str
    role: UserRole
    major: str | None
    grade: str | None


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    user: UserOut
```

- [ ] **Step 6: 写 `backend/app/api/v1/deps.py`**

```python
from typing import Annotated

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db import get_session
from app.core.security import decode_access_token
from app.models.user import User

bearer_scheme = HTTPBearer(auto_error=False)

SessionDep = Annotated[AsyncSession, Depends(get_session)]


async def get_current_user(
    session: SessionDep,
    credentials: Annotated[
        HTTPAuthorizationCredentials | None, Depends(bearer_scheme)
    ],
) -> User:
    """从 Bearer Token 解析当前用户。"""
    unauthorized = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED, detail="未认证或登录已过期"
    )
    if credentials is None:
        raise unauthorized
    subject = decode_access_token(credentials.credentials)
    if subject is None:
        raise unauthorized
    user = await session.get(User, int(subject))
    if user is None:
        raise unauthorized
    return user


CurrentUser = Annotated[User, Depends(get_current_user)]
```

- [ ] **Step 7: 写 `backend/app/api/v1/auth.py`**

```python
from fastapi import APIRouter, HTTPException, status
from sqlmodel import select

from app.api.v1.deps import CurrentUser, SessionDep
from app.core.security import create_access_token, hash_password, verify_password
from app.models.user import User
from app.schemas.auth import LoginRequest, RegisterRequest, TokenResponse, UserOut

router = APIRouter(prefix="/auth", tags=["auth"])


def _to_out(user: User) -> UserOut:
    return UserOut(
        id=user.id,
        username=user.username,
        display_name=user.display_name,
        role=user.role,
        major=user.major,
        grade=user.grade,
    )


@router.post("/register", response_model=TokenResponse, summary="注册师范生账号")
async def register(payload: RegisterRequest, session: SessionDep) -> TokenResponse:
    existing = await session.scalar(select(User).where(User.username == payload.username))
    if existing is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail="用户名已存在"
        )
    user = User(
        username=payload.username,
        hashed_password=hash_password(payload.password),
        display_name=payload.display_name,
        major=payload.major,
        grade=payload.grade,
    )
    session.add(user)
    await session.commit()
    await session.refresh(user)
    return TokenResponse(access_token=create_access_token(str(user.id)), user=_to_out(user))


@router.post("/login", response_model=TokenResponse, summary="登录")
async def login(payload: LoginRequest, session: SessionDep) -> TokenResponse:
    user = await session.scalar(select(User).where(User.username == payload.username))
    if user is None or not verify_password(payload.password, user.hashed_password):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="用户名或密码错误"
        )
    return TokenResponse(access_token=create_access_token(str(user.id)), user=_to_out(user))


@router.get("/me", response_model=UserOut, summary="获取当前用户")
async def me(user: CurrentUser) -> UserOut:
    return _to_out(user)
```

- [ ] **Step 8: 挂路由到 `backend/app/main.py`**

在 `app = FastAPI(...)` 之后加入:

```python
from app.api.v1 import auth as auth_api

app.include_router(auth_api.router, prefix="/api/v1")
```

- [ ] **Step 9: 写 `backend/tests/conftest.py`**

```python
"""测试全局配置。

环境变量必须在导入任何 app 模块之前设置:`app.core.db` 在导入时即创建引擎。
pytest 先导入 conftest 再导入测试模块,所以这里放在模块顶层,而不是 fixture 里
(fixture 的执行时机晚于测试模块的导入)。
"""

import os

os.environ["DATABASE_URL"] = os.environ.get(
    "TEST_DATABASE_URL",
    "postgresql+asyncpg://postgres:postgres@localhost:5432/shijiangtai_test",
)
os.environ["JWT_SECRET"] = "test-secret"
```

- [ ] **Step 10: 写 `backend/tests/test_auth_api.py`**

```python
import os

import pytest
from fastapi.testclient import TestClient
from sqlalchemy.ext.asyncio import create_async_engine
from sqlmodel import SQLModel

from app.main import app


@pytest.fixture(scope="module", autouse=True)
def _prepare_db() -> None:
    import asyncio

    import app.models  # noqa: F401  确保所有表被导入
    from app.core.db import engine

    async def _create() -> None:
        async with engine.begin() as conn:
            await conn.run_sync(SQLModel.metadata.drop_all)
            await conn.run_sync(SQLModel.metadata.create_all)

    asyncio.run(_create())


def test_register_then_login_and_me() -> None:
    client = TestClient(app)
    body = {
        "username": "s1",
        "password": "pw123456",
        "display_name": "张三",
        "major": "语文",
        "grade": "2023",
    }
    r = client.post("/api/v1/auth/register", json=body)
    assert r.status_code == 200, r.text
    token = r.json()["access_token"]
    assert r.json()["user"]["major"] == "语文"

    r = client.post(
        "/api/v1/auth/login", json={"username": "s1", "password": "pw123456"}
    )
    assert r.status_code == 200

    r = client.get("/api/v1/auth/me", headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 200
    assert r.json()["username"] == "s1"


def test_register_duplicate_rejected() -> None:
    client = TestClient(app)
    body = {"username": "s1", "password": "pw123456", "display_name": "张三"}
    assert client.post("/api/v1/auth/register", json=body).status_code == 409


def test_me_without_token_rejected() -> None:
    assert TestClient(app).get("/api/v1/auth/me").status_code == 401
```

- [ ] **Step 11: 建测试库并运行**

```bash
docker compose exec db psql -U postgres -c "CREATE DATABASE shijiangtai_test;"
cd backend && uv run pytest tests/test_auth_api.py -v
```

预期:3 passed。

- [ ] **Step 12: 提交**

```bash
git add backend/app/core/security.py backend/app/schemas backend/app/api backend/app/main.py backend/tests
git commit -m "feat(auth): JWT 注册/登录/当前用户接口"
```

---

## Phase 1 — 题目与试讲

### Task 5: 试讲题目接口与种子数据

**Files:**
- Create: `backend/app/schemas/lesson.py`, `backend/app/api/v1/lessons.py`, `backend/app/services/seed.py`
- Modify: `backend/app/main.py`

- [ ] **Step 1: 写 `backend/app/schemas/lesson.py`**

```python
from pydantic import BaseModel


class LessonOut(BaseModel):
    id: int
    title: str
    subject: str
    stage: str
    textbook: str
    content: str
    key_points: list[str]
    suggested_duration_seconds: int
    suggested_segments: dict[str, int]
```

- [ ] **Step 2: 写 `backend/app/api/v1/lessons.py`**

```python
from fastapi import APIRouter, HTTPException, status
from sqlmodel import select

from app.api.v1.deps import CurrentUser, SessionDep
from app.models.lesson import Lesson
from app.schemas.lesson import LessonOut

router = APIRouter(prefix="/lessons", tags=["lessons"])


@router.get("", response_model=list[LessonOut], summary="题目列表")
async def list_lessons(
    session: SessionDep,
    user: CurrentUser,
    subject: str | None = None,
    stage: str | None = None,
) -> list[LessonOut]:
    stmt = select(Lesson)
    if subject:
        stmt = stmt.where(Lesson.subject == subject)
    if stage:
        stmt = stmt.where(Lesson.stage == stage)
    lessons = (await session.scalars(stmt)).all()
    return [LessonOut.model_validate(lesson, from_attributes=True) for lesson in lessons]


@router.get("/{lesson_id}", response_model=LessonOut, summary="题目详情")
async def get_lesson(lesson_id: int, session: SessionDep, user: CurrentUser) -> LessonOut:
    lesson = await session.get(Lesson, lesson_id)
    if lesson is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="题目不存在")
    return LessonOut.model_validate(lesson, from_attributes=True)
```

- [ ] **Step 3: 写 `backend/app/services/seed.py`**

```python
"""灌入试讲题目种子数据。用法:uv run python -m app.services.seed"""

import asyncio

from sqlmodel import select

from app.core.db import SessionLocal
from app.models.lesson import Lesson

_SEGMENTS = {"导入": 60, "新授": 300, "练习": 120, "小结": 60, "作业": 60}

_SEED_LESSONS = [
    {
        "title": "《静夜思》古诗教学",
        "subject": "语文",
        "stage": "小学",
        "textbook": "人教版",
        "content": "以《静夜思》为例,引导学生理解诗意、体会思乡之情。",
        "key_points": ["理解重点字词", "体会诗歌意境", "有感情地朗读"],
        "suggested_duration_seconds": 600,
        "suggested_segments": _SEGMENTS,
    },
    {
        "title": "分数的初步认识",
        "subject": "数学",
        "stage": "小学",
        "textbook": "人教版",
        "content": "通过分物情境,认识几分之一,理解分数的含义。",
        "key_points": ["理解平均分", "认识几分之一", "会读写分数"],
        "suggested_duration_seconds": 600,
        "suggested_segments": _SEGMENTS,
    },
    {
        "title": "Unit 3 My Weekend Plan",
        "subject": "英语",
        "stage": "小学",
        "textbook": "人教版 PEP",
        "content": "围绕周末计划,学习 be going to 句型并做对话练习。",
        "key_points": ["掌握 be going to 结构", "能描述周末计划", "正确发音"],
        "suggested_duration_seconds": 600,
        "suggested_segments": _SEGMENTS,
    },
    {
        "title": "做负责任的人",
        "subject": "思政",
        "stage": "初中",
        "textbook": "统编版",
        "content": "结合生活情境,理解责任的含义,学会对自己的行为负责。",
        "key_points": ["理解责任内涵", "辨析责任冲突", "形成责任意识"],
        "suggested_duration_seconds": 600,
        "suggested_segments": _SEGMENTS,
    },
    {
        "title": "小班健康活动:我会洗手",
        "subject": "幼教",
        "stage": "学前",
        "textbook": "园本课程",
        "content": "通过儿歌与示范,教会幼儿正确的七步洗手法。",
        "key_points": ["记住七步顺序", "养成卫生习惯", "配合儿歌动作"],
        "suggested_duration_seconds": 480,
        "suggested_segments": {"导入": 60, "新授": 240, "练习": 90, "小结": 90},
    },
]


async def seed() -> None:
    async with SessionLocal() as session:
        existing = await session.scalar(select(Lesson).limit(1))
        if existing is not None:
            print("已有题目数据,跳过。")
            return
        session.add_all(Lesson(**item) for item in _SEED_LESSONS)
        await session.commit()
        print(f"已灌入 {len(_SEED_LESSONS)} 条试讲题目。")


if __name__ == "__main__":
    asyncio.run(seed())
```

- [ ] **Step 4: 挂路由**

在 `backend/app/main.py` 的 `app.include_router(auth_api.router, prefix="/api/v1")` 之后加入:

```python
from app.api.v1 import lessons as lessons_api

app.include_router(lessons_api.router, prefix="/api/v1")
```

- [ ] **Step 5: 手工验证**

```bash
cd backend && uv run python -m app.services.seed && uv run uvicorn app.main:app --port 8000 &
```

打开 <http://localhost:8000/docs>,用 `POST /api/v1/auth/register` 拿 token,`GET /api/v1/lessons` 带 Bearer token。预期:返回 5 条题目。验证完停掉 uvicorn。

- [ ] **Step 6: 提交**

```bash
git add backend/app/schemas/lesson.py backend/app/api/v1/lessons.py backend/app/services/seed.py backend/app/main.py
git commit -m "feat(lessons): 题目列表/详情接口与种子数据"
```

---

### Task 6: 试讲接口与录制上传

**Files:**
- Create: `backend/app/core/storage.py`, `backend/app/schemas/attempt.py`, `backend/app/api/v1/attempts.py`
- Modify: `backend/app/main.py`

- [ ] **Step 1: 写 `backend/app/core/storage.py`**

```python
from pathlib import Path

from app.core.config import get_settings


def storage_root() -> Path:
    """录制文件根目录,不存在则创建。"""
    root = Path(get_settings().storage_dir)
    root.mkdir(parents=True, exist_ok=True)
    return root


def save_media(attempt_id: int, filename: str, data: bytes) -> str:
    """落盘并返回相对 storage_root 的路径。"""
    relative = f"attempt_{attempt_id}/{filename}"
    (storage_root() / relative).write_bytes(data)
    return relative
```

- [ ] **Step 2: 写 `backend/app/schemas/attempt.py`**

```python
from pydantic import BaseModel

from app.models.attempt import AttemptMode, AttemptStatus


class AttemptCreateRequest(BaseModel):
    lesson_id: int
    mode: AttemptMode = AttemptMode.free
    student_count: int = 12
    planned_seconds: int = 600


class AttemptOut(BaseModel):
    id: int
    lesson_id: int
    mode: AttemptMode
    status: AttemptStatus
    media_path: str | None
    duration_seconds: float | None
    segment_marks: list[dict]
    classroom_script: list[dict]
    error_message: str | None


class SegmentMarkIn(BaseModel):
    name: str
    at: float


class FinishRequest(BaseModel):
    duration_seconds: float
    segment_marks: list[SegmentMarkIn] = []
```

- [ ] **Step 3: 写 `backend/app/services/classroom.py`**

```python
"""虚拟学生行为脚本生成。纯函数,用 seed 保证可复现。"""

import random

_QUESTIONS = [
    "老师,这个地方我还是不太明白,能再讲一遍吗?",
    "老师,为什么是这样呢?",
    "老师,这个和我们上节课学的有什么关系呀?",
    "老师,可以再举个例子吗?",
]

_ACTIONS = ("raise_hand", "ask_question", "answer_correct", "answer_wrong", "distracted")


def generate_classroom_script(
    student_count: int, duration_seconds: int, seed: int
) -> list[dict]:
    """生成虚拟学生行为时间表,按 at 升序。

    每 45~90 秒产生一次事件;action 为下列之一:
    raise_hand / ask_question / answer_correct / answer_wrong / distracted。
    仅 ask_question 带 tts_text。
    """
    if student_count <= 0 or duration_seconds <= 0:
        return []

    rng = random.Random(seed)
    events: list[dict] = []
    at = rng.uniform(30.0, 60.0)
    while at < duration_seconds:
        action = rng.choice(_ACTIONS)
        event = {
            "at": round(at, 1),
            "student_id": rng.randint(1, student_count),
            "action": action,
            "tts_text": rng.choice(_QUESTIONS) if action == "ask_question" else "",
        }
        events.append(event)
        at += rng.uniform(45.0, 90.0)
    return events
```

- [ ] **Step 4: 写失败测试 `backend/tests/test_classroom.py`**

```python
from app.services.classroom import generate_classroom_script


def test_script_is_deterministic_for_same_seed() -> None:
    assert generate_classroom_script(12, 600, 7) == generate_classroom_script(12, 600, 7)


def test_script_sorted_by_time_and_within_duration() -> None:
    events = generate_classroom_script(10, 300, 1)
    assert events
    times = [e["at"] for e in events]
    assert times == sorted(times)
    assert all(0 <= t < 300 for t in times)


def test_empty_when_invalid_input() -> None:
    assert generate_classroom_script(0, 600, 1) == []
    assert generate_classroom_script(10, 0, 1) == []


def test_ask_question_carries_tts_text() -> None:
    events = generate_classroom_script(12, 3600, 3)
    asks = [e for e in events if e["action"] == "ask_question"]
    assert asks
    assert all(e["tts_text"] for e in asks)
```

- [ ] **Step 5: 运行,确认通过**

```bash
cd backend && uv run pytest tests/test_classroom.py -v
```

预期:4 passed。

- [ ] **Step 6: 写 `backend/app/api/v1/attempts.py`**

```python
from fastapi import APIRouter, HTTPException, UploadFile, status
from sqlmodel import select

from app.api.v1.deps import CurrentUser, SessionDep
from app.core.storage import save_media
from app.models.attempt import Attempt, AttemptStatus
from app.models.lesson import Lesson
from app.schemas.attempt import (
    AttemptCreateRequest,
    AttemptOut,
    FinishRequest,
)
from app.services.classroom import generate_classroom_script

router = APIRouter(prefix="/attempts", tags=["attempts"])


def _to_out(attempt: Attempt) -> AttemptOut:
    return AttemptOut.model_validate(attempt, from_attributes=True)


async def _get_owned(attempt_id: int, user_id: int, session: SessionDep) -> Attempt:
    attempt = await session.get(Attempt, attempt_id)
    if attempt is None or attempt.user_id != user_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="试讲不存在")
    return attempt


@router.post("", response_model=AttemptOut, summary="创建试讲")
async def create_attempt(
    payload: AttemptCreateRequest, session: SessionDep, user: CurrentUser
) -> AttemptOut:
    lesson = await session.get(Lesson, payload.lesson_id)
    if lesson is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="题目不存在")
    script = generate_classroom_script(
        student_count=payload.student_count,
        duration_seconds=payload.planned_seconds,
        seed=payload.lesson_id * 1000 + user.id,
    )
    attempt = Attempt(
        user_id=user.id,
        lesson_id=payload.lesson_id,
        mode=payload.mode,
        classroom_script=script,
    )
    session.add(attempt)
    await session.commit()
    await session.refresh(attempt)
    return _to_out(attempt)


@router.post(
    "/{attempt_id}/media",
    response_model=AttemptOut,
    summary="上传录制文件",
)
async def upload_media(
    attempt_id: int,
    file: UploadFile,
    session: SessionDep,
    user: CurrentUser,
) -> AttemptOut:
    attempt = await _get_owned(attempt_id, user.id, session)
    data = await file.read()
    suffix = (file.filename or "rec.webm").rsplit(".", 1)[-1]
    attempt.media_path = save_media(attempt_id, f"recording.{suffix}", data)
    attempt.media_mime = file.content_type
    attempt.status = AttemptStatus.uploaded
    session.add(attempt)
    await session.commit()
    await session.refresh(attempt)
    return _to_out(attempt)


@router.post(
    "/{attempt_id}/finish",
    response_model=AttemptOut,
    summary="结束试讲并打点,触发评测",
)
async def finish_attempt(
    attempt_id: int,
    payload: FinishRequest,
    session: SessionDep,
    user: CurrentUser,
) -> AttemptOut:
    attempt = await _get_owned(attempt_id, user.id, session)
    if attempt.status != AttemptStatus.uploaded:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail="请先上传录制文件"
        )
    attempt.duration_seconds = payload.duration_seconds
    attempt.segment_marks = [m.model_dump() for m in payload.segment_marks]
    session.add(attempt)
    await session.commit()
    await session.refresh(attempt)
    # 评测流水线在 Task 10 接入
    return _to_out(attempt)


@router.get("/{attempt_id}", response_model=AttemptOut, summary="试讲详情")
async def get_attempt(
    attempt_id: int, session: SessionDep, user: CurrentUser
) -> AttemptOut:
    return _to_out(await _get_owned(attempt_id, user.id, session))


@router.get("", response_model=list[AttemptOut], summary="我的试讲列表")
async def list_attempts(session: SessionDep, user: CurrentUser) -> list[AttemptOut]:
    stmt = select(Attempt).where(Attempt.user_id == user.id).order_by(Attempt.id.desc())
    return [_to_out(a) for a in (await session.scalars(stmt)).all()]
```

- [ ] **Step 7: 挂路由 + 挂载 `/media` 静态目录**

在 `backend/app/main.py` 顶部补 import:

```python
from fastapi.staticfiles import StaticFiles

from app.core.storage import storage_root
from app.api.v1 import attempts as attempts_api
```

在文件末尾(所有 `include_router` 之后)加入:

```python
app.include_router(attempts_api.router, prefix="/api/v1")
app.mount("/media", StaticFiles(directory=storage_root()), name="media")
```

- [ ] **Step 8: 写 `backend/tests/test_attempts_api.py`**

```python
import os

import pytest
from fastapi.testclient import TestClient
from sqlalchemy.ext.asyncio import create_async_engine
from sqlmodel import SQLModel

from app.main import app
from app.models.lesson import Lesson


@pytest.fixture(scope="module", autouse=True)
def _prepare_db() -> None:
    import asyncio

    async def _create() -> None:
        engine = create_async_engine(os.environ["DATABASE_URL"])
        async with engine.begin() as conn:
            await conn.run_sync(SQLModel.metadata.drop_all)
            await conn.run_sync(SQLModel.metadata.create_all)
        await engine.dispose()

    asyncio.run(_create())


def _auth_headers(client: TestClient) -> dict[str, str]:
    body = {"username": "a1", "password": "pw123456", "display_name": "李四"}
    r = client.post("/api/v1/auth/register", json=body)
    if r.status_code == 409:
        r = client.post(
            "/api/v1/auth/login",
            json={"username": "a1", "password": "pw123456"},
        )
    return {"Authorization": f"Bearer {r.json()['access_token']}"}


def test_create_upload_finish_flow() -> None:
    client = TestClient(app)
    headers = _auth_headers(client)

    import asyncio

    from app.core.db import SessionLocal

    async def _add_lesson() -> int:
        async with SessionLocal() as s:
            lesson = Lesson(
                title="测试题", subject="语文", stage="小学",
                textbook="人教版", content="c", key_points=["a"],
                suggested_segments={"导入": 60},
            )
            s.add(lesson)
            await s.commit()
            await s.refresh(lesson)
            return lesson.id

    lesson_id = asyncio.run(_add_lesson())

    r = client.post(
        "/api/v1/attempts",
        json={"lesson_id": lesson_id, "planned_seconds": 300},
        headers=headers,
    )
    assert r.status_code == 200, r.text
    attempt = r.json()
    assert attempt["status"] == "draft"
    assert attempt["classroom_script"]

    r = client.post(
        f"/api/v1/attempts/{attempt['id']}/media",
        files={"file": ("rec.webm", b"fake-bytes", "video/webm")},
        headers=headers,
    )
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "uploaded"

    r = client.post(
        f"/api/v1/attempts/{attempt['id']}/finish",
        json={
            "duration_seconds": 300.0,
            "segment_marks": [{"name": "导入", "at": 60.0}],
        },
        headers=headers,
    )
    assert r.status_code == 200, r.text
    assert r.json()["duration_seconds"] == 300.0
```

- [ ] **Step 9: 运行**

```bash
cd backend && uv run pytest tests/test_attempts_api.py -v
```

预期:PASS。

- [ ] **Step 10: 提交**

```bash
git add backend/app/core/storage.py backend/app/schemas/attempt.py backend/app/services/classroom.py backend/app/api/v1/attempts.py backend/app/main.py backend/tests
git commit -m "feat(attempts): 创建/上传/结束试讲接口与虚拟学生脚本"
```

---

## Phase 2 — 评测流水线

### Task 7: 规则计算 `metrics.py`

**Files:**
- Create: `backend/app/services/asr.py`(先建 `AsrSegment`,供 metrics 复用), `backend/app/services/metrics.py`
- Test: `backend/tests/test_metrics.py`

- [ ] **Step 1: 写 `backend/app/services/asr.py` 的 `AsrSegment`**

本步只写数据类与协议,实现在 Task 8。

```python
from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True)
class AsrSegment:
    """一条带时间戳的转写。"""

    text: str
    start: float
    end: float
    confidence: float = 1.0


class AsrClient(Protocol):
    async def transcribe(self, media_path: str) -> list[AsrSegment]:
        """把音频转成带时间戳的转写段。"""
        ...
```

- [ ] **Step 2: 写失败测试 `backend/tests/test_metrics.py`**

```python
import pytest

from app.services.asr import AsrSegment
from app.services.metrics import (
    count_fillers,
    find_long_pauses,
    speaking_seconds,
    speech_rate,
    split_by_marks,
)


def test_speaking_seconds_sums_segment_durations() -> None:
    segments = [AsrSegment("你好", 0.0, 2.0), AsrSegment("同学们", 3.0, 4.0)]
    assert speaking_seconds(segments) == pytest.approx(3.0)


def test_speech_rate_handles_zero_seconds() -> None:
    assert speech_rate("", 0.0) == 0.0
    assert speech_rate("你好世界", 2.0) == pytest.approx(120.0)


def test_find_long_pauses() -> None:
    segments = [
        AsrSegment("第一句", 0.0, 2.0),
        AsrSegment("第二句", 3.0, 5.0),   # 间隔 1s,不算长停顿
        AsrSegment("第三句", 10.0, 12.0),  # 间隔 5s,算长停顿
    ]
    pauses = find_long_pauses(segments, threshold=3.0)
    assert pauses == [(5.0, 5.0)]


def test_count_fillers() -> None:
    assert count_fillers("嗯,那个,然后我们来看") == {"嗯": 1, "那个": 1, "然后": 1}


def test_split_by_marks() -> None:
    segments = [
        AsrSegment("导入部分", 0.0, 30.0),
        AsrSegment("新授部分", 70.0, 200.0),
    ]
    marks = [{"name": "导入", "at": 0.0}, {"name": "新授", "at": 60.0}]
    split = split_by_marks(segments, marks, total_duration=300.0)
    assert [s.text for s in split["导入"]] == ["导入部分"]
    assert [s.text for s in split["新授"]] == ["新授部分"]
```

- [ ] **Step 3: 运行,确认失败**

```bash
cd backend && uv run pytest tests/test_metrics.py -v
```

预期:FAIL,`ModuleNotFoundError: No module named 'app.services.metrics'`。

- [ ] **Step 4: 写 `backend/app/services/metrics.py`**

```python
"""规则计算:教学表达与时间管理。全部为纯函数,便于单测。"""

from app.services.asr import AsrSegment

LONG_PAUSE_SECONDS = 3.0
FILLER_WORDS = ("嗯", "那个", "就是", "然后", "呃", "这个")
SPEECH_RATE_MIN = 150.0   # 字/分钟,低于此偏慢
SPEECH_RATE_MAX = 320.0   # 字/分钟,高于此偏快


def speaking_seconds(segments: list[AsrSegment]) -> float:
    """所有转写段的语音时长之和(秒)。"""
    return sum(max(0.0, s.end - s.start) for s in segments)


def speech_rate(text: str, seconds: float) -> float:
    """语速(字/分钟)。seconds<=0 返回 0。"""
    if seconds <= 0:
        return 0.0
    return len(text) / seconds * 60.0


def find_long_pauses(
    segments: list[AsrSegment], threshold: float = LONG_PAUSE_SECONDS
) -> list[tuple[float, float]]:
    """相邻段的静默间隔 > threshold 时,返回 (静默起点, 时长)。"""
    pauses: list[tuple[float, float]] = []
    for prev, nxt in zip(segments, segments[1:]):
        gap = nxt.start - prev.end
        if gap > threshold:
            pauses.append((round(prev.end, 2), round(gap, 2)))
    return pauses


def count_fillers(text: str, fillers: tuple[str, ...] = FILLER_WORDS) -> dict[str, int]:
    """统计口头禅词频,只保留出现过的词。"""
    return {word: text.count(word) for word in fillers if text.count(word) > 0}


def split_by_marks(
    segments: list[AsrSegment], marks: list[dict], total_duration: float
) -> dict[str, list[AsrSegment]]:
    """按 marks 的 at 时间点把转写段切分到各环节。

    marks 需含 name/at;按 at 升序,第一个标记视作 0 时刻起点。
    """
    if not marks:
        return {}
    ordered = sorted(marks, key=lambda m: float(m["at"]))
    names = [m["name"] for m in ordered]
    bounds = [0.0] + [float(m["at"]) for m in ordered[1:]] + [total_duration]

    result: dict[str, list[AsrSegment]] = {name: [] for name in names}
    for segment in segments:
        for i, name in enumerate(names):
            if bounds[i] <= segment.start < bounds[i + 1]:
                result[name].append(segment)
                break
    return result


def segment_durations(total_duration: float, marks: list[dict]) -> dict[str, float]:
    """各环节实际时长:按标记边界切分总时长。"""
    if not marks:
        return {}
    ordered = sorted(marks, key=lambda m: float(m["at"]))
    names = [m["name"] for m in ordered]
    bounds = [0.0] + [float(m["at"]) for m in ordered[1:]] + [total_duration]
    return {
        name: round(bounds[i + 1] - bounds[i], 2) for i, name in enumerate(names)
    }


def segment_deviations(
    actual: dict[str, float], suggested: dict[str, int]
) -> dict[str, dict]:
    """各环节实际 vs 建议时长的偏差比例。"""
    deviations: dict[str, dict] = {}
    for name, seconds in actual.items():
        expected = suggested.get(name)
        if not expected:
            continue
        deviations[name] = {
            "actual": seconds,
            "suggested": expected,
            "ratio": round(seconds / expected, 2),
        }
    return deviations


def build_expression_report(segments: list[AsrSegment]) -> dict:
    """教学表达维度的原始指标。"""
    text = "".join(s.text for s in segments)
    seconds = speaking_seconds(segments)
    rate = speech_rate(text, seconds)
    fillers = count_fillers(text)
    return {
        "speech_rate": round(rate, 1),
        "speech_rate_in_range": SPEECH_RATE_MIN <= rate <= SPEECH_RATE_MAX,
        "long_pauses": find_long_pauses(segments),
        "filler_counts": fillers,
        "filler_total": sum(fillers.values()),
        "filler_per_minute": round(sum(fillers.values()) / (seconds / 60), 2)
        if seconds > 0
        else 0.0,
        "speaking_seconds": round(seconds, 2),
    }


def build_time_report(
    marks: list[dict],
    total_duration: float,
    suggested_segments: dict[str, int],
    suggested_total: int,
) -> dict:
    """时间管理维度的原始指标。"""
    durations = segment_durations(total_duration, marks)
    return {
        "segment_durations": durations,
        "deviations": segment_deviations(durations, suggested_segments),
        "total_duration": round(total_duration, 2),
        "suggested_total": suggested_total,
        "over_time": total_duration > suggested_total * 1.1,
        "too_short": total_duration < suggested_total * 0.9,
    }
```

- [ ] **Step 5: 运行,确认通过**

```bash
cd backend && uv run pytest tests/test_metrics.py -v
```

预期:5 passed。

- [ ] **Step 6: 提交**

```bash
git add backend/app/services/asr.py backend/app/services/metrics.py backend/tests/test_metrics.py
git commit -m "feat(metrics): 语速/停顿/口头禅/环节时长规则计算"
```

---

### Task 8: ASR 适配层与 Fake 实现

**Files:**
- Modify: `backend/app/services/asr.py`
- Test: `backend/tests/test_asr.py`

- [ ] **Step 1: 写失败测试 `backend/tests/test_asr.py`**

```python
import pytest

from app.services.asr import FakeAsrClient, get_asr_client


@pytest.mark.asyncio
async def test_fake_asr_returns_timestamped_segments() -> None:
    segments = await FakeAsrClient().transcribe("storage/whatever.webm")
    assert len(segments) >= 5
    assert all(s.end > s.start for s in segments)
    times = [s.start for s in segments]
    assert times == sorted(times)
    assert "".join(s.text for s in segments).strip()


def test_factory_returns_fake_by_default() -> None:
    assert isinstance(get_asr_client(), FakeAsrClient)
```

- [ ] **Step 2: 运行,确认失败**

```bash
cd backend && uv run pytest tests/test_asr.py -v
```

预期:FAIL,`ImportError: cannot import name 'FakeAsrClient'`。

- [ ] **Step 3: 实现 `backend/app/services/asr.py`(追加到已有内容后)**

在文件末尾追加:

```python
from app.core.config import get_settings

# 一段合成的示例试讲转写,用于开发 / 测试 / 演示(Fake ASR)。
_FIXTURE: list[tuple[str, float]] = [
    ("同学们好,上课。今天我们一起学习《静夜思》。", 3.0),
    ("嗯,那个,请大家先自己读一遍这首诗。", 6.0),
    ("好,读完了,我们来看题目。", 4.0),
    ("然后我们来理解一下,这个'疑是地上霜'是什么意思呢?", 7.0),
    ("有的同学说是地上的霜,对,就是月光像霜一样白。", 7.0),
    ("那么,诗人为什么要这样写呢?", 4.0),
    ("这个是本节课的重点,请大家记一下。", 5.0),
    ("好,我们做个练习,把这首诗背一遍。", 5.0),
    ("最后小结一下,这节课我们体会了诗人的思乡之情。", 6.0),
    ("作业是背诵并默写这首诗,下课。", 4.0),
]


class FakeAsrClient:
    """确定性 Fake:忽略音频内容,返回一段合成转写。"""

    async def transcribe(self, media_path: str) -> list[AsrSegment]:
        segments: list[AsrSegment] = []
        cursor = 0.0
        for text, duration in _FIXTURE:
            segments.append(
                AsrSegment(
                    text=text,
                    start=round(cursor, 2),
                    end=round(cursor + duration, 2),
                    confidence=0.95,
                )
            )
            cursor += duration + 1.5  # 句间 1.5s 间隔
        return segments


def get_asr_client() -> AsrClient:
    """按配置返回 ASR 客户端。新增厂商时在此注册分支。"""
    provider = get_settings().asr_provider
    if provider == "fake":
        return FakeAsrClient()
    raise ValueError(f"未支持的 ASR_PROVIDER: {provider}")
```

- [ ] **Step 4: 运行,确认通过**

```bash
cd backend && uv run pytest tests/test_asr.py -v
```

预期:2 passed。

- [ ] **Step 5: 提交**

```bash
git add backend/app/services/asr.py backend/tests/test_asr.py
git commit -m "feat(asr): 定义 AsrClient 协议与 Fake 实现"
```

---

### Task 9: LLM 客户端与评测图

**Files:**
- Create: `backend/app/agents/__init__.py`, `backend/app/agents/llm.py`, `backend/app/agents/evaluation_graph.py`
- Test: `backend/tests/test_evaluation_graph.py`

- [ ] **Step 1: 写 `backend/app/agents/llm.py`**

```python
from functools import lru_cache

from langchain_openai import ChatOpenAI

from app.core.config import get_settings


@lru_cache
def get_chat_model() -> ChatOpenAI:
    """DeepSeek(OpenAI 兼容端点)客户端。"""
    settings = get_settings()
    return ChatOpenAI(
        model=settings.deepseek_model,
        base_url=settings.deepseek_base_url,
        api_key=settings.deepseek_api_key,
        temperature=0.2,
    )
```

- [ ] **Step 2: 写失败测试 `backend/tests/test_evaluation_graph.py`**

用一个可注入的 fake model,不调真实 API。

```python
import json

import pytest

from app.agents.evaluation_graph import (
    EVALUATION_DIMENSIONS,
    run_evaluation,
    score_content,
)


class _FakeMessage:
    def __init__(self, content: str) -> None:
        self.content = content


class _FakeModel:
    """按维度返回固定 JSON。"""

    async def ainvoke(self, messages):  # noqa: ANN001, ANN201
        payload = {
            "scores": {dim: 80 for dim in EVALUATION_DIMENSIONS},
            "suggestions": [
                {"at": 12.0, "issue": "语速偏快", "advice": "放慢一点,注意停顿"}
            ],
            "summary": "整体表达流畅,建议加强互动。",
        }
        return _FakeMessage(json.dumps(payload, ensure_ascii=False))


@pytest.mark.asyncio
async def test_run_evaluation_returns_scores_and_suggestions() -> None:
    state = {
        "lesson_title": "《静夜思》古诗教学",
        "key_points": ["理解重点字词"],
        "transcript_text": "同学们好,今天我们学习静夜思。",
        "segment_marks": [{"name": "导入", "at": 0.0}],
        "suggested_segments": {"导入": 60},
        "expression": {"speech_rate": 200.0, "filler_total": 1},
        "time_report": {"over_time": False},
        "total_duration": 300.0,
    }
    result = await run_evaluation(state, model=_FakeModel())
    assert result["dimensions"]["content"]["知识点准确性"] == 80
    assert result["dimensions"]["content"]["逻辑清晰度"] == 80
    assert result["suggestions"][0]["issue"] == "语速偏快"
    assert result["total_score"] > 0
    assert result["summary"]


def test_unknown_dimension_score_is_rejected() -> None:
    with pytest.raises(ValueError):
        score_content({"scores": {"不存在": 90}}, EVALUATION_DIMENSIONS)
```

- [ ] **Step 3: 运行,确认失败**

```bash
cd backend && uv run pytest tests/test_evaluation_graph.py -v
```

预期:FAIL,`ModuleNotFoundError: No module named 'app.agents.evaluation_graph'`。

- [ ] **Step 4: 写 `backend/app/agents/evaluation_graph.py`**

```python
"""教学内容评测图。三个纯节点:结构化 -> 评分与建议 -> 汇总。"""

import json

from langchain_core.messages import HumanMessage, SystemMessage

from app.agents.llm import get_chat_model

EVALUATION_DIMENSIONS = (
    "知识点准确性",
    "逻辑清晰度",
    "重难点把握",
    "语言规范性",
    "讲解完整度",
)

# 教学内容维度在总分中的权重(教学表达 / 时间管理由规则分补齐,见 services/evaluation.py)
CONTENT_WEIGHT = 0.6

_SYSTEM_PROMPT = (
    "你是一位资深的师范专业教学法专家。"
    "请依据给出的试讲转写与教案要点,对教学内容做多维度评分(0-100 整数),"
    "并给出问题定位与优化话术。"
    "只输出 JSON,格式为:"
    '{"scores": {"<维度>": <整数>}, '
    '"suggestions": [{"at": <秒>, "issue": "<问题>", "advice": "<优化话术>"}], '
    '"summary": "<总评>"}。'
    f"维度必须且仅有:{', '.join(EVALUATION_DIMENSIONS)}。"
)


def _build_user_prompt(state: dict) -> str:
    return (
        f"试讲题目:{state['lesson_title']}\n"
        f"教案要点:{', '.join(state['key_points'])}\n"
        f"试讲转写:\n{state['transcript_text']}\n"
    )


def score_content(raw: dict, dimensions: tuple[str, ...]) -> dict[str, int]:
    """校验并归一化 LLM 返回的维度分。"""
    scores = raw.get("scores", {})
    unknown = set(scores) - set(dimensions)
    if unknown:
        raise ValueError(f"未知维度: {sorted(unknown)}")
    missing = set(dimensions) - set(scores)
    if missing:
        raise ValueError(f"缺少维度: {sorted(missing)}")
    return {dim: int(scores[dim]) for dim in dimensions}


async def run_evaluation(state: dict, model=None) -> dict:
    """执行评测图,返回 dimensions / suggestions / summary / total_score。

    model 可注入(测试用 fake);默认取 DeepSeek 客户端。
    """
    # 节点 1:结构化 —— 整理输入(此处按环节标记切段,信息已在 state 中)
    structured = {
        "lesson_title": state["lesson_title"],
        "key_points": state["key_points"],
        "transcript_text": state["transcript_text"],
    }

    # 节点 2:评分 + 建议
    chat = model or get_chat_model()
    messages = [
        SystemMessage(content=_SYSTEM_PROMPT),
        HumanMessage(content=_build_user_prompt({**structured, **state})),
    ]
    response = await chat.ainvoke(messages)
    payload = json.loads(response.content)
    content_scores = score_content(payload, EVALUATION_DIMENSIONS)

    # 节点 3:汇总 —— 教学内容分 + 规则分(表达 / 时间)加权
    expression_score = _expression_score(state.get("expression", {}))
    time_score = _time_score(state.get("time_report", {}))
    total = round(
        _mean(content_scores.values()) * CONTENT_WEIGHT
        + expression_score * 0.25
        + time_score * 0.15,
        1,
    )

    return {
        "dimensions": {
            "content": content_scores,
            "expression": {
                "得分": expression_score,
                **state.get("expression", {}),
            },
            "time": {"得分": time_score, **state.get("time_report", {})},
        },
        "suggestions": payload.get("suggestions", []),
        "summary": payload.get("summary", ""),
        "total_score": total,
    }


def _mean(values) -> float:  # noqa: ANN001
    values = list(values)
    return sum(values) / len(values) if values else 0.0


def _expression_score(expression: dict) -> float:
    """教学表达规则分:满分 100,按偏离项扣分。"""
    score = 100.0
    rate = expression.get("speech_rate")
    if rate is not None and not expression.get("speech_rate_in_range", True):
        score -= 15.0
    score -= min(20.0, float(expression.get("filler_total", 0)) * 2.0)
    score -= min(15.0, len(expression.get("long_pauses", [])) * 3.0)
    return round(max(0.0, score), 1)


def _time_score(time_report: dict) -> float:
    """时间管理规则分:满分 100,超时/过短与环节偏差扣分。"""
    score = 100.0
    if time_report.get("over_time") or time_report.get("too_short"):
        score -= 20.0
    for item in (time_report.get("deviations") or {}).values():
        if item.get("ratio", 1.0) > 1.5 or item.get("ratio", 1.0) < 0.5:
            score -= 8.0
    return round(max(0.0, score), 1)
```

- [ ] **Step 5: 建空 `backend/app/agents/__init__.py`**

- [ ] **Step 6: 运行,确认通过**

```bash
cd backend && uv run pytest tests/test_evaluation_graph.py -v
```

预期:2 passed。

- [ ] **Step 7: 提交**

```bash
git add backend/app/agents backend/tests/test_evaluation_graph.py
git commit -m "feat(evaluation): LangGraph 教学内容评测图与维度加权"
```

---

### Task 10: 评测流水线编排与后台任务

**Files:**
- Create: `backend/app/services/evaluation.py`
- Modify: `backend/app/api/v1/attempts.py`(finish 触发后台任务), `backend/app/main.py`(启动重置), `backend/app/schemas/evaluation.py`, `backend/app/api/v1/evaluations.py`
- Test: `backend/tests/test_evaluation_pipeline.py`

- [ ] **Step 1: 写失败测试 `backend/tests/test_evaluation_pipeline.py`**

用 FakeAsrClient 与 fake model,验证流水线把 attempt 推到 done 并落报告。

```python
import asyncio
import json
import os

import pytest
from sqlalchemy.ext.asyncio import create_async_engine
from sqlmodel import SQLModel, select

from app.core.db import SessionLocal
from app.models.attempt import Attempt, AttemptStatus
from app.models.evaluation import Evaluation
from app.models.lesson import Lesson
from app.models.user import User
from app.services.asr import FakeAsrClient


@pytest.fixture(scope="module", autouse=True)
def _prepare_db() -> None:
    async def _create() -> None:
        engine = create_async_engine(os.environ["DATABASE_URL"])
        async with engine.begin() as conn:
            await conn.run_sync(SQLModel.metadata.drop_all)
            await conn.run_sync(SQLModel.metadata.create_all)
        await engine.dispose()

    asyncio.run(_create())


async def _seed_attempt() -> int:
    async with SessionLocal() as s:
        user = User(username="p1", hashed_password="x", display_name="王五")
        lesson = Lesson(
            title="《静夜思》古诗教学", subject="语文", stage="小学",
            textbook="人教版", content="c", key_points=["理解重点字词"],
            suggested_duration_seconds=600,
            suggested_segments={"导入": 60, "新授": 300},
        )
        s.add(user)
        s.add(lesson)
        await s.commit()
        await s.refresh(user)
        await s.refresh(lesson)
        attempt = Attempt(
            user_id=user.id, lesson_id=lesson.id,
            status=AttemptStatus.uploaded, media_path="x.webm",
            duration_seconds=600.0,
            segment_marks=[{"name": "导入", "at": 0.0}, {"name": "新授", "at": 60.0}],
        )
        s.add(attempt)
        await s.commit()
        await s.refresh(attempt)
        return attempt.id


class _FakeModel:
    class _Msg:
        def __init__(self, content: str) -> None:
            self.content = content

    async def ainvoke(self, messages):  # noqa: ANN001, ANN201
        from app.agents.evaluation_graph import EVALUATION_DIMENSIONS

        payload = {
            "scores": {d: 75 for d in EVALUATION_DIMENSIONS},
            "suggestions": [{"at": 5.0, "issue": "互动不足", "advice": "多点名提问"}],
            "summary": "整体尚可。",
        }
        return self._Msg(json.dumps(payload, ensure_ascii=False))


def test_pipeline_marks_done_and_writes_evaluation(monkeypatch) -> None:
    from app.agents import evaluation_graph
    from app.services import evaluation as pipeline

    async def _fake_run(state, model=None):  # noqa: ANN001
        return await evaluation_graph.run_evaluation(state, model=_FakeModel())

    monkeypatch.setattr(pipeline, "run_evaluation", _fake_run)

    attempt_id = asyncio.run(_seed_attempt())
    asyncio.run(pipeline.run_pipeline(attempt_id, FakeAsrClient()))

    async def _check() -> None:
        async with SessionLocal() as s:
            attempt = await s.get(Attempt, attempt_id)
            assert attempt.status == AttemptStatus.done
            report = await s.scalar(
                select(Evaluation).where(Evaluation.attempt_id == attempt_id)
            )
            assert report is not None
            assert report.total_score > 0
            assert report.suggestions

    asyncio.run(_check())
```

- [ ] **Step 2: 运行,确认失败**

```bash
cd backend && uv run pytest tests/test_evaluation_pipeline.py -v
```

预期:FAIL,`ImportError: cannot import name 'run_pipeline'`。

- [ ] **Step 3: 写 `backend/app/schemas/evaluation.py`**

```python
from pydantic import BaseModel


class EvaluationOut(BaseModel):
    attempt_id: int
    dimensions: dict
    total_score: float
    suggestions: list[dict]
    summary: str
```

- [ ] **Step 4: 写 `backend/app/services/evaluation.py`**

```python
"""评测流水线编排:ASR -> 规则计算 -> 评测图 -> 落库。"""

from sqlmodel import select

from app.agents.evaluation_graph import run_evaluation
from app.core.db import SessionLocal
from app.models.attempt import Attempt, AttemptStatus
from app.models.evaluation import Evaluation
from app.models.lesson import Lesson
from app.models.transcript import Transcript
from app.services.asr import AsrClient
from app.services.metrics import build_expression_report, build_time_report

CONTENT_W_MARK = 0.6


async def run_pipeline(attempt_id: int, asr: AsrClient) -> None:
    """执行一次完整评测。异常时把 attempt 置为 failed。"""
    async with SessionLocal() as session:
        attempt = await session.get(Attempt, attempt_id)
        if attempt is None:
            return
        try:
            lesson = await session.get(Lesson, attempt.lesson_id)
            assert lesson is not None

            attempt.status = AttemptStatus.transcribing
            session.add(attempt)
            await session.commit()

            segments = await asr.transcribe(attempt.media_path or "")
            session.add(
                Transcript(
                    attempt_id=attempt_id,
                    segments=[
                        {
                            "text": s.text,
                            "start": s.start,
                            "end": s.end,
                            "confidence": s.confidence,
                        }
                        for s in segments
                    ],
                    full_text="".join(s.text for s in segments),
                )
            )

            attempt.status = AttemptStatus.evaluating
            session.add(attempt)
            await session.commit()

            transcript_text = "".join(s.text for s in segments)
            expression = build_expression_report(segments)
            time_report = build_time_report(
                marks=attempt.segment_marks,
                total_duration=attempt.duration_seconds or 0.0,
                suggested_segments=lesson.suggested_segments,
                suggested_total=lesson.suggested_duration_seconds,
            )

            result = await run_evaluation(
                {
                    "lesson_title": lesson.title,
                    "key_points": lesson.key_points,
                    "transcript_text": transcript_text,
                    "segment_marks": attempt.segment_marks,
                    "suggested_segments": lesson.suggested_segments,
                    "expression": expression,
                    "time_report": time_report,
                    "total_duration": attempt.duration_seconds or 0.0,
                }
            )

            existing = await session.scalar(
                select(Evaluation).where(Evaluation.attempt_id == attempt_id)
            )
            if existing is None:
                session.add(
                    Evaluation(
                        attempt_id=attempt_id,
                        dimensions=result["dimensions"],
                        total_score=result["total_score"],
                        suggestions=result["suggestions"],
                        summary=result["summary"],
                    )
                )
            else:
                existing.dimensions = result["dimensions"]
                existing.total_score = result["total_score"]
                existing.suggestions = result["suggestions"]
                existing.summary = result["summary"]
                session.add(existing)

            attempt.status = AttemptStatus.done
            session.add(attempt)
            await session.commit()
        except Exception as exc:  # 流水线失败必须可见,不能静默
            await session.rollback()
            attempt = await session.get(Attempt, attempt_id)
            if attempt is not None:
                attempt.status = AttemptStatus.failed
                attempt.error_message = f"{type(exc).__name__}: {exc}"
                session.add(attempt)
                await session.commit()


async def reset_stuck_attempts() -> None:
    """启动时把中间态重置为 failed,避免进程重启后卡住。"""
    async with SessionLocal() as session:
        stuck_statuses = (
            AttemptStatus.transcribing,
            AttemptStatus.evaluating,
        )
        stmt = select(Attempt).where(Attempt.status.in_(stuck_statuses))
        for attempt in (await session.scalars(stmt)).all():
            attempt.status = AttemptStatus.failed
            attempt.error_message = "评测被服务重启中断,请重试"
            session.add(attempt)
        await session.commit()
```

- [ ] **Step 5: 运行,确认通过**

```bash
cd backend && uv run pytest tests/test_evaluation_pipeline.py -v
```

预期:PASS(测试注入 FakeAsrClient 与 fake model,不打真实 API)。

- [ ] **Step 6: 在 `finish` 接口触发后台任务**

修改 `backend/app/api/v1/attempts.py`:顶部补 import

```python
from fastapi import BackgroundTasks

from app.services.asr import get_asr_client
from app.services.evaluation import run_pipeline
```

把 `finish_attempt` 签名改为(加 `background_tasks: BackgroundTasks` 参数):

```python
@router.post(
    "/{attempt_id}/finish",
    response_model=AttemptOut,
    summary="结束试讲并打点,触发评测",
)
async def finish_attempt(
    attempt_id: int,
    payload: FinishRequest,
    background_tasks: BackgroundTasks,
    session: SessionDep,
    user: CurrentUser,
) -> AttemptOut:
    attempt = await _get_owned(attempt_id, user.id, session)
    if attempt.status != AttemptStatus.uploaded:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail="请先上传录制文件"
        )
    attempt.duration_seconds = payload.duration_seconds
    attempt.segment_marks = [m.model_dump() for m in payload.segment_marks]
    session.add(attempt)
    await session.commit()
    await session.refresh(attempt)

    background_tasks.add_task(run_pipeline, attempt.id, get_asr_client())
    return _to_out(attempt)
```

- [ ] **Step 7: 写 `backend/app/api/v1/evaluations.py`**

```python
from fastapi import APIRouter, HTTPException, status
from sqlmodel import select

from app.api.v1.deps import CurrentUser, SessionDep
from app.models.attempt import Attempt
from app.models.evaluation import Evaluation
from app.schemas.evaluation import EvaluationOut

router = APIRouter(prefix="/attempts", tags=["evaluations"])


@router.get(
    "/{attempt_id}/evaluation",
    response_model=EvaluationOut,
    summary="获取评测报告",
)
async def get_evaluation(
    attempt_id: int, session: SessionDep, user: CurrentUser
) -> EvaluationOut:
    attempt = await session.get(Attempt, attempt_id)
    if attempt is None or attempt.user_id != user.id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="试讲不存在")
    report = await session.scalar(
        select(Evaluation).where(Evaluation.attempt_id == attempt_id)
    )
    if report is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="评测报告尚未生成"
        )
    return EvaluationOut.model_validate(report, from_attributes=True)
```

- [ ] **Step 8: 启动时重置卡住的评测**

修改 `backend/app/main.py`,在文件末尾加入:

```python
from contextlib import asynccontextmanager
```

并改为使用 lifespan(把 `app = FastAPI(...)` 前加 lifespan 定义):

```python
from app.services.evaluation import reset_stuck_attempts


@asynccontextmanager
async def lifespan(_: FastAPI):
    await reset_stuck_attempts()
    yield


app = FastAPI(title="师范 AI 试讲台 API", version="0.1.0", lifespan=lifespan)
```

并挂 evaluations 路由:

```python
from app.api.v1 import evaluations as evaluations_api

app.include_router(evaluations_api.router, prefix="/api/v1")
```

- [ ] **Step 9: 运行全部测试**

```bash
cd backend && uv run pytest -v
```

预期:全绿。

- [ ] **Step 10: 提交**

```bash
git add backend/app/services/evaluation.py backend/app/schemas/evaluation.py \
  backend/app/api/v1 backend/app/main.py backend/tests/test_evaluation_pipeline.py
git commit -m "feat(evaluation): 评测流水线、后台任务接线与报告接口"
```

---

## 后端完成标准

```bash
cd backend && uv run pytest -v
```

全绿,且以下接口在 `/docs` 可用:

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/health` | 健康检查 |
| POST | `/api/v1/auth/register` | 注册 |
| POST | `/api/v1/auth/login` | 登录 |
| GET | `/api/v1/auth/me` | 当前用户 |
| GET | `/api/v1/lessons` | 题目列表 |
| GET | `/api/v1/lessons/{id}` | 题目详情 |
| POST | `/api/v1/attempts` | 创建试讲 |
| POST | `/api/v1/attempts/{id}/media` | 上传录制 |
| POST | `/api/v1/attempts/{id}/finish` | 结束并触发评测 |
| GET | `/api/v1/attempts` | 我的试讲列表 |
| GET | `/api/v1/attempts/{id}` | 试讲详情 |
| GET | `/api/v1/attempts/{id}/evaluation` | 评测报告 |
| GET | `/media/...` | 录制文件 |

## 已知限制(演示前必读)

- `ASR_PROVIDER=fake` 时,转写来自合成文本而非真实录音 —— **评测报告的「教学内容」分基于示例讲稿**。要评测真实语音,需接入云端 ASR(在 `app/services/asr.py` 的 `get_asr_client()` 注册新分支)。
- 虚拟学生语音由前端 Web SpeechSynthesis 播放,不在后端。
