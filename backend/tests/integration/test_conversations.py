import pytest

pytestmark = pytest.mark.integration


async def test_conversation_access_close_and_sequential_events(env, login):
    owner, stranger, staff = await login(), await login("customer2"), await login("agent")
    created = await owner.post("/api/v1/conversations", json={"title": "耳机售后"})
    assert created.status_code == 201, created.text
    cid = created.json()["id"]
    assert (await stranger.get(f"/api/v1/conversations/{cid}")).status_code == 404
    assert (
        await stranger.post(
            f"/api/v1/conversations/{cid}/messages",
            json={"client_message_id": "intruder", "body": "恶意回复"},
        )
    ).status_code == 404
    assert (
        await staff.post(
            f"/api/v1/conversations/{cid}/messages",
            json={"client_message_id": "unclaimed", "body": "未经领取"},
        )
    ).status_code == 403
    assert (
        await owner.post(
            f"/api/v1/conversations/{cid}/messages",
            json={"client_message_id": "message-1", "body": "能退货吗"},
        )
    ).status_code == 200
    assert (await owner.post(f"/api/v1/conversations/{cid}/handoff")).status_code == 200
    assert (await owner.post(f"/api/v1/conversations/{cid}/close")).status_code == 200
    response = await owner.post(
        f"/api/v1/conversations/{cid}/messages",
        json={"client_message_id": "after-close", "body": "追加"},
    )
    assert response.status_code == 409
    events = (await owner.get(f"/api/v1/conversations/{cid}/events?after_seq=0")).json()["items"]
    assert [event["seq"] for event in events] == [1, 2, 3, 4]
    assert len({event["seq"] for event in events}) == 4
