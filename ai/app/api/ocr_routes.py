from __future__ import annotations

from fastapi import APIRouter, Depends, Request

from app.api.deps import verify_service_token
from app.schemas.ocr import OCRRequest, OCRResponse

router = APIRouter(
    prefix="/internal/v1",
    tags=["ocr"],
    dependencies=[Depends(verify_service_token)],
)


@router.post("/ocr", response_model=OCRResponse)
async def recognize(payload: OCRRequest, request: Request) -> OCRResponse:
    return await request.app.state.container.ocr.recognize(payload)
