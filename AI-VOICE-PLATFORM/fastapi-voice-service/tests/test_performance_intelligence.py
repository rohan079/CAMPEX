"""AI layer tests. Providers are always faked: no OpenAI or Anthropic request is made."""

import json
from types import SimpleNamespace

import httpx
import pytest

from config import Settings
from services.llm_providers import (
    AnthropicAdapter,
    LlmResult,
    OpenAIChatAdapter,
    ProviderUnavailable,
    adapter_for,
    parse_json,
)
from services.performance_intelligence import (
    PerformanceIntelligenceService,
    allowed_numbers,
    check_grounding,
)

CONTEXT = {
    "currency": "INR",
    "totals": {"spend": 18500.0, "qualified_leads": 41, "cost_per_qualified_lead": 451.22, "ctr": 0.0123},
    "campaigns": [
        {"name": "Villas Guntur", "spend": 9000.0, "qualified_leads": 30, "cost_per_qualified_lead": 300.0},
        {"name": "Plots Vijayawada", "spend": 9500.0, "qualified_leads": 11, "cost_per_qualified_lead": 863.64},
    ],
    "data_notes": ["Meta figures from 2026-11-19 onward may still change."],
}


class FakeAdapter:
    provider = "fake"
    model = "fake-model"

    def __init__(self, replies):
        self.replies = list(replies)
        self.calls = []

    async def complete(self, system, user, *, schema=None, max_tokens=2000):
        self.calls.append({"system": system, "user": user, "schema": schema})
        return LlmResult(self.replies.pop(0), self.provider, self.model)


def test_grounding_accepts_context_numbers_rounding_and_percentages():
    allowed = allowed_numbers(CONTEXT)
    ok = check_grounding("Villas Guntur produced 30 qualified leads at ₹300 each; CTR was 1.23% and spend ₹18,500.", allowed)
    assert ok.grounded
    assert check_grounding("Cost per qualified lead was ₹451 overall.", allowed).grounded


def test_grounding_flags_fabricated_numbers():
    allowed = allowed_numbers(CONTEXT)
    check = check_grounding("Increasing budget will bring 57 more qualified leads and a ROAS of 4.8x.", allowed)
    assert not check.grounded
    assert "57" in check.unsupported and "4.8x" in check.unsupported


def test_grounding_understands_indian_scale_suffixes():
    allowed = allowed_numbers({"revenue": 150000})
    assert check_grounding("Confirmed revenue was 1.5L.", allowed).grounded
    assert not check_grounding("Confirmed revenue was 2.5L.", allowed).grounded


@pytest.mark.asyncio
async def test_ask_retries_once_then_reports_ungrounded_numbers():
    adapter = FakeAdapter(["Spend will reach ₹99,999 next week.", "Spend may reach ₹77,777."])
    result = await PerformanceIntelligenceService(adapter).ask("What next?", CONTEXT)
    assert len(adapter.calls) == 2
    assert "99,999" in adapter.calls[1]["user"]
    assert result["grounded"] is False
    assert result["unsupported_numbers"] == ["77,777"]
    assert result["data_gaps"] == CONTEXT["data_notes"]


@pytest.mark.asyncio
async def test_ask_grounded_answer_and_prompt_forbids_new_calculations():
    adapter = FakeAdapter(["Villas Guntur is the most efficient: 30 qualified leads at ₹300 each vs ₹863.64 for Plots Vijayawada."])
    result = await PerformanceIntelligenceService(adapter).ask("Which campaign generates the most qualified leads?", CONTEXT)
    assert result["grounded"] is True
    assert len(adapter.calls) == 1
    system = adapter.calls[0]["system"]
    assert "Never calculate new figures" in system
    assert "data is unavailable" in system
    assert "estimate" in system


@pytest.mark.asyncio
async def test_ask_reports_missing_data_when_context_lacks_it():
    adapter = FakeAdapter(["Location performance is unavailable in the supplied data; run the audience breakdown."])
    result = await PerformanceIntelligenceService(adapter).ask("Which location gives the most site visits?", CONTEXT)
    assert result["grounded"] is True
    assert "unavailable" in result["answer"]


@pytest.mark.asyncio
async def test_explain_marks_each_explanation_and_ignores_unknown_ids():
    items = [{"id": "r1", "title": "Increase budget", "evidence": [{"label": "Cost per qualified lead", "value": 300.0}]}]
    reply = json.dumps({"explanations": [
        {"id": "r1", "text": "At ₹300 per qualified lead this campaign beats its peers."},
        {"id": "unknown", "text": "ignored"},
    ]})
    result = await PerformanceIntelligenceService(FakeAdapter([reply])).explain("recommendations", CONTEXT, items)
    assert [e["id"] for e in result["explanations"]] == ["r1"]
    assert result["explanations"][0]["grounded"] is True

    bad = json.dumps({"explanations": [{"id": "r1", "text": "This will double revenue to ₹5,00,000."}]})
    result = await PerformanceIntelligenceService(FakeAdapter([bad])).explain("recommendations", CONTEXT, items)
    assert result["explanations"][0]["grounded"] is False


