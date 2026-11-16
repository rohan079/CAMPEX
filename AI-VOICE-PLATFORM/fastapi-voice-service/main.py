import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from api import campaign_assistant, call_controller, edesy_webhook, media_edesy, tts_preview, voice_webhook
from config import get_settings
from services.conversation_service import ConversationService
from services.campaign_assistant import CampaignAssistantService
from services.lead_client import LeadServiceClient
from services.telephony_service import TelephonyService

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    app.state.conversation = ConversationService(
        settings, TelephonyService(settings), LeadServiceClient(settings)
    )
    app.state.campaign_assistant = CampaignAssistantService(settings)
    try:
        yield
    finally:
        await app.state.conversation.shutdown()


app = FastAPI(title="AI Voice Platform - Voice Service", version="0.1.0", lifespan=lifespan)
app.include_router(call_controller.router)
app.include_router(campaign_assistant.router)
app.include_router(voice_webhook.router)
app.include_router(edesy_webhook.router)
app.include_router(tts_preview.router)
app.include_router(media_edesy.router)


@app.get("/health")
async def health() -> dict:
    settings = get_settings()
    return {
        "status": "ok",
        "service": "voice-service",
        "environment": settings.environment,
        "call_provider": settings.call_provider,
        "speech_engine": settings.speech_engine,
        "telephony_configured": settings.telephony_configured,
        "speech_backend_configured": settings.speech_backend_configured,
        "campaign_ai_configured": settings.ai_configured,
        "max_concurrent_calls": settings.max_concurrent_calls,
    }
