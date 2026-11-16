import hmac

from fastapi import Depends, Header, HTTPException, Request

from config import Settings, get_settings


def _check(expected: str, supplied: str | None, label: str, settings: Settings) -> None:
    if not expected:
        if settings.is_development:
            return
        raise HTTPException(status_code=503, detail=f"{label} is not configured")
    if not supplied or not hmac.compare_digest(supplied, expected):
        raise HTTPException(status_code=401, detail=f"Invalid {label.lower()}")


async def require_service_key(
    x_service_key: str | None = Header(default=None),
    settings: Settings = Depends(get_settings),
) -> None:
    """Only the lead service may start or end calls."""
    _check(settings.internal_service_key, x_service_key, "Service key", settings)


async def require_webhook_secret(
    x_webhook_secret: str | None = Header(default=None),
    settings: Settings = Depends(get_settings),
) -> None:
    _check(settings.voice_webhook_secret, x_webhook_secret, "Webhook secret", settings)


def get_conversation(request: Request):
    return request.app.state.conversation
