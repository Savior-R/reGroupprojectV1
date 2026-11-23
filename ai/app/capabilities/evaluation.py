"""M4 dimension evaluation capability (06 §6).

The capability returns dimension scores, sub-scores, issues and warnings only. It never
composes a total score or a final status — Java owns both.
"""

from __future__ import annotations

import asyncio
import json
import re
from dataclasses import dataclass, field
from typing import Any

from pydantic import BaseModel, ConfigDict, Field

from app.prompts.evaluation import (
    DIMENSIONS,
    PROMPT_VERSION,
    RUBRIC_VERSION,
    SUB_WEIGHTS,
    dimension_messages,
)
from app.providers.base import LLMProvider, ProviderError, Usage
from app.schemas.evaluation import (
    AudioFeatures,
    DimensionResult,
    EventItem,
    EvaluationResult,
    FrameItem,
    IssuePayload,
    Manifest,
    TextItem,
)

FILLER_WORDS = ("嗯", "啊", "那个", "就是", "然后", "这个", "额")
MAX_ISSUES_PER_DIMENSION = 5
MAX_TOTAL_ISSUES = 20
SEVERITY_ORDER = {"high": 0, "medium": 1, "low": 2}


class _DimensionOutput(BaseModel):
    model_config = ConfigDict(extra="ignore")

    sub_scores: dict[str, float] = Field(default_factory=dict)
    confidence: float = Field(default=0.7, ge=0.0, le=1.0)
    issues: list[dict[str, Any]] = Field(default_factory=list)


@dataclass
class EvaluationContext:
    manifest: Manifest
    texts: list[TextItem] = field(default_factory=list)
    frames: list[FrameItem] = field(default_factory=list)
    events: list[EventItem] = field(default_factory=list)
    audio: AudioFeatures | None = None

    @property
    def duration_ms(self) -> int:
        if self.manifest.session.duration_ms:
            return int(self.manifest.session.duration_ms)
        times = [item.at_ms for item in self.texts]
        times += [item.timestamp_ms for item in self.events]
        return max(times, default=0)


@dataclass
class _DimensionOutcome:
    result: DimensionResult | None
    issues: list[IssuePayload]
    warnings: list[str]
    partial_reasons: list[str]
    tokens: Usage


