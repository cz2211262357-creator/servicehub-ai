from dataclasses import dataclass
from datetime import datetime

from pydantic import BaseModel, ConfigDict


class JobDTO(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: int
    tenant_id: int
    kind: str
    state: str
    payload: dict
    execution_version: int
    attempts: int
    error_code: str | None
    available_at: datetime
    finished_at: datetime | None


@dataclass(frozen=True, slots=True)
class JobLease:
    job_id: int
    owner: str
    version: int
    expires_at: datetime
    tenant_id: int
    kind: str
    payload: dict
