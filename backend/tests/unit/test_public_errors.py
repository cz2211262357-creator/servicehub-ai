import pytest
from servicehub.conversations.schemas import AIReplyDTO


def test_reply_dto_accepts_optional_public_error_message():
    reply = AIReplyDTO(
        run_id=1,
        state="failed",
        generation_epoch=1,
        attempts=1,
        error_message="知识检索暂不可用，请稍后重试或转人工。",
    )
    assert reply.model_dump()["error_message"] == "知识检索暂不可用，请稍后重试或转人工。"


@pytest.mark.parametrize(
    "code, expected",
    [
        ("knowledge_unavailable", "知识检索暂不可用，请稍后重试或转人工。"),
        ("retrieval_unavailable", "知识检索暂不可用，请稍后重试或转人工。"),
        ("model_timeout", "模型响应超时或暂不可用，请稍后重试或转人工。"),
        ("model_unavailable", "模型响应超时或暂不可用，请稍后重试或转人工。"),
        ("private-provider-secret", "暂时无法完成本次回答，请稍后重试或转人工。"),
        (None, "暂时无法完成本次回答，请稍后重试或转人工。"),
    ],
)
def test_public_error_message_uses_fixed_allowlist(code, expected):
    from servicehub.conversations.public_errors import public_error_message

    assert public_error_message(code) == expected
