from fastapi import APIRouter, Depends, HTTPException, Response

from config import Settings, get_settings
from dependencies import require_service_key
from services.elevenlabs_tts import synthesize_bytes

router = APIRouter(prefix="/api/tts", tags=["TTS"], dependencies=[Depends(require_service_key)])


@router.get("/preview")
async def tts_preview(
    text: str, content_type: str | None = None, settings: Settings = Depends(get_settings)
) -> Response:
    """Development helper: synthesize TTS with ElevenLabs and return audio bytes.

    Requires X-Service-Key; allowed in all envs but intended for development.
    """
    if not settings.elevenlabs_configured:
        raise HTTPException(status_code=503, detail="ElevenLabs is not configured")

    data = await synthesize_bytes(settings, text)
    # Infer a content-type for common formats
    fmt = settings.elevenlabs_output_format.lower()
    ctype = content_type or (
        "audio/wav" if fmt.startswith("pcm_") else ("audio/mpeg" if fmt.startswith("mp3") else "application/octet-stream")
    )
    return Response(content=data, media_type=ctype)
