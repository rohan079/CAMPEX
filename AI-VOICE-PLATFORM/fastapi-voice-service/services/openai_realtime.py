"""OpenAI GPT-Live specifics: session config, sideband events, and the sideband connection."""

from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import Awaitable, Callable

import websockets

from config import Settings

logger = logging.getLogger(__name__)

# OpenAI event type -> the vocabulary the lead service understands.
TRANSPORT_EVENTS = {
    "transport.ringing": "ringing",
    "transport.answered": "answered",
    "transport.failed": "failed",
    "session.closed": "closed",
}
TERMINAL_EVENTS = {"failed", "closed"}

GREETING_TRIGGERS = {"session.started", "transport.ringing", "transport.answered"}

USER_TRANSCRIPT_EVENTS = {
    "session.input_transcript.done",
    "session.input_transcript.completed",
    "conversation.item.input_audio_transcription.completed",
}

EventHandler = Callable[[dict], Awaitable[bool]]


def build_session_config(settings: Settings, *, instructions: str, greeting: str) -> dict:
    return {
        "model": settings.openai_live_model,
        "instructions": instructions,
        "audio": {"output": {"voice": settings.openai_voice}},
        "delegation": {"type": "client"},
        "input": [
            {
                "type": "message",
                "role": "developer",
                "content": [{"type": "input_text", "text": greeting}],
            }
        ],
    }


def transport_status(event: dict) -> str | None:
    """'ringing' | 'answered' | 'failed' | 'closed', or None for events we do not report."""
    return TRANSPORT_EVENTS.get(event.get("type") or "")


def failure_detail(event: dict) -> str | None:
    error = event.get("error") or event.get("reason")
    if isinstance(error, dict):
        error = error.get("message") or error.get("code")
    return str(error)[:200] if error else None


def user_transcript(event: dict) -> str | None:
    """What the person on the phone said, when the event carries a finished transcript of it."""
    if (event.get("type") or "") not in USER_TRANSCRIPT_EVENTS:
        return None
    for source in (event, event.get("item")):
        if not isinstance(source, dict):
            continue
        for key in ("transcript", "text"):
            value = source.get(key)
            if isinstance(value, str) and value.strip():
                return value
    return None


def greeting_event(greeting: str) -> dict:
    return {
        "type": "session.commentary.append",
        "event_id": "greeting_1",
        "delegation_id": None,
        "content": greeting,
    }


async def attach_sideband(
    settings: Settings,
    session_id: str,
    on_event: EventHandler,
    *,
    greeting: str | None = None,
) -> None:
    """Listen to one live session until it ends, `on_event` returns True, or the task is cancelled.

    Raw audio is never read here, and transcripts are only passed to `on_event`, never logged.
    """
    url = f"{settings.openai_live_ws_url}/{session_id}/attach"
    headers = {"Authorization": f"Bearer {settings.openai_api_key}"}
    greeting_sent = False
    try:
        async with websockets.connect(url, additional_headers=headers, ping_interval=20) as ws:
            async for raw in ws:
                event = json.loads(raw)
                event_type = event.get("type", "unknown")
                logger.info("Live event session=%s type=%s", session_id, event_type)
                if greeting and not greeting_sent and event_type in GREETING_TRIGGERS:
                    # GPT-Live may paraphrase this; the same text is also seeded as a developer message.
                    await ws.send(json.dumps(greeting_event(greeting)))
                    greeting_sent = True
                if await on_event(event):
                    break
    except asyncio.CancelledError:
        raise
    except Exception:
        logger.exception("Sideband connection ended unexpectedly for session %s", session_id)
