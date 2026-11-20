import json

import httpx
import pytest
from fastmcp import FastMCP

from config import Settings
from schemas import CampaignAssistRequest
from services.campaign_assistant import CampaignAssistantService, CampaignAssistantUnavailable


def request() -> CampaignAssistRequest:
    return CampaignAssistRequest(
        campaign_name="Summer plots",
        objective="leads",
        platforms=["facebook", "instagram"],
        language="en",
        prompt="Summarize this and make it clearer",
        content="Residential plots near Vijayawada. Ask for verified price and availability.",
    )


@pytest.mark.asyncio
async def test_openai_assistant_uses_server_key_and_returns_copy():
    seen = {}

    def handler(req: httpx.Request) -> httpx.Response:
        seen["authorization"] = req.headers.get("authorization")
        seen["body"] = req.read().decode()
        return httpx.Response(200, json={"choices": [{"message": {"content": "Verified residential plots near Vijayawada."}}]})

    settings = Settings(ai_provider="openai", ai_api_key="server-only", ai_base_url="https://api.openai.com/v1")
    result = await CampaignAssistantService(settings, httpx.MockTransport(handler)).assist(request())

    assert result.reply == "Verified residential plots near Vijayawada."
    assert result.provider == "openai"
    assert seen["authorization"] == "Bearer server-only"
    body = seen["body"].lower()
    assert "never invent prices" in body
    assert "get_meta_performance_report" in body
    assert "campex ai campaign assistant" in body


@pytest.mark.asyncio
async def test_openai_compatible_local_assistant_does_not_require_provider_key():
    seen = {}

    def handler(req: httpx.Request) -> httpx.Response:
        seen["url"] = str(req.url)
        seen["authorization"] = req.headers.get("authorization")
        return httpx.Response(200, json={"choices": [{"message": {"content": "Local model reply"}}]})

    settings = Settings(
        ai_provider="openai_compatible",
        ai_base_url="http://ollama:11434/v1",
        ai_model="qwen2.5:7b",
    )
    result = await CampaignAssistantService(settings, httpx.MockTransport(handler)).assist(request())
    assert result.provider == "openai_compatible"
    assert result.model == "qwen2.5:7b"
    assert seen["url"] == "http://ollama:11434/v1/chat/completions"
    assert seen["authorization"] is None


@pytest.mark.asyncio
async def test_assistant_reports_missing_configuration_and_provider_errors():
    with pytest.raises(CampaignAssistantUnavailable, match="not configured"):
        await CampaignAssistantService(Settings(ai_provider="openai")).assist(request())

    failing = CampaignAssistantService(
        Settings(ai_provider="openai_compatible", ai_base_url="http://local/v1", ai_model="test"),
        httpx.MockTransport(lambda req: httpx.Response(500)),
    )
    with pytest.raises(CampaignAssistantUnavailable, match="HTTP 500"):
        await failing.assist(request())


@pytest.mark.asyncio
async def test_assistant_discovers_read_tools_and_keeps_meta_token_out_of_openai_payloads():
    server = FastMCP("Test Meta")
    seen = {"token": None, "openai_bodies": []}

    @server.tool
    async def get_campaigns(ad_account_id: str, access_token: str) -> dict[str, object]:
        """Return campaigns for an authorized account."""
        seen["token"] = access_token
        return {"campaigns": [{"id": "c1", "name": "Spring", "spend": "125.00"}]}

    @server.tool
    async def delete_campaign(campaign_id: str, access_token: str, confirmed: bool) -> dict[str, object]:
        """Delete a campaign after confirmation."""
        raise AssertionError("Mutating tool must not be advertised or executed by the assistant")

    def handler(req: httpx.Request) -> httpx.Response:
        payload = json.loads(req.content)
        seen["openai_bodies"].append(payload)
        assert "meta-secret" not in req.content.decode()
        if len(seen["openai_bodies"]) == 1:
            tools = payload["tools"]
            assert [tool["function"]["name"] for tool in tools] == ["get_campaigns"]
            schema = tools[0]["function"]["parameters"]
            assert "access_token" not in schema.get("properties", {})
            return httpx.Response(200, json={"choices": [{"message": {
                "role": "assistant",
                "content": None,
                "tool_calls": [{
                    "id": "call-1",
                    "type": "function",
                    "function": {"name": "get_campaigns", "arguments": "{\"ad_account_id\":\"act_123\"}"},
                }],
            }}]})
        tool_message = next(message for message in payload["messages"] if message["role"] == "tool")
        assert "Spring" in tool_message["content"]
        return httpx.Response(200, json={"choices": [{"message": {"content": "Spring spent 125.00."}}]})

    assistant = CampaignAssistantService(
        Settings(ai_provider="openai", ai_api_key="server-only"),
        httpx.MockTransport(handler),
        meta_mcp=server,
    )
    result = await assistant.assist(request(), meta_access_token="meta-secret")

    assert result.reply == "Spring spent 125.00."
    assert result.tools_used == ["get_campaigns"]
    assert seen["token"] == "meta-secret"
