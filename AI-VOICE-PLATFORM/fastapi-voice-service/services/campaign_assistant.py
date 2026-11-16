"""Server-side campaign copy assistance using OpenAI or an OpenAI-compatible local model."""

from __future__ import annotations

import logging

import httpx

from config import Settings
from schemas import CampaignAssistRequest, CampaignAssistResponse

logger = logging.getLogger(__name__)


class CampaignAssistantUnavailable(RuntimeError):
    """AI assistance is not configured or the selected model endpoint failed."""


class CampaignAssistantService:
    def __init__(self, settings: Settings, transport: httpx.AsyncBaseTransport | None = None):
        self._settings = settings
        self._transport = transport

    async def assist(self, request: CampaignAssistRequest) -> CampaignAssistResponse:
        settings = self._settings
        if not settings.ai_configured:
            raise CampaignAssistantUnavailable("Campaign AI is not configured")

        system = (
            "You are a careful real-estate campaign copy assistant. Summarize the user's content and propose "
            "a concise, platform-aware rewrite. Use only facts in the supplied content; never invent prices, "
            "returns, approvals, amenities, availability, or legal claims. Explicitly flag missing information. "
            "Return exactly two labeled sections: 'Summary:' (one sentence) and 'Suggested copy:' (ready-to-review ad text). "
            f"Write in {'natural Telugu' if request.language == 'te' else 'English'}."
        )
        user = (
            f"Campaign: {request.campaign_name}\nObjective: {request.objective}\n"
            f"Selected channels: {', '.join(request.platforms)}\n"
            f"User request: {request.prompt}\n\nCurrent ad content:\n{request.content}"
        )
        payload = {
            "model": settings.ai_model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            "temperature": 0.4,
            "max_tokens": 700,
        }
        headers = {"Content-Type": "application/json"}
        if settings.ai_api_key:
            headers["Authorization"] = f"Bearer {settings.ai_api_key}"

        try:
            async with httpx.AsyncClient(timeout=settings.ai_timeout_seconds, transport=self._transport) as client:
                response = await client.post(
                    f"{settings.ai_base_url.rstrip('/')}/chat/completions",
                    json=payload,
                    headers=headers,
                )
        except httpx.HTTPError as exc:
            logger.warning("Campaign AI request failed (%s)", exc.__class__.__name__)
            raise CampaignAssistantUnavailable("Campaign AI endpoint could not be reached") from exc

        if response.is_error:
            logger.warning("Campaign AI endpoint returned HTTP %s", response.status_code)
            raise CampaignAssistantUnavailable(f"Campaign AI endpoint returned HTTP {response.status_code}")
        try:
            reply = response.json()["choices"][0]["message"]["content"]
        except (ValueError, KeyError, IndexError, TypeError) as exc:
            raise CampaignAssistantUnavailable("Campaign AI response had no assistant text") from exc
        if not isinstance(reply, str) or not reply.strip():
            raise CampaignAssistantUnavailable("Campaign AI returned an empty response")
        return CampaignAssistResponse(reply=reply.strip(), provider=settings.ai_provider, model=settings.ai_model)
