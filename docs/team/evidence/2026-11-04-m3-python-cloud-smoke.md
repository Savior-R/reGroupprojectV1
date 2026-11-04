# M3 Python 调用层真实云 smoke 记录

> 日期：2026-11-04
> 范围：M3 Python 调用层（DeepSeek 文本 / Qwen-OCR / Paraformer 实时 ASR）
> Owner：M3 Owner
> 环境：WSL2，Python 3.14，uvicorn 监听 `127.0.0.1:8001`，`AI_MOCK=false`
> 凭证：本地 `ai/.env`（已 gitignore，权限 600）；本文不记录任何密钥
> 结论：5 个接口全部通过；随后按产品决策关闭 classroom thinking，32 条 mock 测试通过，classroom 真实 smoke 延迟降至 0.6–1.2 s。测试数据已持久化到 `ai/tests/fixtures/`，新增 4 条 fixture 校验 + 3 条 live 测试（默认 skip，`AI_LIVE=1` 才调用真实云）。

## 1. 测试对象

| 能力 | 模型 | 协议 |
|---|---|---|
| 文本 LLM | DeepSeek `deepseek-flash` | OpenAI-compatible `/chat/completions` |
| OCR | Qwen `qwen3.5-ocr` | DashScope OpenAI-compatible |
| 实时 ASR | Paraformer `paraformer-realtime-v2` | DashScope WebSocket |

覆盖接口：

- `GET /healthz`
- `POST /internal/v1/classroom/question`
- `POST /internal/v1/classroom/answer`
- `POST /internal/v1/classroom/classify`
- `POST /internal/v1/ocr`
- `WS /internal/v1/asr/stream`

## 2. 执行命令

```bash
cd ai
# 本地 ai/.env 已有真实 key；清空三个 key 环境变量，避免影响“缺少 key”用例
DEEPSEEK_API_KEY= OCR_API_KEY= ASR_API_KEY= PYTHONPATH=. .venv/bin/pytest -q   # 36 passed, 3 skipped
PYTHONPATH=. .venv/bin/uvicorn app.main:create_app --factory --host 127.0.0.1 --port 8001

# 真实云 live smoke；读取 ai/.env，默认不在 CI 运行
AI_LIVE=1 PYTHONPATH=. .venv/bin/pytest -q tests/integration/test_live_cloud_smoke.py   # 3 passed
```

初次 smoke 时为 28 条测试；关闭 classroom thinking 后新增 4 条断言，当前 32 条 mock；
再加上 4 条 fixture 校验，普通测试为 36 passed；3 条 live 测试默认 skip。

## 3. 首次 smoke 结果（thinking 开启）

| 接口 | 状态 | 延迟 | 关键结果 |
|---|---:|---:|---|
| `GET /healthz` | 200 | — | `{"status":"ok"}` |
| `POST /classroom/question` | 200 | 1958 ms | JSON 合法；`confidence=0.86`；返回学生问题 |
| `POST /classroom/answer` | 200 | 3585 ms | JSON 合法；`quality=partial`；返回学生回答 |
| `POST /classroom/classify` | 200 | 1274 ms | `label=question`；`confidence=0.99` |
| `POST /internal/v1/ocr` | 200 | 2062 ms | `qwen3.5-ocr` 返回 `Hello OCR 123` |
| `WS /internal/v1/asr/stream` | done | 5370 ms | 最终文本 `Hello word, 这里是阿里巴巴语音实验室。` |

说明：

- OCR 使用本地生成的英文测试图；中文截图待补。
- ASR 使用 DashScope 公开样例音频（16 kHz 单声道，3.83 s）：
  `https://dashscope.oss-cn-beijing.aliyuncs.com/samples/audio/paraformer/hello_world_female2.wav`
- ASR 返回 partial 与 final；上表只记录 final。

## 4. 已知问题 / 后续

1. **DeepSeek thinking（已处理）**：首次 smoke 时 `deepseek-flash` 默认 thinking，question/answer 延迟约 2–3.6 s，answer 的 `quality` 稳定为 `partial`。按“优先响应速度 + quality 标签稳定”的决策，M3 classroom 已关闭 thinking；实现见第 5 节。
2. **OCR 框 / 置信度未解析**：当前 `OCRCloudProvider` 只返回文本，`boxes` 与 `confidence` 为空；若验收要求框级结果，需要补 provider，属于 AI Owner 路径。
3. **测试密钥管理**：真实 key 只存在本地 `ai/.env`，不提交；生产前建议限制模型访问范围并定期轮换。

