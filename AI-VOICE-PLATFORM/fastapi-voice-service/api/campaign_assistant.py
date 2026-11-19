from fastapi import APIRouter, Depends, Header, HTTPException, Request, status

from dependencies import require_service_key
from schemas import (
    CampaignAssistRequest,
    CampaignAssistResponse,
    SkillSummary,
    SkillUploadRequest,
)
from services.campaign_assistant import CampaignAssistantUnavailable

router = APIRouter(prefix="/api/ads", tags=["Campaign AI"], dependencies=[Depends(require_service_key)])


@router.post("/assist", response_model=CampaignAssistResponse)
async def campaign_assist(
    body: CampaignAssistRequest,
    request: Request,
    x_owner_email: str | None = Header(default=None, alias="X-Owner-Email"),
) -> CampaignAssistResponse:
    try:
        return await request.app.state.campaign_assistant.assist(
            body,
            meta_access_token=request.headers.get("X-Meta-Access-Token"),
            owner_email=x_owner_email,
        )
    except CampaignAssistantUnavailable as exc:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, str(exc)) from exc


@router.get("/skills", response_model=list[SkillSummary])
async def list_skills(
    request: Request,
    x_owner_email: str | None = Header(default=None, alias="X-Owner-Email"),
) -> list[SkillSummary]:
    registry = request.app.state.skill_registry
    return [
        SkillSummary(
            id=item.id,
            name=item.name,
            description=item.description,
            source=item.source,  # type: ignore[arg-type]
            featured=item.featured,
        )
        for item in registry.list_skills(x_owner_email)
    ]


@router.post("/skills", response_model=SkillSummary, status_code=status.HTTP_201_CREATED)
async def upload_skill(
    body: SkillUploadRequest,
    request: Request,
    x_owner_email: str | None = Header(default=None, alias="X-Owner-Email"),
) -> SkillSummary:
    if not x_owner_email or not x_owner_email.strip():
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Owner email is required")
    try:
        saved = request.app.state.skill_registry.save_upload(
            x_owner_email,
            body.markdown,
            name=body.name,
            description=body.description,
        )
    except ValueError as exc:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, str(exc)) from exc
    return SkillSummary(
        id=saved.id,
        name=saved.name,
        description=saved.description,
        source="uploaded",
        featured=saved.featured,
    )


@router.delete("/skills/{skill_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_skill(
    skill_id: str,
    request: Request,
    x_owner_email: str | None = Header(default=None, alias="X-Owner-Email"),
) -> None:
    if not x_owner_email or not x_owner_email.strip():
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Owner email is required")
    deleted = request.app.state.skill_registry.delete_upload(x_owner_email, skill_id)
    if not deleted:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Skill not found")
