# 2026-11-06 M3 全链路（感知 + AI + 课堂 + 录制回放）

> 目标：在 M3 单模块内打通“教师 M3A API 建任务 → 学生课堂 → 麦克风 PCM / 屏幕 JPEG 感知 → Java → Python ASR / OCR / 内容生成 → 虚拟学生对话面板 → 三路录制上传 → 结束 → 回看 → 提交 → 回放”。
> 结论：free 课堂与 interview 两条链路在 Docker Compose 内全部通过；AI 使用 `AI_MOCK=true` 的确定性 provider，真实云 key 不进入仓库。

## 1. 授权与范围

用户 2026-11-06 明确确认“全链路，含 AI 容器”，授权当前 M3 Owner 代 AI Owner / Foundation 增加：

- `ai/Dockerfile`（新建）
- `docker-compose.yml` 的 `ai` 服务与 `app` 的 `M3_AI_BASE_URL` / `M3_AI_SERVICE_TOKEN`
- 其余为 M3 Owner 范围：`server/src/main/java/com/shijiangtai/m3classroom/**`、`frontend/src/**`、M3 证据与看板

未修改 Python 业务代码、AI provider、数据库表、WS 信封、REST 字段或权限。

## 2. 实现

### 2.1 AI 服务容器

- `ai/Dockerfile`：`python:3.12-slim`（可通过 `PYTHON_IMAGE` 构建参数替换镜像源），安装 `ai/pyproject.toml`，启动 `uvicorn app.main:create_app --factory --port 8001`。
- Compose 新增 `ai` 服务（`AI_MOCK=true`、`SERVICE_TOKEN=dev-service-token`、healthcheck `/healthz`）；`app` 增加 `M3_AI_BASE_URL=http://ai:8001` 和 `M3_AI_SERVICE_TOKEN`，并依赖 `ai` healthy。
- `ai/app/**` 未改；`AI_MOCK` 使用已有 Mock provider（OCR `mock ocr text`、ASR `mock final`、内容 `这是模拟的学生回答。`）。

### 2.2 Java AI 适配

- `PythonPerceptionHttpClient` 增加 `ContentClient` 实现，调用 `/internal/v1/classroom/question|answer`；失败仍由 `ContentGenerationService` 的 2s/3s 模板 fallback 兜底。
- **关键修复**：Java `HttpClient` 默认发送 `Upgrade: h2c`，Uvicorn 不支持 h2c upgrade，导致所有 Java → Python POST 被 422；改为 `HttpClient.Version.HTTP_1_1`。
- 新增 `PythonPerceptionHttpClientTest` 的 question / answer 用例，覆盖请求字段与响应映射。

### 2.3 前端感知

- `frontend/src/utils/pcmCapture.ts`：AudioWorklet 采集麦克风，100 ms 块、Float32 → PCM16 mono 16 kHz（必要时平均降采样）。
- `frontend/src/utils/perceptionClient.ts`：一次性 ticket 连接 `/ws/v1/sessions/{id}/perception`，发送 `audio.start` / PCM 二进制 / `audio.stop`；解析 `asr.partial` / `asr.final` / `error`；异常断线最多重连 3 次。
- `frontend/src/utils/screenSampler.ts`：复用屏幕流，每 5 秒抓 960px JPEG，计算变化分数，`POST /perception/screen`；单请求在途时跳过，失败只降级。
- 课堂 / 面试页在 `in_progress` 时启动；结束前先 `await stopPerception()` 发送 `audio.stop` 并等待 ASR 最终段，再停止录制 / 结束会话；失败、卸载、终态都会清理。

### 2.4 对话与交互

- `DialoguePanel.vue`：轮询 `GET /sessions/{id}/texts`（2s）并合并 `asr.partial` 实时文本；显示虚拟学生姓名、回答 / 追问 / 提问、时间；按 `text.id` 去重。
- `ClassroomControls`：按 `macroState` / `qaPhase` 门控——问答阶段禁用提问、只有 QA 阶段启用“结束问答”、点名要求 `waiting_hands`、结束对话要求活动对话。
- `RecordingStatus`：新增 `bufferedBytes`，未达到 5 MB part 时显示“录制中 · 已缓存 X MB”，不再表现为 0% 上传假象。

## 3. 验收流程

环境：

```bash
docker compose up -d ai app
npm --prefix frontend run dev -- --host 127.0.0.1
# Playwright Chromium headless + fake camera/mic/screen
```

脚本：`2026-11-06-m3-standalone-acceptance/ai-flow.mjs`

### 3.1 Free 课堂

```text
- open task and start recording ... ok
- ask class and wait for a raised hand ... ok
- call on student and receive AI answer in dialogue panel ... ok
- end session and wait for review ... ok
- submit recording ... ok
perception messages: audio.start, audio.stop, asr.partial(mock partial), asr.final(mock final)
AI FLOW ACCEPTANCE PASSED
```

数据库（session `01a1b10e-d8c2-...`）：

| 项 | 值 |
|---|---|
| session | `submitted`，`duration_ms=10550` |
| `session_texts` | teacher/utterance `mock final`（asr）；student/answer `这是模拟的学生回答。`；student/follow_up `老师，为什么要这样计算？` |
| `frames` | 3 条，全部 `processed` |
| 录制 | camera / screen / audio 三路 uploaded，offset 非零（前一轮同链路验证） |

