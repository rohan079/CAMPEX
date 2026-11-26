import json

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
        assert {
            "get_ad_accounts",
            "get_campaigns",
            "get_account_insights",
            "get_meta_performance_report",
            "get_ad_performance",
            "create_paused_campaign",
        } <= names
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


@pytest.mark.asyncio
async def test_meta_performance_report_prefers_account_insights_and_marks_empty():
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path.endswith("/act_123/insights")
        assert "last_14d" in str(request.url)
        return httpx.Response(200, json={"data": []})

    server = create_meta_ads_mcp(Settings(meta_graph_api_version="v27.0"), httpx.MockTransport(handler))
    async with Client(server) as client:
        result = await client.call_tool("get_meta_performance_report", {
            "ad_account_id": "act_123",
            "access_token": "sensitive-token",
        })

    data = tool_payload(result)
    assert data["status"] == "empty"
    assert data["metrics"] == []
    assert "No metrics were returned" in data["message"]


@pytest.mark.asyncio
async def test_meta_performance_report_totals_and_zero_status():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"data": [{
            "spend": "0",
            "impressions": "0",
            "reach": "0",
            "clicks": "0",
            "ctr": "0",
            "cpc": "0",
            "cpm": "0",
        }]})

    server = create_meta_ads_mcp(Settings(meta_graph_api_version="v27.0"), httpx.MockTransport(handler))
    async with Client(server) as client:
        result = await client.call_tool("get_meta_performance_report", {
            "ad_account_id": "123",
            "access_token": "sensitive-token",
            "date_preset": "last_7d",
        })

    data = tool_payload(result)
    assert data["status"] == "zero_metrics"
    assert data["totals"]["spend"] == 0.0
    assert data["scope"]["ad_account_id"] == "act_123"


@pytest.mark.asyncio
async def test_ad_performance_resolves_exact_name_and_uses_last_7d():
    seen = {"paths": []}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["paths"].append(request.url.path)
        if request.url.path.endswith("/act_55/ads"):
            query = str(request.url)
            assert "EQUAL" in query
            assert "Spring+Launch" in query or "Spring%20Launch" in query or "Spring Launch" in query
            return httpx.Response(200, json={"data": [{
                "id": "111",
                "name": "Spring Launch",
                "campaign_id": "222",
                "adset_id": "333",
                "effective_status": "ACTIVE",
            }]})
        if request.url.path.endswith("/111/insights"):
            assert "last_7d" in str(request.url)
            return httpx.Response(200, json={"data": [{
                "spend": "25.5",
                "impressions": "1000",
                "reach": "800",
                "clicks": "40",
            }]})
        raise AssertionError(f"Unexpected path {request.url.path}")

    server = create_meta_ads_mcp(Settings(meta_graph_api_version="v27.0"), httpx.MockTransport(handler))
    async with Client(server) as client:
        result = await client.call_tool("get_ad_performance", {
            "ad_account_id": "act_55",
            "ad_name": "Spring Launch",
            "access_token": "sensitive-token",
        })

    data = tool_payload(result)
    assert data["status"] == "ok"
    assert data["scope"]["ad_id"] == "111"
    assert data["period"]["date_preset"] == "last_7d"
    assert data["totals"]["clicks"] == 40.0
    assert seen["paths"] == ["/v27.0/act_55/ads", "/v27.0/111/insights"]


@pytest.mark.asyncio
async def test_ad_performance_reports_exact_name_misses():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"data": []})

    server = create_meta_ads_mcp(Settings(meta_graph_api_version="v27.0"), httpx.MockTransport(handler))
    async with Client(server) as client:
        result = await client.call_tool("get_ad_performance", {
            "ad_account_id": "act_55",
            "ad_name": "Missing Ad",
            "access_token": "sensitive-token",
        })

    data = tool_payload(result)
    assert data["status"] == "not_found"
    assert "exact name" in data["message"]


@pytest.mark.asyncio
async def test_find_campaigns_by_name_filters_server_side_and_exactly():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["path"] = request.url.path
        seen["filtering"] = request.url.params.get("filtering")
        seen["method"] = request.method
        return httpx.Response(200, json={"data": [
            {"id": "1", "name": "Villas [cx-abc123def0]", "status": "PAUSED"},
            {"id": "2", "name": "Villas [CX-ABC123DEF0]", "status": "PAUSED"},
        ]})

    server = create_meta_ads_mcp(Settings(meta_graph_api_version="v27.0"), httpx.MockTransport(handler))
    async with Client(server) as client:
        result = await client.call_tool("find_campaigns_by_name", {
            "ad_account_id": "act_123", "name_contains": "cx-abc123def0", "access_token": "t"})

    assert seen["method"] == "GET"
    assert seen["path"].endswith("/act_123/campaigns")
    assert '"operator": "CONTAIN"' in seen["filtering"]
    assert [row["id"] for row in tool_payload(result)] == ["1"]


