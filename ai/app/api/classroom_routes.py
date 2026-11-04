from __future__ import annotations

from fastapi import APIRouter, Depends, Request

from app.api.deps import verify_service_token
from app.schemas.classroom import (
    ClassroomAnswerRequest,
    ClassroomClassifyRequest,
    ClassroomClassifyResponse,
    ClassroomContentResponse,
    ClassroomQuestionRequest,
)

router = APIRouter(
    prefix="/internal/v1/classroom",
    tags=["classroom"],
    dependencies=[Depends(verify_service_token)],
)


@router.post("/question", response_model=ClassroomContentResponse)
async def question(
    payload: ClassroomQuestionRequest,
    request: Request,
) -> ClassroomContentResponse:
    return await request.app.state.container.classroom.question(payload)


@router.post("/answer", response_model=ClassroomContentResponse)
async def answer(
    payload: ClassroomAnswerRequest,
    request: Request,
) -> ClassroomContentResponse:
    return await request.app.state.container.classroom.answer(payload)


@router.post("/classify", response_model=ClassroomClassifyResponse)
async def classify(
    payload: ClassroomClassifyRequest,
    request: Request,
) -> ClassroomClassifyResponse:
    return await request.app.state.container.classroom.classify(payload)
