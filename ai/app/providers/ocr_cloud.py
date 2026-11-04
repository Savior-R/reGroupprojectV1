from __future__ import annotations

import time
from typing import Any

import httpx

from app.providers.base import OCRResult, ProviderError


class OCRCloudProvider:
    """Qwen-OCR adapter using the DashScope OpenAI-compatible endpoint."""

    def __init__(
        self,
        *,
        api_key: str,
        endpoint: str = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
        model: str = "qwen3.5-ocr",
        client: httpx.AsyncClient | None = None,
    ) -> None:
        if not api_key:
            raise ValueError("OCR api_key is required")
        self._api_key = api_key
        self._endpoint = endpoint
        self._model = model
        self._client = client

    async def recognize(
        self,
        image_url: str | None = None,
        image_base64: str | None = None,
        model: str | None = None,
        timeout_s: float = 15.0,
    ) -> OCRResult:
        if not image_url and not image_base64:
            raise ValueError("image_url or image_base64 is required")

        if image_url:
            source = image_url
        else:
            source = image_base64 or ""
            if not source.startswith("data:"):
                source = f"data:image/jpeg;base64,{source}"

        payload: dict[str, Any] = {
            "model": model or self._model,
            "messages": [
                {
                    "role": "system",
                    "content": "你是一个 OCR 引擎。只输出图片中的文字，不要解释。",
                },
                {
                    "role": "user",
                    "content": [
                        {"type": "image_url", "image_url": {"url": source}},
                        {"type": "text", "text": "提取图片中的全部文字。"},
                    ],
                },
            ],
            "stream": False,
        }

        started = time.perf_counter()
        try:
            response = await self._post(payload, timeout_s)
        except httpx.TimeoutException as exc:
            raise ProviderError("TIMEOUT", "OCR request timed out", retryable=True) from exc
        except httpx.HTTPError as exc:
            raise ProviderError("TRANSPORT_ERROR", str(exc), retryable=True) from exc

        if response.status_code >= 400:
            raise self._error_from_response(response)

        try:
            data = response.json()
            content = data["choices"][0]["message"]["content"]
        except (ValueError, KeyError, IndexError, TypeError) as exc:
            raise ProviderError(
                "INVALID_RESPONSE",
                "OCR returned an unexpected response shape",
                retryable=True,
                status_code=response.status_code,
            ) from exc

        return OCRResult(
            text=self._extract_text(content),
            model_version=str(data.get("model") or payload["model"]),
            latency_ms=int((time.perf_counter() - started) * 1000),
        )

    async def _post(self, payload: dict[str, Any], timeout_s: float) -> httpx.Response:
        headers = {
            "Authorization": f"Bearer {self._api_key}",
            "Content-Type": "application/json",
        }
        if self._client is not None:
            return await self._client.post(
                self._endpoint, headers=headers, json=payload, timeout=timeout_s
            )
        async with httpx.AsyncClient() as client:
            return await client.post(
                self._endpoint, headers=headers, json=payload, timeout=timeout_s
            )

    @staticmethod
    def _extract_text(content: Any) -> str:
        if isinstance(content, str):
            return content.strip()
        if isinstance(content, list):
            parts = []
            for item in content:
                if isinstance(item, dict) and item.get("type") == "text":
                    parts.append(str(item.get("text", "")))
            return "".join(parts).strip()
        return str(content).strip()

    @staticmethod
    def _error_from_response(response: httpx.Response) -> ProviderError:
        message = response.text[:500]
        if response.status_code == 429:
            return ProviderError(
                "RATE_LIMITED",
                message or "OCR rate limit exceeded",
                retryable=True,
                status_code=429,
            )
        if response.status_code >= 500:
            return ProviderError(
                "UPSTREAM_ERROR",
                message or "OCR upstream error",
                retryable=True,
                status_code=response.status_code,
            )
        return ProviderError(
            "BAD_REQUEST",
            message or "OCR rejected the request",
            retryable=False,
            status_code=response.status_code,
        )
