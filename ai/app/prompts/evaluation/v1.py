"""Versioned M4 evaluation prompts (06 §7)."""

from __future__ import annotations

import json
from typing import Any

PROMPT_VERSION = "m4-eval-v1"
RUBRIC_VERSION = "m4-rubric-v1"

DIMENSIONS: dict[str, list[str]] = {
    "content": ["accuracy", "logic", "key_points", "terminology", "completeness"],
    "expression": ["speech_rate", "pause", "filler", "volume", "fluency"],
    "interaction": ["question_design", "feedback", "discipline", "rhythm"],
    "board": ["structure", "completeness", "consistency", "usage"],
    "time": ["phases", "allocation", "pacing", "overtime"],
}

SUB_WEIGHTS: dict[str, dict[str, int]] = {
    "content": {
        "accuracy": 30,
        "logic": 20,
        "key_points": 20,
        "terminology": 15,
        "completeness": 15,
    },
    "expression": {
        "speech_rate": 20,
        "pause": 20,
        "filler": 20,
        "volume": 20,
        "fluency": 20,
    },
    "interaction": {
        "question_design": 30,
        "feedback": 30,
        "discipline": 20,
        "rhythm": 20,
    },
    "board": {
        "structure": 30,
        "completeness": 25,
        "consistency": 25,
        "usage": 20,
    },
    "time": {
        "phases": 25,
        "allocation": 30,
        "pacing": 25,
        "overtime": 20,
    },
}

DIMENSION_GUIDE: dict[str, str] = {
    "content": "依据教师/考生转写与参考材料或答案要点，评价准确性、逻辑性、要点覆盖、术语与完整性。",
    "expression": "依据转写时间戳、口头禅统计与音频特征，评价语速、停顿、口头禅、音量与流畅度。不得评价普通话标准度。",
    "interaction": "依据师生问答文本与事件统计，评价提问设计、反馈质量、纪律控制与课堂节奏。",
    "board": "依据 OCR 时间线与关键帧描述，评价结构、完整性、一致性、PPT/板书使用。",
    "time": "依据时间戳、阶段/题目窗口与推荐时长，评价阶段划分、时间分配、节奏与超时。",
}

_JSON_CONTRACT = {
    "sub_scores": {name: "0-100 一位小数" for name in DIMENSIONS["content"]},
    "confidence": "0-1",
    "issues": [
        {
            "start_ms": "session 相对毫秒",
            "end_ms": "session 相对毫秒或 null",
            "severity": "high|medium|low",
            "evidence": "引用原文/事件，不超过 500 字",
            "suggestion": "可执行建议，不超过 500 字",
        }
    ],
}


def dimension_messages(dimension: str, view: dict[str, Any]) -> list[dict[str, str]]:
    sub_items = DIMENSIONS[dimension]
    contract = dict(_JSON_CONTRACT)
    contract["sub_scores"] = {name: "0-100 一位小数" for name in sub_items}
    system = (
        "你是教师资格证课堂评测助手。"
        f"维度={dimension}。{DIMENSION_GUIDE[dimension]}"
        " 只返回 JSON，不要输出 Markdown。"
        f" sub_scores 必须且只能包含这些键：{', '.join(sub_items)}。"
        " 问题必须带 start_ms；没有证据不要扣分。"
        f" 输出结构示例：{json.dumps(contract, ensure_ascii=False)}"
    )
    user = json.dumps(view, ensure_ascii=False)
    return [
        {"role": "system", "content": system},
        {"role": "user", "content": user},
    ]
