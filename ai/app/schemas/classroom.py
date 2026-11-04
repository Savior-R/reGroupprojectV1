from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class VirtualStudent(BaseModel):
    model_config = ConfigDict(extra="ignore")

    student_id: str
    name: str
    personality: str = ""
    ability: float = Field(default=0.5, ge=0.0, le=1.0)
    activity: float = Field(default=0.5, ge=0.0, le=1.0)
    question_rate: float = Field(default=0.5, ge=0.0, le=1.0)
    attention: float = Field(default=0.5, ge=0.0, le=1.0)
    discipline: float = Field(default=0.5, ge=0.0, le=1.0)


class ClassroomContext(BaseModel):
    model_config = ConfigDict(extra="ignore")

    session_id: str
    task_id: str
    mode: Literal["free", "scenario", "interview"] = "free"
    topic: str = ""
    recent_texts: list[str] = Field(default_factory=list)
    screen_text: str = ""
    scenario: str = ""
    student: VirtualStudent


class ClassroomQuestionRequest(BaseModel):
    context: ClassroomContext
    question_kind: Literal["student_question", "teacher_question"] = "student_question"
    max_chars: int = Field(default=120, ge=20, le=300)


class ClassroomAnswerRequest(BaseModel):
    context: ClassroomContext
    question: str
    teacher_feedback: str | None = None
    max_chars: int = Field(default=180, ge=20, le=500)


class ClassroomClassifyRequest(BaseModel):
    text: str
    recent_texts: list[str] = Field(default_factory=list)
    mode: Literal["free", "scenario", "interview"] = "free"


class ClassroomContentResponse(BaseModel):
    text: str
    context_sufficient: bool
    input_question_complete: bool
    output_complete: bool
    missing: list[str] = Field(default_factory=list)
    confidence: float = Field(default=0.0, ge=0.0, le=1.0)
    quality: Literal["correct", "partial", "incorrect"] | None = None
    model_version: str
    prompt_version: str


class ClassroomClassifyResponse(BaseModel):
    label: Literal["question", "not_question"]
    confidence: float = Field(default=0.0, ge=0.0, le=1.0)
    model_version: str
    prompt_version: str
