import base64
import json

import httpx
import pytest

from config import Settings
from schemas import CampaignAssistRequest
from services.campaign_assistant import CampaignAssistantService


def design_request(**overrides):
    body = dict(
        campaign_name="Coconut Grove",
        objective="leads",
        platforms=["facebook", "instagram"],
        language="en",
        prompt="Generate an ad image for this luxury home",
        content="Property priced at 1.5 crores in Coconut Grove.",
        mode="design",
        generate_image=True,
        client_name="Grove Homes",
    )
    body.update(overrides)
    return CampaignAssistRequest(**body)


@pytest.mark.asyncio
async def test_assist_generates_image_from_image_prompt_section():
    png = base64.b64encode(b"fake-png-bytes").decode("ascii")
    seen = {"paths": []}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["paths"].append(request.url.path)
        if request.url.path.endswith("/chat/completions"):
            return httpx.Response(200, json={
                "choices": [{
                    "message": {
                        "content": (
                            "Summary: Luxury ad concept.\n"
                            "Suggested headline: Luxury Living in Coconut Grove\n"
                            "Suggested copy: Discover your dream home.\n"
                            "CTA: Inquire Now\n"
                            "Image prompt: Photorealistic Coconut Grove villa at golden hour, "
                            "BMW in driveway, clean negative space for headline."
                        ),
                    },
                }],
            })
        if request.url.path.endswith("/images/generations"):
            payload = json.loads(request.content.decode())
            assert payload["model"] == "dall-e-3"
            assert payload["response_format"] == "b64_json"
            assert "Coconut Grove" in payload["prompt"]
            return httpx.Response(200, json={"data": [{"b64_json": png}]})
        raise AssertionError(request.url.path)

    settings = Settings(
        ai_provider="openai",
        ai_api_key="server-only",
        ai_base_url="https://api.openai.com/v1",
        ai_image_enabled=True,
        ai_image_model="dall-e-3",
    )
    result = await CampaignAssistantService(settings, httpx.MockTransport(handler)).assist(design_request())

    assert result.generated_images
    assert result.generated_images[0].data_base64 == png
    assert result.image_error is None
    assert seen["paths"] == ["/v1/chat/completions", "/v1/images/generations"]


@pytest.mark.asyncio
async def test_gpt_image_model_omits_response_format():
    png = base64.b64encode(b"gpt-image-bytes").decode("ascii")

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/chat/completions"):
            return httpx.Response(200, json={
                "choices": [{"message": {"content": "Summary: ok\nImage prompt: villa at dusk"}}],
            })
        payload = json.loads(request.content.decode())
        assert payload["model"] == "gpt-image-1"
        assert "response_format" not in payload
        return httpx.Response(200, json={"data": [{"b64_json": png}]})

    settings = Settings(
        ai_provider="openai",
        ai_api_key="server-only",
        ai_base_url="https://api.openai.com/v1",
        ai_image_enabled=True,
        ai_image_model="gpt-image-1",
    )
    result = await CampaignAssistantService(settings, httpx.MockTransport(handler)).assist(design_request())
    assert result.generated_images[0].data_base64 == png
    assert result.image_error is None


@pytest.mark.asyncio
async def test_assist_surfaces_image_api_error_without_failing_copy():
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/chat/completions"):
            return httpx.Response(200, json={
                "choices": [{"message": {"content": "Summary: ok\nImage prompt: a villa exterior"}}],
            })
        return httpx.Response(400, json={"error": {"message": "Billing hard limit reached"}})

    settings = Settings(
        ai_provider="openai",
        ai_api_key="server-only",
        ai_base_url="https://api.openai.com/v1",
        ai_image_enabled=True,
    )
    result = await CampaignAssistantService(settings, httpx.MockTransport(handler)).assist(design_request())
    assert result.reply.startswith("Summary: ok")
    assert result.generated_images == []
    assert "Billing hard limit reached" in (result.image_error or "")
