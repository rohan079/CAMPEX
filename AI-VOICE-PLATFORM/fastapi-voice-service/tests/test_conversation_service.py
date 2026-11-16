import asyncio

import httpx
import pytest

from config import Settings
from schemas import CallEvent, CallSummary, StartCallRequest
from services.conversation_service import CallCapacityReached, ConversationService, is_not_interested
from services.lead_client import LeadServiceClient


class FakeTelephony:
    def __init__(self):
        self.created = []
        self.edesy_created = []
        self.hangups = []

    async def create_outbound_sip_session(self, destination, session_config):
        self.created.append((destination, session_config))
        return f"sess_{len(self.created)}"

    async def hangup(self, session_id):
        self.hangups.append(session_id)

    async def create_outbound_edesy_call(self, destination, *, call_id, lead_id):
        self.edesy_created.append((destination, call_id, lead_id))
        return f"ed_{len(self.edesy_created)}"


class FakeLead:
    def __init__(self):
        self.events: list[CallEvent] = []
        self.summaries = []

    async def publish(self, event):
        self.events.append(event)
        return True

    async def publish_summary(self, summary):
        self.summaries.append(summary)
        return True


def request(**overrides) -> StartCallRequest:
    data = dict(call_id="call-1", destination="+919800000001", language="te", business_name="Acme")
    data.update(overrides)
    return StartCallRequest(**data)


def make(attach=None):
    telephony, lead = FakeTelephony(), FakeLead()

    async def idle(settings, session_id, on_event, greeting=None):
        await asyncio.sleep(3600)

    service = ConversationService(Settings(), telephony, lead, attach=attach or idle)
    return service, telephony, lead


def test_not_interested_detection():
    assert is_not_interested("I'm not interested, thanks")
    assert is_not_interested("Please stop calling me")
    assert is_not_interested("నాకు ఆసక్తి లేదు")
    assert is_not_interested("No thanks.")
    assert not is_not_interested("stop, can you repeat that")
    assert not is_not_interested("Tell me the price")
    assert not is_not_interested(None)


async def test_start_call_builds_prompt_in_requested_language_and_tracks_session():
    service, telephony, _ = make()
    response = await service.start_call(request(
        first_name="Ravi",
        ad_headline="Summer sale",
        follow_up=True,
        knowledge_base="Approved project: Green Fields in Guntur.",
    ))
    try:
        assert response.session_id == "sess_1"
        destination, config = telephony.created[0]
        assert destination == "+919800000001"
        assert "తెలుగులో మాట్లాడండి" in config["instructions"]
        assert "Green Fields in Guntur" in config["instructions"]
        assert "తెలుగులో పలకరించండి" in config["input"][0]["content"][0]["text"]
        assert service.knows_session("sess_1")
    finally:
        await service.shutdown()


async def test_edesy_start_registers_media_match_and_does_not_open_openai_sideband():
    telephony, lead = FakeTelephony(), FakeLead()
    settings = Settings(call_provider="edesy", edesy_from_number="+917000000001")

    async def unexpected_attach(*args, **kwargs):
        raise AssertionError("Edesy calls must not start OpenAI sideband sessions")

    service = ConversationService(settings, telephony, lead, attach=unexpected_attach)
    request_data = request(call_id="call-edesy", lead_id="lead-1")
    response = await service.start_call(request_data)
    active = service.attach_edesy_media(
        {"to": "917000000001", "call_sid": "media-leg", "sampleRate": 16000, "mixType": "mono"}
    )
    try:
        assert response.session_id == "ed_1"
        assert telephony.edesy_created == [("+919800000001", "call-edesy", "lead-1")]
        assert active is not None and active.session_id == "ed_1"
        assert active.media_call_sid == "media-leg"
        assert "AI" in active.instructions
    finally:
        await service.shutdown()


async def test_edesy_status_webhook_maps_answer_and_completion():
    service, _, lead = make()
    # Use the provider-status path on the same active-call registry.
    active = await service.start_call(request(call_id="status-call"))
    try:
        assert await service.handle_edesy_status(
            {"CallSid": active.session_id, "CallStatus": "in-progress"}
        )
        assert await service.handle_edesy_status(
            {"CallSid": active.session_id, "CallStatus": "completed"}
        )
    finally:
        await service.shutdown()
    assert [event.type for event in lead.events] == ["answered", "closed"]


async def test_edesy_terminal_status_waits_until_media_and_summary_release_the_slot():
    telephony, lead = FakeTelephony(), FakeLead()
    settings = Settings(call_provider="edesy", edesy_from_number="+917000000001")
    service = ConversationService(settings, telephony, lead)
    await service.start_call(request(call_id="media-terminal"))
    active = service.attach_edesy_media(
        {"to": "917000000001", "call_sid": "media-leg", "sampleRate": 16000, "mixType": "mono"}
    )
    assert active is not None
    try:
        assert await service.handle_edesy_status(
            {"CallSid": active.session_id, "CallStatus": "completed"}
        )
        assert service.knows_session(active.session_id)
        assert lead.events == []

        await service.finish_edesy_media(active)
        assert [event.type for event in lead.events] == ["closed"]
        assert not service.knows_session(active.session_id)
    finally:
        await service.shutdown()


