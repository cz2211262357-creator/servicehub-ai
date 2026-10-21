import pytest
from pydantic import ValidationError


def settings_type():
    try:
        from servicehub.core.config import Settings
    except ModuleNotFoundError:
        pytest.fail("Production configuration validation has not been implemented")
    return Settings


def production_settings(**overrides):
    values = dict(
        _env_file=None,
        environment="production",
        model_mode="online",
        cookie_secure=True,
        allowed_origins=["https://support.example.com"],
        database_url="mysql+asyncmy://app:fixture-only-secure-value@db:3306/app",
    )
    values.update(overrides)
    return settings_type()(**values)


def test_production_rejects_demo_model():
    with pytest.raises(ValidationError):
        production_settings(model_mode="demo")


def test_production_rejects_wildcard_origin():
    with pytest.raises(ValidationError):
        production_settings(allowed_origins=["*"])


def test_production_requires_secure_cookie():
    with pytest.raises(ValidationError):
        production_settings(cookie_secure=False)


def test_production_accepts_explicit_secure_settings():
    assert production_settings().environment == "production"


def test_private_upload_storage_resolves_inside_project(tmp_path):
    settings = settings_type()(
        _env_file=None, environment="test", project_root=tmp_path, upload_dir=".data/uploads"
    )
    assert settings.upload_path == tmp_path / ".data" / "uploads"
