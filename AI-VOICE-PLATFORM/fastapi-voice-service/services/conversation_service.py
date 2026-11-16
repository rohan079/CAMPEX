"""Runs one outbound conversation: prompts in, call started, events relayed, opt-outs honoured."""

from __future__ import annotations

import asyncio
import logging
import uuid
from collections import deque
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field as dataclass_field
from typing import Any

from config import Settings
from prompts import build_agent_instructions, build_greeting
from schemas import CallEvent, CallSummary, StartCallRequest, StartCallResponse
from services import openai_realtime
from services.lead_client import LeadServiceClient
from services.telephony_service import TelephonyService

logger = logging.getLogger(__name__)

_NOT_INTERESTED_PHRASES = {
    "NOT INTERESTED",
    "NO THANKS",
    "NO THANK YOU",
    # A bare "stop" is left out on purpose: mid-call it often means "stop, say that again",
    # and an opt-out here permanently deletes the lead.
    "STOP CALLING",
    "DO NOT CALL",
    "DONT CALL",
    "REMOVE ME",
    "REMOVE MY NUMBER",
    "DELETE MY NUMBER",
    "DELETE MY DETAILS",
}
_TELUGU_NOT_INTERESTED = ("ఆసక్తి లేదు", "అవసరం లేదు", "కాల్ చేయవద్దు", "వద్దండి", "వద్దు")

Attach = Callable[..., Awaitable[None]]


class CallCapacityReached(RuntimeError):
    """The configured media pool is full; no carrier call was placed."""


def is_not_interested(text: str | None) -> bool:
    compact = " ".join((text or "").strip().split())
    if not compact:
        return False
    if compact.upper().strip(" .!") in _NOT_INTERESTED_PHRASES:
        return True
    lowered = compact.lower()
    if "not interested" in lowered or "no thanks" in lowered or "stop calling" in lowered:
        return True
    return any(phrase in compact for phrase in _TELUGU_NOT_INTERESTED)


@dataclass
class ActiveCall:
    call_id: str
    session_id: str
    greeting: str
    instructions: str = ""
    destination: str = ""
    pending_line: str | None = None
    media_call_sid: str | None = None
    media_attached: bool = False
    terminal: bool = False
    pending_terminal: tuple[str, str | None] | None = None
    controls: asyncio.Queue[dict[str, str]] = dataclass_field(default_factory=asyncio.Queue)
    transcript: list[str] = dataclass_field(default_factory=list)
    provider_ready: asyncio.Event = dataclass_field(default_factory=asyncio.Event)
    task: asyncio.Task | None = None


