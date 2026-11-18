import httpx
import pytest
from fastmcp import Client
from fastmcp.exceptions import ToolError

from config import Settings
from services.meta_ads_mcp import create_meta_ads_mcp


def tool_payload(result):
    value = result.structured_content
    if isinstance(value, dict) and set(value) == {"result"}:
        return value["result"]
    return value


@pytest.mark.asyncio
async def test_mcp_discovers_meta_tools_and_keeps_token_out_of_graph_urls():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["authorization"] = request.headers.get("authorization")
        return httpx.Response(200, json={"data": [{"id": "act_123", "name": "Campex Ads", "currency": "INR"}]})

    server = create_meta_ads_mcp(
        Settings(meta_graph_api_version="v27.0"),
        httpx.MockTransport(handler),
    )
    async with Client(server) as client:
        tools = await client.list_tools()
        names = {tool.name for tool in tools}
        assert {"get_ad_accounts", "get_campaigns", "get_account_insights", "create_paused_campaign"} <= names
        result = await client.call_tool("get_ad_accounts", {"access_token": "sensitive-token"})

    assert result.is_error is False
    assert tool_payload(result)[0]["id"] == "act_123"
    assert seen["authorization"] == "Bearer sensitive-token"
    assert "sensitive-token" not in seen["url"]


@pytest.mark.asyncio
async def test_campaign_creation_is_paused_and_checks_account_ownership():
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append((request.method, request.url.path, request.headers.get("authorization")))
        if request.url.path.endswith("/me/adaccounts"):
            return httpx.Response(200, json={"data": [{"id": "act_123", "account_id": "123"}]})
        if request.url.path.endswith("/search"):
            return httpx.Response(200, json={"data": [{"key": "456", "type": "city", "name": "Vijayawada"}]})
        if request.url.path.endswith("/act_123/campaigns"):
            form = dict(httpx.QueryParams(request.content.decode()))
            assert form["status"] == "PAUSED"
            return httpx.Response(200, json={"id": "campaign-1"})
        if request.url.path.endswith("/act_123/adsets"):
            form = dict(httpx.QueryParams(request.content.decode()))
            assert form["status"] == "PAUSED"
            return httpx.Response(200, json={"id": "adset-1"})
        raise AssertionError(f"Unexpected Graph request path: {request.url.path}")

    server = create_meta_ads_mcp(Settings(meta_graph_api_version="v27.0"), httpx.MockTransport(handler))
    async with Client(server) as client:
        result = await client.call_tool("create_paused_campaign", {
            "ad_account_id": "act_123",
            "name": "Spring homes",
            "objective": "leads",
            "location": "Vijayawada",
            "age_min": 18,
            "age_max": 65,
            "platforms": ["facebook"],
            "daily_budget_minor": 50000,
            "confirmed": True,
            "access_token": "sensitive-token",
        })

    data = tool_payload(result)
    assert data["campaign_id"] == "campaign-1"
    assert data["ad_set_id"] == "adset-1"
    assert data["status"] == "PAUSED"
    assert all(item[2] == "Bearer sensitive-token" for item in calls)


@pytest.mark.asyncio
async def test_mcp_does_not_create_campaign_for_an_unowned_account():
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request.url.path)
        return httpx.Response(200, json={"data": [{"id": "act_123"}]})

    server = create_meta_ads_mcp(Settings(meta_graph_api_version="v27.0"), httpx.MockTransport(handler))
    async with Client(server) as client:
        with pytest.raises(ToolError, match="not available to the connected Meta user"):
            await client.call_tool("create_paused_campaign", {
                "ad_account_id": "act_999",
                "name": "Spring homes",
                "objective": "leads",
                "location": "Vijayawada",
                "age_min": 18,
                "age_max": 65,
                "platforms": ["facebook"],
                "daily_budget_minor": 50000,
                "confirmed": True,
                "access_token": "sensitive-token",
            })

    assert calls == ["/v27.0/me/adaccounts"]
