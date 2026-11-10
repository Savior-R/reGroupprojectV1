from __future__ import annotations

import pytest

from app.capabilities.evaluation import EvaluationCapability, EvaluationContext
from app.prompts.evaluation import SUB_WEIGHTS
from app.worker.mock_provider import EvaluationMockProvider
from tests.evaluation import helpers


def _capability() -> EvaluationCapability:
    return EvaluationCapability(llm=EvaluationMockProvider(), model="mock")


@pytest.mark.parametrize("mode", ["free", "scenario"])
async def test_free_and_scenario_modes_produce_five_dimensions(mode: str) -> None:
    context = EvaluationContext(
        manifest=helpers.manifest(mode),
        texts=helpers.texts(),
        frames=helpers.frames(),
        events=helpers.events(),
        audio=helpers.audio(),
    )

    result = await _capability().evaluate(context)

    assert result.status == "completed"
    assert {item.dimension for item in result.dimensions} == {
        "content",
        "expression",
        "interaction",
        "board",
        "time",
    }
    assert result.partial_reasons == []
    assert result.prompt_version == "m4-eval-v1"
    assert result.rubric_version == "m4-rubric-v1"
    assert "total_score" not in result.model_dump()


async def test_interview_mode_produces_three_dimensions() -> None:
    context = EvaluationContext(
        manifest=helpers.manifest("interview"),
        texts=helpers.texts(),
        frames=helpers.frames(),
        events=helpers.events(),
        audio=helpers.audio(),
    )

    result = await _capability().evaluate(context)

    assert result.status == "completed"
    assert {item.dimension for item in result.dimensions} == {
        "content",
        "expression",
        "time",
    }


async def test_sub_scores_and_dimension_score_contract() -> None:
    context = EvaluationContext(
        manifest=helpers.manifest(),
        texts=helpers.texts(),
        frames=helpers.frames(),
        events=helpers.events(),
        audio=helpers.audio(),
    )

    result = await _capability().evaluate(context)

    for dimension in result.dimensions:
        assert set(dimension.sub_scores) == set(SUB_WEIGHTS[dimension.dimension])
        assert 0 <= dimension.score <= 100
        assert round(dimension.score, 1) == dimension.score
        assert 0 <= dimension.confidence <= 1
        for value in dimension.sub_scores.values():
            assert 0 <= value <= 100


async def test_missing_audio_makes_expression_missing_and_result_partial() -> None:
    context = EvaluationContext(
        manifest=helpers.manifest(),
        texts=helpers.texts(),
        frames=helpers.frames(),
        events=helpers.events(),
        audio=None,
    )

    result = await _capability().evaluate(context)

    assert result.status == "partial"
    assert "AUDIO_MISSING" in result.partial_reasons
    assert "expression" not in {item.dimension for item in result.dimensions}


async def test_no_usable_input_fails_without_dimensions() -> None:
    manifest = helpers.manifest("interview").model_copy(
        update={"session": helpers.ManifestSession(duration_ms=0)}
    )
    context = EvaluationContext(
        manifest=manifest,
        texts=[],
        frames=[],
        events=[],
        audio=None,
    )

    result = await _capability().evaluate(context)

    assert result.status == "failed"
    assert result.dimensions == []
    assert result.partial_reasons


async def test_missing_texts_keeps_only_time_and_reports_partial() -> None:
    context = EvaluationContext(
        manifest=helpers.manifest("interview"),
        texts=[],
        frames=[],
        events=[],
        audio=None,
    )

    result = await _capability().evaluate(context)

    assert result.status == "partial"
    assert [item.dimension for item in result.dimensions] == ["time"]
    assert "TRANSCRIPT_MISSING" in result.partial_reasons


async def test_issues_and_suggestions_always_have_dimension_and_timestamp() -> None:
    context = EvaluationContext(
        manifest=helpers.manifest(),
        texts=helpers.texts(),
        frames=helpers.frames(),
        events=helpers.events(),
        audio=helpers.audio(),
    )

    result = await _capability().evaluate(context)

    suggestions = [issue for issue in result.issues if issue.is_suggestion]
    assert 3 <= len(suggestions) <= 5
    assert [issue.suggestion_rank for issue in suggestions] == list(
        range(1, len(suggestions) + 1)
    )
    for issue in result.issues:
        assert issue.dimension in {
            "content",
            "expression",
            "interaction",
            "board",
            "time",
        }
        assert issue.start_ms is not None
        assert 0 <= issue.start_ms <= 900_000
        assert issue.evidence
        assert issue.suggestion


async def test_issue_times_are_clamped_to_session_duration() -> None:
    class _OutOfRangeProvider(EvaluationMockProvider):
        async def chat(self, messages, model, timeout_s, **kwargs):  # type: ignore[override]
            response = await super().chat(messages, model, timeout_s, **kwargs)
            response = response.__class__(
                text=response.text.replace("12000", "99999999").replace(
                    "18000", "99999999"
                ),
                model_version=response.model_version,
                usage=response.usage,
                latency_ms=response.latency_ms,
            )
            return response

    capability = EvaluationCapability(llm=_OutOfRangeProvider(), model="mock")
    context = EvaluationContext(
        manifest=helpers.manifest(),
        texts=helpers.texts(),
        frames=helpers.frames(),
        events=helpers.events(),
        audio=helpers.audio(),
    )

    result = await capability.evaluate(context)

    assert all(issue.start_ms <= 900_000 for issue in result.issues)
