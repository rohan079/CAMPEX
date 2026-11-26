"""Internal ad-building routes for the Spring execution engine (service key only, never reachable from AI tools)."""

import base64
import binascii
from typing import Literal

from fastapi import APIRouter, Depends, File, Form, Header, HTTPException, Request, UploadFile, status
from pydantic import BaseModel, Field

from dependencies import require_service_key
from services.meta_ads_mcp import MetaAdsToolError

router = APIRouter(prefix="/api/meta/ads-builder", tags=["Meta ad builder"], dependencies=[Depends(require_service_key)])

ID = r"^(act_)?[0-9]{1,32}$"


class ImageUpload(BaseModel):
    ad_account_id: str = Field(pattern=ID)
    name: str = Field(min_length=1, max_length=100)
    data_base64: str = Field(min_length=8, max_length=12_000_000)


class CreativeRequest(BaseModel):
    ad_account_id: str = Field(pattern=ID)
    page_id: str = Field(pattern=r"^[0-9]{1,32}$")
    kind: Literal["image", "video"]
    name: str = Field(min_length=1, max_length=100)
    link_url: str = Field(min_length=10, max_length=2048)
    message: str = Field(min_length=1, max_length=1500)
    headline: str = Field(min_length=1, max_length=80)
    description: str | None = Field(default=None, max_length=255)
    cta: str = Field(max_length=20)
    image_hash: str = Field(min_length=1, max_length=128)
    video_id: str | None = Field(default=None, pattern=r"^[0-9]{1,32}$")


class FindAds(BaseModel):
    adset_id: str = Field(pattern=r"^[0-9]{1,32}$")
    name_contains: str = Field(min_length=4, max_length=100)


class CreateAd(BaseModel):
    ad_account_id: str = Field(pattern=ID)
    adset_id: str = Field(pattern=r"^[0-9]{1,32}$")
    creative_id: str = Field(pattern=r"^[0-9]{1,32}$")
    name: str = Field(min_length=1, max_length=100)
    confirmed: bool


class VideoStatus(BaseModel):
    video_id: str = Field(pattern=r"^[0-9]{1,32}$")


def _token(token: str | None) -> str:
    if not token:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Connect Meta Business first")
    return token


def _builder(request: Request):
    return request.app.state.meta_ad_builder


async def _run(coro):
    try:
        return await coro
    except MetaAdsToolError as exc:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, str(exc)) from exc


@router.post("/image")
async def upload_image(body: ImageUpload, request: Request, access_token: str | None = Header(default=None, alias="X-Meta-Access-Token")) -> dict:
    try:
        data = base64.b64decode(body.data_base64, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Image is not valid base64") from exc
    return {"image_hash": await _run(_builder(request).upload_image(_token(access_token), body.ad_account_id, body.name, data))}


@router.post("/video")
async def upload_video(
    request: Request,
    ad_account_id: str = Form(pattern=ID),
    name: str = Form(min_length=1, max_length=100),
    file: UploadFile = File(...),
    access_token: str | None = Header(default=None, alias="X-Meta-Access-Token"),
) -> dict:
    if file.content_type not in {"video/mp4", "video/quicktime"}:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Only MP4 or MOV videos can be uploaded")
    video_id = await _run(_builder(request).upload_video(_token(access_token), ad_account_id, name, file.file, file.size or 0, file.content_type))
    return {"video_id": video_id}


@router.post("/video-status")
async def video_status(body: VideoStatus, request: Request, access_token: str | None = Header(default=None, alias="X-Meta-Access-Token")) -> dict:
    return await _run(_builder(request).video_status(_token(access_token), body.video_id))


@router.post("/creative")
async def create_creative(body: CreativeRequest, request: Request, access_token: str | None = Header(default=None, alias="X-Meta-Access-Token")) -> dict:
    creative_id = await _run(_builder(request).create_creative(
        _token(access_token), ad_account_id=body.ad_account_id, page_id=body.page_id, kind=body.kind, name=body.name,
        link_url=body.link_url, message=body.message, headline=body.headline, description=body.description, cta=body.cta,
        image_hash=body.image_hash, video_id=body.video_id))
    return {"creative_id": creative_id}


@router.post("/find-ads")
async def find_ads(body: FindAds, request: Request, access_token: str | None = Header(default=None, alias="X-Meta-Access-Token")) -> dict:
    rows = await _run(_builder(request).find_ads(_token(access_token), body.adset_id, body.name_contains))
    return {"ads": [{k: r.get(k) for k in ("id", "name", "status", "effective_status")} for r in rows]}


@router.post("/ad")
async def create_ad(body: CreateAd, request: Request, access_token: str | None = Header(default=None, alias="X-Meta-Access-Token")) -> dict:
    if not body.confirmed:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Approved ad creation must be confirmed")
    ad_id = await _run(_builder(request).create_ad(_token(access_token), body.ad_account_id, body.adset_id, body.creative_id, body.name))
    return {"ad_id": ad_id, "status": "PAUSED"}
