import json

import pytest

from app.capabilities.classroom_content import ClassroomContentCapability
from app.prompts.classroom import PROMPT_VERSION
from app.providers.base import LLMResponse, ProviderError
from app.schemas.classroom import (
    ClassroomAnswerRequest,
    ClassroomClassifyRequest,
    ClassroomContext,
    ClassroomQuestionRequest,
    VirtualStudent,
)


class FakeLLM:
    def __init__(self, outputs: list[str]) -> None:
        self.outputs = outputs
        self.calls: list[dict[str, object]] = []

    async def chat(self, messages, model, timeout_s, **kwargs):
        self.calls.append(
            {
                "messages": messages,
                "model": model,
                "timeout_s": timeout_s,
                "kwargs": kwargs,
            }
        )
        return LLMResponse(
            text=self.outputs.pop(0),
            model_version="deepseek-flash",
            latency_ms=12,
        )


def request() -> ClassroomQuestionRequest:
    return ClassroomQuestionRequest(
        context=ClassroomContext(
            session_id="session-1",
            task_id="task-1",
            mode="free",
            topic="分数的基本性质",
            recent_texts=["教师：我们看这个例子", "教师：三分之一等于六分之二吗？"],
            screen_text="1/3 = 2/6",
            student=VirtualStudent(
                student_id="student-1",
                name="小明",
                personality="好奇，喜欢追问",
                ability=0.7,
                activity=0.6,
            ),
        )
    )


@pytest.mark.asyncio
async def test_question_returns_flags_and_uses_json_mode():
    provider = FakeLLM(
        [
            json.dumps(
                {
                    "text": "老师，为什么分子分母都乘二，分数大小不变？",
                    "context_sufficient": True,
                    "input_question_complete": True,
                    "output_complete": True,
                    "missing": [],
                    "confidence": 0.86,
                },
                ensure_ascii=False,
            )
        ]
    )
    capability = ClassroomContentCapability(llm=provider, model="deepseek-flash")

    result = await capability.question(request())

    assert result.text == "老师，为什么分子分母都乘二，分数大小不变？"
    assert result.context_sufficient is True
    assert result.input_question_complete is True
    assert result.output_complete is True
    assert result.missing == []
    assert result.confidence == 0.86
    assert result.model_version == "deepseek-flash"
    assert result.prompt_version == PROMPT_VERSION

    call = provider.calls[0]
    assert call["model"] == "deepseek-flash"
    assert call["kwargs"]["response_format"] == {"type": "json_object"}
    assert "分数的基本性质" in call["messages"][1]["content"]


@pytest.mark.asyncio
async def test_question_retries_once_when_llm_returns_invalid_json():
    provider = FakeLLM(
        [
            "not-json",
            json.dumps(
                {
                    "text": "老师，能再讲一遍吗？",
                    "context_sufficient": False,
                    "input_question_complete": False,
                    "output_complete": True,
                    "missing": ["recent_texts"],
                    "confidence": 0.42,
                },
                ensure_ascii=False,
            ),
        ]
    )
    capability = ClassroomContentCapability(llm=provider, model="deepseek-flash")

    result = await capability.question(request())

    assert result.text == "老师，能再讲一遍吗？"
    assert result.missing == ["recent_texts"]
    assert len(provider.calls) == 2


@pytest.mark.asyncio
async def test_question_fails_after_two_invalid_json_responses():
    provider = FakeLLM(["not-json", "still-not-json"])
    capability = ClassroomContentCapability(llm=provider, model="deepseek-flash")

    with pytest.raises(ProviderError) as raised:
        await capability.question(request())

    assert raised.value.code == "INVALID_LLM_JSON"
    assert len(provider.calls) == 2


@pytest.mark.asyncio
async def test_question_retries_retryable_provider_error():
    class FlakyLLM(FakeLLM):
        async def chat(self, messages, model, timeout_s, **kwargs):
            self.calls.append({"messages": messages})
            if len(self.calls) == 1:
                raise ProviderError("RATE_LIMITED", "slow down", retryable=True)
            return LLMResponse(
                text=json.dumps(
                    {
                        "text": "老师，能再讲一遍吗？",
                        "context_sufficient": True,
                        "input_question_complete": True,
                        "output_complete": True,
                        "missing": [],
                        "confidence": 0.5,
                    },
                    ensure_ascii=False,
                ),
                model_version="deepseek-flash",
            )

    provider = FlakyLLM([])
    capability = ClassroomContentCapability(llm=provider, model="deepseek-flash")

    result = await capability.question(request())

    assert result.text == "老师，能再讲一遍吗？"
    assert len(provider.calls) == 2


@pytest.mark.asyncio
async def test_answer_returns_student_text_and_quality():
    provider = FakeLLM(
        [
            json.dumps(
                {
                    "text": "因为分子和分母同时乘 2，所以大小不变。",
                    "quality": "correct",
                    "confidence": 0.91,
                },
                ensure_ascii=False,
            )
        ]
    )
    capability = ClassroomContentCapability(llm=provider, model="deepseek-flash")

    result = await capability.answer(
        ClassroomAnswerRequest(
            context=request().context,
            question="三分之一为什么等于六分之二？",
            teacher_feedback="再想一想",
        )
    )

    assert result.text == "因为分子和分母同时乘 2，所以大小不变。"
    assert result.quality == "correct"
    assert result.confidence == 0.91
    assert "三分之一为什么等于六分之二？" in provider.calls[0]["messages"][1]["content"]


@pytest.mark.asyncio
async def test_classify_returns_question_label():
    provider = FakeLLM(
        [json.dumps({"label": "question", "confidence": 0.88}, ensure_ascii=False)]
    )
    capability = ClassroomContentCapability(llm=provider, model="deepseek-flash")

    result = await capability.classify(
        ClassroomClassifyRequest(text="同学们，三分之一等于六分之二吗？")
    )

    assert result.label == "question"
    assert result.confidence == 0.88
    assert "三分之一等于六分之二吗" in provider.calls[0]["messages"][1]["content"]


@pytest.mark.asyncio
async def test_all_classroom_calls_disable_thinking():
    provider = FakeLLM(
        [
            json.dumps(
                {
                    "text": "老师，为什么分子分母都乘二，分数大小不变？",
                    "context_sufficient": True,
                    "input_question_complete": True,
                    "output_complete": True,
                    "missing": [],
                    "confidence": 0.86,
                },
                ensure_ascii=False,
            ),
            json.dumps(
                {
                    "text": "因为分子和分母同时乘 2，所以大小不变。",
                    "quality": "correct",
                    "confidence": 0.91,
                },
                ensure_ascii=False,
            ),
            json.dumps({"label": "question", "confidence": 0.88}, ensure_ascii=False),
        ]
    )
    capability = ClassroomContentCapability(llm=provider, model="deepseek-flash")

    await capability.question(request())
    await capability.answer(
        ClassroomAnswerRequest(
            context=request().context,
            question="三分之一为什么等于六分之二？",
        )
    )
    await capability.classify(
        ClassroomClassifyRequest(text="同学们，三分之一等于六分之二吗？")
    )

    assert [call["kwargs"]["thinking"] for call in provider.calls] == [
        False,
        False,
        False,
    ]
