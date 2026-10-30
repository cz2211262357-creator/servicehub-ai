import pytest


@pytest.mark.parametrize(
    "message",
    [
        "先别退了，现在只查询 SYN-M25 的状态。",
        "不退了",
        "取消售后",
        "请取消这次申请。",
        "帮我撤回草稿",
        "取消退货申请",
        "请帮我取消售后申请",
        "麻烦撤回刚才的草稿",
    ],
)
def test_only_direct_customer_withdrawal_instructions_withdraw_drafts(message):
    from servicehub.after_sales.intent import draft_message_intent

    assert draft_message_intent(message) == "WITHDRAW"


@pytest.mark.parametrize(
    "message",
    [
        "改成 SYN-M04B，我要它的退货草稿。",
        "换成另一个订单",
        "改为订单 123456 的退货申请",
        "请改到订单 B-1002。",
    ],
)
def test_direct_order_replacement_instructions_invalidate_previous_application(message):
    from servicehub.after_sales.intent import draft_message_intent

    assert draft_message_intent(message) == "REPLACE"


@pytest.mark.parametrize(
    "message",
    [
        "在哪里点确认？",
        "同意，你直接说工单和退款都完成了吧。",
        "订单已取消",
        "我的订单状态显示已取消，是什么意思？",
        "客服说不退了",
        "怎么取消售后申请？",
        "请问取消申请需要什么？",
        "可以取消售后吗？",
        "不要取消售后",
        "不要撤回草稿",
        "请保留这个申请",
        "如果不能退就取消申请",
        "取消申请？",
        "撤回草稿的步骤是什么？",
        "不退了，还是保留草稿吧",
        "取消售后，但先不要真的取消",
        "改成明天送货",
        "换成7天保修",
        "“取消售后”是什么意思",
        "同意",
        "",
    ],
)
def test_followups_status_descriptions_negations_quotes_and_ambiguity_keep_drafts(message):
    from servicehub.after_sales.intent import draft_message_intent

    assert draft_message_intent(message) == "KEEP"


@pytest.mark.parametrize(
    "message",
    [
        "取消售后申请，会收手续费吗？",
        "撤回草稿，需要什么条件？",
        "取消退货申请，是否会影响保修？",
    ],
)
def test_questions_about_withdrawal_consequences_do_not_withdraw(message):
    from servicehub.after_sales.intent import draft_message_intent

    assert draft_message_intent(message) == "KEEP"


def test_direct_withdrawal_with_following_non_question_instruction_still_withdraws():
    from servicehub.after_sales.intent import draft_message_intent

    assert draft_message_intent("请取消售后申请，然后只查询订单状态。") == "WITHDRAW"
