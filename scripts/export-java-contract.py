"""Derive additive Java API types without changing the frozen Python baseline."""

import json
from pathlib import Path

workspace = Path(__file__).resolve().parents[1]
baseline = json.loads((workspace / "contracts/python-v1/openapi.json").read_text(encoding="utf-8"))
schemas = baseline["components"]["schemas"]
schemas["RuntimeStatus"]["properties"]["dependencies"] = {
    "type": "object",
    "additionalProperties": {"type": "string", "enum": ["ok", "unavailable"]},
}
schemas["JobDTO"]["properties"].update(
    {
        "manual_retries": {"type": "integer", "minimum": 0},
        "lifetime_attempts": {"type": "integer", "minimum": 0},
        "budget_revision": {"type": "integer", "minimum": 0},
        "deadline_at": {"anyOf": [{"type": "string", "format": "date-time"}, {"type": "null"}]},
    }
)
nullable_int = {"anyOf": [{"type": "integer"}, {"type": "null"}]}
schemas["AIReplyDTO"]["properties"]["execution_version"] = {"type": "integer", "minimum": 0}
schemas["ConversationDTO"]["properties"]["active_topic_id"] = nullable_int
schemas["MessageDTO"]["properties"].update(
    {
        "topic_id": nullable_int,
        "routing_state": {"type": "string"},
        "context_status": {"type": "string"},
        "accepted_generation_epoch": {"type": "integer", "minimum": 0},
        "trigger_run_id": nullable_int,
        "trigger_job_id": nullable_int,
    }
)
schemas["LiveEvent"]["properties"].update(
    {name: nullable_int for name in ("topic_id", "run_id", "execution_version")}
)
schemas["TopicDTO"] = {
    "type": "object",
    "required": ["id", "title", "status", "last_activity_at"],
    "properties": {
        "id": {"type": "integer", "minimum": 1},
        "title": {"type": "string"},
        "status": {"type": "string", "enum": ["ACTIVE", "SUSPENDED", "COMPLETED"]},
        "last_activity_at": {"type": "string", "format": "date-time"},
    },
}
schemas["TopicPage"] = {
    "type": "object",
    "required": ["items", "active_topic_id"],
    "properties": {
        "items": {"type": "array", "items": {"$ref": "#/components/schemas/TopicDTO"}},
        "active_topic_id": nullable_int,
    },
}
schemas["InterruptInput"] = {
    "type": "object",
    "required": ["expected_generation_epoch"],
    "properties": {
        "expected_generation_epoch": {"type": "integer", "minimum": 0},
    },
}
schemas["TopicSwitchInput"] = {
    "type": "object",
    "required": ["mode", "expected_generation_epoch"],
    "properties": {
        "mode": {"type": "string", "enum": ["NEW", "RESUME"]},
        "topic_id": {"type": "integer", "minimum": 1},
        "expected_generation_epoch": {"type": "integer", "minimum": 0},
        "pending_message_id": {"type": "integer", "minimum": 1},
    },
}
for suffix, method, operation, result, request in [
    ("interrupt", "post", "interruptConversation", "ConversationDTO", "InterruptInput"),
    ("topics", "get", "listConversationTopics", "TopicPage", None),
    ("topics/switch", "post", "switchConversationTopic", "ConversationDTO", "TopicSwitchInput"),
]:
    endpoint = {
        "operationId": operation,
        "parameters": [
            {
                "name": "conversation_id",
                "in": "path",
                "required": True,
                "schema": {"type": "integer", "minimum": 1},
            },
        ],
        "responses": {
            "200": {
                "description": "Success",
                "content": {
                    "application/json": {"schema": {"$ref": "#/components/schemas/" + result}}
                },
            }
        },
    }
    if request:
        endpoint["requestBody"] = {
            "required": True,
            "content": {
                "application/json": {"schema": {"$ref": "#/components/schemas/" + request}}
            },
        }
    baseline["paths"]["/api/v1/conversations/{conversation_id}/" + suffix] = {method: endpoint}
assert (
    sum(
        len([m for m in v if m in ("get", "post", "patch", "delete", "put")])
        for v in baseline["paths"].values()
    )
    == 42
)
output = workspace / ".cache/java-openapi.json"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_bytes((json.dumps(baseline, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
print("Exported42 HTTP contracts and additive topic/fencing schemas:", output)
