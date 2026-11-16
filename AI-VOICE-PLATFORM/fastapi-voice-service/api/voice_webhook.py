from fastapi import APIRouter, Depends

from dependencies import get_conversation, require_webhook_secret
from schemas import ProviderEvent
from services.conversation_service import ConversationService

router = APIRouter(prefix="/webhooks/voice", tags=["Voice webhook"], dependencies=[Depends(require_webhook_secret)])


@router.post("/events")
async def voice_event(body: ProviderEvent, conversation: ConversationService = Depends(get_conversation)) -> dict:
    """Status callbacks from a SIP gateway or provider.

    The event goes through the same handling as events from the live sideband connection, so a
    ringing/answered/failed/closed update or a caller transcript reaches the lead service exactly once
    per call outcome. Events for sessions this process is not tracking are accepted and ignored.
    """
    known = conversation.knows_session(body.session_id)
    if known:
        await conversation.handle_event(body.session_id, body.model_dump())
    return {"accepted": True, "known_session": known}
