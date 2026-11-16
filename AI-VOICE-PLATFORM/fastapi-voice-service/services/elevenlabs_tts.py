"""ElevenLabs Text-to-Speech integration (voice clone output).

Uses the streaming TTS endpoint to synthesize audio. Kept independent from call media so it can be
used for dev previews immediately; live-call use will be wired via the media bridge in Phase 3–4.
"""

from __future__ import annotations

import logging
from typing import AsyncGenerator

import httpx

from config import Settings

logger = logging.getLogger(__name__)

ELEVEN_TTS_URL = "https://api.elevenlabs.io/v1/text-to-speech/{voice_id}/stream"


async def synthesize_bytes(
    settings: Settings,
    text: str,
    *,
    voice_id: str | None = None,
    output_format: str | None = None,
    model_id: str | None = None,
    stability: float | None = None,
    similarity_boost: float | None = None,
) -> bytes:
    """Return the entire synthesized audio as bytes for quick preview/testing.

    output_format examples: "pcm_16000", "pcm_22050", "mp3_44100"
    """
    if not settings.elevenlabs_configured and not (settings.elevenlabs_api_key and (voice_id or settings.elevenlabs_voice_id)):
        raise RuntimeError("ElevenLabs is not configured")

    v_id = voice_id or settings.elevenlabs_voice_id
    url = ELEVEN_TTS_URL.format(voice_id=v_id)
    payload = {
        "text": text,
        "model_id": model_id or settings.elevenlabs_tts_model,
        "output_format": output_format or settings.elevenlabs_output_format,
        "voice_settings": {
            "stability": 0.5 if stability is None else stability,
            "similarity_boost": 0.85 if similarity_boost is None else similarity_boost,
            "use_speaker_boost": True,
        },
        # Lower latency chunking; tune later for barge-in behavior
        "optimize_streaming_latency": 3,
    }

    headers = {
        "xi-api-key": settings.elevenlabs_api_key,
        "Accept": "*/*",
        "Content-Type": "application/json",
    }

    # Stream and accumulate. For live-call we will forward chunks instead of buffering.
    chunks: list[bytes] = []
    async with httpx.AsyncClient(timeout=60) as client:
        async with client.stream("POST", url, json=payload, headers=headers) as resp:
            resp.raise_for_status()
            async for part in resp.aiter_bytes():
                if part:
                    chunks.append(part)
    return b"".join(chunks)


async def synthesize_stream(
    settings: Settings,
    text: str,
    *,
    voice_id: str | None = None,
    output_format: str | None = None,
    model_id: str | None = None,
) -> AsyncGenerator[bytes, None]:
    """Yield audio bytes incrementally. Live-call bridge should use this."""
    v_id = voice_id or settings.elevenlabs_voice_id
    url = ELEVEN_TTS_URL.format(voice_id=v_id)
    payload = {
        "text": text,
        "model_id": model_id or settings.elevenlabs_tts_model,
        "output_format": output_format or settings.elevenlabs_output_format,
        "optimize_streaming_latency": 3,
    }
    headers = {
        "xi-api-key": settings.elevenlabs_api_key,
        "Accept": "*/*",
        "Content-Type": "application/json",
    }
    async with httpx.AsyncClient(timeout=None) as client:
        async with client.stream("POST", url, json=payload, headers=headers) as resp:
            resp.raise_for_status()
            async for part in resp.aiter_bytes():
                if part:
                    yield part
