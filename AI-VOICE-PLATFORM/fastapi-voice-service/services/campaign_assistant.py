"""Server-side copy assistance and read-only Meta analysis through MCP."""

from __future__ import annotations

import json
import logging
import re
from pathlib import Path
from typing import Any

import httpx
from fastmcp import Client, FastMCP
from fastmcp.exceptions import FastMCPError, ToolError

from config import Settings
from schemas import CampaignAssistRequest, CampaignAssistResponse

logger = logging.getLogger(__name__)

READ_ONLY_META_TOOLS = frozenset({
    "get_business_accounts",
    "get_ad_accounts",
    "get_ad_account_details",
    "get_facebook_pages",
    "get_instagram_accounts",
    "get_campaigns",
    "get_campaign_details",
    "get_ad_sets",
    "get_adset_details",
    "get_ads",
    "get_ad_details",
    "get_ad_creatives",
    "get_ad_creative",
    "get_campaign_insights",
    "get_adset_insights",
    "get_ad_insights",
    "get_account_insights",
})
MAX_TOOL_ROUNDS = 4
MAX_TOOL_OUTPUT_CHARS = 12_000
AUDIT_SKILL_PATH = Path(__file__).resolve().parents[1] / "prompts" / "meta_ads_audit.txt"


def _schema_for_model(tool: Any) -> dict[str, Any] | None:
    schema = getattr(tool, "input_schema", None) or getattr(tool, "inputSchema", None)
    if schema is None and hasattr(tool, "model_dump"):
        raw = tool.model_dump(by_alias=True)
        schema = raw.get("inputSchema") or raw.get("input_schema")
    if isinstance(schema, str):
        try:
            schema = json.loads(schema)
        except ValueError:
            return None
    if not isinstance(schema, dict):
        return None

    # The authenticated user's token is an internal MCP argument, never an LLM argument.
    public_schema = json.loads(json.dumps(schema))
    properties = public_schema.get("properties", {})
    if isinstance(properties, dict):
        properties.pop("access_token", None)
    required = public_schema.get("required", [])
    if isinstance(required, list):
        public_schema["required"] = [name for name in required if name != "access_token"]
    return public_schema


def _openai_tool(tool: Any) -> dict[str, Any] | None:
    name = getattr(tool, "name", None)
    if name not in READ_ONLY_META_TOOLS:
        return None
    parameters = _schema_for_model(tool)
    if parameters is None:
        logger.warning("Skipping Meta MCP tool with an invalid schema (%s)", name)
        return None
    return {
        "type": "function",
        "function": {
            "name": name,
            "description": getattr(tool, "description", "") or name,
            "parameters": parameters,
        },
    }


def _tool_result_text(result: Any) -> str:
    structured = getattr(result, "structured_content", None)
    if structured is not None:
        text = json.dumps(structured, ensure_ascii=False, default=str)
    else:
        chunks = [
            block.text for block in getattr(result, "content", [])
            if isinstance(getattr(block, "text", None), str)
        ]
        text = "\n".join(chunks) or "The Meta tool returned no data."
    if len(text) > MAX_TOOL_OUTPUT_CHARS:
        return text[:MAX_TOOL_OUTPUT_CHARS] + "\n[Result truncated; request a narrower period or fewer records.]"
    return text


def _suggested_copy(reply: str) -> str | None:
    match = re.search(r"suggested copy:\s*([\s\S]+)", reply, re.IGNORECASE)
    return match.group(1).strip() if match else None


class CampaignAssistantUnavailable(RuntimeError):
    """AI assistance is not configured or the selected model endpoint failed."""


