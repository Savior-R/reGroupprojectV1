from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field, model_validator


class OCRRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    image_url: str | None = None
    image_base64: str | None = None
    model: str | None = None
    min_pixels: int = 3072
    max_pixels: int = 8_388_608

    @model_validator(mode="after")
    def require_image(self) -> "OCRRequest":
        if not self.image_url and not self.image_base64:
            raise ValueError("image_url or image_base64 is required")
        return self


class OCRBox(BaseModel):
    text: str
    x1: float
    y1: float
    x2: float
    y2: float
    confidence: float | None = Field(default=None, ge=0.0, le=1.0)


class OCRResponse(BaseModel):
    text: str
    boxes: list[OCRBox] = Field(default_factory=list)
    confidence: float | None = Field(default=None, ge=0.0, le=1.0)
    model_version: str
    latency_ms: int | None = None
