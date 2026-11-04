from __future__ import annotations

import json
from typing import Literal, TypeVar

from pydantic import BaseModel, ConfigDict, Field

from app.prompts.classroom import (
    PROMPT_VERSION,
    answer_messages,
    classify_messages,
    question_messages,
)
from app.providers.base import LLMProvider, ProviderError
from app.schemas.classroom import (
    ClassroomAnswerRequest,
    ClassroomClassifyRequest,
    ClassroomClassifyResponse,
    ClassroomContentResponse,
    ClassroomQuestionRequest,
)

SchemaT = TypeVar("SchemaT", bound=BaseModel)


class _QuestionOutput(BaseModel):
    model_config = ConfigDict(extra="ignore")

    text: str
    context_sufficient: bool
    input_question_complete: bool
    output_complete: bool
    missing: list[str] = Field(default_factory=list)
    confidence: float = Field(default=0.0, ge=0.0, le=1.0)


class _AnswerOutput(BaseModel):
    model_config = ConfigDict(extra="ignore")

    text: str
    quality: Literal["correct", "partial", "incorrect"] = "partial"
    confidence: float = Field(default=0.0, ge=0.0, le=1.0)


class _ClassifyOutput(BaseModel):
    model_config = ConfigDict(extra="ignore")

    label: Literal["question", "not_question"]
    confidence: float = Field(default=0.0, ge=0.0, le=1.0)


class ClassroomContentCapability:
    def __init__(
        self,
        *,
        llm: LLMProvider,
        model: str = "deepseek-flash",
        timeout_s: float = 30.0,
        prompt_version: str = PROMPT_VERSION,
    ) -> None:
        self._llm = llm
        self._model = model
        self._timeout_s = timeout_s
        self._prompt_version = prompt_version

    async def question(self, request: ClassroomQuestionRequest) -> ClassroomContentResponse:
        messages = question_messages(request.context)
        output = await self._json_call(messages, _QuestionOutput)
        return ClassroomContentResponse(
            text=output.text,
            context_sufficient=output.context_sufficient,
            input_question_complete=output.input_question_complete,
            output_complete=output.output_complete,
            missing=output.missing,
            confidence=output.confidence,
            model_version=self._model,
            prompt_version=self._prompt_version,
        )

    async def answer(self, request: ClassroomAnswerRequest) -> ClassroomContentResponse:
        messages = answer_messages(
            request.context,
            request.question,
            request.teacher_feedback,
        )
        output = await self._json_call(messages, _AnswerOutput)
        return ClassroomContentResponse(
            text=output.text,
            context_sufficient=True,
            input_question_complete=True,
            output_complete=True,
            missing=[],
            confidence=output.confidence,
            quality=output.quality,
            model_version=self._model,
            prompt_version=self._prompt_version,
        )

    async def classify(
        self,
        request: ClassroomClassifyRequest,
    ) -> ClassroomClassifyResponse:
        output = await self._json_call(classify_messages(request.text), _ClassifyOutput)
        return ClassroomClassifyResponse(
            label=output.label,
            confidence=output.confidence,
            model_version=self._model,
            prompt_version=self._prompt_version,
        )

    async def _json_call(
        self,
        messages: list[dict[str, str]],
        schema: type[SchemaT],
    ) -> SchemaT:
        last_error: Exception | None = None
        for attempt in range(2):
            try:
                response = await self._llm.chat(
                    messages,
                    self._model,
                    self._timeout_s,
                    response_format={"type": "json_object"},
                    temperature=0.7,
                    thinking=False,
                )
            except ProviderError as exc:
                if exc.retryable and attempt == 0:
                    continue
                raise
            try:
                return schema.model_validate(self._parse_json(response.text))
            except Exception as exc:  # JSON decode or schema validation
                last_error = exc
        raise ProviderError(
            "INVALID_LLM_JSON",
            f"LLM did not return valid JSON after 2 attempts: {last_error}",
            retryable=True,
        )

    @staticmethod
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