class EvaluationCapability:
    def __init__(
        self,
        *,
        llm: LLMProvider,
        model: str = "deepseek-flash",
        timeout_s: float = 30.0,
        dimension_timeout_s: float = 45.0,
        prompt_version: str = PROMPT_VERSION,
        rubric_version: str = RUBRIC_VERSION,
    ) -> None:
        self._llm = llm
        self._model = model
        self._timeout_s = timeout_s
        self._dimension_timeout_s = dimension_timeout_s
        self._prompt_version = prompt_version
        self._rubric_version = rubric_version

    async def evaluate(self, context: EvaluationContext) -> EvaluationResult:
        dimensions = self._applicable_dimensions(context.manifest.mode)
        outcomes = await asyncio.gather(
            *[self._guarded_dimension(name, context) for name in dimensions]
        )

        results: list[DimensionResult] = []
        issues: list[IssuePayload] = []
        warnings: list[str] = []
        partial_reasons: list[str] = []
        prompt_tokens = completion_tokens = 0
        for outcome in outcomes:
            if outcome.result is not None:
                results.append(outcome.result)
                issues.extend(outcome.issues)
            warnings.extend(outcome.warnings)
            partial_reasons.extend(outcome.partial_reasons)
            prompt_tokens += outcome.tokens.prompt_tokens
            completion_tokens += outcome.tokens.completion_tokens

        issues = self._cap_issues(issues, context.duration_ms)
        suggestions = self._suggestions(results, issues)
        issues = issues + suggestions

        required_missing = [
            name
            for name in dimensions
            if name not in {result.dimension for result in results}
            and context.manifest.weights.get(name, 0) > 0
        ]
        if not results:
            status = "failed"
        elif required_missing or partial_reasons:
            status = "partial"
        else:
            status = "completed"

        return EvaluationResult(
            status=status,
            model_version=self._model,
            prompt_version=self._prompt_version,
            rubric_version=self._rubric_version,
            dimensions=results,
            issues=issues,
            warnings=sorted(set(warnings)),
            partial_reasons=sorted(set(partial_reasons)),
            input_flags=context.manifest.flags,
            token_usage={
                "prompt": prompt_tokens,
                "completion": completion_tokens,
            },
        )

    @staticmethod
    def _applicable_dimensions(mode: str) -> list[str]:
        if mode == "interview":
            return ["content", "expression", "time"]
        return ["content", "expression", "interaction", "board", "time"]

    async def _guarded_dimension(
        self, dimension: str, context: EvaluationContext
    ) -> _DimensionOutcome:
        try:
            return await asyncio.wait_for(
                self._evaluate_dimension(dimension, context),
                timeout=self._dimension_timeout_s,
            )
        except asyncio.TimeoutError:
            return _DimensionOutcome(None, [], [], ["DIMENSION_TIMEOUT"], Usage())

    async def _evaluate_dimension(
        self, dimension: str, context: EvaluationContext
    ) -> _DimensionOutcome:
        try:
            if dimension == "content":
                return await self._content(context)
            if dimension == "expression":
                return await self._expression(context)
            if dimension == "interaction":
                return await self._interaction(context)
            if dimension == "board":
                return await self._board(context)
            return self._time(context)
        except ProviderError as exc:
            return _DimensionOutcome(
                None,
                [],
                [],
                ["INVALID_MODEL_OUTPUT"],
                Usage(),
            )
        except Exception:
            return _DimensionOutcome(None, [], [], ["DIMENSION_FAILED"], Usage())

    # ------------------------------------------------------------------ content

    async def _content(self, context: EvaluationContext) -> _DimensionOutcome:
        spoken = [
            item
            for item in context.texts
            if item.speaker_type in {"teacher", "examiner", "candidate"}
        ]
        if not context.texts:
            return _DimensionOutcome(None, [], [], ["TRANSCRIPT_MISSING"], Usage())

        config = context.manifest.config
        interview = context.manifest.mode == "interview"
        if interview and not config.get("answer_points"):
            return _DimensionOutcome(None, [], [], ["ANSWER_POINTS_MISSING"], Usage())

        warnings: list[str] = []
        view: dict[str, Any] = {
            "topic": config.get("topic"),
            "texts": [
                {"at_ms": item.at_ms, "speaker": item.speaker_type, "text": item.text}
                for item in spoken[:400]
            ],
        }
        missing_reference = not interview and not config.get("reference_material")
        if config.get("reference_material"):
            view["reference_material"] = str(config["reference_material"])[:20000]
        elif missing_reference:
            warnings.append("REFERENCE_MISSING")
        if config.get("answer_points"):
            view["answer_points"] = config["answer_points"]

        output, tokens = await self._json_call(dimension_messages("content", view))
        sub_scores = self._normalize_subscores("content", output.sub_scores)
        if "accuracy" in sub_scores and missing_reference:
            sub_scores.pop("accuracy", None)
        if not sub_scores:
            raise ProviderError("EMPTY_SUBSCORES", "content returned no sub-scores")
        return self._outcome(
            "content",
            sub_scores,
            output,
            context.duration_ms,
            warnings,
            tokens,
            confidence_cap=0.75 if missing_reference else None,
        )

    # --------------------------------------------------------------- expression

    async def _expression(self, context: EvaluationContext) -> _DimensionOutcome:
        if not context.texts:
            return _DimensionOutcome(None, [], [], ["TRANSCRIPT_MISSING"], Usage())
        if context.audio is None:
            return _DimensionOutcome(None, [], [], ["AUDIO_MISSING"], Usage())

        spoken = [item for item in context.texts if item.speaker_type == "teacher"]
        if not spoken:
            spoken = context.texts
        characters = sum(len(item.text) for item in spoken)
        span_ms = max(context.duration_ms, 1)
        speech_minutes = max(span_ms / 60000.0, 0.01)

        sub_scores: dict[str, float] = {}
        sub_scores["speech_rate"] = _score_speech_rate(characters / speech_minutes)
        if context.audio.long_pause_ratio is not None:
            sub_scores["pause"] = _linear_low(context.audio.long_pause_ratio, 0.05, 0.20)
        if context.audio.volume_low_ratio is not None:
            sub_scores["volume"] = _linear_low(context.audio.volume_low_ratio, 0.02, 0.30)
        filler_per_100 = _filler_per_100("".join(item.text for item in spoken), characters)
        sub_scores["filler"] = _linear_low(filler_per_100, 2.0, 10.0)

        output, tokens = await self._json_call(
            dimension_messages(
                "expression",
                {
                    "texts": [
                        {"at_ms": item.at_ms, "text": item.text}
                        for item in spoken[:200]
                    ],
                    "audio": context.audio.available(),
                    "filler_per_100": round(filler_per_100, 2),
                },
            )
        )
        fluency = output.sub_scores.get("fluency")
        if fluency is not None:
            sub_scores["fluency"] = _clamp_score(fluency)
        if len(sub_scores) < 2:
            return _DimensionOutcome(None, [], [], ["DIMENSION_FAILED"], tokens)

        return self._outcome(
            "expression",
            sub_scores,
            output,
            context.duration_ms,
            [],
            tokens,
            confidence_cap=0.8 if "fluency" not in sub_scores else None,
        )

    # -------------------------------------------------------------- interaction

    async def _interaction(self, context: EvaluationContext) -> _DimensionOutcome:
        if not context.events and not context.texts:
            return _DimensionOutcome(None, [], [], ["DIMENSION_FAILED"], Usage())

        stats = _event_stats(context.events)
        warnings: list[str] = []
        if not context.events:
            warnings.append("EMPTY_EVENTS")

        dialogue = [
            item
            for item in context.texts
            if item.speaker_type in {"teacher", "student"}
        ][:300]
        output, tokens = await self._json_call(
            dimension_messages(
                "interaction",
                {
                    "stats": stats,
                    "dialogue": [
                        {"at_ms": item.at_ms, "speaker": item.speaker_type, "text": item.text}
                        for item in dialogue
                    ],
                },
            )
        )
        sub_scores = self._normalize_subscores("interaction", output.sub_scores)
        if not sub_scores:
            raise ProviderError("EMPTY_SUBSCORES", "interaction returned no sub-scores")
        return self._outcome(
            "interaction",
            sub_scores,
            output,
            context.duration_ms,
            warnings,
            tokens,
            confidence_cap=0.75 if warnings else None,
        )

    # -------------------------------------------------------------------- board

    async def _board(self, context: EvaluationContext) -> _DimensionOutcome:
        ocr_texts = [frame for frame in context.frames if (frame.text or "").strip()]
        if not context.frames or not ocr_texts:
            return _DimensionOutcome(None, [], [], ["FRAMES_MISSING"], Usage())

        seen: set[str] = set()
        timeline: list[dict[str, Any]] = []
        total_chars = 0
        low_confidence = False
        for frame in sorted(ocr_texts, key=lambda item: item.timestamp_ms):
            key = frame.content_hash or (frame.text or "").strip()
            if key in seen:
                continue
            seen.add(key)
            text = (frame.text or "").strip()
            if total_chars + len(text) > 12000:
                break
            total_chars += len(text)
            if frame.confidence is not None and frame.confidence < 0.6:
                low_confidence = True
            timeline.append(
                {
                    "at_ms": frame.timestamp_ms,
                    "frame_type": frame.frame_type,
                    "text": text,
                }
            )

        warnings = ["OCR_LOW_CONFIDENCE"] if low_confidence else []
        output, tokens = await self._json_call(
            dimension_messages(
                "board",
                {
                    "ocr_timeline": timeline,
                    "keyframes": [
                        {"at_ms": frame.timestamp_ms, "type": frame.frame_type}
                        for frame in context.frames
                        if frame.object_key
                    ][:4],
                },
            )
        )
        sub_scores = self._normalize_subscores("board", output.sub_scores)
        if not sub_scores:
            raise ProviderError("EMPTY_SUBSCORES", "board returned no sub-scores")
        return self._outcome(
            "board",
            sub_scores,
            output,
            context.duration_ms,
            warnings,
            tokens,
            confidence_cap=0.75 if low_confidence else None,
        )

    # --------------------------------------------------------------------- time

    def _time(self, context: EvaluationContext) -> _DimensionOutcome:
        if not context.texts and not context.events and not context.duration_ms:
            return _DimensionOutcome(None, [], [], ["DIMENSION_FAILED"], Usage())

        duration_ms = max(context.duration_ms, 1)
        config = context.manifest.config
        recommended = _int(config.get("duration_recommended_seconds")) or 900
        maximum = _int(config.get("duration_max_seconds")) or max(recommended, 1200)
        duration_s = duration_ms / 1000.0

        overtime = _score_overtime(duration_s, recommended, maximum)
        phases = 1 if context.manifest.mode == "interview" else _count_phases(context.texts)
        phases_score = _score_phases(phases)
        allocation = _score_allocation(phases)
        pacing = _score_pacing(context.events, duration_s)

        issues: list[dict[str, Any]] = []
        if duration_s > maximum:
            issues.append(
                {
                    "start_ms": int(maximum * 1000),
                    "end_ms": int(duration_s * 1000),
                    "severity": "high",
                    "evidence": f"总时长 {duration_s:.0f}s 超过最高时长 {maximum}s",
                    "suggestion": "压缩导入或总结环节，核心讲解控制在建议时长内。",
                }
            )
        elif duration_s < recommended * 0.6:
            issues.append(
                {
                    "start_ms": 0,
                    "end_ms": int(duration_s * 1000),
                    "severity": "medium",
                    "evidence": f"总时长 {duration_s:.0f}s 明显短于建议时长 {recommended}s",
                    "suggestion": "为关键知识点补充例子或练习，避免内容过快结束。",
                }
            )
        if phases < 2:
            issues.append(
                {
                    "start_ms": 0,
                    "end_ms": int(duration_s * 1000),
                    "severity": "medium",
                    "evidence": "课堂缺少清晰的阶段切换",
                    "suggestion": "用导入、讲解、练习、总结等阶段组织课堂。",
                }
            )

        output = _DimensionOutput(
            sub_scores={
                "phases": phases_score,
                "allocation": allocation,
                "pacing": pacing,
                "overtime": overtime,
            },
            confidence=0.9,
            issues=issues,
        )
        return self._outcome(
            "time",
            output.sub_scores,
            output,
            duration_ms,
            [],
            Usage(),
        )

    # -------------------------------------------------------------- suggestions

    def _suggestions(
        self, dimensions: list[DimensionResult], issues: list[IssuePayload]
    ) -> list[IssuePayload]:
        ranked = sorted(
            issues,
            key=lambda issue: (
                SEVERITY_ORDER.get(issue.severity, 3),
                issue.start_ms if issue.start_ms is not None else 1 << 62,
            ),
        )
        selected = ranked[:5]
        suggestions: list[IssuePayload] = []
        for index, issue in enumerate(selected, start=1):
            suggestions.append(
                issue.model_copy(
                    update={
                        "is_suggestion": True,
                        "suggestion_rank": index,
                        "suggestion": issue.suggestion
                        or "针对该问题补充讲解或调整课堂节奏。",
                    }
                )
            )
        dimension_names = [result.dimension for result in dimensions] or ["content"]
        templates = {
            "content": "围绕目标知识点补充例子与课堂练习，强化重难点讲解。",
            "expression": "控制语速、减少口头禅，并在长段落之间留出停顿。",
            "interaction": "增加开放性提问与追问，给学生留出思考时间。",
            "board": "优化板书/PPT 结构，突出主线并保持图文一致。",
            "time": "按导入、讲解、练习、总结分配时间，并预留机动时间。",
        }
        index = len(suggestions)
        for dimension in dimension_names:
            if index >= 3:
                break
            index += 1
            suggestions.append(
                IssuePayload(
                    dimension=dimension,  # type: ignore[arg-type]
                    start_ms=0,
                    end_ms=None,
                    severity="low",
                    evidence="未发现明显问题",
                    suggestion=templates.get(dimension, "保持当前课堂节奏。"),
                    is_suggestion=True,
                    suggestion_rank=index,
                )
            )
        return suggestions

    # ----------------------------------------------------------------- helpers

    async def _json_call(
        self, messages: list[dict[str, str]]
    ) -> tuple[_DimensionOutput, Usage]:
        last_error: Exception | None = None
        for attempt in range(2):
            try:
                response = await self._llm.chat(
                    messages,
                    self._model,
                    self._timeout_s,
                    response_format={"type": "json_object"},
                    temperature=0.4,
                    thinking=False,
                )
            except ProviderError as exc:
                if exc.retryable and attempt == 0:
                    continue
                raise
            try:
                payload = _parse_json(response.text)
                return _DimensionOutput.model_validate(payload), response.usage or Usage()
            except Exception as exc:  # JSON decode or schema validation
                last_error = exc
        raise ProviderError(
            "INVALID_LLM_JSON",
            f"model did not return valid JSON after 2 attempts: {last_error}",
            retryable=False,
        )

    def _normalize_subscores(
        self, dimension: str, raw: dict[str, float]
    ) -> dict[str, float]:
        expected = DIMENSIONS[dimension]
        values: dict[str, float] = {}
        for key in expected:
            if key in raw and raw[key] is not None:
                values[key] = _clamp_score(raw[key])
        return values

    def _outcome(
        self,
        dimension: str,
        sub_scores: dict[str, float],
        output: _DimensionOutput,
        duration_ms: int,
        warnings: list[str],
        tokens: Usage,
        *,
        confidence_cap: float | None = None,
    ) -> _DimensionOutcome:
        weights = SUB_WEIGHTS[dimension]
        available = {key: value for key, value in weights.items() if key in sub_scores}
        total_weight = sum(available.values())
        if total_weight <= 0:
            raise ProviderError("EMPTY_SUBSCORES", f"{dimension} has no usable sub-scores")
        score = sum(sub_scores[key] * weight for key, weight in available.items())
        score = round(score / total_weight, 1)
        confidence = output.confidence
        if confidence_cap is not None:
            confidence = min(confidence, confidence_cap)
        result = DimensionResult(
            dimension=dimension,  # type: ignore[arg-type]
            score=score,
            sub_scores={key: round(sub_scores[key], 1) for key in available},
            sub_confidences={key: round(confidence, 2) for key in available},
            confidence=round(confidence, 2),
        )
        issues = self._issues(dimension, output.issues, duration_ms)
        return _DimensionOutcome(result, issues, warnings, [], tokens)

    def _issues(
        self, dimension: str, raw_issues: list[dict[str, Any]], duration_ms: int
    ) -> list[IssuePayload]:
        issues: list[IssuePayload] = []
        for item in raw_issues[:MAX_ISSUES_PER_DIMENSION]:
            if not isinstance(item, dict):
                continue
            start = _clamp_time(_int(item.get("start_ms")), duration_ms)
            end = _clamp_time(_int(item.get("end_ms")), duration_ms)
            if start is None:
                start = 0
            if end is not None and end < start:
                end = start
            severity = str(item.get("severity") or "medium")
            if severity not in SEVERITY_ORDER:
                severity = "medium"
            evidence = str(item.get("evidence") or "").strip()
            suggestion = str(item.get("suggestion") or "").strip()
            if not evidence:
                continue
            issues.append(
                IssuePayload(
                    dimension=dimension,  # type: ignore[arg-type]
                    start_ms=start,
                    end_ms=end,
                    severity=severity,  # type: ignore[arg-type]
                    evidence=evidence[:500],
                    suggestion=(suggestion or "针对该问题调整讲解方式。")[:500],
                    is_suggestion=False,
                )
            )
        return issues

    @staticmethod
    def _cap_issues(issues: list[IssuePayload], duration_ms: int) -> list[IssuePayload]:
        capped: list[IssuePayload] = []
        per_dimension: dict[str, int] = {}
        for issue in issues:
            if per_dimension.get(issue.dimension, 0) >= MAX_ISSUES_PER_DIMENSION:
                continue
            if len(capped) >= MAX_TOTAL_ISSUES:
                break
            per_dimension[issue.dimension] = per_dimension.get(issue.dimension, 0) + 1
            if issue.start_ms is not None:
                issue = issue.model_copy(
                    update={"start_ms": min(issue.start_ms, max(duration_ms, 0))}
                )
            capped.append(issue)
        return capped


