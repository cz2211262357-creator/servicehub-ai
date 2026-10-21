import json
from pathlib import Path

from servicehub.main import create_app

root = Path(__file__).resolve().parents[1]
target = root / ".cache" / "openapi.json"
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(
    json.dumps(create_app().openapi(), ensure_ascii=False, indent=2), encoding="utf-8"
)
print("Exported application OpenAPI without dependency connections")
