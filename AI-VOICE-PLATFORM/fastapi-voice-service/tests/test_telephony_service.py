import httpx
import pytest

from config import Settings
from services.telephony_service import (
    SessionRejected,
    SessionStartUnknown,
    TelephonyConfigurationError,
    TelephonyService,
)

CONFIGURED = Settings(
    openai_api_key="sk-test",
    sip_provider_url="sips:trunk.example.com",
    sip_username="user",
    sip_password="pass",
    sip_caller_number="+15550001111",
)

EDESY_CONFIGURED = Settings(
    call_provider="edesy",
    edesy_api_key="ed-test-key",
    edesy_from_number="+917969000000",
    edesy_media_ws_url="wss://voice.example.test/media/edesy",
    edesy_stream_token="test-stream-token",
    edesy_status_callback_url="https://voice.example.test/webhooks/edesy/status",
    edesy_webhook_secret="webhook-test-secret",
)


def service(handler, settings=CONFIGURED):
    return TelephonyService(settings, transport=httpx.MockTransport(handler))


async def test_posts_sip_transport_and_returns_session_id():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["auth"] = request.headers["authorization"]
        seen["body"] = request.read().decode()
        return httpx.Response(200, json={"session": {"id": "sess_123"}})

    session_id = await service(handler).create_outbound_sip_session("+919800000001", {"model": "m"})
    assert session_id == "sess_123"
    assert seen["auth"] == "Bearer sk-test"
    assert '"destination":"+919800000001"' in seen["body"].replace(" ", "")
    assert '"caller_number":"+15550001111"' in seen["body"].replace(" ", "")


async def test_posts_edesy_stream_call_and_returns_outbound_call_sid():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["authorization"] = request.headers["authorization"]
        seen["body"] = request.read().decode()
        return httpx.Response(201, json={"data": {"call_sid": "edesy-outbound-1"}})

    service_instance = service(handler, EDESY_CONFIGURED)
    sid = await service_instance.create_outbound_edesy_call(
        "+919800000001", call_id="call-1", lead_id="lead-1"
    )

    assert sid == "edesy-outbound-1"
    assert seen["url"] == "https://voice-api.edesy.in/v1/calls"
    assert seen["authorization"] == "Bearer ed-test-key"
    assert '"stream_sample_rate":16000' in seen["body"].replace(" ", "")
    assert "token=test-stream-token" in seen["body"]
    assert '"call_id":"call-1"' in seen["body"].replace(" ", "")
    assert "919800000001" in seen["body"]


async def test_edesy_does_not_place_call_when_selected_openai_engine_is_unconfigured():
    def unexpected_request(request):
        raise AssertionError("call must not be placed without the selected speech engine")

    settings = EDESY_CONFIGURED.model_copy(
        update={"speech_engine": "openai_realtime", "openai_api_key": ""}
    )
    with pytest.raises(TelephonyConfigurationError):
        await service(unexpected_request, settings).create_outbound_edesy_call(
            "+919800000001", call_id="call-1", lead_id="lead-1"
        )


async def test_edesy_hangup_uses_delete_and_treats_already_ended_as_success():
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append((request.method, str(request.url)))
        return httpx.Response(409)

    await service(handler, EDESY_CONFIGURED).hangup("edesy-outbound-1")
    assert seen == [("DELETE", "https://voice-api.edesy.in/v1/calls/edesy-outbound-1")]


async def test_incomplete_settings_never_reach_the_network():
    def handler(request):
        raise AssertionError("no request expected")

    with pytest.raises(TelephonyConfigurationError):
        await service(handler, Settings()).create_outbound_sip_session("+919800000001", {})


async def test_4xx_is_a_definite_rejection():
    handler = lambda request: httpx.Response(400, json={"error": {"message": "bad number"}})  # noqa: E731
    with pytest.raises(SessionRejected):
        await service(handler).create_outbound_sip_session("+919800000001", {})


async def test_5xx_and_timeouts_are_ambiguous_and_must_not_be_retried():
    with pytest.raises(SessionStartUnknown):
        await service(lambda request: httpx.Response(500)).create_outbound_sip_session("+919800000001", {})

    def times_out(request):
        raise httpx.ReadTimeout("slow", request=request)

    with pytest.raises(SessionStartUnknown):
        await service(times_out).create_outbound_sip_session("+919800000001", {})


async def test_connection_failure_is_a_definite_rejection():
    def refuse(request):
        raise httpx.ConnectError("refused", request=request)

    with pytest.raises(SessionRejected):
        await service(refuse).create_outbound_sip_session("+919800000001", {})


async def test_response_without_session_id_is_ambiguous():
    with pytest.raises(SessionStartUnknown):
        await service(lambda request: httpx.Response(200, json={})).create_outbound_sip_session("+919800000001", {})


async def test_hangup_posts_to_the_session_endpoint():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        return httpx.Response(200)

    await service(handler).hangup("sess_9")
    assert seen["url"].endswith("/sess_9/hangup")
