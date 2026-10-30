"""Conservative citation rules, independent from business authorization.

Only narrow context requests/refusals are exempt from an incidental retrieval hit.
Policy assertions need a citation even after a successful order/draft tool call.
This grammar is not a semantic entailment checker; offline policy gates and the
isolated judge remain necessary. Explicit numeric citations are always validated.
"""

import re

_POLICY_CLAIM = re.compile(
    r"(?:[0-9一二三四五六七八九十百]+\s*(?:个工作日|工作日|天|日|个月|月|年))"
    r"|(?:商品|产品|退货|换货|维修|保修|无理由|运费|邮费).{0,25}"
    r"(?:都支持|都可以|都能|均可|支持|不支持|必须|应当|符合|不符合|承担|免费|收费|不适用)"
    r"|(?:支持|不支持|可以|不可以|允许|不允许|必须|应当).{0,15}(?:退货|换货|维修|无理由)"
    r"|(?:运费|邮费).{0,20}(?:由|承担|支付|免费)|免费|包邮|超出保修|超过保修"
)
_REQUEST = re.compile(
    r"^(?:请|麻烦|烦请)(?:您)?(?:先)?(?:提供|告知|补充|确认|说明|核对|告诉我|选择)"
    r".*(?:订单号|订单编号|申请类型|退货还是|换货还是|具体原因|售后原因|故障|问题|签收日期|商品状态|信息|资料)"
)
_REFUSAL = re.compile(
    r"^(?:抱歉)?(?:暂时)?(?:无法|不能|无权|不允许)(?:为您|替您|帮您)?"
    r"(?:查询|提供|访问|查看|核实|确认).*(?:订单|客户|身份|信息|权限)"
    r"|^(?:订单不存在|该订单不存在|没有权限|未找到|暂未找到|暂时无法核实|请转人工|建议转人工)"
)
_CONTEXT = re.compile(
    r"^(?:需要|需|还需要)(?:先)?(?:核实|确认|查询|补充).*(?:订单|身份|原因|资料|信息)"
)


def requires_policy_citation(text, evidence, tool_calls):
    clean = text.replace("**", "").replace("`", "").strip()
    if _POLICY_CLAIM.search(clean):
        return True
    if not evidence:
        return False
    clauses = [part.strip() for part in re.split(r"[。！？!?，,；;\n]+", clean) if part.strip()]
    if clauses and all(
        clause in {"您好", "你好", "好的", "抱歉"}
        or _REQUEST.match(clause)
        or _REFUSAL.match(clause)
        or _CONTEXT.match(clause)
        for clause in clauses
    ):
        return False
    authoritative_tool = any(
        call.get("outcome") == "success"
        and call.get("name") in {"lookup_order", "prepare_after_sale"}
        for call in tool_calls
    )
    return not authoritative_tool
