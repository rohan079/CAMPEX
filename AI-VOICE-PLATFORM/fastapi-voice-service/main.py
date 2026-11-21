import hmac
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from api import (
    campaign_assistant,
    call_controller,
    creative,
    edesy_webhook,
    media_edesy,
    meta_campaigns,
    meta_dashboard,
    performance,
    tts_preview,
    voice_webhook,
)
from config import get_settings
from services.conversation_service import ConversationService
from services.campaign_assistant import CampaignAssistantService
from services.lead_client import LeadServiceClient
from services.meta_ads_mcp import create_meta_ads_mcp
from services.skill_registry import SkillRegistry
from services.telephony_service import TelephonyService

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
meta_ads_mcp = create_meta_ads_mcp()
meta_ads_http_app = meta_ads_mcp.http_app(path="/", stateless_http=True, host_origin_protection="auto")


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    app.state.settings = settings
    app.state.conversation = ConversationService(
        settings, TelephonyService(settings), LeadServiceClient(settings)
    )
    app.state.meta_ads_mcp = meta_ads_mcp
    app.state.skill_registry = SkillRegistry()
    app.state.campaign_assistant = CampaignAssistantService(
        settings,
        meta_mcp=meta_ads_mcp,
        skills=app.state.skill_registry,
    )
    try:
        async with meta_ads_http_app.router.lifespan_context(meta_ads_http_app):
            yield
    finally:
        await app.state.conversation.shutdown()


app = FastAPI(title="AI Voice Platform - Voice Service", version="0.1.0", lifespan=lifespan)
app.include_router(call_controller.router)
app.include_router(campaign_assistant.router)
app.include_router(creative.router)
app.include_router(performance.router)
app.include_router(meta_campaigns.router)
app.include_router(meta_dashboard.router)
app.include_router(voice_webhook.router)
app.include_router(edesy_webhook.router)
app.include_router(tts_preview.router)
app.include_router(media_edesy.router)
app.mount("/mcp/meta-ads", meta_ads_http_app)


@app.middleware("http")
async def protect_meta_mcp(request: Request, call_next):
    path = request.url.path
    if path == "/mcp/meta-ads" or path.startswith("/mcp/meta-ads/"):
        settings = getattr(request.app.state, "settings", None) or get_settings()
        expected = settings.internal_service_key
        supplied = request.headers.get("X-Service-Key")
        if not expected:
            if not settings.is_development:
                return JSONResponse(status_code=503, content={"detail": "MCP service key is not configured"})
        elif not supplied or not hmac.compare_digest(supplied, expected):
            return JSONResponse(status_code=401, content={"detail": "Invalid service key"})
    return await call_next(request)


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
