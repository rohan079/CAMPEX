"""Creates the ad inside a paused campaign: media upload → ad creative → ad (always PAUSED).

Called only by the Spring execution engine after a recorded approval. Every write is idempotent from the caller's
point of view: the ad name carries the Campex publish tag, and `find_ads` lets the caller check before creating.
"""

from __future__ import annotations

import base64
import json
import logging
from typing import Any, Literal

import httpx

from config import Settings
from services.meta_ads_mcp import MetaAdsGraphApi, MetaAdsToolError

logger = logging.getLogger(__name__)

CTA_TYPES = {"LEARN_MORE", "SIGN_UP", "CONTACT_US", "MESSAGE_PAGE", "BOOK_NOW", "GET_QUOTE", "APPLY_NOW"}


class MetaAdBuilder:
    def __init__(self, settings: Settings, transport: httpx.AsyncBaseTransport | None = None):
        self._graph = MetaAdsGraphApi(settings, transport)
        self._video_base = f"https://graph-video.facebook.com/{settings.meta_graph_api_version}"
        self._transport = transport

    async def upload_image(self, token: str, ad_account_id: str, name: str, data: bytes) -> str:
        account = self._graph.account_id(ad_account_id)
        payload = await self._graph.request("POST", f"{account}/adimages", token, form={
            "bytes": base64.b64encode(data).decode("ascii"), "name": name[:100]})
        images = payload.get("images")
        if isinstance(images, dict):
            for item in images.values():
                if isinstance(item, dict) and item.get("hash"):
                    return str(item["hash"])
        raise MetaAdsToolError("Meta did not return an image hash.")

    async def upload_video(self, token: str, ad_account_id: str, name: str, stream, size: int, mime: str) -> str:
        """Non-resumable upload; suitable for short ad videos (Campex limits uploads to a few hundred MB)."""
        account = self._graph.account_id(ad_account_id)
        if not token or not token.strip():
            raise MetaAdsToolError("Connect Meta Business before uploading media.")
        try:
            async with httpx.AsyncClient(timeout=httpx.Timeout(600, connect=20), transport=self._transport) as client:
                response = await client.post(
                    f"{self._video_base}/{account}/advideos",
                    headers={"Authorization": f"Bearer {token.strip()}"},
                    data={"name": name[:100]},
                    files={"source": (name[:100] or "video.mp4", stream, mime)},
                )
        except httpx.HTTPError as exc:
            logger.warning("Meta video upload failed (%s)", type(exc).__name__)
            raise MetaAdsToolError("Meta could not be reached for the video upload. Retry shortly.") from exc
        try:
            payload = response.json()
        except ValueError as exc:
            raise MetaAdsToolError("Meta returned an unreadable response to the video upload.") from exc
        if not response.is_success or "error" in payload:
            code = (payload.get("error") or {}).get("code") if isinstance(payload, dict) else None
            logger.warning("Meta rejected a video upload (HTTP %s, code %s)", response.status_code, code)
            raise MetaAdsToolError(f"Meta rejected the video (HTTP {response.status_code}, code {code}).")
        video_id = str(payload.get("id", ""))
        if not video_id:
            raise MetaAdsToolError("Meta did not return a video ID.")
        return video_id

    async def video_status(self, token: str, video_id: str) -> dict[str, Any]:
        payload = await self._graph.request("GET", self._graph.object_id(video_id), token, params={"fields": "status"})
        status = payload.get("status") if isinstance(payload.get("status"), dict) else {}
        return {"video_status": status.get("video_status"), "processing_progress": (status.get("processing_phase") or {}).get("progress")
                if isinstance(status.get("processing_phase"), dict) else None}

    async def create_creative(self, token: str, *, ad_account_id: str, page_id: str, kind: Literal["image", "video"],
                              name: str, link_url: str, message: str, headline: str, description: str | None, cta: str,
                              image_hash: str, video_id: str | None = None) -> str:
        if cta not in CTA_TYPES:
            raise MetaAdsToolError("Unsupported call to action.")
        if not link_url.startswith("https://"):
            raise MetaAdsToolError("The ad destination must be an https:// link.")
        account = self._graph.account_id(ad_account_id)
        page = self._graph.object_id(page_id)
        call_to_action = {"type": cta, "value": {"link": link_url}}
        if kind == "video":
            if not video_id:
                raise MetaAdsToolError("A Meta video ID is required for a video creative.")
            video_data: dict[str, Any] = {"video_id": self._graph.object_id(video_id), "image_hash": image_hash,
                                          "message": message, "title": headline, "call_to_action": call_to_action}
            if description:
                video_data["link_description"] = description
            spec = {"page_id": page, "video_data": video_data}
        else:
            link_data: dict[str, Any] = {"image_hash": image_hash, "message": message, "name": headline, "link": link_url,
                                         "call_to_action": call_to_action}
            if description:
                link_data["description"] = description
            spec = {"page_id": page, "link_data": link_data}
        payload = await self._graph.request("POST", f"{account}/adcreatives", token,
                                            form={"name": name[:100], "object_story_spec": json.dumps(spec)})
        creative_id = str(payload.get("id", ""))
        if not creative_id:
            raise MetaAdsToolError("Meta did not return a creative ID.")
        return creative_id

    async def find_ads(self, token: str, adset_id: str, name_contains: str) -> list[dict[str, Any]]:
        rows = await self._graph.list_edge(f"{self._graph.object_id(adset_id)}/ads", token, "id,name,status,effective_status", 25,
                                           {"filtering": json.dumps([{"field": "name", "operator": "CONTAIN", "value": name_contains}])})
        return [r for r in rows if name_contains in str(r.get("name", ""))]

    async def create_ad(self, token: str, ad_account_id: str, adset_id: str, creative_id: str, name: str) -> str:
        account = self._graph.account_id(ad_account_id)
        payload = await self._graph.request("POST", f"{account}/ads", token, form={
            "name": name[:100], "adset_id": self._graph.object_id(adset_id),
            "creative": json.dumps({"creative_id": self._graph.object_id(creative_id)}), "status": "PAUSED"})
        ad_id = str(payload.get("id", ""))
        if not ad_id:
            raise MetaAdsToolError("Meta did not return an ad ID.")
        return ad_id
