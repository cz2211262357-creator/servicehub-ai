import hashlib
import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
DATA = ROOT / "evals" / "servicehub"
TOOLS = {"search_policy", "lookup_order", "prepare_after_sale"}
CATEGORIES = {"policy", "order_lookup", "after_sale", "security", "recovery_multiturn"}
REQUIRED = {
    "id",
    "category",
    "user_input",
    "turns",
    "initial_state",
    "expected_final_state",
    "allowed_tools",
    "required_tools",
    "forbidden_operations",
    "reference_answer",
    "relevant_policy_ids",
}


def test_gold_set_has_exactly_thirty_distinct_cases_per_category():
    payload = json.loads((DATA / "cases.json").read_text(encoding="utf-8"))
    assert payload["synthetic"] is True
    assert payload["as_of"] == "2026-10-29T00:00:00Z"
    cases = payload["cases"]
    assert Counter(case["category"] for case in cases) == {c: 30 for c in CATEGORIES}
    assert len({case["id"] for case in cases}) == 150
    assert len({case["user_input"] for case in cases}) == 150
    for case in cases:
        assert REQUIRED <= case.keys(), case["id"]
        assert case["turns"][0] == case["user_input"]
        assert all(isinstance(turn, str) and turn.strip() for turn in case["turns"])
        assert set(case["allowed_tools"]) <= TOOLS
        assert set(case["required_tools"]) <= set(case["allowed_tools"])
        assert case["expected_final_state"]["ticket_count"] == 0
        assert case["expected_final_state"]["order_status"] == "unchanged"
        assert "execute_refund" in case["forbidden_operations"]
        assert "create_ticket_without_confirmation" in case["forbidden_operations"]
        assert len(case["reference_answer"]) >= 12
        for order in case["initial_state"]["orders"]:
            assert {
                "order_number",
                "owner",
                "status",
                "amount",
                "category",
                "delivered_days_ago",
                "facts",
            } <= order.keys()
            assert order["owner"] in {"self", "other", "other_tenant"}
            assert order["status"] in {"DELIVERED", "SHIPPED", "PAID", "CANCELLED"}
            assert order["order_number"].startswith(("SYN-", "DEV-"))


def test_corpus_manifest_integrity_and_gold_relevance():
    manifest = json.loads((DATA / "manifest.json").read_text(encoding="utf-8"))
    payload = json.loads((DATA / "cases.json").read_text(encoding="utf-8"))
    policies = set()
    for entry in manifest["documents"]:
        path = DATA / entry["path"]
        raw = path.read_bytes()
        assert hashlib.sha256(raw).hexdigest() == entry["sha256"]
        assert entry["policy_id"] not in policies
        policies.add(entry["policy_id"])
        assert "合成商家" in raw.decode("utf-8")
        assert "2026-10-29" in raw.decode("utf-8")
    assert len(policies) == 30
    assert (
        hashlib.sha256((DATA / "cases.json").read_bytes()).hexdigest()
        == manifest["gold_set"]["sha256"]
    )
    for case in payload["cases"]:
        assert set(case["relevant_policy_ids"]) <= policies, case["id"]
        if case["category"] == "policy":
            assert case["relevant_policy_ids"], case["id"]
        if case["expected_final_state"]["outcome"] == "draft_prepared":
            assert "prepare_after_sale" in case["required_tools"]
            assert case["expected_tool_arguments"]["prepare_after_sale"]["reason_code"] in {
                "NO_REASON",
                "QUALITY",
                "WRONG_ITEM",
                "LOGISTICS_DAMAGE",
            }


def test_dev_cases_are_separate_from_frozen_gold_set():
    gold = json.loads((DATA / "cases.json").read_text(encoding="utf-8"))["cases"]
    dev = json.loads((DATA / "dev_cases.json").read_text(encoding="utf-8"))
    assert dev["split"] == "development_only"
    assert len(dev["cases"]) == 5
    assert not ({c["id"] for c in gold} & {c["id"] for c in dev["cases"]})
    assert not ({c["user_input"] for c in gold} & {c["user_input"] for c in dev["cases"]})
