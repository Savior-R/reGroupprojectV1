from __future__ import annotations

from collections.abc import AsyncIterator

from app.providers.base import ASRChunk, ASRConfig, ASRProvider


class ASRGateway:
    def __init__(self, *, provider: ASRProvider) -> None:
        self._provider = provider

    async def stream(
        self,
        audio: AsyncIterator[bytes],
        config: ASRConfig,
    ) -> AsyncIterator[ASRChunk]:
        async for chunk in self._provider.stream(audio, config):
            yield chunk
