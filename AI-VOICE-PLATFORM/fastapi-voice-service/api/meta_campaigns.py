"""Internal Meta publishing entry point; all Graph mutations go through FastMCP tools."""

from fastapi import APIRouter, Depends, Header, HTTPException, Request, status
from fastmcp.exceptions import FastMCPError, ToolError

from dependencies import require_service_key
from schemas import MetaCampaignLookupRequest, MetaCampaignPublishRequest, MetaCampaignStatusRequest

router = APIRouter(prefix="/api/meta", tags=["Meta Ads"], dependencies=[Depends(require_service_key)])


def _tool_error(result) -> None:
    if result.is_error:
        details = " ".join(
            block.text for block in result.content if isinstance(getattr(block, "text", None), str)
        )
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, details or "Meta tool failed")


def _tool_rows(result) -> list[dict]:
    _tool_error(result)
    value = result.structured_content
    if isinstance(value, dict) and isinstance(value.get("result"), list):
        value = value["result"]
    if isinstance(value, list):
        return [row for row in value if isinstance(row, dict)]
    raise HTTPException(status.HTTP_502_BAD_GATEWAY, "Meta tool returned an invalid response")


async def _call(request: Request, name: str, arguments: dict):
    from fastmcp import Client

    try:
        async with Client(request.app.state.meta_ads_mcp) as client:
            return await client.call_tool(name, arguments, raise_on_error=False)
    except ToolError as exc:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, str(exc)) from exc
    except FastMCPError as exc:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Meta MCP service is unavailable") from exc


def _tool_data(result) -> dict:
    if result.is_error:
        details = " ".join(
            block.text for block in result.content if isinstance(getattr(block, "text", None), str)
        )
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, details or "Meta publish tool failed")
    value = result.structured_content
    if isinstance(value, dict):
        if set(value) == {"result"} and isinstance(value["result"], dict):
            return value["result"]
        return value
    raise HTTPException(status.HTTP_502_BAD_GATEWAY, "Meta publish tool returned an invalid response")


@router.post("/publish")
async def publish_paused_campaign(
    body: MetaCampaignPublishRequest,
    request: Request,
    access_token: str | None = Header(default=None, alias="X-Meta-Access-Token"),
) -> dict:
    if not body.confirmed:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Confirm campaign creation before publishing")
    if not access_token:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Connect Meta Business before publishing")
    if body.age_max < body.age_min:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Maximum age must be greater than or equal to minimum age")

    from fastmcp import Client

    try:
        async with Client(request.app.state.meta_ads_mcp) as client:
            result = await client.call_tool("create_paused_campaign", {
                "ad_account_id": body.ad_account_id,
                "name": body.campaign_name,
                "objective": body.objective,
                "location": body.location,
                "age_min": body.age_min,
                "age_max": body.age_max,
                "platforms": body.platforms,
                "daily_budget_minor": body.daily_budget_minor or 50_000,
                "confirmed": body.confirmed,
                "access_token": access_token,
            })
    except ToolError as exc:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, str(exc)) from exc
    except FastMCPError as exc:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Meta MCP service is unavailable") from exc
    return _tool_data(result)


@router.post("/campaigns/lookup")
async def lookup_campaigns(
    body: MetaCampaignLookupRequest,
    request: Request,
    access_token: str | None = Header(default=None, alias="X-Meta-Access-Token"),
) -> dict:
    """Read-only. Used by the Spring execution engine to reconcile a publish whose outcome is unknown."""
    if not access_token:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Connect Meta Business first")
    result = await _call(request, "find_campaigns_by_name", {
        "ad_account_id": body.ad_account_id,
        "name_contains": body.name_contains,
        "access_token": access_token,
    })
    campaigns = []
    for row in _tool_rows(result):
        adsets = _tool_rows(await _call(request, "get_ad_sets", {
            "ad_account_id": body.ad_account_id,
            "campaign_id": str(row.get("id", "")),
            "access_token": access_token,
            "limit": 10,
        }))
        campaigns.append({
            "id": row.get("id"),
            "name": row.get("name"),
            "status": row.get("status"),
            "effective_status": row.get("effective_status"),
            "adset_ids": [str(a.get("id")) for a in adsets if a.get("id")],
        })
    return {"campaigns": campaigns}


@router.post("/campaigns/status")
async def campaign_status(
    body: MetaCampaignStatusRequest,
    request: Request,
    access_token: str | None = Header(default=None, alias="X-Meta-Access-Token"),
) -> dict:
    """Read-only: what Meta currently reports for one campaign."""
    if not access_token:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Connect Meta Business first")
    data = _tool_data(await _call(request, "get_campaign_details", {
        "campaign_id": body.campaign_id,
        "access_token": access_token,
    }))
    return {
        "id": data.get("id"),
        "name": data.get("name"),
        "status": data.get("status"),
        "effective_status": data.get("effective_status"),
    }
