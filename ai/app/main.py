from __future__ import annotations

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from app.api.asr_routes import router as asr_router
from app.api.classroom_routes import router as classroom_router
from app.api.ocr_routes import router as ocr_router
from app.config import Settings, get_settings
from app.container import AppContainer, build_container
from app.providers.base import ProviderError


def create_app(
    container: AppContainer | None = None,
    settings: Settings | None = None,
) -> FastAPI:
    resolved_settings = settings or get_settings()
    resolved_container = container or build_container(resolved_settings)

    app = FastAPI(
        title="Shijiangtai AI Service",
        version="0.1.0",
        docs_url="/docs",
    )
    app.state.container = resolved_container
    app.include_router(classroom_router)
    app.include_router(ocr_router)
    app.include_router(asr_router)

    @app.get("/healthz")
    async def healthz() -> dict[str, str]:
        return {"status": "ok"}

    @app.exception_handler(ProviderError)
    async def provider_error_handler(
        request: Request,
        exc: ProviderError,
    ) -> JSONResponse:
        status = _status_for(exc)
        return JSONResponse(
            status_code=status,
            content={"code": exc.code, "message": exc.message},
        )

    return app


def _status_for(exc: ProviderError) -> int:
    if exc.code == "RATE_LIMITED":
        return 429
    if exc.code == "TIMEOUT":
        return 504
    if exc.code == "BAD_REQUEST":
        return 400
    return 502
