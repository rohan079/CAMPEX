"""Provider adapters for text/JSON generation used by performance intelligence.

Each adapter takes a system prompt, a user prompt and an optional JSON schema, and returns text. Adapters never
receive Meta tokens or raw customer data - only computed metrics prepared by the lead service.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from typing import Any, Protocol

import httpx

from config import Settings

logger = logging.getLogger(__name__)


class ProviderUnavailable(RuntimeError):
    """No provider is configured, or the provider failed/refused."""


@dataclass(frozen=True)
class LlmResult:
    text: str
    provider: str
    model: str


class LlmAdapter(Protocol):
    provider: str
    model: str

    async def complete(self, system: str, user: str, *, schema: dict[str, Any] | None = None,
                       max_tokens: int = 2000) -> LlmResult: ...


class OpenAIChatAdapter:
    """OpenAI and OpenAI-compatible (Ollama, vLLM, ...) chat completions."""

    def __init__(self, settings: Settings, model: str, transport: httpx.AsyncBaseTransport | None = None):
        self.provider = settings.ai_provider
        self.model = model
        self._settings = settings
        self._transport = transport

    async def complete(self, system: str, user: str, *, schema: dict[str, Any] | None = None,
                       max_tokens: int = 2000) -> LlmResult:
        headers = {"Content-Type": "application/json"}
        if self._settings.campaign_ai_api_key:
            headers["Authorization"] = f"Bearer {self._settings.campaign_ai_api_key}"
        payload: dict[str, Any] = {
            "model": self.model,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
            "temperature": 0.2,
            "max_tokens": max_tokens,
        }
        if schema is not None:
            payload["response_format"] = {"type": "json_object"}
        async with httpx.AsyncClient(timeout=self._settings.performance_ai_timeout_seconds,
                                     transport=self._transport) as client:
            response = await client.post(f"{self._settings.ai_base_url.rstrip('/')}/chat/completions",
                                         json=payload, headers=headers)
        if response.is_error:
            logger.warning("Performance AI endpoint returned HTTP %s", response.status_code)
            raise ProviderUnavailable(f"AI provider returned HTTP {response.status_code}")
        try:
            text = response.json()["choices"][0]["message"]["content"]
        except (ValueError, KeyError, IndexError, TypeError) as exc:
            raise ProviderUnavailable("AI provider returned no message") from exc
        if not isinstance(text, str) or not text.strip():
            raise ProviderUnavailable("AI provider returned an empty message")
        return LlmResult(text.strip(), self.provider, self.model)


class AnthropicAdapter:
    """Claude through the official Anthropic SDK, with structured JSON output and server-side refusal fallback."""

    provider = "anthropic"

    def __init__(self, settings: Settings, model: str, client: Any | None = None):
        self.model = model
        self._settings = settings
        if client is None:
            import anthropic

            client = anthropic.AsyncAnthropic(api_key=settings.anthropic_api_key,
                                              timeout=settings.performance_ai_timeout_seconds, max_retries=2)
        self._client = client

    async def complete(self, system: str, user: str, *, schema: dict[str, Any] | None = None,
                       max_tokens: int = 2000) -> LlmResult:
        import anthropic

        output_config: dict[str, Any] = {"effort": self._settings.anthropic_effort}
        if schema is not None:
            output_config["format"] = {"type": "json_schema", "schema": schema}
        kwargs: dict[str, Any] = {
            "model": self.model,
            "max_tokens": max_tokens,
            "system": system,
            "messages": [{"role": "user", "content": user}],
            "output_config": output_config,
        }
        if self._settings.anthropic_refusal_fallback:
            kwargs["betas"] = ["server-side-fallback-2026-07-01"]
            kwargs["fallbacks"] = "default"
        try:
            response = await self._client.beta.messages.create(**kwargs)
        except anthropic.RateLimitError as exc:
            raise ProviderUnavailable("Claude rate limit reached; try again shortly") from exc
        except anthropic.APIStatusError as exc:
            logger.warning("Claude request failed with HTTP %s", exc.status_code)
            raise ProviderUnavailable(f"Claude returned HTTP {exc.status_code}") from exc
        except anthropic.APIConnectionError as exc:
            raise ProviderUnavailable("Claude could not be reached") from exc
        if response.stop_reason == "refusal":
            raise ProviderUnavailable("Claude declined this request")
        if response.stop_reason == "max_tokens":
            raise ProviderUnavailable("Claude's answer was cut off; try a narrower question")
        text = next((block.text for block in response.content if block.type == "text"), "")
        if not text.strip():
            raise ProviderUnavailable("Claude returned an empty answer")
        return LlmResult(text.strip(), self.provider, getattr(response, "model", self.model))


def adapter_for(settings: Settings, transport: httpx.AsyncBaseTransport | None = None,
                anthropic_client: Any | None = None) -> LlmAdapter:
    """Selects the adapter for performance tasks. Raises ProviderUnavailable when nothing is configured."""
    provider = settings.performance_ai_provider or settings.ai_provider
    if provider == "anthropic":
        if not settings.anthropic_api_key and anthropic_client is None:
            raise ProviderUnavailable("ANTHROPIC_API_KEY is not configured")
        return AnthropicAdapter(settings, settings.performance_ai_model or settings.anthropic_model, anthropic_client)
    if not settings.ai_configured:
        raise ProviderUnavailable("No AI provider is configured for performance analysis")
    return OpenAIChatAdapter(settings, settings.performance_ai_model or settings.ai_model, transport)


def parse_json(text: str) -> dict[str, Any]:
    """Parses a JSON object, tolerating a fenced code block from providers without strict JSON mode."""
    cleaned = text.strip()
    if cleaned.startswith("```"):
        cleaned = cleaned.strip("`")
        cleaned = cleaned[cleaned.find("{"):]
    start, end = cleaned.find("{"), cleaned.rfind("}")
    if start < 0 or end < start:
        raise ProviderUnavailable("AI response was not JSON")
    try:
        value = json.loads(cleaned[start:end + 1])
    except json.JSONDecodeError as exc:
        raise ProviderUnavailable("AI response was not valid JSON") from exc
    if not isinstance(value, dict):
        raise ProviderUnavailable("AI response was not a JSON object")
    return value
