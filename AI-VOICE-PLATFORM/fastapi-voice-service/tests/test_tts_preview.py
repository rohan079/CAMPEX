import pytest
import httpx

from config import get_settings
from main import app


@pytest.mark.asyncio
async def test_tts_preview_not_configured(monkeypatch):
    get_settings.cache_clear()
    # Ensure ElevenLabs is not configured
    monkeypatch.delenv("ELEVENLABS_API_KEY", raising=False)
    monkeypatch.delenv("ELEVENLABS_VOICE_ID", raising=False)

    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        r = await client.get("/api/tts/preview", params={"text": "hello"}, headers={"X-Service-Key": "dev"})
        assert r.status_code == 503


@pytest.mark.asyncio
async def test_tts_preview_success(monkeypatch):
    get_settings.cache_clear()
    # Configure env and stub synthesize_bytes
    monkeypatch.setenv("ELEVENLABS_API_KEY", "test")
    monkeypatch.setenv("ELEVENLABS_VOICE_ID", "voice123")

    async def fake_synth(settings, text: str):
        assert text == "hello"
        return b"RIFF....WAVEfmt "

    import api.tts_preview as preview

    monkeypatch.setattr(preview, "synthesize_bytes", fake_synth)

    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        r = await client.get(
            "/api/tts/preview", params={"text": "hello"}, headers={"X-Service-Key": "dev"}
        )
        assert r.status_code == 200
        assert r.headers.get("content-type", "").startswith("audio/")
        assert r.content.startswith(b"RIFF")
    get_settings.cache_clear()
