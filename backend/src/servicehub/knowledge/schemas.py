from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field


class VersionDTO(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: int
    number: int
    filename: str
    media_type: str
    size_bytes: int
    status: str
    error_code: str | None
    job_id: int | None
    created_at: datetime


class DocumentDTO(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: int
    title: str
    enabled: bool
    version: int
    created_at: datetime
    active_version_id: int | None = None
    latest_version: VersionDTO | None = None


class DocumentPage(BaseModel):
    items: list[DocumentDTO]
    next_cursor: int | None = None


class EnableInput(BaseModel):
    enabled: bool
    version: int = Field(ge=1)
