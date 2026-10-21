from fastapi.testclient import TestClient
from servicehub.core.config import Settings
from servicehub.main import create_app


def test_readiness_reports_503_for_unreachable_database_and_cache():
    settings = Settings(
        _env_file=None,
        environment="test",
        database_url="mysql+asyncmy://nobody:none@127.0.0.1:1/servicehub_test",
        redis_url="redis://127.0.0.1:1/15",
    )
    with TestClient(create_app(settings)) as client:
        result = client.get("/api/v1/health/ready")
    assert result.status_code == 503
    assert result.json()["error"]["code"] == "dependencies_unavailable"
    assert "nobody" not in result.text