## 5. 追加：关闭 classroom thinking（2026-11-04）

### 5.1 A/B 依据

同一组课堂输入，每个阶段 thinking 开/关各跑 5 次：

| 阶段 | thinking 关闭 | thinking 开启 |
|---|---|---|
| question | 平均 1.46 s；0 reasoning token；5/5 `input_question_complete=true` | 平均 1.91 s；53–177 reasoning token；1/5 `input_question_complete=false` |
| answer | 平均 1.43 s；5/5 `quality=correct`；置信度 0.80–0.85 | 平均 3.34 s；5/5 `quality=partial`；置信度 0.55–0.80 |
| classify | 1.1–1.2 s；判断正确 | 1.5–2.4 s；判断正确，无质量收益 |

结论：M3 classroom 的 question / answer / classify 全部关闭 thinking；M4 evaluation 后续可按需显式开启。

### 5.2 实现

| 文件 | Owner | 变更 |
|---|---|---|
| `ai/app/providers/base.py` | AI Owner | `LLMProvider.chat` / `stream` 增加可选 `thinking: bool \| None = None` |
| `ai/app/providers/deepseek.py` | AI Owner | `thinking` 非 None 时写入 `{"thinking":{"type":"enabled"\|"disabled"}}`；不传时保持服务端默认 |
| `ai/app/providers/mock.py` | AI Owner | mock LLM 接受并透传 `thinking` |
| `ai/app/capabilities/classroom_content.py` | M3 Owner | `_json_call` 固定传 `thinking=False` |
| `ai/tests/providers/test_deepseek.py` | AI Owner | 断言 disabled / enabled 请求体；断言不传时无 `thinking` 字段 |
| `ai/tests/providers/test_mock.py` | AI Owner | 覆盖 mock 的 `thinking` 参数 |
| `ai/tests/capabilities/test_classroom_content.py` | M3 Owner | 断言 question / answer / classify 都传 `thinking=False` |

- 无 API / DTO / WS / DB / 事件契约变更。
- 回滚：删除 capability 中的 `thinking=False` 即恢复服务端默认。
- 跨模块确认：2026-11-04 用户在本会话中明确确认包含 AI Owner 部分的全部文件。

### 5.3 复测

`AI_MOCK=false` 启动服务，classroom 各跑 2 次：

| 接口 | 第 1 次 | 第 2 次 |
|---|---:|---:|
| question | 1207 ms；confidence=0.90 | 1089 ms；confidence=0.90 |
| answer | 1144 ms；quality=correct | 636 ms；quality=partial（回答文本本身带“不太清楚”，非 thinking 导致） |
| classify | 953 ms；label=question | 760 ms；label=question |

- 32 条 mock 测试全部通过。
- OCR / ASR 代码路径未变，首次 smoke 结果仍然有效。

## 6. 测试数据持久化（2026-11-04）

新增测试数据与 live 测试：

```text
ai/tests/fixtures/
├─ README.md
├─ classroom_requests.json      # classroom question/answer/classify 请求体
├─ expected.json                # OCR / ASR 断言值
├─ images/ocr_sample.png        # 520×140，文字 Hello OCR 123
├─ audio/asr_sample.wav         # DashScope 公开样例，16 kHz mono 16-bit，3.83 s
├─ audio/SOURCE.md              # 样例 URL + SHA256
└─ __init__.py                  # fixture 读取工具

ai/tests/integration/
├─ __init__.py
├─ test_fixtures.py             # 4 条离线 fixture 校验
└─ test_live_cloud_smoke.py     # 3 条真实云测试，默认 skip
```

- `test_fixtures.py` 在普通 CI / 本地 mock 测试中运行，不需要云 key。
- `test_live_cloud_smoke.py` 只有 `AI_LIVE=1` 且 `ai/.env` 三个 key 齐全时才运行；覆盖 classroom、OCR、ASR。
- 所有 fixture 不含密钥；`.env` 继续 gitignore。
- 验证结果：普通 `pytest` 为 **36 passed, 3 skipped**；`AI_LIVE=1` 时 live 测试 **3 passed**。
- 跨模块确认：用户在本会话中确认新增 `ai/tests/fixtures/**` + `ai/tests/integration/**`，包含共享给 AI Owner provider 测试使用的 fixture。
