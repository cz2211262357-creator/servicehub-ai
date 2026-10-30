"""A deliberately small grammar for direct customer draft-withdrawal instructions.

This is not a general natural-language intent classifier. Unknown wording, questions,
quoted statements and conflicting requests leave the draft unchanged for clarification.
Only customer messages consume this result; model replies cannot invalidate drafts.
"""

import re
from typing import Literal

DraftMessageIntent = Literal["KEEP", "WITHDRAW", "REPLACE"]
_PREFIX = r"^\s*(?:(?:请|麻烦)\s*)?(?:帮我\s*)?(?:我\s*)?"
_BOUNDARY = r"(?=$|[\s，,。；;！!]|的(?:退货|换货|维修|售后|申请))"
_TARGET = r"(?:(?:这(?:次|个|笔)|刚才|之前)(?:的)?)?"
_APPLICATION = r"(?:售后(?:申请)?|退货(?:申请)?|换货(?:申请)?|维修(?:申请)?|申请|草稿)"
_WITHDRAW = re.compile(
    _PREFIX
    + r"(?:先别退了|不退了|先不要退了|不要退货了|(?:取消|撤回)"
    + _TARGET
    + _APPLICATION
    + r")(?:吧|了)?"
    + _BOUNDARY
)
# Bare identifiers must look like order identifiers; arbitrary words/quantities are ambiguous.
_ORDER_ID = r"(?:[A-Za-z][A-Za-z0-9_]*-[A-Za-z0-9_-]+|[A-Za-z]+[0-9][A-Za-z0-9_-]*|[0-9]{6,})"
_REPLACE = re.compile(
    _PREFIX
    + r"(?:改(?:成|为|到)|换(?:成|为|到))\s*"
    + r"(?:另(?:一)?(?:个|笔)订单|其他订单|订单\s*[:：#]?\s*[A-Za-z0-9][A-Za-z0-9_-]*|"
    + _ORDER_ID
    + r")"
    + _BOUNDARY
)
_CONFLICT = re.compile(
    r"还是(?:要|先|继续)?(?:退|申请|保留)|(?:不要|别|先别)(?:真的)?(?:取消|撤回)"
    r"|(?:保留|继续)(?:这个|这次|原来|原|之前的)?(?:草稿|申请|退货)"
)


def draft_message_intent(message: str) -> DraftMessageIntent:
    """Recognize a direct withdrawal/replacement; KEEP never means customer confirmation."""
    # Consequence/permission questions are not unambiguous withdrawal instructions,
    # even when a comma follows the verb. Ask the customer to issue a clear command.
    if (
        not isinstance(message, str)
        or _CONFLICT.search(message)
        or re.search(r"[?？]|是否|会不会|能否|可否|怎么|如何|什么|吗", message)
    ):
        return "KEEP"
    if _WITHDRAW.match(message):
        return "WITHDRAW"
    if _REPLACE.match(message):
        return "REPLACE"
    return "KEEP"
