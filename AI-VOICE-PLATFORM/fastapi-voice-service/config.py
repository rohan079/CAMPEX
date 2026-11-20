from functools import lru_cache
from typing import Literal
from urllib.parse import urlsplit

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Telephony secrets live here and nowhere else in the platform."""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    environment: str = "development"

    # The legacy provider remains available until the Edesy/HF path is accepted.
    call_provider: Literal["openai_sip", "edesy"] = "openai_sip"

    # Speech engine used by Edesy media calls; independent from the SIP/CPaaS call provider.
    speech_engine: Literal["hf", "openai_realtime"] = "hf"

    # OpenAI Live (default provider-managed calling)
    openai_api_key: str = ""
    openai_live_model: str = "gpt-live-1"
    openai_voice: str = "marin"
    openai_live_url: str = "https://api.openai.com/v1/live/sessions"
    openai_live_ws_url: str = "wss://api.openai.com/v1/live/sessions"
    openai_realtime_ws_url: str = "wss://api.openai.com/v1/realtime"
    openai_realtime_model: str = "gpt-realtime-2.1"
    openai_realtime_voice: str = "marin"
    openai_realtime_transcription_model: str = "gpt-4o-transcribe"
    openai_realtime_connect_timeout_seconds: float = 15

    # SIP trunk used by OpenAI Live
    sip_provider_url: str = ""
    sip_username: str = ""
    sip_password: str = ""
    sip_caller_number: str = ""

    # Edesy CPaaS owns SIP signalling and supplies a public WebSocket media stream.
    edesy_api_base_url: str = "https://voice-api.edesy.in/v1"
    edesy_api_key: str = ""
    edesy_from_number: str = ""
    edesy_media_ws_url: str = ""
    edesy_stream_token: str = ""
    edesy_status_callback_url: str = ""
    edesy_webhook_secret: str = ""
    edesy_call_timeout_seconds: int = Field(default=60, ge=5, le=600)

    # Hugging Face speech-to-speech is a private-network Realtime WebSocket service.
    hf_speech_ws_url: str = "ws://hf-speech-service:8765/v1/realtime?model=local"
    hf_connect_timeout_seconds: float = 15
    max_concurrent_calls: int = Field(default=1, ge=1, le=100)

    # Campaign copy assistant. This runs server-side; no provider key reaches React.
    ai_provider: Literal["openai", "openai_compatible"] = "openai"
    ai_base_url: str = "https://api.openai.com/v1"
    ai_api_key: str = ""
    ai_model: str = "gpt-4o-mini"
    ai_timeout_seconds: float = 45
    # Ad creative image generation (OpenAI Images API). Disabled for openai_compatible unless configured.
    ai_image_model: str = "dall-e-3"
    ai_image_size: str = "1024x1024"
    ai_image_timeout_seconds: float = 90
    ai_image_enabled: bool = True

    # Meta Marketing API version used by the private Meta Ads MCP server.
    meta_graph_api_version: str = Field(default="v27.0", pattern=r"^v\d+\.\d+$")

    # ElevenLabs (optional, used for cloned voice)
    elevenlabs_api_key: str = ""
    elevenlabs_voice_id: str = ""
    elevenlabs_tts_model: str = "eleven_turbo_v2"
    elevenlabs_output_format: str = "pcm_16000"  # pcm_16000 | pcm_22050 | mp3_44100

    # How this service reaches, and is reached by, the Spring lead service.
    lead_service_url: str = "http://localhost:8080"
    internal_service_key: str = ""
    voice_webhook_secret: str = ""

    session_create_timeout_seconds: float = 30
    hangup_timeout_seconds: float = 15

    @property
    def is_development(self) -> bool:
        return self.environment.lower() == "development"

    @property
    def telephony_configured(self) -> bool:
        if self.call_provider.lower() == "edesy":
            media_url = urlsplit(self.edesy_media_ws_url)
            callback_url = urlsplit(self.edesy_status_callback_url)
            return all(
                [
                    self.edesy_api_key,
                    self.edesy_from_number,
                    media_url.scheme == "wss" and media_url.netloc,
                    self.edesy_stream_token,
                    callback_url.scheme == "https" and callback_url.netloc,
                    self.edesy_webhook_secret or self.is_development,
                ]
            )
        return all([
            self.openai_api_key,
            self.sip_provider_url,
            self.sip_username,
            self.sip_password,
            self.sip_caller_number,
        ])

    @property
    def speech_backend_configured(self) -> bool:
        if self.speech_engine == "openai_realtime":
            url = urlsplit(self.openai_realtime_ws_url)
            return bool(self.openai_api_key) and url.scheme == "wss" and bool(url.netloc)
        return urlsplit(self.hf_speech_ws_url).scheme in {"ws", "wss"} and bool(urlsplit(self.hf_speech_ws_url).netloc)

    @property
    def campaign_ai_api_key(self) -> str:
        """Prefer AI_API_KEY; fall back to OPENAI_API_KEY so one secret covers both speech and copy assist."""
        return self.ai_api_key or self.openai_api_key

    @property
    def ai_configured(self) -> bool:
        base = urlsplit(self.ai_base_url)
        endpoint_valid = base.scheme in {"http", "https"} and bool(base.netloc)
        if self.ai_provider == "openai":
            return endpoint_valid and bool(self.campaign_ai_api_key)
        return endpoint_valid and bool(self.ai_model)

    @property
    def elevenlabs_configured(self) -> bool:
        return bool(self.elevenlabs_api_key and self.elevenlabs_voice_id)


@lru_cache
def get_settings() -> Settings:
    return Settings()
