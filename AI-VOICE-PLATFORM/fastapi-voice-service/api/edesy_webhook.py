"""Authenticated Edesy call-status callback."""

from __future__ import annotations

import hashlib
import hmac
import json
import logging
import time
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, Request, status

from config import get_settings
from dependencies import get_conversation
from services.conversation_service import ConversationService

router = APIRouter(prefix="/webhooks/edesy", tags=["Edesy"])
logger = logging.getLogger(__name__)
MAX_WEBHOOK_AGE_SECONDS = 300


def _timestamp_seconds(value: str) -> float | None:
    try:
        return float(value)
    except ValueError:
        try:
            return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()
        except ValueError:
            return None


def _valid_signature(raw_body: bytes, timestamp: str | None, signature: str | None, secret: str) -> bool:
    if not timestamp or not signature:
        return False
    parsed = _timestamp_seconds(timestamp)
    if parsed is None or abs(time.time() - parsed) > MAX_WEBHOOK_AGE_SECONDS:
        return False
    expected = "v1=" + hmac.new(
        secret.encode("utf-8"), timestamp.encode("utf-8") + b"." + raw_body, hashlib.sha256
    ).hexdigest()
    return hmac.compare_digest(signature, expected)


@router.post("/status")
async def edesy_status(
    request: Request,
    conversation: ConversationService = Depends(get_conversation),
) -> dict[str, bool]:
    """Translate Edesy's status webhook into the existing Spring call-event contract."""
    settings = get_settings()
    raw_body = await request.body()
    secret = settings.edesy_webhook_secret
    if not secret:
        if not settings.is_development:
            raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Edesy webhook secret is not configured")
    elif not _valid_signature(
        raw_body,
        request.headers.get("X-Webhook-Timestamp"),
        request.headers.get("X-Webhook-Signature"),
        secret,
    ):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid Edesy webhook signature")

    try:
        body = json.loads(raw_body)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Invalid Edesy webhook JSON") from exc
    if not isinstance(body, dict):
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Expected Edesy status object")

    known = await conversation.handle_edesy_status(body)
    if not known:
        logger.info("Ignored Edesy status for an untracked call")
    return {"accepted": True, "known_call": known}