@pytest.mark.asyncio
async def test_creative_suggestions_validate_shape_and_flag_invented_prices():
    reply = json.dumps({
        "headlines": ["Villas near Guntur", "Plots from [price range]", "Visit this weekend"],
        "primary_texts": ["a", "b", "Only ₹45 lakh!"],
        "cta": "BOOK_NOW", "audience_messaging": "Families", "concepts": ["c1"], "image_prompts": ["p1"],
    })
    body = {"observation": "CTR 2% vs median 1%", "creative": {"headline": "Villas"}, "metrics": {"link_ctr": 0.02}}
    result = await PerformanceIntelligenceService(FakeAdapter([reply])).creative_suggestions(body)
    assert result["status"] == "draft"
    assert len(result["headlines"]) == 3
    assert "45 lakh" in result["unverified_numbers"]

    short = json.dumps({"headlines": ["one"], "primary_texts": ["x"], "cta": "", "audience_messaging": "",
                        "concepts": [], "image_prompts": []})
    with pytest.raises(ProviderUnavailable):
        await PerformanceIntelligenceService(FakeAdapter([short])).creative_suggestions(body)


@pytest.mark.asyncio
async def test_openai_adapter_uses_server_key_and_json_mode():
    seen = {}

    def handler(req: httpx.Request) -> httpx.Response:
        seen["auth"] = req.headers.get("authorization")
        seen["body"] = json.loads(req.read())
        return httpx.Response(200, json={"choices": [{"message": {"content": "{\"ok\": true}"}}]})

    settings = Settings(ai_provider="openai", ai_api_key="server-only", ai_model="gpt-test")
    adapter = OpenAIChatAdapter(settings, "gpt-test", httpx.MockTransport(handler))
    result = await adapter.complete("sys", "user", schema={"type": "object"})
    assert result.text == "{\"ok\": true}"
    assert seen["auth"] == "Bearer server-only"
    assert seen["body"]["response_format"] == {"type": "json_object"}


class FakeClaudeMessages:
    def __init__(self, response):
        self.response = response
        self.kwargs = None

    async def create(self, **kwargs):
        self.kwargs = kwargs
        return self.response


def claude_client(response):
    messages = FakeClaudeMessages(response)
    return SimpleNamespace(beta=SimpleNamespace(messages=messages)), messages


@pytest.mark.asyncio
async def test_anthropic_adapter_sends_structured_output_effort_and_fallbacks():
    response = SimpleNamespace(stop_reason="end_turn", model="claude-opus-5-5",
                               content=[SimpleNamespace(type="thinking", thinking=""), SimpleNamespace(type="text", text="{\"x\": 1}")])
    client, messages = claude_client(response)
    settings = Settings(performance_ai_provider="anthropic", anthropic_api_key="k", anthropic_effort="medium")
    adapter = adapter_for(settings, anthropic_client=client)
    assert isinstance(adapter, AnthropicAdapter)
    result = await adapter.complete("system", "user", schema={"type": "object"})
    assert result.text == "{\"x\": 1}"
    assert result.provider == "anthropic"
    k = messages.kwargs
    assert k["model"] == "claude-opus-5-5"
    assert k["output_config"]["effort"] == "medium"
    assert k["output_config"]["format"]["type"] == "json_schema"
    assert k["fallbacks"] == "default"
    assert k["betas"] == ["server-side-fallback-2026-07-01"]
    assert "thinking" not in k and "temperature" not in k


@pytest.mark.asyncio
async def test_anthropic_adapter_handles_refusal_and_missing_key():
    client, _ = claude_client(SimpleNamespace(stop_reason="refusal", content=[], model="claude-opus-5-5"))
    adapter = AnthropicAdapter(Settings(anthropic_api_key="k"), "claude-opus-5-5", client)
    with pytest.raises(ProviderUnavailable):
        await adapter.complete("s", "u")
    with pytest.raises(ProviderUnavailable):
        adapter_for(Settings(performance_ai_provider="anthropic", anthropic_api_key=""))


def test_unconfigured_openai_compatible_provider_is_unavailable():
    with pytest.raises(ProviderUnavailable):
        adapter_for(Settings(ai_provider="openai", ai_api_key="", openai_api_key=""))


def test_parse_json_tolerates_fences():
    assert parse_json("```json\n{\"a\": 1}\n```") == {"a": 1}
    with pytest.raises(ProviderUnavailable):
        parse_json("no json here")
