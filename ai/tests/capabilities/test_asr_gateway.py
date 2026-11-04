import pytest

from app.capabilities.asr_gateway import ASRGateway
from app.providers.base import ASRChunk, ASRConfig


class FakeASR:
    async def stream(self, audio, config):
        yield ASRChunk(text="同学", is_final=False, begin_ms=0, end_ms=100)
        async for _ in audio:
            pass
        yield ASRChunk(text="同学们好", is_final=True, begin_ms=0, end_ms=300)


async def audio():
    yield b"a"
    yield b"b"


@pytest.mark.asyncio
async def test_stream_passes_provider_chunks_through():
    gateway = ASRGateway(provider=FakeASR())

    chunks = [chunk async for chunk in gateway.stream(audio(), ASRConfig())]

    assert [chunk.text for chunk in chunks] == ["同学", "同学们好"]
    assert chunks[-1].is_final is True
