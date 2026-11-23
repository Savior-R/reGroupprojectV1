"""Rule metrics, partial semantics and LLM-backed time/suggestion paths (06 §6.3—§6.7)."""

from __future__ import annotations

import asyncio

import pytest

from app.capabilities.evaluation import EvaluationCapability, EvaluationContext, _event_stats
from app.providers.base import ProviderError
from app.schemas.evaluation import EventItem
from app.worker.mock_provider import EvaluationMockProvider
from tests.evaluation import helpers


def _context(*, frames=None, events=None, manifest=None) -> EvaluationContext:
    return EvaluationContext(
        manifest=manifest or helpers.manifest(),
        texts=helpers.texts(),
        frames=helpers.frames() if frames is None else frames,
        events=helpers.events() if events is None else events,
        audio=helpers.audio(),
    )


def test_interaction_stats_cover_the_documented_event_sources() -> None:
    events = [
        EventItem(id="1", seq=1, type="classroom.question_asked", timestamp_ms=1000,
                  actor="teacher", payload={"intent": "answer"}),
        EventItem(id="2", seq=2, type="classroom.question_asked", timestamp_ms=2000,
                  actor="teacher", payload={"intent": "flow"}),
        EventItem(id="3", seq=3, type="student.question", timestamp_ms=3000),
        EventItem(id="4", seq=4, type="student.hand_raised", timestamp_ms=4000),
        EventItem(id="5", seq=5, type="teacher.called_on", timestamp_ms=5000),
        EventItem(id="6", seq=6, type="student.answer", timestamp_ms=8000),
        EventItem(id="7", seq=7, type="student.follow_up", timestamp_ms=9000),
        EventItem(id="8", seq=8, type="teacher.marked", timestamp_ms=10_000,
                  payload={"result": "correct"}),
        EventItem(id="9", seq=9, type="teacher.marked", timestamp_ms=11_000,
                  payload={"result": "wrong"}),
        EventItem(id="10", seq=10, type="discipline.event", timestamp_ms=12_000),
        EventItem(id="11", seq=11, type="teacher.management_action", timestamp_ms=13_000),
        EventItem(id="12", seq=12, type="dialogue.started", timestamp_ms=14_000),
    ]

    stats = _event_stats(events)

    assert stats["question_count"] == 1  # only intent=answer
    assert stats["student_question_count"] == 1
    assert stats["hand_raise_count"] == 1
    assert stats["called_on_count"] == 1
    assert stats["answer_count"] == 1
    assert stats["follow_up_count"] == 1
    assert stats["marked_correct"] == 1
    assert stats["marked_wrong"] == 1
    assert stats["discipline_count"] == 1
    assert stats["management_action_count"] == 1
    assert stats["dialogue_rounds"] == 1
    assert stats["avg_response_ms"] == 3000


async def test_missing_zero_weight_dimension_is_completed_with_warning() -> None:
    manifest = helpers.manifest(
        weights={
            "content": 30,
            "expression": 20,
            "interaction": 20,
            "board": 0,
            "time": 30,
        }
    )
    context = EvaluationContext(
        manifest=manifest,
        texts=helpers.texts(),
        frames=[],
        events=helpers.events(),
        audio=helpers.audio(),
    )

    result = await EvaluationCapability(
        llm=EvaluationMockProvider(), model="mock"
    ).evaluate(context)

    assert result.status == "completed"
    assert "board" not in {item.dimension for item in result.dimensions}
    assert "ZERO_WEIGHT_DIMENSION_MISSING" in result.warnings
    assert "FRAMES_MISSING" not in result.partial_reasons


