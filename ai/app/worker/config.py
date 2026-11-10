"""Environment configuration for the standalone M4 worker.

The worker deliberately does not read ``app.config`` so the web service and the worker can be
deployed with different credentials and concurrency.
"""

from __future__ import annotations

import os
import socket

from pydantic_settings import BaseSettings, SettingsConfigDict


def _default_consumer_name() -> str:
    return f"worker-{socket.gethostname()}-{os.getpid()}"


class WorkerSettings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    redis_url: str = "redis://localhost:6379/0"
    java_base_url: str = "http://localhost:8080"
    service_token: str = ""

    ai_mock: bool = False
    deepseek_api_key: str = ""
    deepseek_base_url: str = "https://api.deepseek.com"
    deepseek_model: str = "deepseek-flash"
    llm_timeout_s: float = 30.0
    dimension_timeout_s: float = 45.0
    job_timeout_s: float = 90.0

    worker_concurrency: int = 3
    llm_concurrency: int = 8

    stream: str = "evaluation:jobs"
    dead_stream: str = "evaluation:dead"
    consumer_group: str = "eval-workers"
    consumer_name: str = ""
    batch_size: int = 10
    block_ms: int = 2000
    reclaim_idle_ms: int = 600_000
    reclaim_interval_ms: int = 60_000
    max_deliveries: int = 3

    text_page_size: int = 500
    frame_page_size: int = 200
    event_page_size: int = 500
    max_texts: int = 20_000
    max_frames: int = 10_000
    max_events: int = 20_000

    def consumer(self) -> str:
        return self.consumer_name or _default_consumer_name()
