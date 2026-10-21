import pytest
from fastapi.testclient import TestClient


def build_app():
    try:
        from servicehub.core.config import Settings
        from servicehub.main import create_app
    except ModuleNotFoundError:
        pytest.fail("The configured FastAPI application factory has not been implemented")
    return create_app(Settings(_env_file=None, environment="test"))


def test_liveness_is_available_without_external_services():
    with TestClient(build_app()) as client:
        result = client.get("/api/v1/health/live")
    assert result.status_code == 200
    assert result.json()["status"] == "ok"


def test_unexpected_error_has_request_id_and_does_not_expose_exception():
    app = build_app()

    @app.get("/api/v1/test-error")
    async def fail():
        raise RuntimeError("private-database-password")

    with TestClient(app, raise_server_exceptions=False) as client:
        result = client.get("/api/v1/test-error")
    assert result.status_code == 500
    assert result.json()["error"]["code"] == "internal_error"
    assert result.json()["error"]["request_id"] == result.headers["x-request-id"]
    assert "private-database-password" not in result.text


def test_business_conflict_keeps_status_and_machine_readable_code():
    app = build_app()
    from servicehub.core.errors import BusinessError

    @app.get("/api/v1/test-conflict")
    async def conflict():
        raise BusinessError("conversation_conflict", "Already claimed", 409)

    with TestClient(app) as client:
        result = client.get("/api/v1/test-conflict")
    assert result.status_code == 409
    assert result.json()["error"]["code"] == "conversation_conflict"


def test_validation_error_does_not_echo_private_input():
    app = build_app()
    from pydantic import BaseModel, Field

    class Input(BaseModel):
        password: str = Field(min_length=30)

    @app.post("/api/v1/test-validate")
    async def validate(payload: Input):
        return {"ok": True}

    with TestClient(app) as client:
        result = client.post("/api/v1/test-validate", json={"password": "secret-value"})
    assert result.status_code == 422
    assert "secret-value" not in result.text
