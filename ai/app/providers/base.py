from __future__ import annotations

from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from typing import Any, Protocol


class ProviderError(Exception):
    """A normalized provider failure that capabilities can map to HTTP errors."""

    def __init__(
        self,
        code: str,
        message: str,
        *,
        retryable: bool = False,
        status_code: int | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.retryable = retryable
        self.status_code = status_code


@dataclass(frozen=True)
class Usage:
    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0


@dataclass(frozen=True)
class LLMResponse:
    text: str
    model_version: str
    usage: Usage | None = None
    latency_ms: int | None = None


@dataclass(frozen=True)
class LLMChunk:
    text: str
    model_version: str
    is_final: bool = False


class LLMProvider(Protocol):
    async def chat(
        self,
        messages: list[dict[str, str]],
        model: str,
        timeout_s: float,
        *,
        response_format: dict[str, Any] | None = None,
        temperature: float | None = None,
        thinking: bool | None = None,
    ) -> LLMResponse: ...

    def stream(
        self,
        messages: list[dict[str, str]],
        model: str,
        timeout_s: float,
        *,
        temperature: float | None = None,
        thinking: bool | None = None,
    ) -> AsyncIterator[LLMChunk]: ...


@dataclass(frozen=True)
class OCRBox:
    text: str
    x1: float
    y1: float
    x2: float
    y2: float
    confidence: float | None = None


@dataclass(frozen=True)
class OCRResult:
    text: str
    model_version: str
    boxes: list[OCRBox] = field(default_factory=list)
    confidence: float | None = None
    latency_ms: int | None = None


class OCRProvider(Protocol):
    async def recognize(
        self,
        image_url: str | None = None,
        image_base64: str | None = None,
        model: str | None = None,
        timeout_s: float = 15.0,
    ) -> OCRResult: ...


@dataclass(frozen=True)
class ASRConfig:
    sample_rate: int = 16000
    format: str = "pcm"
    language: str = "zh"
    model: str = "paraformer-realtime-v2"
    max_sentence_silence_ms: int = 800


@dataclass(frozen=True)
class ASRChunk:
    text: str
    is_final: bool
    begin_ms: int | None = None
    end_ms: int | None = None
    confidence: float | None = None


class ASRProvider(Protocol):
    def stream(
        self,
        audio: AsyncIterator[bytes],
        config: ASRConfig,
    ) -> AsyncIterator[ASRChunk]: ...


