from dataclasses import dataclass
from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, EmailStr, Field, SecretStr

Role = Literal["admin", "agent", "customer"]


@dataclass(frozen=True, slots=True)
class Principal:
    user_id: int
    tenant_id: int
    role: str
    session_version: int = 1
    session_digest: str = ""
    csrf_token: str = ""
    email: str = ""
    display_name: str = ""


class UserDTO(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: int
    tenant_id: int
    email: str
    display_name: str
    role: Role
    is_active: bool
    created_at: datetime


class LoginInput(BaseModel):
    tenant_id: int = Field(gt=0)
    email: EmailStr
    password: SecretStr = Field(min_length=1, max_length=128)


class SessionDTO(BaseModel):
    user: UserDTO
    csrf_token: str


class MemberInput(BaseModel):
    email: EmailStr
    display_name: str = Field(min_length=1, max_length=80)
    role: Role = "agent"
    password: SecretStr = Field(min_length=12, max_length=128)


class MemberUpdate(BaseModel):
    role: Role | None = None
    is_active: bool | None = None
    display_name: str | None = Field(default=None, min_length=1, max_length=80)


class MemberPage(BaseModel):
    items: list[UserDTO]
    next_cursor: int | None = None
