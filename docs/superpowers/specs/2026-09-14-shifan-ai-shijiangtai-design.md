# 师范专业 AI 试讲台系统 — 设计文档

- 日期:2026-09-14
- 状态:已确认(设计阶段)
- 范围:产品定位、MVP 范围、技术栈、架构、数据模型、评测设计、项目结构、风险。本阶段**不实现业务代码**。
- 取代:「考研 AI 导学系统」(本项目前身,已废弃;原设计文档已删除)

## 1. 背景与定位

**产品定位**:面向师范类专业(师范生)的 AI 虚拟试讲训练与考核平台,用「虚拟学生 + AI 多维度评测」替代 / 辅助真实课堂试讲,实现课前磨课、技能训练、教学评价、达标认证一体化。

**适用场景**:微格教学、教师资格证面试训练、教学技能大赛备赛、课堂实训。

**规模问题**:原始需求含 9 大模块(身份课程管理、AI 虚拟课堂、录制直播、多维度评测、即时反馈、教材题库、训练阶梯、教师后台、达标认证),各模块是独立子系统,基础设施需求差异大,不适合作为单个实现计划推进。因此本文档只覆盖 **MVP 一条主闭环**,其余模块列为后续阶段。

**MVP 定位:可演示的课程 / 毕设项目**。允许重型管线(虚拟学生、语音评测)用脚本化 / 近似方案,核心目标是跑通闭环、能演示。

**已确认决策**:

| 决策点 | 结论 |
|---|---|
| 交付目标 | 可演示的课程 / 毕设 MVP |
| MVP 核心闭环 | 试讲课堂 → 语音评测 → 报告 |
| 虚拟学生互动 | 脚本化交互(TTS 语音 + 预设时间点举手 / 提问) |
| 评测维度 | 可计算的:教学内容(LLM)+ 教学表达 + 时间管理(规则计算) |
| ASR | 云端 API(厂商待定) |
| 录制范围 | 音频 + 摄像头 |
| 课堂布局 | B · 三栏工作台 |
| 后端架构 | 方案 A — 模块化单体 + 进程内异步评测 |
| LLM 编排 | LangGraph 评测图 |
| 数据库 | PostgreSQL 16(不使用 pgvector) |

## 2. 现有依赖盘点

仓库当前是空壳,业务代码为零。

**已有**:
- `backend/`:`fastapi 0.141`、`langgraph 1.2`、`langchain-core 1.6`、`langsmith`、`sqlmodel`、`sqlalchemy 2.0`、`pydantic 2.13`、`httpx`
- `frontend/`:`vue 3.5`、`vite 8`、`typescript 6`、`vue-tsc`、`@vitejs/plugin-vue`

**缺口**:见第 3 节「待加」列。

## 3. 技术栈

### 后端

| 层 | 选型 | 状态 |
|---|---|---|
| 语言 / 包管理 | Python 3.12 + uv | 已有 |
| Web 框架 | FastAPI 0.141 | 已有 |
| ASGI 服务器 | Uvicorn | 待加 |
| LLM 编排 | LangGraph 1.2(评测图) | 已有 |
| 大模型 | DeepSeek(OpenAI 兼容,经 `langchain-openai`) | 待加 |
| 可观测 | LangSmith tracing | 已有 |
| ORM | SQLModel + SQLAlchemy 2.0(async) | 已有 |
| 数据库 | PostgreSQL 16(Docker,不使用 pgvector) | 待加 |
| DB 驱动 | asyncpg | 待加 |
| 迁移 | Alembic | 待加 |
| 鉴权 | python-jose + bcrypt | 待加 |
| 配置 | pydantic-settings | 待加 |
| 文件上传 | python-multipart | 待加 |
| 后台任务 | FastAPI `BackgroundTasks`(内置) | — |
| ASR | 适配层 + `FakeAsrClient`(云端厂商待定) | 待加 |

### 前端

| 层 | 选型 | 状态 |
|---|---|---|
| 框架 / 构建 | Vue 3.5 + TS 6 + Vite 8 | 已有 |
| 路由 / 状态 | Vue Router + Pinia | 待加 |
| UI 组件库 | Element Plus(自动导入) | 待加 |
| 图表 | ECharts(`vue-echarts`,雷达图) | 待加 |
| HTTP | axios | 待加 |
| 录制 | 浏览器原生 `MediaRecorder`(零依赖) | — |
| 虚拟学生语音 | 浏览器 Web SpeechSynthesis(云端 TTS 待厂商确定后替换) | — |
| 波形回放 | wavesurfer.js(可选) | 待定 |

