"""Internal Meta publishing entry point; all Graph mutations go through FastMCP tools."""

from fastapi import APIRouter, Depends, Header, HTTPException, Request, status
from fastmcp.exceptions import FastMCPError, ToolError

from dependencies import require_service_key
from schemas import MetaCampaignPublishRequest

router = APIRouter(prefix="/api/meta", tags=["Meta Ads"], dependencies=[Depends(require_service_key)])


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
