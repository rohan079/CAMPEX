from fastapi import APIRouter, Depends, HTTPException, status

from dependencies import get_conversation, require_service_key
from schemas import StartCallRequest, StartCallResponse
from services.conversation_service import CallCapacityReached, ConversationService
from services.telephony_service import (
    SessionRejected,
    SessionStartUnknown,
    TelephonyConfigurationError,
)

router = APIRouter(prefix="/api/calls", tags=["Calls"], dependencies=[Depends(require_service_key)])


@router.post("", response_model=StartCallResponse)
async def start_call(
    body: StartCallRequest, conversation: ConversationService = Depends(get_conversation)
) -> StartCallResponse:
    """Start one outbound call.

    502 means the provider definitely refused. 504 means it may have dialled anyway, so do not retry.
    """
    try:
        return await conversation.start_call(body)
    except CallCapacityReached as exc:
        raise HTTPException(
            status.HTTP_429_TOO_MANY_REQUESTS,
            str(exc),
            headers={"Retry-After": "3"},
        ) from exc
    except TelephonyConfigurationError as exc:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, str(exc)) from exc
    except SessionRejected as exc:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, str(exc)) from exc
    except SessionStartUnknown as exc:
        raise HTTPException(status.HTTP_504_GATEWAY_TIMEOUT, str(exc)) from exc


@router.post("/{session_id}/hangup", status_code=status.HTTP_204_NO_CONTENT)
async def hangup(session_id: str, conversation: ConversationService = Depends(get_conversation)) -> None:
    try:
        await conversation.hangup(session_id)
    except Exception as exc:  # the session may already be over
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, "Provider could not hang up this session") from exc
