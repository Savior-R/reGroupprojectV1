# AI 测试 fixtures

供 `ai/tests/integration` 的 live smoke 和后续自动化测试直接读取。所有数据都是合成或公开样例，**不包含任何 API 密钥**。

## 文件

| 文件 | 用途 |
|---|---|
| `classroom_requests.json` | classroom question / answer / classify 的请求体（共享同一份 context） |
| `expected.json` | OCR / ASR 的断言值 |
| `images/ocr_sample.png` | 本地生成的 OCR 测试图（520×140，文字 `Hello OCR 123`） |
| `audio/asr_sample.wav` | DashScope 公开中文 ASR 样例（16 kHz、单声道、16-bit PCM、3.83 s） |
| `audio/SOURCE.md` | ASR 样例来源、SHA256、格式说明 |

## 使用

```python
from tests.fixtures import (
    asr_sample_path,
    load_classroom_requests,
    load_expected,
    ocr_image_base64,
)
```

## Live 测试

`ai/tests/integration/test_live_cloud_smoke.py` 默认跳过；只有显式设置 `AI_LIVE=1` 且 `ai/.env` 中三个 key 都存在时才会调用真实云。

```bash
cd ai
AI_LIVE=1 PYTHONPATH=. .venv/bin/pytest -q tests/integration/test_live_cloud_smoke.py
```

普通 CI / 本地 mock 测试不会读取真实 key，也不会调用云 API：

```bash
cd ai
DEEPSEEK_API_KEY= OCR_API_KEY= ASR_API_KEY= PYTHONPATH=. .venv/bin/pytest -q
```

## 注意

- 不要把 `ai/.env`、真实 key、预签名 URL 放进本目录。
- OCR 图片由 Pillow 生成；如需修改文案，重新生成 `images/ocr_sample.png` 即可。
- ASR 样例来自公开 OSS，来源和校验和见 `audio/SOURCE.md`。
