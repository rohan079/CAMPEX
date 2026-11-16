"""Reports call events to the Spring lead service, which owns all call and lead state."""

from __future__ import annotations

import asyncio
import logging

import httpx

from config import Settings
from schemas import CallEvent, CallSummary

logger = logging.getLogger(__name__)

RETRY_DELAYS_SECONDS = (0.5, 2, 5)


class LeadServiceClient:
    def __init__(
        self,
        settings: Settings,
        transport: httpx.AsyncBaseTransport | None = None,
        retry_delays: tuple[float, ...] = RETRY_DELAYS_SECONDS,
    ):
        self._settings = settings
        self._transport = transport
        self._retry_delays = retry_delays

    async def publish(self, event: CallEvent) -> bool:
        """POST the event, retrying transient failures. Returns whether the lead service accepted it."""
        url = f"{self._settings.lead_service_url.rstrip('/')}/internal/call-events"
        headers = {"X-Service-Key": self._settings.internal_service_key}
        attempts = len(self._retry_delays) + 1
        for attempt in range(attempts):
            try:
                async with httpx.AsyncClient(timeout=10, transport=self._transport) as client:
                    response = await client.post(url, json=event.model_dump(), headers=headers)
                if response.status_code < 300:
                    return True
                if response.status_code < 500:
                    # The lead service understood and refused; retrying will not help.
                    logger.warning("Lead service rejected %s event: HTTP %s", event.type, response.status_code)
                    return False
                logger.warning("Lead service error on %s event: HTTP %s", event.type, response.status_code)
            except httpx.HTTPError as exc:
                logger.warning("Could not reach lead service for %s event: %s", event.type, exc.__class__.__name__)
            if attempt < attempts - 1:
                await asyncio.sleep(self._retry_delays[attempt])
        logger.error("Gave up reporting %s event for call %s", event.type, event.call_id)
        return False

    async def publish_summary(self, summary: CallSummary) -> bool:
        """Persist a call summary without sending the raw transcript to Spring."""
        url = f"{self._settings.lead_service_url.rstrip('/')}/internal/call-summaries"
        headers = {"X-Service-Key": self._settings.internal_service_key}
        try:
            async with httpx.AsyncClient(timeout=10, transport=self._transport) as client:
                response = await client.post(url, json=summary.model_dump(), headers=headers)
            if response.status_code < 300:
                return True
            logger.warning("Lead service rejected call summary: HTTP %s", response.status_code)
        except httpx.HTTPError as exc:
            logger.warning("Could not publish call summary: %s", exc.__class__.__name__)
        return False
