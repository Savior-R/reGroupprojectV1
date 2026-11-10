"""Pydantic models shared by the M4 evaluation capability and worker.

The JSON field names mirror the Java internal API exactly (snake_case) so the same
models can be used for the manifest, paged input and callback payloads.
"""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field

DimensionName = Literal["content", "expression", "interaction", "board", "time"]
Severity = Literal["high", "medium", "low"]


class ManifestSession(BaseModel):
    model_config = ConfigDict(extra="ignore")

    session_type: str = "task"
    status: str = ""
    duration_ms: int | None = None
    stream_offsets: dict[str, Any] = Field(default_factory=dict)
    started_at: str | None = None
    ended_at: str | None = None


class Recording(BaseModel):
    model_config = ConfigDict(extra="ignore")

    stream_id: str
    stream_type: str
    url: str
    duration_ms: int | None = None
    offset_ms: int = 0
    mime: str | None = None


class ManifestCounts(BaseModel):
    model_config = ConfigDict(extra="ignore")

    texts: int = 0
    frames: int = 0
    events: int = 0


class Manifest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    evaluation_id: str
    session_id: str
    source_type: str
    source_id: str
    mode: Literal["free", "scenario", "interview"]
    session: ManifestSession = Field(default_factory=ManifestSession)
    config: dict[str, Any] = Field(default_factory=dict)
    recordings: list[Recording] = Field(default_factory=list)
    counts: ManifestCounts = Field(default_factory=ManifestCounts)
    weights: dict[str, int] = Field(default_factory=dict)
    rubric_version: str = "m4-rubric-v1"
    flags: dict[str, Any] = Field(default_factory=dict)


class TextItem(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: str
    speaker_type: str
    speaker_ref: str | None = None
    source: str = "asr"
    kind: str = "utterance"
    text: str
    start_ms: int | None = None
    end_ms: int | None = None
    at_ms: int = 0
    confidence: float | None = None
    model_version: str | None = None
    prompt_version: str | None = None


class FrameItem(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: str
    timestamp_ms: int
    text: str | None = None
    confidence: float | None = None
    frame_type: str = "normal"
    object_key: str | None = None
    content_hash: str | None = None
    status: str = "processed"


class EventItem(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: str
    seq: int
    type: str
    timestamp_ms: int
    actor: str | None = None
    target: str | None = None
    payload: dict[str, Any] = Field(default_factory=dict)


class Page(BaseModel):
    model_config = ConfigDict(extra="ignore")

    items: list[dict[str, Any]] = Field(default_factory=list)
    total: int = 0
    page: int = 0
    size: int = 0


class AudioFeatures(BaseModel):
    speech_rate: float | None = None
    pause_ratio: float | None = None
    long_pause_ratio: float | None = None
    filler_per_100: float | None = None
    volume_low_ratio: float | None = None

    def available(self) -> dict[str, float]:
        return {
            key: float(value)
            for key, value in self.model_dump().items()
            if value is not None
        }


class IssuePayload(BaseModel):
    model_config = ConfigDict(extra="ignore")

    dimension: DimensionName
    start_ms: int | None = None
    end_ms: int | None = None
    severity: Severity = "medium"
    evidence: str = ""
    suggestion: str = ""
    is_suggestion: bool = False
    suggestion_rank: int | None = None


class DimensionResult(BaseModel):
    model_config = ConfigDict(extra="ignore")

    dimension: DimensionName
    score: float = Field(ge=0, le=100)
    sub_scores: dict[str, float] = Field(default_factory=dict)
    sub_confidences: dict[str, float] = Field(default_factory=dict)
    confidence: float = Field(ge=0, le=1)


class EvaluationResult(BaseModel):
    model_config = ConfigDict(extra="ignore")

    status: Literal["completed", "partial", "failed"] = "completed"
    model_version: str
    prompt_version: str
    rubric_version: str
    dimensions: list[DimensionResult] = Field(default_factory=list)
    issues: list[IssuePayload] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)
    partial_reasons: list[str] = Field(default_factory=list)
    input_flags: dict[str, Any] = Field(default_factory=dict)
    token_usage: dict[str, int] = Field(default_factory=dict)


class JobMessage(BaseModel):
    model_config = ConfigDict(extra="ignore")

    job_id: str
    evaluation_id: str
    source_type: str
    source_id: str
    session_id: str
    attempt: int = Field(ge=1)
    published_at: str | None = None
