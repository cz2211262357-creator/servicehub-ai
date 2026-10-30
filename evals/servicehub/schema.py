"""Dataset loading with integrity checks, independent of model providers."""

import hashlib
import json
from pathlib import Path

DATA_ROOT = Path(__file__).resolve().parent
TOOLS = {"search_policy", "lookup_order", "prepare_after_sale"}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def load_dataset(dataset="cases", root=DATA_ROOT):
    if dataset not in {"cases", "dev"}:
        raise ValueError("dataset must be cases or dev")
    root = Path(root)
    manifest_bytes = (root / "manifest.json").read_bytes()
    manifest = json.loads(manifest_bytes)
    filename = "cases.json" if dataset == "cases" else "dev_cases.json"
    raw = (root / filename).read_bytes()
    data = json.loads(raw)
    cases = data["cases"]
    if dataset == "cases" and sha256(raw) != manifest["gold_set"]["sha256"]:
        raise ValueError("Frozen gold SHA256 mismatch")
    for doc in manifest["documents"]:
        if sha256((root / doc["path"]).read_bytes()) != doc["sha256"]:
            raise ValueError("Frozen corpus SHA256 mismatch: " + doc["policy_id"])
    if len({case["id"] for case in cases}) != len(cases):
        raise ValueError("Duplicate case IDs")
    for case in cases:
        for key in (
            "category",
            "initial_state",
            "expected_final_state",
            "turns",
            "reference_answer",
        ):
            if key not in case:
                raise ValueError("Missing case field " + key)
        if not case["turns"] or case["turns"][0] != case["user_input"]:
            raise ValueError("Invalid first turn")
        if not set(case["allowed_tools"]) <= TOOLS:
            raise ValueError("Unregistered tool in dataset")
    return cases, {
        "dataset": dataset,
        "dataset_version": manifest["dataset_version"],
        "as_of": manifest["as_of"],
        "data_sha256": sha256(raw),
        "manifest_sha256": sha256(manifest_bytes),
        "snapshot_sha256": manifest["snapshot_sha256"],
        "synthetic": True,
        "case_count": len(cases),
    }