截图：[等待举手](2026-11-06-m3-standalone-acceptance/20-waiting-hands.png)、[AI 回答进入对话面板](2026-11-06-m3-standalone-acceptance/21-dialogue-answer.png)、[提交后回放](2026-11-06-m3-standalone-acceptance/22-ai-replay.png)。

### 3.2 结构化面试

脚本：`2026-11-06-m3-standalone-acceptance/interview.mjs`

```text
INTERVIEW ACCEPTANCE PASSED
```

数据库：

| 项 | 值 |
|---|---|
| session | `submitted` |
| `session_texts` | examiner/question `请谈谈以学生为中心的理解。`（manual）；candidate/answer `mock final`（asr） |
| 录制 | camera + audio 两路 uploaded |

## 4. 测试

- 后端全量（停止 `app` / `ai` 避免 Hikari 连接叠加）：**93 classes / 696 tests**，0 failure / 0 error / 0 skipped。
- 前端：**18 test files / 143 tests** 通过；`npm run build` 通过（仅既有 Element Plus chunk 警告）。
- 新增前端测试：`perceptionClient.spec.ts`（协议 / 解析 / stop）、`pcmCapture.spec.ts`（PCM16 与降采样）、`DialoguePanel.spec.ts`（文本与 live ASR）。
- 新增后端测试：`PythonPerceptionHttpClientTest` question / answer 两条。

## 5. 已知边界

- `AI_MOCK=true` 是验收默认；真实 DeepSeek / Paraformer / Qwen-OCR 需要 `AI_MOCK=false` 与云 key（不提交）。
- Mock ASR 只在 `audio.stop` 时产出 partial/final；真实 provider 才会边说话边 partial。
- 屏幕 OCR 文本目前不渲染到课堂 UI（帧状态与事件可查）；对话面板展示 ASR 与虚拟学生文本。
- 真实摄像头 / 屏幕共享、Safari、性能 / 并发 NFR 未覆盖。
- AI 镜像默认使用 Huawei 镜像源；其他环境可通过 `PYTHON_IMAGE` 构建参数替换。
- Java AI HTTP 客户端已固定 HTTP/1.1；若未来换用支持 h2c 的服务端，可再评估。

## 6. 真实 Provider 测试（AI_MOCK=false，2026-11-06）

### 6.1 环境

- 本地 `ai/.venv` 启动 `AI_MOCK=false` 的 FastAPI 服务（真实 DeepSeek / Paraformer / Qwen-OCR key 只从被 gitignore 的 `ai/.env` 读取，未写入仓库）。
- 为测试临时把 `app` 的 `M3_AI_BASE_URL` 指向 `host.docker.internal:8001`；测试后恢复为 Compose 的 `http://ai:8001`（AI 容器，默认 mock）。
- Playwright 使用完整 Chromium（`channel: 'chromium'`），`--use-file-for-fake-audio-capture=/tmp/asr_sample_48k.wav`；fixture 从 16 kHz 重采样到 48 kHz，headless shell + 16 kHz 会得到 `NO_VALID_AUDIO_ERROR`。

### 6.2 结果

```text
- wait for real ASR teacher text ... ok
- ask class and call on a student ... ok
- receive a real LLM student answer ... ok
- end and submit ... ok
REAL AI FLOW PASSED
```

对话面板实际内容（节选）：

- 教师 ASR：`这里是阿里巴巴语音实验室。`
- 教师 ASR：`Hello, 我的这里是阿里巴巴语音实验室。`
- 小明（DeepSeek `deepseek-flash`）：`老师，这句话里“我的”和“这里是”好像重复了，是不是应该说“你好，这里是阿里巴巴语音实验室”？`

数据库：4 条 `session_texts`（3 条 teacher/asr + 1 条 student/llm，后者 `model_version=deepseek-flash`）；3 条屏幕帧全部 `processed`（真实 Qwen-OCR）。

### 6.3 LLM 调用频率

问答环节不是持续调用 LLM，而是**事件驱动**：

| 事件 | LLM 调用 |
|---|---|
| `teacher.ask_class` / 举手 / 点名 | 无 |
| `teacher.call_on` → 学生回答 | 1 次 `/classroom/answer` |
| 回答后按 `question_rate * 0.5` 命中追问 | 1 次 `/classroom/question` |
| 教师 ASR final（lecturing） | 1 次 `/classroom/classify` |
| 教师 ASR final（dialogue 中） | 命中追问时 1 次 `/classroom/question`，否则不调用 |
| 学生主动提问 / 追问问世 | 1 次 `/classroom/question` |

没有轮询或持续对话循环；每次生成受 `ContentGenerationService` 的 2 s / 3 s 超时保护，失败回退模板。

### 6.4 多轮语义提醒

真实 ASR 下，教师在 `qa.dialogue` 中说话会推进多轮，但当前 05A 设计产生的是学生 **`student.follow_up`（学生再问老师）**，不是“学生回答老师的追问”。若产品要求“老师追问 → 学生回答”，需要改状态机与事件契约，不能在现有 UI 上直接实现。