### 基础设施

- **Docker Compose**:仅用于拉起 `postgres:16`(不带 pgvector)。
- **本地文件存储**:录制音视频落 `backend/storage/`(运行时目录,gitignore)。
- **环境要求**:Python 3.12、uv、Node ≥ 20.19 或 ≥ 22.12(Vite 8 硬性要求)、Docker Desktop。

## 4. 架构

### 4.1 后端:模块化单体 + 进程内异步评测(方案 A)

- 单个 FastAPI 应用,按业务域分模块(`auth` / `lessons` / `attempts` / `evaluations`)。
- 评测流水线作为 `BackgroundTasks` 在进程内运行,不引入 Celery / Redis。
- LangGraph 评测图位于 `agents/`,被 `services/` 调用,不感知 HTTP。
- **选型理由**:与现有骨架零冲突、依赖最少、单机可跑、毕设易交付;单用户演示场景下单进程并发不是瓶颈。
- **未采纳**:方案 B(单体 + Celery + Redis,多两个基础设施组件,属过度设计)、方案 C(评测拆微服务,过早拆分、联调成本高)。

### 4.2 依赖方向

```
api/v1  →  services  →  agents(LangGraph 评测图)
                    ↘  models / core(config, db, security, storage)
```

单向依赖,禁止反向。

`services/` 细分职责:

| 模块 | 职责 |
|---|---|
| `asr.py` | ASR 适配层(`AsrClient` 协议 + `FakeAsrClient` + 工厂) |
| `metrics.py` | 规则计算(语速 / 停顿 / 口头禅 / 环节时长),**纯函数、可单测** |
| `evaluation.py` | 评测流水线编排(ASR → metrics → 评测图 → 报告落库) |
| `classroom.py` | 虚拟学生脚本调度(生成举手 / 提问时间表) |

### 4.3 关键取舍

评测是**「先录完、后评测」的异步流程**,不做实时流式 ASR。这是 MVP 里最稳、也是唯一能控制住工作量的选择。

## 5. 数据模型

MVP 共 5 张表。

**`user`** — `id` / `username` / `hashed_password` / `display_name` / `role`(`student`·`teacher`) / `major`(语文·数学·英语·思政·幼教) / `grade` / `created_at`

**`lesson`**(试讲题目)— `id` / `title` / `subject` / `stage`(小学·初中·高中·学前) / `textbook`(教材版本) / `content`(题目或课文) / `key_points`(重难点,JSON) / `suggested_duration_seconds` / `suggested_segments`(导入·新授·练习·小结·作业 各自建议时长,JSON)

**`attempt`**(一次试讲)— `id` / `user_id` / `lesson_id` / `mode`(`free`·`scenario`·`interview`,MVP 只实现 `free`) / `status` / `media_path` / `media_mime` / `duration_seconds` / `segment_marks`(学生点「进入下一环节」的时间戳,JSON) / `classroom_script`(虚拟学生行为时间表,JSON) / `error_message` / `created_at` / `updated_at`

**`transcript`**(ASR 结果)— `id` / `attempt_id` / `segments`(`[{text, start, end, confidence}]`) / `full_text` / `created_at`

**`evaluation`**(评测报告)— `id` / `attempt_id` / `dimensions`(各子维度得分,JSON) / `total_score` / `suggestions`(问题定位 + 逐句建议,JSON) / `summary`(LLM 总评文本) / `created_at`

> 雷达图数据由 `dimensions` 在前端派生,不额外落库。

**设计说明**:

- `classroom_script` 由**后端**生成(而非前端随机),使回放时虚拟学生行为可精确同步,并为后续「互动响应」维度提供基准真值。
- `segment_marks` 由学生**手动点按钮**产生,而非 LLM 猜测环节边界——规则计算时间管理需要可靠的环节边界。
- `mode` 枚举保留三种模式,但 MVP 只实现 `free`,避免后续改表。

## 6. 状态机

```
draft ──上传录制──> uploaded ──> transcribing ──> evaluating ──> done
                       │              │               │
                       └──────────────┴───────────────┴──> failed
```

## 7. 核心流程

