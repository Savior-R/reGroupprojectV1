"""Deterministic evaluation LLM used when ``AI_MOCK=true``.

Mock output is only for CI and local smoke tests; it is never presented as a real model result.
"""

from __future__ import annotations

import json
import re
from collections.abc import AsyncIterator
from typing import Any

from app.providers.base import LLMChunk, LLMResponse, Usage

_DIMENSIONS: dict[str, dict[str, float]] = {
    "content": {
        "accuracy": 82.0,
        "logic": 80.0,
        "key_points": 84.0,
        "terminology": 86.0,
        "completeness": 78.0,
    },
    "expression": {
        "speech_rate": 85.0,
        "pause": 78.0,
        "filler": 82.0,
        "volume": 88.0,
        "fluency": 80.0,
    },
    "interaction": {
        "question_design": 80.0,
        "feedback": 76.0,
        "discipline": 85.0,
        "rhythm": 82.0,
    },
    "board": {
        "structure": 82.0,
        "completeness": 78.0,
        "consistency": 84.0,
        "usage": 80.0,
    },
}

_ISSUES: dict[str, list[dict[str, Any]]] = {
    "content": [
        {
            "start_ms": 12_000,
            "end_ms": 18_000,
            "severity": "medium",
            "evidence": "该段讲解缺少与参考材料对应的关键结论。",
            "suggestion": "补充关键结论，并用一句话总结与目标的联系。",
        }
    ],
    "expression": [
        {
            "start_ms": 30_000,
            "end_ms": 42_000,
            "severity": "medium",
            "evidence": "该段连续出现多次口头禅，停顿偏少。",
            "suggestion": "放慢语速，在段落之间留出 1—2 秒停顿。",
        }
    ],
    "interaction": [
        {
            "start_ms": 60_000,
            "end_ms": 70_000,
            "severity": "low",
            "evidence": "提问后等待时间较短。",
            "suggestion": "提问后等待 3—5 秒，再点名或补充。",
        }
    ],
    "board": [
        {
            "start_ms": 5_000,
            "end_ms": 25_000,
            "severity": "low",
            "evidence": "板书内容与讲解主线基本一致，但层次不够清晰。",
            "suggestion": "用编号或分栏组织板书，突出主线。",
        }
    ],
}


class EvaluationMockProvider:
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
        system = messages[0].get("content", "") if messages else ""
        match = re.search(r"维度=([a-z_]+)", system)
        dimension = match.group(1) if match else "content"
        payload: dict[str, Any] = {
            "sub_scores": _DIMENSIONS.get(dimension, _DIMENSIONS["content"]),
            "confidence": 0.85,
            "issues": _ISSUES.get(dimension, []),
        }
        return LLMResponse(
            text=json.dumps(payload, ensure_ascii=False),
            model_version="mock",
            usage=Usage(prompt_tokens=600, completion_tokens=180, total_tokens=780),
            latency_ms=0,
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
        response = await self.chat(
            messages, model, timeout_s, temperature=temperature, thinking=thinking
        )
        yield LLMChunk(text=response.text, model_version="mock", is_final=True)