class CampaignAssistantService:
    def __init__(
        self,
        settings: Settings,
        transport: httpx.AsyncBaseTransport | None = None,
        meta_mcp: FastMCP | None = None,
    ):
        self._settings = settings
        self._transport = transport
        self._meta_mcp = meta_mcp
        try:
            self._audit_skill = AUDIT_SKILL_PATH.read_text(encoding="utf-8")
        except OSError:
            self._audit_skill = "Use only returned Meta metrics and state when a requested audit signal is unavailable."

    async def assist(
        self,
        request: CampaignAssistRequest,
        meta_access_token: str | None = None,
    ) -> CampaignAssistResponse:
        settings = self._settings
        if not settings.ai_configured:
            raise CampaignAssistantUnavailable("Campaign AI is not configured")

        system = (
            "You are a careful real-estate campaign copy assistant and Meta Ads analyst. Use only facts and "
            "metrics actually supplied or returned by tools. Never invent prices, returns, approvals, amenities, "
            "availability, conversions, ROAS, or legal claims. Explicitly flag missing information. "
            "Use Meta tools only for live account or campaign questions. This chat exposes read-only tools; "
            "never create, pause, activate, update, or delete advertising objects. Campaign publishing is a "
            "separate user-confirmed action. For copy requests, return Summary and Suggested copy sections. "
            f"Write copy in {'natural Telugu' if request.language == 'te' else 'English'}. "
            "For audits, follow these local skill instructions:\n"
            f"{self._audit_skill}"
        )
        if not meta_access_token:
            system += "\nNo Meta Business connection is available for this request. Do not imply access to live ad data; ask the user to connect Meta under Channels."
        user = (
            f"Campaign: {request.campaign_name}\nObjective: {request.objective}\n"
            f"Selected channels: {', '.join(request.platforms)}\n"
            f"Selected ad account: {request.ad_account_id or 'not selected'}\n"
            f"User request: {request.prompt}\n\nCurrent ad content:\n{request.content}"
        )
        headers = {"Content-Type": "application/json"}
        api_key = settings.campaign_ai_api_key
        if api_key:
            headers["Authorization"] = f"Bearer {api_key}"

        try:
            async with httpx.AsyncClient(timeout=settings.ai_timeout_seconds, transport=self._transport) as client:
                if meta_access_token and self._meta_mcp is not None:
                    try:
                        return await self._assist_with_meta_tools(client, headers, system, user, meta_access_token)
                    except CampaignAssistantUnavailable as exc:
                        # Copy assist should still work when Meta tools are unhealthy.
                        logger.warning("Meta tool assist failed (%s); falling back to copy-only", exc)
                        system += (
                            "\nMeta Ads tools failed for this request. Answer from the supplied draft only "
                            "and say live Meta metrics were unavailable."
                        )
                        return await self._complete_without_tools(client, headers, system, user)
                return await self._complete_without_tools(client, headers, system, user)
        except httpx.HTTPError as exc:
            logger.warning("Campaign AI request failed (%s)", exc.__class__.__name__)
            raise CampaignAssistantUnavailable("Campaign AI endpoint could not be reached") from exc

    async def _complete_without_tools(
        self,
        client: httpx.AsyncClient,
        headers: dict[str, str],
        system: str,
        user: str,
    ) -> CampaignAssistResponse:
        payload = {
            "model": self._settings.ai_model,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
            "temperature": 0.4,
            "max_tokens": 1000,
        }
        response = await self._post_completion(client, headers, payload)
        return self._response(self._assistant_text(response), [])

    async def _assist_with_meta_tools(
        self,
        client: httpx.AsyncClient,
        headers: dict[str, str],
        system: str,
        user: str,
        meta_access_token: str,
    ) -> CampaignAssistResponse:
        if not meta_access_token.strip() or self._meta_mcp is None:
            return await self._complete_without_tools(client, headers, system, user)

        try:
            async with Client(self._meta_mcp) as mcp_client:
                available = await mcp_client.list_tools()
                available_tools = getattr(available, "tools", available)
                tool_specs = [spec for tool in available_tools if (spec := _openai_tool(tool)) is not None]
                if not tool_specs:
                    raise CampaignAssistantUnavailable("Meta MCP server exposes no supported read-only tools")

                messages: list[dict[str, Any]] = [
                    {"role": "system", "content": system},
                    {"role": "user", "content": user},
                ]
                tools_used: list[str] = []
                for _ in range(MAX_TOOL_ROUNDS):
                    payload = {
                        "model": self._settings.ai_model,
                        "messages": messages,
                        "tools": tool_specs,
                        "tool_choice": "auto",
                        "parallel_tool_calls": False,
                        "temperature": 0.4,
                        "max_tokens": 1000,
                    }
                    response = await self._post_completion(client, headers, payload)
                    message = response["choices"][0].get("message") or {}
                    calls = message.get("tool_calls") or []
                    if not calls:
                        return self._response(self._assistant_text(response), tools_used)

                    messages.append(message)
                    for call in calls:
                        function = call.get("function") or {}
                        name = function.get("name")
                        call_id = call.get("id")
                        if name not in READ_ONLY_META_TOOLS or not isinstance(call_id, str):
                            tool_text = "This operation is not available to the AI assistant."
                        else:
                            try:
                                arguments = json.loads(function.get("arguments") or "{}")
                            except (TypeError, ValueError):
                                arguments = {}
                                tool_text = "The requested tool arguments were invalid."
                            else:
                                if not isinstance(arguments, dict):
                                    arguments = {}
                                    tool_text = "The requested tool arguments were invalid."
                                else:
                                    # Never trust or forward a token supplied by the model.
                                    arguments.pop("access_token", None)
                                    arguments["access_token"] = meta_access_token
                                    logger.info("Meta MCP read tool invoked (%s)", name)
                                    try:
                                        result = await mcp_client.call_tool(name, arguments)
                                    except ToolError as exc:
                                        tool_text = str(exc)
                                    else:
                                        tool_text = _tool_result_text(result)
                                        tools_used.append(name)
                        messages.append({"role": "tool", "tool_call_id": call_id, "content": tool_text})

                raise CampaignAssistantUnavailable(
                    "The Meta assistant reached its tool-call limit; narrow the request and retry"
                )
        except FastMCPError as exc:
            logger.warning("Meta MCP interaction failed (%s)", type(exc).__name__)
            raise CampaignAssistantUnavailable("Meta Ads tools are temporarily unavailable") from exc

    async def _post_completion(
        self,
        client: httpx.AsyncClient,
        headers: dict[str, str],
        payload: dict[str, Any],
    ) -> dict[str, Any]:
        response = await client.post(
            f"{self._settings.ai_base_url.rstrip('/')}/chat/completions",
            json=payload,
            headers=headers,
        )
        if response.is_error:
            logger.warning("Campaign AI endpoint returned HTTP %s", response.status_code)
            raise CampaignAssistantUnavailable(f"Campaign AI endpoint returned HTTP {response.status_code}")
        try:
            body = response.json()
        except ValueError as exc:
            raise CampaignAssistantUnavailable("Campaign AI response was not valid JSON") from exc
        if not isinstance(body, dict) or not body.get("choices"):
            raise CampaignAssistantUnavailable("Campaign AI response had no assistant message")
        return body

    @staticmethod
    def _assistant_text(response: dict[str, Any]) -> str:
        try:
            reply = response["choices"][0]["message"].get("content")
        except (KeyError, IndexError, TypeError) as exc:
            raise CampaignAssistantUnavailable("Campaign AI response had no assistant text") from exc
        if not isinstance(reply, str) or not reply.strip():
            raise CampaignAssistantUnavailable("Campaign AI returned an empty response")
        return reply.strip()

    def _response(self, reply: str, tools_used: list[str]) -> CampaignAssistResponse:
        return CampaignAssistResponse(
            reply=reply,
            provider=self._settings.ai_provider,
            model=self._settings.ai_model,
            suggested_copy=_suggested_copy(reply),
            tools_used=tools_used,
        )
