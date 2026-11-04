from __future__ import annotations

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    ai_mock: bool = False
    service_token: str = ""

    deepseek_api_key: str = ""
    deepseek_base_url: str = "https://api.deepseek.com"
    deepseek_model: str = "deepseek-flash"
    llm_timeout_s: float = 30.0

    ocr_api_key: str = ""
    ocr_endpoint: str = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
    ocr_model: str = "qwen3.5-ocr"
    ocr_timeout_s: float = 15.0

    asr_api_key: str = ""
    asr_endpoint: str = "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
    asr_model: str = "paraformer-realtime-v2"
    asr_workspace_id: str = ""

    max_concurrency: int = 8


@lru_cache
def get_settings() -> Settings:
    return Settings()