@pytest.mark.asyncio
async def test_apply_object_change_sends_only_the_changed_fields():
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append((request.method, request.url.path, dict(httpx.QueryParams(request.content.decode()))))
        return httpx.Response(200, json={"success": True})

    server = create_meta_ads_mcp(Settings(meta_graph_api_version="v27.0"), httpx.MockTransport(handler))
    async with Client(server) as client:
        await client.call_tool("apply_object_change", {"level": "adset", "object_id": "123", "access_token": "t",
                                                       "confirmed": True, "daily_budget_minor": 38500})
        await client.call_tool("update_ad_set", {"adset_id": "123", "access_token": "t", "confirmed": True,
                                                 "daily_budget_minor": 40000})
        with pytest.raises(ToolError):
            await client.call_tool("apply_object_change", {"level": "ad", "object_id": "9", "access_token": "t",
                                                           "confirmed": True, "daily_budget_minor": 500})
        with pytest.raises(ToolError):
            await client.call_tool("apply_object_change", {"level": "ad", "object_id": "9", "access_token": "t",
                                                           "confirmed": False, "status": "PAUSED"})

    # Budget-only changes never touch status (update_ad_set used to send status=PAUSED by default).
    assert seen[0][2] == {"daily_budget": "38500"}
    assert seen[1][2] == {"daily_budget": "40000"}
    assert len(seen) == 2


def test_write_tools_stay_out_of_the_ai_allowlist():
    from services.campaign_assistant import READ_ONLY_META_TOOLS

    assert "apply_object_change" not in READ_ONLY_META_TOOLS


def _campaign_args(**overrides):
    args = {
        "ad_account_id": "act_123", "name": "Spring homes", "objective": "leads", "location": "Vijayawada",
        "age_min": 18, "age_max": 65, "platforms": ["facebook"], "daily_budget_minor": 50000,
        "confirmed": True, "access_token": "sensitive-token",
    }
    args.update(overrides)
    return args


def _location_handler(search_rows, seen):
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/me/adaccounts"):
            return httpx.Response(200, json={"data": [{"id": "act_123"}]})
        if request.url.path.endswith("/search"):
            q = request.url.params.get("q")
            seen.setdefault("queries", []).append(q)
            return httpx.Response(200, json={"data": search_rows(q)})
        if request.url.path.endswith("/act_123/campaigns"):
            return httpx.Response(200, json={"id": "campaign-1"})
        if request.url.path.endswith("/act_123/adsets"):
            seen["targeting"] = json.loads(dict(httpx.QueryParams(request.content.decode()))["targeting"])
            return httpx.Response(200, json={"id": "adset-1"})
        raise AssertionError(f"Unexpected Graph request path: {request.url.path}")
    return handler


GUNTUR = {"key": "1035921", "type": "city", "name": "Guntur", "region": "Andhra Pradesh", "country_name": "India"}
GUNTUR_OTHER = {"key": "999", "type": "city", "name": "Guntur", "region": "Elsewhere", "country_name": "India"}


@pytest.mark.asyncio
async def test_location_with_region_falls_back_to_place_search_and_filters():
    seen: dict = {}
    rows = lambda q: [] if "," in q else [GUNTUR, GUNTUR_OTHER]  # noqa: E731
    server = create_meta_ads_mcp(Settings(meta_graph_api_version="v27.0"), httpx.MockTransport(_location_handler(rows, seen)))
    async with Client(server) as client:
        await client.call_tool("create_paused_campaign", _campaign_args(location="Guntur, Andhra Pradesh"))
    assert seen["queries"] == ["Guntur, Andhra Pradesh", "Guntur"]
    assert seen["targeting"]["geo_locations"] == {"cities": [{"key": "1035921"}]}


@pytest.mark.asyncio
async def test_exact_geo_key_skips_search_and_ambiguity_lists_options():
    seen: dict = {}
    server = create_meta_ads_mcp(Settings(meta_graph_api_version="v27.0"),
                                 httpx.MockTransport(_location_handler(lambda q: [GUNTUR, GUNTUR_OTHER], seen)))
    async with Client(server) as client:
        await client.call_tool("create_paused_campaign", _campaign_args(location="Andhra Pradesh, India",
                                                                        geo_key="1234", geo_type="region"))
        assert "queries" not in seen
        assert seen["targeting"]["geo_locations"] == {"regions": [{"key": "1234"}]}
        with pytest.raises(ToolError, match="matches several places.*Guntur, Andhra Pradesh, India"):
            await client.call_tool("create_paused_campaign", _campaign_args(location="Guntur"))
        found = await client.call_tool("search_ad_locations", {"query": "Gun", "access_token": "sensitive-token"})
    labels = [row["label"] for row in tool_payload(found)]
    assert labels == ["Guntur, Andhra Pradesh, India", "Guntur, Elsewhere, India"]
