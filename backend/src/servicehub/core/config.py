from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import Field, SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="SERVICEHUB_",
        env_file=".env",
        extra="ignore",
        hide_input_in_errors=True,
    )

    environment: Literal["development", "test", "production"] = "development"
    project_root: Path = Path(__file__).resolve().parents[4]
    database_url: str = Field(
        default="mysql+asyncmy://servicehub:dev-password@127.0.0.1:3306/servicehub",
        repr=False,
    )
    redis_url: str = Field(default="redis://127.0.0.1:6379/0", repr=False)
    allowed_origins: list[str] = Field(
        default_factory=lambda: [
            "http://localhost:5173",
            "http://127.0.0.1:5173",
            "http://localhost:8080",
            "http://127.0.0.1:8080",
        ]
    )
    cookie_secure: bool = False
    session_cookie_name: str = "servicehub_session"
    session_ttl_seconds: int = Field(default=28800, ge=60, le=28800)
    upload_dir: Path = Path(".data/uploads")
    max_upload_bytes: int = 10 * 1024 * 1024
    model_mode: Literal["demo", "online"] = "online"
    model_base_url: str = ""
    model_name: str = ""
    model_api_key: SecretStr = SecretStr("")
    embedding_base_url: str = ""
    embedding_model: str = "text-embedding-v4"
    embedding_api_key: SecretStr = SecretStr("")
    embedding_dimensions: int = Field(default=1024, ge=1, le=8192)
    model_timeout_seconds: float = Field(default=45, gt=0, le=120)
    agent_timeout_seconds: float = Field(default=120, gt=0, le=300)
    max_tool_steps: int = Field(default=4, ge=1, le=8)
    max_input_characters: int = Field(default=8000, ge=1, le=32000)
    max_output_tokens: int = Field(default=1600, ge=64, le=8192)
    rate_limit_requests: int = Field(default=30, ge=1)
    rate_limit_window_seconds: int = Field(default=60, ge=1)
    global_model_slots: int = Field(default=4, ge=1, le=64)
    worker_concurrency: int = Field(default=4, ge=1, le=32)
    job_lease_seconds: int = Field(default=60, ge=15)
    worker_poll_seconds: float = Field(default=1, gt=0)
    live_buffer_size: int = Field(default=128, ge=2, le=1024)
    live_auth_interval_seconds: float = Field(default=2, ge=0.1, le=30)
    demo_model_delay_seconds: float = Field(default=0.03, ge=0, le=10)

    @property
    def upload_path(self) -> Path:
        return (self.project_root / self.upload_dir).resolve()

    @model_validator(mode="after")
    def validate_boundaries(self):
        self.project_root = self.project_root.resolve()
        if not self.upload_path.is_relative_to(self.project_root):
            raise ValueError("Upload storage must remain inside the project directory")
        if not self.database_url.startswith("mysql+asyncmy://"):
            raise ValueError("The business database must use MySQL with the asyncmy driver")
        if self.environment == "production":
            if self.model_mode != "online":
                raise ValueError("Production requires explicit online model mode")
            if not self.cookie_secure:
                raise ValueError("Production requires secure session cookies")
            if not self.allowed_origins or any(
                origin == "*" or not origin.startswith("https://")
                for origin in self.allowed_origins
            ):
                raise ValueError("Production requires explicit HTTPS origins")
            if "dev-password" in self.database_url:
                raise ValueError("Production database credentials must be supplied explicitly")
        return self


@lru_cache
def load_settings() -> Settings:
    return Settings()