# ------------------------------------------------------------------ scoring


def _parse_json(text: str) -> object:
    cleaned = text.strip()
    if cleaned.startswith("```"):
        lines = cleaned.splitlines()
        if lines and lines[0].startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        cleaned = "\n".join(lines).strip()
    return json.loads(cleaned)


def _clamp_score(value: float) -> float:
    return round(min(100.0, max(0.0, float(value))), 1)


def _clamp_time(value: int | None, duration_ms: int) -> int | None:
    if value is None:
        return None
    return max(0, min(int(value), max(duration_ms, 0)))


def _int(value: Any) -> int | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _linear_low(value: float, best: float, worst: float) -> float:
    if value <= best:
        return 100.0
    if value >= worst:
        return 0.0
    return round(100.0 * (worst - value) / (worst - best), 1)


def _score_speech_rate(chars_per_minute: float) -> float:
    if 220 <= chars_per_minute <= 280:
        return 100.0
    if chars_per_minute < 120 or chars_per_minute > 400:
        return 0.0
    if chars_per_minute < 220:
        return round((chars_per_minute - 120) / 100 * 100, 1)
    return round((400 - chars_per_minute) / 120 * 100, 1)


def _filler_per_100(text: str, characters: int) -> float:
    if characters <= 0:
        return 0.0
    count = sum(len(re.findall(word, text)) for word in FILLER_WORDS)
    return count * 100.0 / characters


