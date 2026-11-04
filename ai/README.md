# Shijiangtai AI Service

M3 的 Python AI 调用层：DeepSeek 文本、阿里云 Paraformer 实时 ASR、Qwen-OCR，以及对应的 FastAPI 路由。

## 目录

```text
ai/
├─ app/
│  ├─ api/          # classroom / ocr / asr 路由
│  ├─ capabilities/ # 能力封装：内容生成、OCR、ASR
│  ├─ providers/    # DeepSeek / 阿里云 / Mock 适配器
│  ├─ prompts/      # M3 课堂 prompt 与版本
│  ├─ schemas/      # 请求 / 响应 Pydantic 模型
│  ├─ config.py     # 环境变量
│  ├─ container.py  # 依赖组装
│  └─ main.py       # create_app 工厂
└─ tests/           # 全部使用 mock，不访问真实云 API
```

## 本地运行

```bash
cd ai
python3.12 -m venv .venv
.venv/bin/pip install -e ".[dev]"
cp .env.example .env
.venv/bin/uvicorn app.main:create_app --factory --reload --port 8001
```

默认 `.env.example` 中 `AI_MOCK=true`，不会调用云 API。需要真实调用时：

```bash
AI_MOCK=false
DEEPSEEK_API_KEY=...
OCR_API_KEY=...
ASR_API_KEY=...
```

真实调用只用于手工 smoke，不进入 CI。

## 内部接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/internal/v1/classroom/question` | 虚拟学生提问 |
| POST | `/internal/v1/classroom/answer` | 虚拟学生回答 |
| POST | `/internal/v1/classroom/classify` | 教师话语分类 |
| POST | `/internal/v1/ocr` | 共享窗口截图 OCR |
| WS | `/internal/v1/asr/stream` | Paraformer 实时 ASR |
| GET | `/healthz` | 健康检查 |

除 `/healthz` 外，请求需带 `X-Service-Token`（配置了 `SERVICE_TOKEN` 时）。

ASR WebSocket：

1. 建立连接，可带 `sample_rate` / `language` / `model` 查询参数。
2. 发送二进制 PCM 分片。
3. 发送文本 `{"type":"finish"}` 结束音频。
4. 服务端返回 `{"type":"chunk", ...}`，最后返回 `{"type":"done"}`。

## 测试

```bash
cd ai
PYTHONPATH=. .venv/bin/pytest
```

测试覆盖：

- DeepSeek 请求构造、响应解析、429 映射
- Qwen-OCR 请求构造、URL / base64 输入
- Paraformer run-task / finish-task、partial / final、task-failed
- Classroom 内容生成 JSON 校验与一次重试
- OCR 重试、ASR 透传
- FastAPI 路由、Service Token、ASR WebSocket
- Mock 模式与缺失配置

## 与设计文档的对应

- 接口：`docs/design/02-API与实时协议.md` §5.2
- 类与方法：`docs/implementation/08-Python-AI-实现设计.md`
- 选型：`docs/design/00-总体设计.md` §3.3
