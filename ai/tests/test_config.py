import pytest

from app.config import Settings
from app.container import build_container
from app.providers.mock import MockProvider


def test_mock_mode_builds_all_three_capabilities():
    container = build_container(Settings(ai_mock=True))

    assert isinstance(container.classroom._llm, MockProvider)
    assert isinstance(container.ocr._provider, MockProvider)
    assert isinstance(container.asr._provider, MockProvider)


def test_live_mode_requires_all_provider_keys():
    with pytest.raises(RuntimeError) as raised:
        build_container(Settings(ai_mock=False))

    message = str(raised.value)
    assert "DEEPSEEK_API_KEY" in message
    assert "OCR_API_KEY" in message
    assert "ASR_API_KEY" in message
