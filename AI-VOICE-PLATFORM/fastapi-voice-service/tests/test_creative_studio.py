import base64
import json

import httpx
import pytest
from fastapi.testclient import TestClient

from config import Settings, get_settings
from main import app
from services.creative_studio import CreativeStudioService, check_claims, generate_ad_image
from services.llm_providers import LlmResult

KEY = {"X-Service-Key": "svc"}
FACTS = ["3 BHK villas from ₹85 lakh", "Possession by December 2027", "RERA registered: P02400005678", "12 km from Guntur"]


class FakeAdapter:
    def __init__(self, *payloads):
        self.payloads = list(payloads)
        self.calls = []

    async def complete(self, system, user, *, schema=None, max_tokens=1000):
        self.calls.append(user)
        return LlmResult(json.dumps(self.payloads.pop(0)), "fake", "fake-1")


def copy(headline="East-facing 3 BHK villas", text="Villas from ₹85 lakh, 12 km from Guntur."):
    return {"headlines": [headline, "Villas near Guntur", "Visit the model villa", "Plan your site visit", "Homes for families"],
            "primary_texts": [text, "Book a site visit this weekend.", "Ask for the price list."],
            "descriptions": ["RERA registered project", "Site visits daily", "Talk to our team"],
            "cta_recommendations": [{"cta": "LEARN_MORE", "reason": "Lead form"}, {"cta": "BUY_NOW", "reason": "invalid"}],
            "concepts": [{"title": "Morning light", "visual": "Villa facade at sunrise", "angle": "Family"}]}


def test_claim_check_allows_verified_numbers_and_flags_invented_ones():
    ok = check_claims(["Villas from ₹85 lakh", "12 km from Guntur", "3 BHK"], FACTS, {})
    assert ok.clean
    bad = check_claims(["Only ₹60 lakh!", "Guaranteed 20% returns", "Best investment in Guntur"], FACTS, {})
    assert "60 lakh" in " ".join(bad.unsupported_numbers) or "60" in " ".join(bad.unsupported_numbers)
    reasons = {f["text"] for f in bad.flagged_phrases}
    assert {"guaranteed", "best investment"} <= reasons


def test_approval_claims_are_allowed_only_when_in_the_facts():
    assert check_claims(["RERA registered project"], FACTS, {}).clean
    assert not check_claims(["DTCP approved layout"], FACTS, {}).clean


@pytest.mark.asyncio
async def test_generate_copy_retries_once_when_the_first_draft_invents_numbers():
    adapter = FakeAdapter(copy(text="Prices start at ₹49 lakh, assured rental income!"), copy())
    result = await CreativeStudioService(adapter).generate_copy({"product": "Green Villas"}, FACTS)
    assert len(adapter.calls) == 2
    assert "49" in adapter.calls[1] and "assured rental" in adapter.calls[1]
    assert result["claim_check"] == {"unsupported_numbers": [], "flagged_phrases": []}
    assert len(result["headlines"]) == 5 and len(result["primary_texts"]) == 3 and len(result["descriptions"]) == 3
    assert [c["cta"] for c in result["cta_recommendations"]] == ["LEARN_MORE"]


@pytest.mark.asyncio
async def test_generate_copy_reports_remaining_problems_instead_of_hiding_them():
    bad = copy(text="Guaranteed appreciation, only ₹49 lakh")
    result = await CreativeStudioService(FakeAdapter(bad, bad)).generate_copy({"product": "Green Villas"}, FACTS)
    assert result["claim_check"]["unsupported_numbers"]
    assert result["claim_check"]["flagged_phrases"]


@pytest.mark.asyncio
async def test_image_generation_returns_bytes_not_urls_and_forbids_text_in_artwork():
    seen = {}
    png = base64.b64encode(b"\x89PNG fake").decode()

    def handler(request: httpx.Request) -> httpx.Response:
        seen["path"] = request.url.path
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json={"data": [{"b64_json": png}]})

    settings = Settings(ai_api_key="k", ai_image_model="gpt-image-1")
    result = await generate_ad_image(settings, "Villa at sunrise", transport=httpx.MockTransport(handler))
    assert seen["path"].endswith("/images/generations")
    assert "Do not render any text" in seen["body"]["prompt"]
    assert result["data_base64"] == png and "url" not in result


@pytest.mark.asyncio
async def test_reference_images_need_a_gpt_image_model():
    from services.creative_studio import ImageGenerationError

    settings = Settings(ai_api_key="k", ai_image_model="dall-e-3")
    with pytest.raises(ImageGenerationError, match="GPT Image"):
        await generate_ad_image(settings, "Villa", (b"img", "image/png"))


def test_endpoints_require_the_service_key():
    app.dependency_overrides[get_settings] = lambda: Settings(internal_service_key="svc")
    try:
        with TestClient(app) as client:
            assert client.post("/api/creative/check", json={"texts": ["x"]}).status_code == 401
            response = client.post("/api/creative/check", headers=KEY,
                                   json={"texts": ["Assured returns of 18%"], "facts": FACTS})
            assert response.status_code == 200
            assert response.json()["flagged_phrases"]
            assert response.json()["unsupported_numbers"]
    finally:
        app.dependency_overrides.clear()


@pytest.mark.asyncio
async def test_vertical_aspect_asks_for_the_tallest_size_and_vertical_composition():
    seen = {}
    png = base64.b64encode(b"\x89PNG fake").decode()

    def handler(request: httpx.Request) -> httpx.Response:
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json={"data": [{"b64_json": png}]})

    for model, size in (("gpt-image-1", "1024x1536"), ("dall-e-3", "1024x1792")):
        settings = Settings(ai_api_key="k", ai_image_model=model)
        await generate_ad_image(settings, "Villa", transport=httpx.MockTransport(handler), aspect="9:16")
        assert seen["body"]["size"] == size
        assert "9:16" in seen["body"]["prompt"]
    await generate_ad_image(Settings(ai_api_key="k", ai_image_model="gpt-image-1"), "Villa",
                            transport=httpx.MockTransport(handler), aspect="1:1")
    assert seen["body"]["size"] == "1024x1024"
