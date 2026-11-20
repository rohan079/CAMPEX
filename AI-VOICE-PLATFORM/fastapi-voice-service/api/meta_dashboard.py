"""Dashboard snapshot built from Meta Ads MCP read tools."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request, status
from fastmcp.exceptions import FastMCPError, ToolError

from dependencies import require_service_key

router = APIRouter(prefix="/api/meta", tags=["Meta Ads"], dependencies=[Depends(require_service_key)])

READ_TOOLS = (
    "get_business_accounts",
    "get_ad_accounts",
    "get_ad_account_details",
    "get_facebook_pages",
    "get_instagram_accounts",
    "get_campaigns",
    "get_campaign_details",
    "get_ad_sets",
    "get_adset_details",
    "get_ads",
    "get_ad_details",
    "get_ad_creatives",
    "get_ad_creative",
    "get_campaign_insights",
    "get_adset_insights",
    "get_ad_insights",
    "get_account_insights",
    "get_meta_performance_report",
)


def _unwrap(result: Any) -> Any:
    if getattr(result, "is_error", False):
        details = " ".join(
            block.text for block in getattr(result, "content", [])
            if isinstance(getattr(block, "text", None), str)
        )
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, details or "Meta MCP tool failed")
    value = getattr(result, "structured_content", None)
    if isinstance(value, dict) and set(value) == {"result"}:
        return value["result"]
    if value is not None:
        return value
    chunks = [
        block.text for block in getattr(result, "content", [])
        if isinstance(getattr(block, "text", None), str)
    ]
    return {"text": "\n".join(chunks)} if chunks else None


async def _call(client: Any, name: str, arguments: dict[str, Any]) -> Any:
    try:
        return _unwrap(await client.call_tool(name, arguments))
    except ToolError as exc:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, str(exc)) from exc


@router.get("/tools")
async def list_meta_mcp_tools(request: Request) -> dict[str, Any]:
    from fastmcp import Client

    try:
        async with Client(request.app.state.meta_ads_mcp) as client:
            listed = await client.list_tools()
            tools = getattr(listed, "tools", listed)
            catalog = []
            for tool in tools:
                name = getattr(tool, "name", "")
                catalog.append({
                    "name": name,
                    "description": getattr(tool, "description", "") or name,
                    "read_only": name in READ_TOOLS,
                })
            catalog.sort(key=lambda item: (not item["read_only"], item["name"]))
            return {"tools": catalog, "count": len(catalog)}
    except FastMCPError as exc:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Meta MCP service is unavailable") from exc


@router.get("/dashboard")
async def meta_ads_dashboard(
    request: Request,
    access_token: str | None = Header(default=None, alias="X-Meta-Access-Token"),
    ad_account_id: str | None = Query(default=None, max_length=64),
    date_preset: str = Query(default="last_14d", max_length=32),
) -> dict[str, Any]:
    if not access_token:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Connect Meta Business under Channels first")

    from fastmcp import Client

    try:
        async with Client(request.app.state.meta_ads_mcp) as client:
            listed = await client.list_tools()
            tools = getattr(listed, "tools", listed)
            catalog = [
                {
                    "name": getattr(tool, "name", ""),
                    "description": getattr(tool, "description", "") or getattr(tool, "name", ""),
                    "read_only": getattr(tool, "name", "") in READ_TOOLS,
                }
                for tool in tools
            ]
            catalog.sort(key=lambda item: (not item["read_only"], item["name"]))

            businesses = await _call(client, "get_business_accounts", {"access_token": access_token, "limit": 50})
            ad_accounts = await _call(client, "get_ad_accounts", {"access_token": access_token, "limit": 50})
            pages = await _call(client, "get_facebook_pages", {"access_token": access_token, "limit": 100})
            instagram = await _call(client, "get_instagram_accounts", {"access_token": access_token, "limit": 100})

            selected_id = ad_account_id
            if not selected_id and isinstance(ad_accounts, list) and ad_accounts:
                first = ad_accounts[0]
                if isinstance(first, dict):
                    selected_id = str(first.get("id") or first.get("account_id") or "")

            account_details = None
            campaigns: list[Any] = []
            ad_sets: list[Any] = []
            ads: list[Any] = []
            insights: list[Any] = []
            if selected_id:
                account_details = await _call(client, "get_ad_account_details", {
                    "ad_account_id": selected_id,
                    "access_token": access_token,
                })
                campaigns = await _call(client, "get_campaigns", {
                    "ad_account_id": selected_id,
                    "access_token": access_token,
                    "limit": 50,
                }) or []
                ad_sets = await _call(client, "get_ad_sets", {
                    "ad_account_id": selected_id,
                    "access_token": access_token,
                    "limit": 50,
                }) or []
                ads = await _call(client, "get_ads", {
                    "ad_account_id": selected_id,
                    "access_token": access_token,
                    "limit": 50,
                }) or []
                insights = await _call(client, "get_account_insights", {
                    "ad_account_id": selected_id,
                    "access_token": access_token,
                    "date_preset": date_preset,
                }) or []

            return {
                "connected": True,
                "tools": catalog,
                "businesses": businesses or [],
                "ad_accounts": ad_accounts or [],
                "pages": pages or [],
                "instagram_accounts": instagram or [],
                "selected_ad_account_id": selected_id,
                "account_details": account_details,
                "campaigns": campaigns if isinstance(campaigns, list) else [],
                "ad_sets": ad_sets if isinstance(ad_sets, list) else [],
                "ads": ads if isinstance(ads, list) else [],
                "account_insights": insights if isinstance(insights, list) else [],
                "date_preset": date_preset,
            }
    except FastMCPError as exc:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Meta MCP service is unavailable") from exc
