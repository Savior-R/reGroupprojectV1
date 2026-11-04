from __future__ import annotations

from dataclasses import dataclass

from app.capabilities.asr_gateway import ASRGateway
from app.capabilities.classroom_content import ClassroomContentCapability
from app.capabilities.ocr_gateway import OCRGateway
from app.config import Settings
from app.providers.asr_cloud import ASRCloudProvider
from app.providers.deepseek import DeepSeekProvider
from app.providers.mock import MockLLMProvider, MockProvider
from app.providers.ocr_cloud import OCRCloudProvider


@dataclass
class AppContainer:
    settings: Settings
    classroom: ClassroomContentCapability
    ocr: OCRGateway
    asr: ASRGateway


def build_container(settings: Settings) -> AppContainer:
    if settings.ai_mock:
        provider = MockProvider()
        return AppContainer(
            settings=settings,
            classroom=ClassroomContentCapability(
                llm=MockLLMProvider(),
                model="mock",
                timeout_s=settings.llm_timeout_s,
            ),
            ocr=OCRGateway(provider=provider),
            asr=ASRGateway(provider=provider),
        )

    missing = [
        name
        for name, value in (
            ("DEEPSEEK_API_KEY", settings.deepseek_api_key),
            ("OCR_API_KEY", settings.ocr_api_key),
            ("ASR_API_KEY", settings.asr_api_key),
        )
        if not value
    ]
    if missing:
        raise RuntimeError("Missing AI provider configuration: " + ", ".join(missing))

    llm = DeepSeekProvider(
        api_key=settings.deepseek_api_key,
        base_url=settings.deepseek_base_url,
        model=settings.deepseek_model,
    )
    ocr_provider = OCRCloudProvider(
        api_key=settings.ocr_api_key,
        endpoint=settings.ocr_endpoint,
        model=settings.ocr_model,
    )
    asr_provider = ASRCloudProvider(
        api_key=settings.asr_api_key,
        endpoint=settings.asr_endpoint,
        model=settings.asr_model,
        workspace_id=settings.asr_workspace_id or None,
    )
    return AppContainer(
        settings=settings,
        classroom=ClassroomContentCapability(
            llm=llm,
            model=settings.deepseek_model,
            timeout_s=settings.llm_timeout_s,
        ),
        ocr=OCRGateway(provider=ocr_provider),
        asr=ASRGateway(provider=asr_provider),
    )
