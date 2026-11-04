from __future__ import annotations

from app.providers.base import OCRProvider, OCRResult, ProviderError
from app.schemas.ocr import OCRBox as OCRBoxView
from app.schemas.ocr import OCRRequest, OCRResponse


class OCRGateway:
    def __init__(self, *, provider: OCRProvider, max_attempts: int = 3) -> None:
        self._provider = provider
        self._max_attempts = max_attempts

    async def recognize(self, request: OCRRequest) -> OCRResponse:
        last_error: ProviderError | None = None
        for _ in range(self._max_attempts):
            try:
                result = await self._provider.recognize(
                    image_url=request.image_url,
                    image_base64=request.image_base64,
                    model=request.model,
                )
                return self._to_response(result)
            except ProviderError as exc:
                last_error = exc
                if not exc.retryable:
                    raise
        assert last_error is not None
        raise last_error

    @staticmethod
    def _to_response(result: OCRResult) -> OCRResponse:
        return OCRResponse(
            text=result.text,
            boxes=[
                OCRBoxView(
                    text=box.text,
                    x1=box.x1,
                    y1=box.y1,
                    x2=box.x2,
                    y2=box.y2,
                    confidence=box.confidence,
                )
                for box in result.boxes
            ],
            confidence=result.confidence,
            model_version=result.model_version,
            latency_ms=result.latency_ms,
        )
