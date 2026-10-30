_KNOWLEDGE = "知识检索暂不可用，请稍后重试或转人工。"
_MODEL = "模型响应超时或暂不可用，请稍后重试或转人工。"
_GENERIC = "暂时无法完成本次回答，请稍后重试或转人工。"

_PUBLIC_MESSAGES = {
    "knowledge_unavailable": _KNOWLEDGE,
    "retrieval_unavailable": _KNOWLEDGE,
    "retrieval_failed": _KNOWLEDGE,
    "model_timeout": _MODEL,
    "model_unavailable": _MODEL,
}


def public_error_message(code: str | None) -> str:
    return _PUBLIC_MESSAGES.get(code, _GENERIC)
