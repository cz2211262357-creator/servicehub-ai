import asyncio

from fastapi import APIRouter, Query, WebSocket, WebSocketDisconnect
from redis.exceptions import RedisError
from sqlalchemy.exc import SQLAlchemyError

from servicehub.auth.service import AuthService
from servicehub.core.errors import BusinessError
from servicehub.realtime.hub import RealtimeHub

router = APIRouter(tags=["realtime"])


@router.websocket("/api/v1/conversations/{conversation_id}/live")
async def live(websocket: WebSocket, conversation_id: int, after_seq: int = Query(default=0, ge=0)):
    settings = websocket.app.state.settings
    if websocket.headers.get("origin") not in settings.allowed_origins:
        await websocket.close(code=4403)
        return
    auth = AuthService(websocket.app.state.sessions, websocket.app.state.redis, settings)
    try:
        principal = await auth.authenticate(websocket.cookies.get(settings.session_cookie_name))
        hub = RealtimeHub(websocket.app.state.sessions, websocket.app.state.redis, settings)
        await hub.conversations.get(principal, conversation_id)
        await websocket.accept()
        async for event in hub.subscribe(principal, conversation_id, after_seq):
            await asyncio.wait_for(websocket.send_json(event.model_dump(mode="json")), timeout=5)
    except BusinessError as exc:
        await websocket.close(
            code=4401 if exc.status_code == 401 else 4403 if exc.status_code in (403, 404) else 1013
        )
    except (RedisError, SQLAlchemyError, TimeoutError):
        await websocket.close(code=1013)
    except (WebSocketDisconnect, RuntimeError):
        return
