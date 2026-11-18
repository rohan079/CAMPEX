import pytest
from fastapi.testclient import TestClient

from config import Settings, get_settings
from main import app
from schemas import CampaignAssistResponse, StartCallResponse
from services.conversation_service import CallCapacityReached
from services.telephony_service import (
    SessionRejected,
    SessionStartUnknown,
    TelephonyConfigurationError,
)

KEY = {"X-Service-Key": "svc"}
BODY = {"call_id": "c1", "destination": "+919800000001", "language": "te", "business_name": "Acme"}


class FakeConversation:
    def __init__(self):
        self.error = None
        self.hangups = []
        self.events = []

    async def start_call(self, request):
        if self.error:
            raise self.error
        return StartCallResponse(session_id="sess_1")

    async def hangup(self, session_id):
        self.hangups.append(session_id)

    async def shutdown(self):
        pass

    def knows_session(self, session_id):
        return session_id == "sess_1"

    async def handle_event(self, session_id, event):
        self.events.append((session_id, event["type"]))
        return False


class FakeCampaignAssistant:
    def __init__(self):
        self.meta_access_token = None

    async def assist(self, body, meta_access_token=None):
        self.meta_access_token = meta_access_token
        return CampaignAssistResponse(reply="Verified copy summary", provider="openai_compatible", model="local")


@pytest.fixture
def client():
    app.dependency_overrides[get_settings] = lambda: Settings(
        environment="production", internal_service_key="svc", voice_webhook_secret="hook"
    )
    with TestClient(app) as test_client:
        fake = FakeConversation()
        app.state.conversation = fake
        app.state.settings = Settings(
            environment="production", internal_service_key="svc", voice_webhook_secret="hook"
        )
        assistant = FakeCampaignAssistant()
        app.state.campaign_assistant = assistant
        test_client.fake = fake
        test_client.fake_assistant = assistant
        yield test_client
    app.dependency_overrides.clear()


def test_health_is_open(client):
    assert client.get("/health").json()["service"] == "voice-service"


def test_start_call_requires_the_service_key(client):
    assert client.post("/api/calls", json=BODY).status_code == 401
    assert client.post("/api/calls", json=BODY, headers={"X-Service-Key": "wrong"}).status_code == 401


def test_campaign_assistant_uses_existing_internal_service_auth(client):
    body = {
        "campaign_name": "Summer plots",
        "objective": "leads",
        "platforms": ["facebook", "instagram"],
        "language": "en",
        "prompt": "Summarize this",
        "content": "Verified plot information",
    }
    assert client.post("/api/ads/assist", json=body).status_code == 401
    response = client.post("/api/ads/assist", json=body, headers=KEY)
    assert response.status_code == 200
    assert response.json()["reply"] == "Verified copy summary"


def test_campaign_assistant_receives_meta_token_only_from_internal_header(client):
    body = {
        "campaign_name": "Summer plots",
        "objective": "leads",
        "platforms": ["facebook"],
        "language": "en",
        "prompt": "Show my campaign spend",
        "content": "No draft copy supplied",
    }
    response = client.post(
        "/api/ads/assist",
        json=body,
        headers={**KEY, "X-Meta-Access-Token": "server-only-token"},
    )
    assert response.status_code == 200
    assert client.fake_assistant.meta_access_token == "server-only-token"
    assert "server-only-token" not in response.text


def test_meta_mcp_http_endpoint_requires_the_internal_service_key(client):
    assert client.post("/mcp/meta-ads", content="{}").status_code == 401


def test_meta_mcp_http_endpoint_serves_tools_to_internal_clients(client):
    request = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "tools/list",
        "params": {
            "_meta": {
                "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                "io.modelcontextprotocol/clientInfo": {"name": "CAMPEX test", "version": "1.0"},
                "io.modelcontextprotocol/clientCapabilities": {},
            },
        },
    }
    response = client.post(
        "/mcp/meta-ads/",
        json=request,
        headers={
            **KEY,
            "Host": "localhost",
            "MCP-Protocol-Version": "2026-07-28",
            "Mcp-Method": "tools/list",
            "Accept": "application/json, text/event-stream",
        },
    )
    assert response.status_code == 200
    names = {tool["name"] for tool in response.json()["result"]["tools"]}
    assert "get_campaigns" in names
    assert "create_paused_campaign" in names


