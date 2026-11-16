import httpx
import pytest

from config import Settings
from schemas import CampaignAssistRequest
from services.campaign_assistant import CampaignAssistantService, CampaignAssistantUnavailable


def request() -> CampaignAssistRequest:
    return CampaignAssistRequest(
        campaign_name="Summer plots",
        objective="leads",
        platforms=["facebook", "instagram"],
        language="en",
        prompt="Summarize this and make it clearer",
        content="Residential plots near Vijayawada. Ask for verified price and availability.",
    )


@pytest.mark.asyncio
async def test_openai_assistant_uses_server_key_and_returns_copy():
    seen = {}

    def handler(req: httpx.Request) -> httpx.Response:
        seen["authorization"] = req.headers.get("authorization")
        seen["body"] = req.read().decode()
        return httpx.Response(200, json={"choices": [{"message": {"content": "Verified residential plots near Vijayawada."}}]})

    settings = Settings(ai_provider="openai", ai_api_key="server-only", ai_base_url="https://api.openai.com/v1")
    result = await CampaignAssistantService(settings, httpx.MockTransport(handler)).assist(request())

    assert result.reply == "Verified residential plots near Vijayawada."
    assert result.provider == "openai"
    assert seen["authorization"] == "Bearer server-only"
    assert "never invent prices" in seen["body"].lower()


@pytest.mark.asyncio
async def test_openai_compatible_local_assistant_does_not_require_provider_key():
    seen = {}

    def handler(req: httpx.Request) -> httpx.Response:
        seen["url"] = str(req.url)
        seen["authorization"] = req.headers.get("authorization")
        return httpx.Response(200, json={"choices": [{"message": {"content": "Local model reply"}}]})

    settings = Settings(
        ai_provider="openai_compatible",
        ai_base_url="http://ollama:11434/v1",
        ai_model="qwen2.5:7b",
    )
    result = await CampaignAssistantService(settings, httpx.MockTransport(handler)).assist(request())
    assert result.provider == "openai_compatible"
    assert result.model == "qwen2.5:7b"
    assert seen["url"] == "http://ollama:11434/v1/chat/completions"
    assert seen["authorization"] is None


@pytest.mark.asyncio
async def test_assistant_reports_missing_configuration_and_provider_errors():
    with pytest.raises(CampaignAssistantUnavailable, match="not configured"):
        await CampaignAssistantService(Settings(ai_provider="openai")).assist(request())

    failing = CampaignAssistantService(
        Settings(ai_provider="openai_compatible", ai_base_url="http://local/v1", ai_model="test"),
        httpx.MockTransport(lambda req: httpx.Response(500)),
    )
    with pytest.raises(CampaignAssistantUnavailable, match="HTTP 500"):
        await failing.assist(request())