```
登录 → 选题目(lesson)→ 进入课堂(三栏布局)
  │  后端生成 classroom_script,前端按时间表触发虚拟学生(TTS 语音 + 举手)
  │  学生点「环节」按钮打点 → segment_marks
  │  MediaRecorder 同时录 麦克风 + 摄像头
  ▼
结束试讲 → POST /api/v1/attempts/{id}/media(上传)→ 后端落盘 → status=uploaded
  ▼
BackgroundTasks 触发评测流水线:
  ① ffmpeg 转码(如需)→ ② 云端 ASR(带时间戳)→ 写 transcript
  ③ metrics.py 规则计算:语速 / 停顿 / 口头禅 / 各环节时长
  ④ LangGraph 评测图:教学内容多维评分 + 生成建议
  ⑤ 汇总落库 evaluation → status=done
  ▼
前端轮询 GET /api/v1/attempts/{id} 直到 done
  → 报告页(雷达图 + 逐句建议 + 录制回放)
```

## 8. 评测设计(核心价值)

### 8.1 维度 1 · 教学表达(规则计算,零 LLM 成本)

| 指标 | 算法 |
|---|---|
| 语速 | `full_text` 字数 / 有效说话时长 → 字/分钟;按环节分别统计 |
| 停顿 | 相邻转写段 `next.start - prev.end > 3s` → 长停顿次数与位置 |
| 口头禅 | 词表匹配(`嗯` / `那个` / `就是` / `然后` / `呃`)→ 频次 + 每分钟频次 |
| 音量 | 音频分段 RMS(需 ffmpeg 解码 + numpy)→ 过小 / 过大占比。**MVP 不做** |

普通话标准度需要专门的发音评测 API,**不在 MVP**。

### 8.2 维度 2 · 时间管理(规则计算)

- 按 `segment_marks` 计算各环节实际时长 vs `suggested_segments` 的偏差。
- 总时长 vs `suggested_duration_seconds` → 超时 / 提前结束。

### 8.3 维度 3 · 教学内容(LLM)

- **输入**:转写全文(按环节切段)+ `lesson.content` + `lesson.key_points`。
- **LangGraph 评测图,3 个纯节点**:
  1. **结构化** — 按环节切分整理转写文本
  2. **评分 + 建议** — 单次结构化调用(JSON Schema 约束),覆盖 知识点准确性 / 逻辑清晰度 / 重难点把握 / 语言规范性 / 讲解完整度
  3. **汇总** — 合并规则分与 LLM 分,生成总评
- **刻意的取舍**:不做「每维度一次 LLM 调用」的 fan-out——单次结构化调用省 token、降延迟。LangGraph 的价值在于把「转写 → 评分 → 建议 → 汇总」阶段化、可观测、可回退,而非拆得越多越好。

### 8.4 报告页

0–100 分 + 加权总分 · ECharts 雷达图 · 问题定位(带时间戳,点击跳转回放)· 逐句建议(问题句 + 类型 + 优化话术)· 录制回放。

## 9. 项目结构

```
project_school_temp/
├── docker-compose.yml            # postgres:16
├── README.md                     # 总说明
├── CODE_STANDARDS.md             # 代码规范
├── docs/superpowers/specs/       # 设计文档
├── backend/
│   ├── pyproject.toml
│   ├── .env.example              # DEEPSEEK_API_KEY / DATABASE_URL / JWT_SECRET / ASR_* / TTS_*
│   ├── alembic/                  # 迁移脚本
│   ├── storage/                  # 录制音视频(gitignore)
│   └── app/
│       ├── main.py               # FastAPI 入口 + 路由挂载 + CORS + /media 静态挂载
│       ├── core/                 # config / security(JWT) / db(engine+session) / storage
│       ├── models/               # SQLModel:user / lesson / attempt / transcript / evaluation
│       ├── schemas/              # 请求响应 DTO
│       ├── api/v1/               # auth / lessons / attempts / evaluations
│       ├── agents/               # llm.py(DeepSeek 客户端) / evaluation_graph.py(评测图)
│       └── services/             # asr / metrics / classroom / seed / evaluation
└── frontend/
    ├── vite.config.ts            # /api 代理 → localhost:8000
    └── src/
        ├── main.ts  App.vue
        ├── router/  stores/  api/
        ├── views/                # Login / LessonList / Classroom / Report / History
        ├── components/           # ClassroomBoard / StudentPanel / RecorderControls /
        │                         #   SegmentTimeline / RadarChart / ReportCard
        ├── composables/          # useRecorder / useClassroomScript / useAttemptPolling
        └── assets/
```

