"""Provider call control for the legacy OpenAI SIP path and Edesy CPaaS."""

from __future__ import annotations

import logging
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

import httpx

from config import Settings

logger = logging.getLogger(__name__)


class TelephonyConfigurationError(RuntimeError):
    """OpenAI or SIP trunk settings are missing. Nothing was sent."""


class SessionRejected(RuntimeError):
    """The provider definitely did not start a call."""


class SessionStartUnknown(RuntimeError):
    """The request may or may not have started a call (timeout, dropped connection, provider 5xx).

    Callers must not retry automatically: the customer's phone may already be ringing.
    """


def _provider_message(response: httpx.Response) -> str:
    try:
        error = response.json().get("error")
        if isinstance(error, dict):
            return str(error.get("message") or error.get("code") or "")[:200]
        if error:
            return str(error)[:200]
    except ValueError:
        pass
    return ""


class TelephonyService:
    def __init__(self, settings: Settings, transport: httpx.AsyncBaseTransport | None = None):
        self._settings = settings
        self._transport = transport  # tests inject a MockTransport

    def _client(self, timeout: float) -> httpx.AsyncClient:
        return httpx.AsyncClient(timeout=timeout, transport=self._transport)

    def _headers(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self._settings.openai_api_key}"}

    def _edesy_headers(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self._settings.edesy_api_key}"}

    def build_transport(self, destination: str) -> dict:
        s = self._settings
        return {
            "type": "sip",
            "destination": destination,
            "trunk": {
                "provider_url": s.sip_provider_url,
                "auth": {"type": "digest", "username": s.sip_username, "password": s.sip_password},
                "caller_number": s.sip_caller_number,
            },
        }

    async def create_outbound_sip_session(self, destination: str, session_config: dict) -> str:
        """Returns the session id. A session id means the call was created, not that anyone answered."""
        if not self._settings.telephony_configured:
            raise TelephonyConfigurationError("OpenAI and SIP trunk settings are incomplete")

        payload = {"session": session_config, "transport": self.build_transport(destination)}
        try:
            async with self._client(self._settings.session_create_timeout_seconds) as client:
                response = await client.post(
                    self._settings.openai_live_url,
                    json=payload,
                    headers={**self._headers(), "Content-Type": "application/json"},
                )
        except (httpx.ConnectError, httpx.ConnectTimeout) as exc:
            raise SessionRejected("Could not reach the voice provider") from exc
        except httpx.HTTPError as exc:
            raise SessionStartUnknown("The voice provider did not confirm the call in time") from exc

        if 400 <= response.status_code < 500:
            detail = _provider_message(response)
            logger.warning("Provider rejected call: HTTP %s %s", response.status_code, detail)
            raise SessionRejected(f"Provider rejected the call (HTTP {response.status_code})")
        if response.status_code >= 500:
            raise SessionStartUnknown(f"Provider error (HTTP {response.status_code})")

        try:
            return response.json()["session"]["id"]
        except (ValueError, KeyError, TypeError) as exc:
            raise SessionStartUnknown("Provider response had no session id") from exc

    def _edesy_stream_url(self) -> str:
        settings = self._settings
        parts = urlsplit(settings.edesy_media_ws_url)
        query = dict(parse_qsl(parts.query, keep_blank_values=True))
        query["token"] = settings.edesy_stream_token
        return urlunsplit((parts.scheme, parts.netloc, parts.path, urlencode(query), parts.fragment))

    async def create_outbound_edesy_call(
        self, destination: str, *, call_id: str, lead_id: str | None
    ) -> str:
        """Place an Edesy call and return its outbound call_sid, not an answered-call claim."""
        settings = self._settings
        if (
            not settings.telephony_configured
            or not settings.speech_backend_configured
            or settings.call_provider.lower() != "edesy"
        ):
            raise TelephonyConfigurationError("Edesy call or selected speech-engine settings are incomplete")

        payload = {
            "from": settings.edesy_from_number,
            "to": {"type": "phone", "number": destination},
            "stream_url": self._edesy_stream_url(),
            "stream_sample_rate": 16000,
            "status_callback": settings.edesy_status_callback_url,
            "status_method": "POST",
            "timeout": settings.edesy_call_timeout_seconds,
            "custom_parameters": {"call_id": call_id, "lead_id": lead_id or ""},
        }
        try:
            async with self._client(settings.session_create_timeout_seconds) as client:
                response = await client.post(
                    f"{settings.edesy_api_base_url.rstrip('/')}/calls",
                    json=payload,
                    headers={**self._edesy_headers(), "Content-Type": "application/json"},
                )
        except (httpx.ConnectError, httpx.ConnectTimeout) as exc:
            raise SessionRejected("Could not connect to Edesy call API") from exc
        except httpx.HTTPError as exc:
            raise SessionStartUnknown("Edesy did not confirm whether the call was placed") from exc

        if 400 <= response.status_code < 500 or response.status_code == 503:
            logger.warning("Edesy rejected call creation: HTTP %s", response.status_code)
            raise SessionRejected(f"Edesy rejected the call (HTTP {response.status_code})")
        if response.status_code >= 500:
            raise SessionStartUnknown(f"Edesy call creation returned HTTP {response.status_code}")

        try:
            body = response.json()
            return body["data"].get("call_sid") or body["data"]["sid"]
        except (ValueError, KeyError, TypeError, AttributeError) as exc:
            raise SessionStartUnknown("Edesy response had no call SID") from exc

    async def hangup(self, session_id: str) -> None:
        if self._settings.call_provider.lower() == "edesy":
            async with self._client(self._settings.hangup_timeout_seconds) as client:
                response = await client.delete(
                    f"{self._settings.edesy_api_base_url.rstrip('/')}/calls/{session_id}",
                    headers=self._edesy_headers(),
                )
                if response.status_code == 409:
                    return
                response.raise_for_status()
            return

        async with self._client(self._settings.hangup_timeout_seconds) as client:
            response = await client.post(
                f"{self._settings.openai_live_url}/{session_id}/hangup", headers=self._headers()
            )
            response.raise_for_status()
