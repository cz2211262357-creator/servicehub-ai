"""Deterministic eligibility from merchant-maintained facts, never model claims."""

from dataclasses import dataclass
from datetime import UTC, datetime, timedelta, timezone
from typing import Literal

from pydantic import BaseModel, ConfigDict, StrictBool, ValidationError, field_validator

ReasonCode = Literal["NO_REASON", "QUALITY", "WRONG_ITEM", "LOGISTICS_DAMAGE", "OTHER"]
# The modern policy's calendar-day window is Asia/Shanghai (UTC+08:00).
SHANGHAI = timezone(timedelta(hours=8), "Asia/Shanghai")
QUALITY_REASONS = {"QUALITY", "WRONG_ITEM", "LOGISTICS_DAMAGE"}


class AfterSaleFacts(BaseModel):
    """Trusted server JSON. Missing values remain unknown rather than favorable defaults.

    exclusion_confirmed on digital goods records confirmed activation/download/performance.
    ordinary includes physical electronics; opening for inspection alone does not exclude them.
    """

    model_config = ConfigDict(extra="forbid")
    category: (
        Literal[
            "ordinary",
            "apparel",
            "electronics",
            "household",
            "home",
            "personal_care",
            "fresh",
            "custom",
            "digital",
        ]
        | None
    ) = None
    signed_at: datetime | None = None
    return_condition: Literal["intact", "opened_inspected", "used", "damaged"] | None = None
    exclusion_confirmed: StrictBool | None = None
    quality_issue: StrictBool | None = None
    warranty_until: datetime | None = None
    requires_manual_review: StrictBool | None = None

    @field_validator("signed_at", "warranty_until")
    @classmethod
    def utc_timestamp(cls, value):
        if value is not None:
            if value.tzinfo is None or value.utcoffset() is None:
                raise ValueError("trusted timestamps must include timezone")
            return value.astimezone(UTC)
        return value


@dataclass(frozen=True)
class EligibilityDecision:
    eligible: bool
    action: Literal["allow", "deny", "clarify", "manual"]
    code: str
    message: str


def evaluate_eligibility(status, facts, kind, reason_code, now: datetime) -> EligibilityDecision:
    """Evaluate one application using the same rule at preparation and confirmation."""

    def reject(code, message, action="clarify"):
        return EligibilityDecision(False, action, code, message)

    if kind not in {"RETURN", "EXCHANGE", "REPAIR"}:
        return reject("invalid_after_sale_kind", "不支持该售后类型", "deny")
    if reason_code not in {"NO_REASON", *QUALITY_REASONS}:
        return reject("after_sale_reason_required", "请明确申请原因后再准备草稿")
    try:
        trusted = AfterSaleFacts.model_validate(facts)
    except (ValidationError, TypeError, ValueError):
        return reject("after_sale_facts_required", "订单售后事实缺失或无效，请补充核实或转人工")
    if trusted.requires_manual_review is not False:
        return reject("after_sale_manual_review", "该订单需要人工核实后处理", "manual")
    if status != "DELIVERED":
        return reject("order_not_eligible", "当前订单状态不可发起此售后申请", "deny")
    if now.tzinfo is None or now.utcoffset() is None:
        raise ValueError("eligibility clock must include timezone")
    now = now.astimezone(UTC)
    if kind == "REPAIR":
        if reason_code not in QUALITY_REASONS:
            return reject("after_sale_quality_required", "维修需先核实质量问题")
        if trusted.quality_issue is None or trusted.warranty_until is None:
            return reject("after_sale_facts_required", "维修需确认质量事实和保修截止时间")
        if not trusted.quality_issue:
            return reject("after_sale_quality_required", "尚无已确认的质量问题", "deny")
        if trusted.warranty_until < now:
            return reject(
                "after_sale_warranty_expired", "已过保修期限，请转人工核实其他方案", "deny"
            )
        return EligibilityDecision(True, "allow", "eligible", "可准备维修草稿，仍需客户确认")
    if trusted.signed_at is None:
        return reject("after_sale_facts_required", "请先核实订单签收时间")
    if trusted.signed_at > now:
        return reject("after_sale_facts_invalid", "签收时间无效，请人工核实", "manual")
    days = (now.astimezone(SHANGHAI).date() - trusted.signed_at.astimezone(SHANGHAI).date()).days
    if reason_code in QUALITY_REASONS:
        if trusted.quality_issue is None:
            return reject("after_sale_facts_required", "请先核实质量、错发或物流损坏事实")
        if not trusted.quality_issue:
            return reject("after_sale_quality_required", "尚无已确认的质量问题", "deny")
        if days > 15:
            return reject(
                "after_sale_window_expired", "已超出商家示例十五天退换承诺，请转人工", "deny"
            )
    else:
        if trusted.category is None or trusted.return_condition is None:
            return reject("after_sale_facts_required", "请先核实品类和商品完好状态")
        if trusted.category in {"fresh", "custom"}:
            return reject(
                "after_sale_excluded", "该品类不适用七天无理由退换，请人工核实其他方案", "deny"
            )
        if trusted.exclusion_confirmed is None:
            return reject("after_sale_facts_required", "请先核实是否存在已确认的排除事实")
        if trusted.exclusion_confirmed:
            return reject("after_sale_excluded", "已有不适用无理由退换的受信排除事实", "deny")
        if trusted.return_condition not in {"intact", "opened_inspected"}:
            return reject(
                "after_sale_condition_excluded", "商品完好状态不符合无理由退换条件", "deny"
            )
        if days > 7:
            return reject(
                "after_sale_window_expired", "已超出七天无理由期限，请转人工核实其他方案", "deny"
            )
    return EligibilityDecision(True, "allow", "eligible", "可准备申请草稿，仍需客户确认")
