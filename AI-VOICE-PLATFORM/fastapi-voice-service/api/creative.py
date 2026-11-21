"""Phase 3 Creative Studio endpoints, called only by the lead service (X-Service-Key)."""

import base64
import binascii
from typing import Any, Literal

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field

from dependencies import require_service_key
from services.creative_studio import CreativeStudioService, ImageGenerationError, check_claims, generate_ad_image
from services.llm_providers import ProviderUnavailable, adapter_for

router = APIRouter(prefix="/api/creative", tags=["Creative Studio"], dependencies=[Depends(require_service_key)])

MAX_REFERENCE_BYTES = 8 * 1024 * 1024


class CopyBrief(BaseModel):
    business_type: str = Field(default="real estate", max_length=80)
    ad_type: str = Field(default="property_launch", max_length=40)
    product: str = Field(min_length=1, max_length=2000)
    audience: str = Field(default="", max_length=500)
    location: str = Field(default="", max_length=160)
    objective: str = Field(default="leads", max_length=20)
    platform: str = Field(default="facebook_instagram", max_length=40)
    language: Literal["en", "te", "hi", "kn", "ta"] = "en"
    tone: str = Field(default="trustworthy", max_length=80)
    offer: str = Field(default="", max_length=500)
    cta: str = Field(default="", max_length=40)


class CopyRequest(BaseModel):
    brief: CopyBrief
    facts: list[str] = Field(default_factory=list, max_length=40)


class CheckRequest(BaseModel):
    texts: list[str] = Field(max_length=20)
    facts: list[str] = Field(default_factory=list, max_length=40)
    brief: dict[str, Any] = Field(default_factory=dict)


class ImageRequest(BaseModel):
    prompt: str = Field(min_length=3, max_length=3000)
    reference_base64: str | None = Field(default=None, max_length=12_000_000)
    reference_mime: Literal["image/png", "image/jpeg", "image/webp"] | None = None


@router.post("/copy")
async def generate_copy(body: CopyRequest, request: Request) -> dict:
    facts = [f.strip()[:300] for f in body.facts if f.strip()]
    try:
        service = CreativeStudioService(adapter_for(request.app.state.settings))
        return await service.generate_copy(body.brief.model_dump(), facts)
    except ProviderUnavailable as exc:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, str(exc)) from exc


@router.post("/check")
async def check(body: CheckRequest) -> dict:
    """Deterministic claim check for copy a user edited by hand. No AI call."""
    result = check_claims([t[:3000] for t in body.texts], [f[:300] for f in body.facts], body.brief)
    return {"unsupported_numbers": result.unsupported_numbers, "flagged_phrases": result.flagged_phrases}


@router.post("/image")
async def generate_image(body: ImageRequest, request: Request) -> dict:
    reference = None
    if body.reference_base64:
        if not body.reference_mime:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, "reference_mime is required with a reference image")
        try:
            data = base64.b64decode(body.reference_base64, validate=True)
        except (binascii.Error, ValueError) as exc:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, "Reference image is not valid base64") from exc
        if len(data) > MAX_REFERENCE_BYTES:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, "Reference image must be 8 MB or smaller")
        reference = (data, body.reference_mime)
    try:
        return await generate_ad_image(request.app.state.settings, body.prompt, reference)
    except ImageGenerationError as exc:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, str(exc)) from exc
