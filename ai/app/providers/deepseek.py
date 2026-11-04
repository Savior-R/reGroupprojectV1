from __future__ import annotations

import json
import time
from collections.abc import AsyncIterator
from typing import Any

import httpx

from app.providers.base import LLMChunk, LLMResponse, ProviderError, Usage


class DeepSeekProvider:
    """OpenAI-compatible DeepSeek adapter.

    The provider owns no retry policy; retries belong to the capability layer.
    """

    def __init__(
        self,
        *,
        api_key: str,
        base_url: str = "https://api.deepseek.com",
        model: str = "deepseek-flash",
        client: httpx.AsyncClient | None = None,
    ) -> None:
        if not api_key:
            raise ValueError("DeepSeek api_key is required")
        self._api_key = api_key
        self._base_url = base_url.rstrip("/")
        self._model = model
        self._client = client

    async def chat(
        self,
        messages: list[dict[str, str]],
        model: str,
        timeout_s: float,
        *,
        response_format: dict[str, Any] | None = None,
        temperature: float | None = None,
        thinking: bool | None = None,
    ) -> LLMResponse:
        payload: dict[str, Any] = {
            "model": model or self._model,
            "messages": messages,
            "stream": False,
        }
        if response_format is not None:
            payload["response_format"] = response_format
        if temperature is not None:
            payload["temperature"] = temperature
        if thinking is not None:
            payload["thinking"] = {"type": "enabled" if thinking else "disabled"}

        started = time.perf_counter()
        try:
            response = await self._post(payload, timeout_s)
        except httpx.TimeoutException as exc:
            raise ProviderError("TIMEOUT", "DeepSeek request timed out", retryable=True) from exc
        except httpx.HTTPError as exc:
            raise ProviderError("TRANSPORT_ERROR", str(exc), retryable=True) from exc

        if response.status_code >= 400:
            raise self._error_from_response(response)

        try:
            data = response.json()
            text = data["choices"][0]["message"]["content"]
        except (ValueError, KeyError, IndexError, TypeError) as exc:
            raise ProviderError(
                "INVALID_RESPONSE",
                "DeepSeek returned an unexpected response shape",
                retryable=True,
                status_code=response.status_code,
            ) from exc

        usage_data = data.get("usage") or {}
        usage = Usage(
            prompt_tokens=int(usage_data.get("prompt_tokens", 0)),
            completion_tokens=int(usage_data.get("completion_tokens", 0)),
            total_tokens=int(usage_data.get("total_tokens", 0)),
        )
        return LLMResponse(
            text=str(text),
            model_version=str(data.get("model") or payload["model"]),
            usage=usage,
            latency_ms=int((time.perf_counter() - started) * 1000),
        )

    async def stream(
        self,
        messages: list[dict[str, str]],
        model: str,
        timeout_s: float,
        *,
        temperature: float | None = None,
        thinking: bool | None = None,
    ) -> AsyncIterator[LLMChunk]:
        payload: dict[str, Any] = {
            "model": model or self._model,
            "messages": messages,
            "stream": True,
        }
        if temperature is not None:
            payload["temperature"] = temperature
        if thinking is not None:
            payload["thinking"] = {"type": "enabled" if thinking else "disabled"}

        try:
            async for chunk in self._stream_request(payload, timeout_s, model):
                yield chunk
        except httpx.TimeoutException as exc:
            raise ProviderError("TIMEOUT", "DeepSeek stream timed out", retryable=True) from exc
        except httpx.HTTPError as exc:
            raise ProviderError("TRANSPORT_ERROR", str(exc), retryable=True) from exc

    async def _stream_request(
        self,
        payload: dict[str, Any],
        timeout_s: float,
        model: str,
    ) -> AsyncIterator[LLMChunk]:
        headers = {
            "Authorization": f"Bearer {self._api_key}",
            "Content-Type": "application/json",
        }
        url = f"{self._base_url}/chat/completions"

        if self._client is not None:
            async with self._client.stream(
                "POST", url, headers=headers, json=payload, timeout=timeout_s
            ) as response:
                async for chunk in self._iter_sse(response, model):
                    yield chunk
            return

        async with httpx.AsyncClient() as client:
            async with client.stream(
                "POST", url, headers=headers, json=payload, timeout=timeout_s
            ) as response:
                async for chunk in self._iter_sse(response, model):
                    yield chunk

    async def _iter_sse(
        self,
        response: httpx.Response,
        model: str,
    ) -> AsyncIterator[LLMChunk]:
        if response.status_code >= 400:
            await response.aread()
            raise self._error_from_response(response)

        async for line in response.aiter_lines():
            if not line.startswith("data:"):
                continue
            data = line.removeprefix("data:").strip()
            if data == "[DONE]":
                return
            try:
                message = json.loads(data)
                delta = message["choices"][0].get("delta", {})
            except (ValueError, KeyError, IndexError, TypeError):
                continue
            text = delta.get("content")
            if text:
                yield LLMChunk(
                    text=str(text),
                    model_version=str(message.get("model") or model),
                )

    async def _post(self, payload: dict[str, Any], timeout_s: float) -> httpx.Response:
        headers = {
            "Authorization": f"Bearer {self._api_key}",
            "Content-Type": "application/json",
        }
        url = f"{self._base_url}/chat/completions"
        if self._client is not None:
            return await self._client.post(url, headers=headers, json=payload, timeout=timeout_s)
        async with httpx.AsyncClient() as client:
            return await client.post(url, headers=headers, json=payload, timeout=timeout_s)

    @staticmethod
    def _error_from_response(response: httpx.Response) -> ProviderError:
        message = response.text[:500]
        if response.status_code == 429:
            return ProviderError(
                "RATE_LIMITED",
                message or "DeepSeek rate limit exceeded",
                retryable=True,
                status_code=429,
            )
        if response.status_code >= 500:
            return ProviderError(
                "UPSTREAM_ERROR",
                message or "DeepSeek upstream error",
                retryable=True,
                status_code=response.status_code,
            )
        return ProviderError(
            "BAD_REQUEST",
            message or "DeepSeek rejected the request",
            retryable=False,
            status_code=response.status_code,
        )
