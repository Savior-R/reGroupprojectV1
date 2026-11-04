import pytest

from app.capabilities.ocr_gateway import OCRGateway
from app.providers.base import OCRBox, OCRResult, ProviderError
from app.schemas.ocr import OCRRequest


class FakeOCR:
    def __init__(self, results):
        self.results = results
        self.calls = 0

    async def recognize(self, image_url=None, image_base64=None, model=None, timeout_s=15.0):
        self.calls += 1
        result = self.results.pop(0)
        if isinstance(result, Exception):
            raise result
        return result


@pytest.mark.asyncio
async def test_recognize_maps_provider_result_to_response():
    provider = FakeOCR(
        [
            OCRResult(
                text="1/3 = 2/6",
                model_version="qwen3.5-ocr",
                boxes=[OCRBox(text="1/3", x1=1, y1=2, x2=3, y2=4, confidence=0.9)],
                confidence=0.9,
                latency_ms=42,
            )
        ]
    )
    gateway = OCRGateway(provider=provider)

    result = await gateway.recognize(OCRRequest(image_url="https://example.com/a.png"))

    assert result.text == "1/3 = 2/6"
    assert result.boxes[0].text == "1/3"
    assert result.boxes[0].x1 == 1
    assert result.model_version == "qwen3.5-ocr"
    assert result.latency_ms == 42


@pytest.mark.asyncio
async def test_recognize_retries_retryable_provider_error():
    provider = FakeOCR(
        [
            ProviderError("RATE_LIMITED", "slow down", retryable=True),
            OCRResult(text="ok", model_version="qwen3.5-ocr"),
        ]
    )
    gateway = OCRGateway(provider=provider, max_attempts=3)

    result = await gateway.recognize(OCRRequest(image_url="https://example.com/a.png"))

    assert result.text == "ok"
    assert provider.calls == 2