class ConversationService:
    def __init__(
        self,
        settings: Settings,
        telephony: TelephonyService,
        lead_client: LeadServiceClient,
        attach: Attach = openai_realtime.attach_sideband,
    ):
        self._settings = settings
        self._telephony = telephony
        self._lead = lead_client
        self._attach = attach
        self._by_session: dict[str, ActiveCall] = {}
        self._by_call: dict[str, ActiveCall] = {}
        self._pending_edesy: dict[str, deque[ActiveCall]] = {}
        self._start_locks: dict[str, asyncio.Lock] = {}
        self._capacity_lock = asyncio.Lock()

    # ------------------------------------------------------------------ starting and ending calls

    async def start_call(self, request: StartCallRequest) -> StartCallResponse:
        lock = self._start_locks.setdefault(request.call_id, asyncio.Lock())
        async with lock:
            existing = self._by_call.get(request.call_id)
            if existing:
                # Duplicate start requests must not place a second carrier call.
                return StartCallResponse(session_id=existing.session_id)

            instructions = build_agent_instructions(
                language=request.language,
                first_name=request.first_name,
                business_name=request.business_name,
                business_description=request.business_description,
                ad_headline=request.ad_headline,
                ad_body=request.ad_body,
                follow_up=request.follow_up,
                knowledge_base=request.knowledge_base,
            )
            greeting = build_greeting(
                language=request.language,
                business_name=request.business_name,
                ad_headline=request.ad_headline,
            )

            if self._settings.call_provider.lower() == "edesy":
                return await self._start_edesy(request, instructions, greeting)

            session_config = openai_realtime.build_session_config(
                self._settings, instructions=instructions, greeting=greeting
            )
            session_id = await self._telephony.create_outbound_sip_session(request.destination, session_config)

            active = ActiveCall(
                call_id=request.call_id,
                session_id=session_id,
                greeting=greeting,
                instructions=instructions,
                destination=request.destination,
            )
            self._by_session[session_id] = active
            self._by_call[request.call_id] = active
            active.task = asyncio.create_task(self._monitor(active), name=f"sideband-{session_id}")
            logger.info("Call %s started as session %s provider=openai_sip", request.call_id, session_id)
            return StartCallResponse(session_id=session_id)

    async def _start_edesy(
        self, request: StartCallRequest, instructions: str, greeting: str
    ) -> StartCallResponse:
        line_key = self._normalize_number(self._settings.edesy_from_number)
        active = ActiveCall(
            call_id=request.call_id,
            session_id=f"pending:{request.call_id}",
            greeting=greeting,
            instructions=instructions,
            destination=request.destination,
            pending_line=line_key,
        )
        async with self._capacity_lock:
            if len(self._by_call) >= self._settings.max_concurrent_calls:
                raise CallCapacityReached("The local speech-to-speech call pool is full")
            self._by_call[request.call_id] = active
            self._pending_edesy.setdefault(line_key, deque()).append(active)
        try:
            session_id = await self._telephony.create_outbound_edesy_call(
                request.destination, call_id=request.call_id, lead_id=request.lead_id
            )
        except Exception:
            active.provider_ready.set()
            self._forget(active)
            raise

        active.session_id = session_id
        self._by_session[session_id] = active
        active.provider_ready.set()
        if active.terminal and not active.media_attached:
            if active.pending_terminal is not None:
                await self._publish(active, *active.pending_terminal)
            self._forget(active)
        logger.info("Call %s placed through Edesy session=%s", request.call_id, session_id)
        return StartCallResponse(session_id=session_id)

    async def hangup(self, session_id: str) -> None:
        await self._telephony.hangup(session_id)
        active = self._by_session.get(session_id)
        if active is not None:
            self._send_control(active, "disconnect")

    def attach_edesy_media(self, metadata: dict[str, Any]) -> ActiveCall | None:
        """Match Edesy's separate media leg to the oldest pending call on our provisioned line."""
        if self._settings.call_provider.lower() != "edesy":
            return None
        line_key = self._normalize_number(metadata.get("to"))
        queue = self._pending_edesy.get(line_key)
        if queue is None:
            pending = [call for calls in self._pending_edesy.values() for call in calls if not call.media_attached]
            if len(pending) != 1:
                return None
            active = pending[0]
            queue = self._pending_edesy.get(active.pending_line or "")
        if not queue:
            return None

        active = next((call for call in queue if not call.media_attached), None)
        if active is None:
            return None
        active.media_attached = True
        active.media_call_sid = str(metadata.get("call_sid") or metadata.get("callSid") or "") or None
        try:
            queue.remove(active)
        except ValueError:
            pass
        if not queue:
            self._pending_edesy.pop(active.pending_line or "", None)
            active.pending_line = None
        return active

    async def record_transcript(self, active: ActiveCall, speaker: str, text: str) -> bool:
        """Keep a bounded transcript in memory for opt-out enforcement and local summarization."""
        clean_text = " ".join(text.split())[:2000]
        if not clean_text:
            return False
        if len(active.transcript) < 200:
            active.transcript.append(f"{speaker}: {clean_text}")
        if speaker == "customer" and is_not_interested(clean_text):
            await self._drop_uninterested(active)
            return True
        return False

    async def save_summary(self, active: ActiveCall, summary: str) -> bool:
        text = " ".join(summary.split())[:10000]
        if not text:
            return False
        return await self._lead.publish_summary(CallSummary(call_id=active.call_id, summary=text))

    async def fail_edesy_media(self, active: ActiveCall) -> None:
        """End a carrier call when its required local speech backend is unavailable."""
        try:
            await self._telephony.hangup(active.session_id)
        except Exception:
            logger.exception("Could not hang up Edesy call after media failure")
        self._send_control(active, "disconnect")
        active.pending_terminal = None
        active.terminal = True
        await self._publish(active, "failed", "speech_backend_unavailable")
        self._forget(active)

    async def handle_edesy_status(self, event: dict[str, Any]) -> bool:
        custom = event.get("Custom") or event.get("custom_parameters") or event.get("custom") or {}
        call_id = custom.get("call_id") if isinstance(custom, dict) else None
        call_sid = event.get("CallSid") or event.get("call_sid") or event.get("sid")
        active = self._by_call.get(str(call_id)) if call_id else None
        if active is None and call_sid:
            active = self._by_session.get(str(call_sid))
        if active is None:
            return False

        provider_status = str(event.get("CallStatus") or event.get("status") or "").lower()
        status_map = {
            "ringing": "ringing",
            "in-progress": "answered",
            "completed": "closed",
            "busy": "failed",
            "no-answer": "failed",
            "failed": "failed",
            "stream-failed": "failed",
        }
        event_type = status_map.get(provider_status)
        if event_type is None:
            return True
        detail = None
        if event_type == "failed":
            detail = str(event.get("ErrorMessage") or event.get("hangup_cause") or provider_status)[:200]
        if event_type in {"closed", "failed"}:
            active.terminal = True
            if active.media_attached or active.session_id.startswith("pending:"):
                active.pending_terminal = (event_type, detail)
            else:
                await self._publish(active, event_type, detail)
                self._forget(active)
        else:
            await self._publish(active, event_type, detail)
        return True

    async def finish_edesy_media(self, active: ActiveCall) -> None:
        """Release the local media slot only after summary and terminal callback handling finish."""
        active.media_attached = False
        if active.pending_terminal is not None:
            event_type, detail = active.pending_terminal
            active.pending_terminal = None
            await self._publish(active, event_type, detail)
            self._forget(active)

    # ------------------------------------------------------------------ events

    async def _monitor(self, active: ActiveCall) -> None:
        async def on_event(event: dict) -> bool:
            return await self.handle_event(active.session_id, event)

        try:
            await self._attach(self._settings, active.session_id, on_event, greeting=active.greeting)
        finally:
            self._forget(active)

    async def handle_event(self, session_id: str, event: dict) -> bool:
        """Relay one provider event. Returns True when the session is over and listening can stop.

        Used by the sideband connection and by the webhook endpoint, so both behave the same.
        """
        active = self._by_session.get(session_id)
        if active is None:
            return False

        transcript = openai_realtime.user_transcript(event)
        if transcript and is_not_interested(transcript):
            await self._drop_uninterested(active)
            return True

        status = openai_realtime.transport_status(event)
        if status is None:
            return False
        detail = openai_realtime.failure_detail(event) if status == "failed" else None
        await self._publish(active, status, detail)
        return status in openai_realtime.TERMINAL_EVENTS

    async def _drop_uninterested(self, active: ActiveCall) -> None:
        """End the call first so the person is not kept on the line, then let the lead service forget them."""
        try:
            await self._telephony.hangup(active.session_id)
        except Exception:
            logger.exception("Could not hang up session %s after opt-out", active.session_id)
        self._send_control(active, "disconnect")
        await self._publish(active, "not_interested", None)

    @staticmethod
    def _normalize_number(value: Any) -> str:
        return "".join(char for char in str(value or "") if char.isdigit())

    @staticmethod
    def _send_control(active: ActiveCall, command: str) -> None:
        try:
            active.controls.put_nowait({"type": command})
        except asyncio.QueueFull:
            logger.warning("Could not queue Edesy control command for call %s", active.call_id)

    async def _publish(self, active: ActiveCall, event_type: str, detail: str | None) -> None:
        await self._lead.publish(
            CallEvent(
                event_id=str(uuid.uuid4()),
                call_id=active.call_id,
                session_id=active.session_id,
                type=event_type,
                detail=detail,
            )
        )

    # ------------------------------------------------------------------ bookkeeping

    def knows_session(self, session_id: str) -> bool:
        return session_id in self._by_session

    @property
    def active_count(self) -> int:
        return len(self._by_session)

    def _forget(self, active: ActiveCall) -> None:
        # Duplicate start requests are only guarded while the session is live; once it is over
        # the lead service has the final status and will not ask for this call again.
        self._by_session.pop(active.session_id, None)
        self._by_call.pop(active.call_id, None)
        if active.pending_line:
            queue = self._pending_edesy.get(active.pending_line)
            if queue is not None:
                try:
                    queue.remove(active)
                except ValueError:
                    pass
                if not queue:
                    self._pending_edesy.pop(active.pending_line, None)

    async def shutdown(self) -> None:
        tasks = [a.task for a in self._by_session.values() if a.task]
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
