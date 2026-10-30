from datetime import UTC, datetime, timedelta

import pytest

NOW = datetime(2026, 10, 29, 0, 0, tzinfo=UTC)


def facts(**overrides):
    return {
        "category": "ordinary",
        "signed_at": "2026-10-22T15:59:00Z",
        "return_condition": "intact",
        "exclusion_confirmed": False,
        "quality_issue": True,
        "warranty_until": "2027-10-29T00:00:00Z",
        "requires_manual_review": False,
        **overrides,
    }


def evaluate(data=None, kind="RETURN", reason="NO_REASON", status="DELIVERED", now=NOW):
    from servicehub.after_sales.eligibility import evaluate_eligibility

    return evaluate_eligibility(status, data, kind, reason, now)


@pytest.mark.parametrize(
    "signed_at,allowed",
    [
        ("2026-10-21T16:00:00Z", True),  # Shanghai October 22, calendar day seven.
        ("2026-10-21T15:59:59Z", False),  # Shanghai October 21, calendar day eight.
        ("2026-10-29T00:00:00Z", True),
        ("2026-10-29T00:00:01Z", False),
    ],
)
def test_no_reason_uses_shanghai_calendar_days_and_rejects_future_receipt(signed_at, allowed):
    assert evaluate(facts(signed_at=signed_at)).eligible is allowed


@pytest.mark.parametrize("condition", ["intact", "opened_inspected"])
def test_inspecting_ordinary_goods_does_not_exclude_return(condition):
    assert evaluate(facts(return_condition=condition)).eligible


@pytest.mark.parametrize("category", ["fresh", "custom"])
def test_confirmed_excluded_categories_cannot_use_no_reason(category):
    result = evaluate(facts(category=category))
    assert not result.eligible
    assert result.action == "deny"


@pytest.mark.parametrize(
    "confirmed,eligible,action",
    [(True, False, "deny"), (False, True, "allow"), (None, False, "clarify")],
)
def test_digital_exclusion_requires_trusted_activation_or_download_fact(
    confirmed, eligible, action
):
    result = evaluate(facts(category="digital", exclusion_confirmed=confirmed))
    assert result.eligible is eligible
    assert result.action == action


@pytest.mark.parametrize("kind", ["RETURN", "EXCHANGE"])
@pytest.mark.parametrize("reason", ["QUALITY", "WRONG_ITEM", "LOGISTICS_DAMAGE"])
def test_merchant_quality_promise_includes_day_fifteen(kind, reason):
    assert evaluate(facts(signed_at="2026-10-13T16:00:00Z"), kind, reason).eligible
    assert not evaluate(facts(signed_at="2026-10-13T15:59:59Z"), kind, reason).eligible
    assert not evaluate(facts(quality_issue=False), kind, reason).eligible


@pytest.mark.parametrize("status", ["SHIPPED", "PENDING", "CANCELLED"])
def test_returns_and_exchanges_require_delivered_status(status):
    assert not evaluate(facts(), status=status).eligible


def test_repair_requires_confirmed_quality_and_current_warranty():
    assert evaluate(facts(warranty_until=NOW.isoformat()), "REPAIR", "QUALITY").eligible
    assert not evaluate(
        facts(warranty_until=(NOW - timedelta(microseconds=1)).isoformat()), "REPAIR", "QUALITY"
    ).eligible
    assert not evaluate(facts(quality_issue=False), "REPAIR", "QUALITY").eligible
    assert not evaluate(facts(warranty_until=None), "REPAIR", "QUALITY").eligible


@pytest.mark.parametrize(
    "data",
    [
        None,
        {},
        facts(category="unknown"),
        facts(signed_at="invalid"),
        facts(signed_at="2026-10-22T00:00:00"),
        facts(requires_manual_review=True),
        facts(requires_manual_review=None),
        facts(quality_issue="true"),
        facts(return_condition=None),
    ],
)
def test_missing_invalid_or_manual_facts_never_authorize_draft(data):
    assert not evaluate(data).eligible


def test_other_reason_and_untrusted_fact_fields_cannot_authorize():
    assert evaluate(facts(), reason="OTHER").action == "clarify"
    assert not evaluate(facts(model_says_eligible=True)).eligible
    assert not evaluate(facts(), kind="REFUND").eligible


@pytest.mark.parametrize("category", ["apparel", "electronics", "household", "personal_care"])
def test_controlled_physical_categories_are_not_guessed_from_product_names(category):
    assert evaluate(facts(category=category)).eligible


def test_controlled_home_category_uses_ordinary_goods_rule():
    assert evaluate(facts(category="home")).eligible