def _event_stats(events: list[EventItem]) -> dict[str, int]:
    stats = {
        "question_count": 0,
        "student_question_count": 0,
        "hand_raise_count": 0,
        "answer_count": 0,
        "follow_up_count": 0,
        "discipline_count": 0,
    }
    mapping = {
        "classroom.question_asked": "question_count",
        "student.question": "student_question_count",
        "student.hand_raised": "hand_raise_count",
        "student.answer": "answer_count",
        "student.follow_up": "follow_up_count",
        "discipline.event": "discipline_count",
        "classroom.discipline_level_changed": "discipline_count",
    }
    for event in events:
        key = mapping.get(event.type)
        if key is not None:
            stats[key] += 1
    return stats


def _count_phases(texts: list[TextItem]) -> int:
    """A phase is a gap of at least 60 s between spoken segments."""
    times = sorted(item.at_ms for item in texts)
    if not times:
        return 0
    phases = 1
    for previous, current in zip(times, times[1:]):
        if current - previous >= 60_000:
            phases += 1
    return phases


def _score_phases(phases: int) -> float:
    if 3 <= phases <= 6:
        return 100.0
    if phases in {2, 7, 8}:
        return 80.0
    if phases <= 1:
        return 50.0
    return 60.0


def _score_allocation(phases: int) -> float:
    if phases <= 1:
        return 50.0
    ideal = 1.0 / phases
    # Without per-phase durations, reward a reasonable number of phases.
    return round(min(100.0, 60.0 + 40.0 * min(phases, 5) / 5), 1)


def _score_pacing(events: list[EventItem], duration_s: float) -> float:
    if duration_s <= 0:
        return 60.0
    transitions = len(events) * 600.0 / duration_s  # per 10 minutes
    if 4 <= transitions <= 10:
        return 100.0
    if transitions < 4:
        return round(max(40.0, 40.0 + transitions * 15), 1)
    return round(max(40.0, 100.0 - (transitions - 10) * 6), 1)


def _score_overtime(duration_s: float, recommended: int, maximum: int) -> float:
    if duration_s <= recommended:
        return 100.0
    if duration_s <= recommended * 1.2:
        return 80.0
    if duration_s <= maximum:
        return 60.0
    return 30.0