async def test_deadline_keeps_finished_dimensions_and_marks_the_rest() -> None:
    class _SlowBoardProvider(EvaluationMockProvider):
        async def chat(self, messages, model, timeout_s, **kwargs):  # type: ignore[override]
            if "维度=board" in messages[0].get("content", ""):
                await asyncio.sleep(5)
            return await super().chat(messages, model, timeout_s, **kwargs)

    capability = EvaluationCapability(llm=_SlowBoardProvider(), model="mock")

    result = await capability.evaluate(_context(), timeout_s=0.05)

    dimensions = {item.dimension for item in result.dimensions}
    assert "board" not in dimensions
    assert {"content", "expression", "interaction", "time"} <= dimensions
    assert result.status == "partial"
    assert "DIMENSION_TIMEOUT" in result.partial_reasons


async def test_interview_time_rules_flag_unanswered_questions() -> None:
    class _TimeFailProvider(EvaluationMockProvider):
        async def chat(self, messages, model, timeout_s, **kwargs):  # type: ignore[override]
            if "维度=time" in messages[0].get("content", ""):
                raise ProviderError("UPSTREAM", "time unavailable")
            return await super().chat(messages, model, timeout_s, **kwargs)

    events = [
        EventItem(id="q1", seq=1, type="interview.question_shown", timestamp_ms=1000),
        EventItem(id="a1", seq=2, type="interview.answer_ended", timestamp_ms=61_000),
        EventItem(id="q2", seq=3, type="interview.question_shown", timestamp_ms=62_000),
    ]
    capability = EvaluationCapability(llm=_TimeFailProvider(), model="mock")

    result = await capability.evaluate(
        EvaluationContext(
            manifest=helpers.manifest("interview"),
            texts=helpers.texts(),
            frames=[],
            events=events,
            audio=helpers.audio(),
        )
    )

    time_dimension = next(item for item in result.dimensions if item.dimension == "time")
    assert time_dimension.sub_scores["phases"] == 50.0
    assert "TIME_RULE_ONLY" in result.warnings
    assert any(
        issue.dimension == "time" and "未作答" in issue.evidence for issue in result.issues
    )


async def test_suggestions_follow_the_llm_selection_when_valid() -> None:
    class _SuggestionProvider(EvaluationMockProvider):
        async def chat(self, messages, model, timeout_s, **kwargs):  # type: ignore[override]
            system = messages[0].get("content", "")
            if "任务=suggestions" in system:
                from app.providers.base import LLMResponse, Usage

                return LLMResponse(
                    text='{"suggestions": ['
                    '{"index": 0, "suggestion": "改写建议 A"},'
                    '{"index": 2, "suggestion": "改写建议 B"}]}',
                    model_version="mock",
                    usage=Usage(prompt_tokens=100, completion_tokens=50),
                )
            return await super().chat(messages, model, timeout_s, **kwargs)

    result = await EvaluationCapability(
        llm=_SuggestionProvider(), model="mock"
    ).evaluate(_context())

    suggestions = [issue for issue in result.issues if issue.is_suggestion]
    assert len(suggestions) >= 3
    assert suggestions[0].suggestion == "改写建议 A"
    assert suggestions[1].suggestion == "改写建议 B"
    assert suggestions[0].dimension != suggestions[1].dimension
    assert all(issue.start_ms is not None for issue in suggestions)
    assert [issue.suggestion_rank for issue in suggestions] == list(
        range(1, len(suggestions) + 1)
    )


async def test_suggestions_fall_back_when_the_llm_output_is_invalid() -> None:
    class _BrokenSuggestionProvider(EvaluationMockProvider):
        async def chat(self, messages, model, timeout_s, **kwargs):  # type: ignore[override]
            system = messages[0].get("content", "")
            if "任务=suggestions" in system:
                from app.providers.base import LLMResponse

                return LLMResponse(text="not json", model_version="mock")
            return await super().chat(messages, model, timeout_s, **kwargs)

    result = await EvaluationCapability(
        llm=_BrokenSuggestionProvider(), model="mock"
    ).evaluate(_context())

    suggestions = [issue for issue in result.issues if issue.is_suggestion]
    assert 3 <= len(suggestions) <= 5
    assert all(issue.suggestion for issue in suggestions)
