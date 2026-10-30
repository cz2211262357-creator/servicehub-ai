from decimal import Decimal

import pytest
from pydantic import ValidationError
from servicehub.core.config import Settings


def test_new_hybrid_is_explicit_default_and_legacy_is_available():
    assert Settings(_env_file=None).retrieval_backend == "redis_hybrid"
    assert (
        Settings(_env_file=None, retrieval_backend="mysql_legacy").retrieval_backend
        == "mysql_legacy"
    )
    with pytest.raises(ValidationError):
        Settings(_env_file=None, retrieval_backend="silent_fallback")


def test_prices_are_unknown_until_supplied_and_reject_negative_values():
    settings = Settings(_env_file=None)
    assert settings.model_input_price_per_million is None
    assert settings.model_output_price_per_million is None
    settings = Settings(_env_file=None, model_input_price_per_million="0.8")
    assert settings.model_input_price_per_million == Decimal("0.8")
    with pytest.raises(ValidationError):
        Settings(_env_file=None, model_input_price_per_million="-0.1")
