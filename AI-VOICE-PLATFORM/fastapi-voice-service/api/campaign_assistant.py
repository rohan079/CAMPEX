from fastapi import APIRouter, Depends, HTTPException, Request, status

from dependencies import require_service_key
from schemas import CampaignAssistRequest, CampaignAssistResponse
from services.campaign_assistant import CampaignAssistantUnavailable

router = APIRouter(prefix="/api/ads", tags=["Campaign AI"], dependencies=[Depends(require_service_key)])


@router.post("/assist", response_model=CampaignAssistResponse)
async def campaign_assist(
    body: CampaignAssistRequest,
    request: Request,
) -> CampaignAssistResponse:
    try:
        return await request.app.state.campaign_assistant.assist(
            body,
            meta_access_token=request.headers.get("X-Meta-Access-Token"),
        )
    except CampaignAssistantUnavailable as exc:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, str(exc)) from exc