async def test_edesy_call_capacity_rejects_before_a_second_carrier_request():
    telephony, lead = FakeTelephony(), FakeLead()
    settings = Settings(
        call_provider="edesy",
        edesy_from_number="+917000000001",
        max_concurrent_calls=1,
    )
    service = ConversationService(settings, telephony, lead)
    try:
        await service.start_call(request(call_id="capacity-1"))
        with pytest.raises(CallCapacityReached):
            await service.start_call(request(call_id="capacity-2"))
        assert len(telephony.edesy_created) == 1
    finally:
        await service.shutdown()


async def test_call_summary_is_published_without_transcript_logging():
    service, _, lead = make()
    response = await service.start_call(request(call_id="summary-call"))
    active = service._by_session[response.session_id]
    try:
        assert await service.save_summary(active, "  Interested in a site visit.  ")
    finally:
        await service.shutdown()
    assert lead.summaries[0].call_id == "summary-call"
    assert lead.summaries[0].summary == "Interested in a site visit."


async def test_duplicate_start_for_a_live_call_does_not_dial_twice():
    service, telephony, _ = make()
    first = await service.start_call(request())
    second = await service.start_call(request())
    try:
        assert first.session_id == second.session_id
        assert len(telephony.created) == 1
    finally:
        await service.shutdown()


async def test_transport_events_are_relayed_with_the_call_id():
    service, _, lead = make()
    await service.start_call(request())
    try:
        assert await service.handle_event("sess_1", {"type": "transport.ringing"}) is False
        assert await service.handle_event("sess_1", {"type": "transport.answered"}) is False
        assert await service.handle_event("sess_1", {"type": "session.started"}) is False
        assert await service.handle_event("sess_1", {"type": "session.closed"}) is True
    finally:
        await service.shutdown()
    assert [e.type for e in lead.events] == ["ringing", "answered", "closed"]
    assert {e.call_id for e in lead.events} == {"call-1"}
    assert {e.session_id for e in lead.events} == {"sess_1"}


async def test_failed_transport_reports_the_reason_and_ends_listening():
    service, _, lead = make()
    await service.start_call(request())
    try:
        stop = await service.handle_event("sess_1", {"type": "transport.failed", "error": {"message": "busy"}})
    finally:
        await service.shutdown()
    assert stop is True
    assert lead.events[0].type == "failed" and lead.events[0].detail == "busy"


async def test_caller_opt_out_hangs_up_then_tells_the_lead_service():
    service, telephony, lead = make()
    await service.start_call(request())
    try:
        stop = await service.handle_event(
            "sess_1", {"type": "session.input_transcript.done", "transcript": "I am not interested"}
        )
    finally:
        await service.shutdown()
    assert stop is True
    assert telephony.hangups == ["sess_1"]
    assert [e.type for e in lead.events] == ["not_interested"]


async def test_ordinary_speech_is_not_reported():
    service, telephony, lead = make()
    await service.start_call(request())
    try:
        stop = await service.handle_event(
            "sess_1", {"type": "session.input_transcript.done", "transcript": "what is the price?"}
        )
    finally:
        await service.shutdown()
    assert stop is False
    assert telephony.hangups == [] and lead.events == []


async def test_events_for_unknown_sessions_are_ignored():
    service, _, lead = make()
    assert await service.handle_event("nope", {"type": "session.closed"}) is False
    assert lead.events == []


async def test_session_is_forgotten_when_the_monitor_ends():
    async def finish_immediately(settings, session_id, on_event, greeting=None):
        await on_event({"type": "session.closed"})

    service, _, lead = make(attach=finish_immediately)
    await service.start_call(request())
    for _ in range(20):
        if service.active_count == 0:
            break
        await asyncio.sleep(0.01)
    assert service.active_count == 0
    assert [e.type for e in lead.events] == ["closed"]


async def test_lead_client_retries_server_errors_but_not_rejections():
    calls = {"n": 0}

    def flaky(req: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        return httpx.Response(503 if calls["n"] < 3 else 200)

    settings = Settings(internal_service_key="k", lead_service_url="http://lead")
    event = CallEvent(event_id="e", call_id="c", type="ringing")
    client = LeadServiceClient(settings, transport=httpx.MockTransport(flaky), retry_delays=(0, 0, 0))
    assert await client.publish(event) is True
    assert calls["n"] == 3

    calls["n"] = 0
    rejected = LeadServiceClient(
        settings, transport=httpx.MockTransport(lambda r: httpx.Response(400)), retry_delays=(0, 0, 0)
    )
    assert await rejected.publish(event) is False


async def test_lead_client_publishes_call_summary_to_internal_endpoint():
    seen = {}

    def accept(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["service_key"] = request.headers.get("X-Service-Key")
        seen["body"] = request.read().decode()
        return httpx.Response(200)

    settings = Settings(internal_service_key="internal-test", lead_service_url="http://lead")
    client = LeadServiceClient(settings, transport=httpx.MockTransport(accept))
    result = await client.publish_summary(
        CallSummary(call_id="call-1", summary="Asked for verified plot pricing.")
    )

    assert result is True
    assert seen["url"] == "http://lead/internal/call-summaries"
    assert seen["service_key"] == "internal-test"
    assert '"summary":"Asked for verified plot pricing."' in seen["body"]