## 10. MVP 边界

**MVP 内(一条主闭环)**:

1. 注册 / 登录(师范生,含专业、年级)
2. 试讲题目浏览与选择(内置种子数据:若干学科 × 学段)
3. 虚拟课堂(B 三栏布局 + 脚本化虚拟学生 + TTS)
4. 录制(音频 + 摄像头)、上传、回放
5. 评测流水线 + 报告页(雷达图 + 逐句建议)
6. 我的试讲历史

**MVP 外(后续阶段)**:

- 教师端:班级管理、任务发布、批阅点评、班级数据看板
- 资源库:教案 / 板书模板、优秀范例、结构化面试题库
- 训练阶梯:跟读训练、情景模拟、模拟考核
- 教姿教态(CV 姿态 / 表情)、板书 PPT(OCR)
- 达标认证:技能证书、薄弱项推送、历史进步曲线
- 实时直播观摩、教师实时指导
- 课堂互动与控场维度(见下方「低成本增强」)
- 教材语义检索(pgvector)

**低成本增强(可选纳入 MVP)**:「课堂互动」维度——脚本学生的举手 / 提问时间点是已知的,只要判断学生在举手后 N 秒内有无语音,即可算出「响应率」。成本极低,默认不做,可在实现阶段按余力决定。

## 11. 启动流程

前置:Python 3.12 + uv、Node ≥ 20.19、Docker Desktop、ffmpeg。

```bash
# 1. 起数据库
docker compose up -d db

# 2. 后端
cd backend
cp .env.example .env          # 填 DEEPSEEK_API_KEY / ASR key
uv sync
uv run alembic upgrade head   # 建表
uv run python -m app.services.seed   # 灌入试讲题目种子数据
uv run uvicorn app.main:app --reload --port 8000
# → API 文档 http://localhost:8000/docs

# 3. 前端(另开终端)
cd frontend
npm install
npm run dev                   # → http://localhost:5173,/api 代理到 8000
```

**环境变量(`backend/.env`)**:

| 变量 | 说明 |
|---|---|
| `DEEPSEEK_API_KEY` | DeepSeek API 密钥 |
| `DEEPSEEK_BASE_URL` | 兼容端点,默认 `https://api.deepseek.com` |
| `DEEPSEEK_MODEL` | 模型名,默认 `deepseek-chat` |
| `DATABASE_URL` | PostgreSQL 连接串 |
| `JWT_SECRET` | JWT 签名密钥 |
| `ASR_PROVIDER` / `ASR_API_KEY` | ASR 实现(默认 `fake`)与密钥 |
| `LANGSMITH_API_KEY` | 可选,LangSmith tracing |
| `STORAGE_DIR` | 录制文件根目录,默认 `backend/storage` |

## 12. 风险与注意

1. **录制格式 ≠ ASR 格式** — `MediaRecorder` 默认 webm/opus,云端 ASR 多要求 wav/pcm/mp3。MVP 用 `FakeAsrClient` 不解码音频,故 **MVP 无需 ffmpeg**;接入真实云端 ASR 时需引入 ffmpeg 转码。
2. **云端 ASR / TTS 依赖网络与 key** — 演示前必须验证额度与延迟;兜底方案:本地 FunASR(离线转写)、浏览器 Web SpeechSynthesis(音色降级)。
3. **评测耗时 10~60s** — 前端必须有 loading / 进度态,不得白屏。
4. **`BackgroundTasks` 重启丢失** — 进程重启会让 in-flight 的 attempt 卡在 `transcribing` / `evaluating` → 启动时把中间态重置为 `failed`,并提供重试接口。
5. **音量维度需音频解码** — 依赖 ffmpeg + numpy,标为可选。
6. **TTS 音色**直接影响演示观感。MVP 先用浏览器 Web SpeechSynthesis(零依赖),音色一般;厂商确定后换云端 TTS。

## 13. 待定项

- **云端 ASR / TTS 厂商**:候选 讯飞 / 阿里云智能语音 / 腾讯云。需评估中文准确率、时间戳粒度、免费额度、接入成本。MVP 用 `FakeAsrClient` + 浏览器 Web SpeechSynthesis 跑通,厂商确定后新增一个 `AsrClient` 实现与后端 TTS 接口即可。
- **波形回放**:是否引入 wavesurfer.js。
- **「课堂互动」维度**:是否纳入 MVP(见第 10 节「低成本增强」)。