def test_meta_campaign_creation_requires_explicit_confirmation(client):
    body = {
        "campaign_name": "Summer plots",
        "objective": "leads",
        "platforms": ["facebook"],
        "location": "Vijayawada",
        "age_min": 18,
        "age_max": 65,
        "headline": "New homes",
        "body": "Verified listing details",
        "cta": "Learn more",
        "ad_account_id": "act_123",
        "confirmed": False,
    }
    response = client.post(
        "/api/meta/publish",
        json=body,
        headers={**KEY, "X-Meta-Access-Token": "server-only"},
    )
    assert response.status_code == 400
    assert "Confirm" in response.json()["detail"]


def test_confirmed_meta_publish_uses_mcp_and_never_returns_the_token(client):
    from fastmcp import FastMCP

    seen = {}
    test_mcp = FastMCP("test Meta tools")

    @test_mcp.tool
    async def create_paused_campaign(
        ad_account_id: str,
        name: str,
        objective: str,
        location: str,
        age_min: int,
        age_max: int,
        platforms: list[str],
        access_token: str,
        confirmed: bool,
        daily_budget_minor: int = 50_000,
    ) -> dict:
        seen["token"] = access_token
        seen["confirmed"] = confirmed
        return {
            "campaign_id": "campaign-1",
            "ad_set_id": "adset-1",
            "ad_id": None,
            "ad_account_id": ad_account_id,
            "status": "PAUSED",
            "ads_manager_url": "https://www.facebook.com/adsmanager/manage/campaigns?act=123",
            "warning": "No creative was uploaded.",
        }

    app.state.meta_ads_mcp = test_mcp
    body = {
        "campaign_name": "Summer plots",
        "objective": "leads",
        "platforms": ["facebook"],
        "location": "Vijayawada",
        "age_min": 18,
        "age_max": 65,
        "headline": "New homes",
        "body": "Verified listing details",
        "cta": "Learn more",
        "ad_account_id": "act_123",
        "confirmed": True,
    }
    response = client.post(
        "/api/meta/publish",
        json=body,
        headers={**KEY, "X-Meta-Access-Token": "private-token"},
    )
    assert response.status_code == 200
    assert response.json()["campaign_id"] == "campaign-1"
    assert response.json()["status"] == "PAUSED"
    assert "private-token" not in response.text
    assert seen == {"token": "private-token", "confirmed": True}


def test_start_call_returns_the_session_id(client):
    response = client.post("/api/calls", json=BODY, headers=KEY)
    assert response.status_code == 200
    assert response.json() == {"session_id": "sess_1", "status": "initiated"}


def test_call_capacity_uses_retryable_response(client):
    client.fake.error = CallCapacityReached("pool full")
    response = client.post("/api/calls", json=BODY, headers=KEY)
    assert response.status_code == 429
    assert response.headers["retry-after"] == "3"


@pytest.mark.parametrize("bad", [
    {"destination": "98000"},
    {"destination": "+919800000001; DROP"},
    {"language": "fr"},
    {"call_id": ""},
])
def test_invalid_requests_are_rejected_before_dialling(client, bad):
    assert client.post("/api/calls", json={**BODY, **bad}, headers=KEY).status_code == 422


@pytest.mark.parametrize("error,status", [
    (TelephonyConfigurationError("not configured"), 503),
    (SessionRejected("no"), 502),
    (SessionStartUnknown("maybe"), 504),
])
def test_provider_failures_map_to_distinct_statuses(client, error, status):
    client.fake.error = error
    assert client.post("/api/calls", json=BODY, headers=KEY).status_code == status


def test_hangup(client):
    assert client.post("/api/calls/sess_1/hangup", headers=KEY).status_code == 204
    assert client.fake.hangups == ["sess_1"]
    assert client.post("/api/calls/sess_1/hangup").status_code == 401


def test_voice_webhook_needs_its_own_secret_and_ignores_unknown_sessions(client):
    event = {"session_id": "sess_1", "type": "transport.ringing"}
    assert client.post("/webhooks/voice/events", json=event, headers=KEY).status_code == 401
    ok = client.post("/webhooks/voice/events", json=event, headers={"X-Webhook-Secret": "hook"})
    assert ok.json() == {"accepted": True, "known_session": True}
    assert client.fake.events == [("sess_1", "transport.ringing")]

    other = client.post("/webhooks/voice/events", json={"session_id": "zzz", "type": "session.closed"},
                        headers={"X-Webhook-Secret": "hook"})
    assert other.json()["known_session"] is False
