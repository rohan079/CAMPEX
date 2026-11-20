"""Server-side copy assistance and read-only Meta analysis through MCP."""

from __future__ import annotations

import base64
import json
import logging
import re
from pathlib import Path
from typing import Any

import httpx
from fastmcp import Client, FastMCP
from fastmcp.exceptions import FastMCPError, ToolError

from config import Settings
from schemas import CampaignAssistRequest, CampaignAssistResponse, GeneratedAssistImage
from services.skill_registry import SkillRegistry

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
    "get_meta_performance_report",
    "get_ad_performance",
})
MAX_TOOL_ROUNDS = 4
MAX_TOOL_OUTPUT_CHARS = 12_000
AUDIT_SKILL_PATH = Path(__file__).resolve().parents[1] / "prompts" / "meta_ads_audit.txt"
TOOL_RULES_PATH = Path(__file__).resolve().parents[1] / "prompts" / "meta_ads_tool_rules.txt"


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


def _section(reply: str, label: str) -> str | None:
    pattern = (
        rf"(?is){re.escape(label)}\s*:\s*(.+?)"
        rf"(?=\n(?:summary|design notes|suggested headline|suggested copy|cta|image prompt)\s*:|\Z)"
    )
    match = re.search(pattern, reply)
    if not match:
        return None
    value = match.group(1).strip()
    return value or None


def _suggested_copy(reply: str) -> str | None:
    direct = _section(reply, "Suggested copy")
    if direct:
        return direct
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
        skills: SkillRegistry | None = None,
    ):
        self._settings = settings
        self._transport = transport
        self._meta_mcp = meta_mcp
        self._skills = skills or SkillRegistry()
        try:
            self._audit_skill = AUDIT_SKILL_PATH.read_text(encoding="utf-8")
        except OSError:
            self._audit_skill = "Use only returned Meta metrics and state when a requested audit signal is unavailable."
        try:
            self._tool_rules = TOOL_RULES_PATH.read_text(encoding="utf-8")
        except OSError:
            self._tool_rules = (
                "Prefer get_meta_performance_report for account performance. "
                "Never invent advertising metrics. Distinguish zero metrics from unavailable metrics."
            )

    async def assist(
        self,
        request: CampaignAssistRequest,
        meta_access_token: str | None = None,
        owner_email: str | None = None,
    ) -> CampaignAssistResponse:
        settings = self._settings
        if not settings.ai_configured:
            raise CampaignAssistantUnavailable("Campaign AI is not configured")

        selected_skill = None
        if request.skill_id:
            selected_skill = self._skills.get_skill(request.skill_id, owner_email)
            if selected_skill is None:
                raise CampaignAssistantUnavailable("Selected skill was not found")

        use_design = request.mode == "design" or bool(request.images)
        want_image = bool(request.generate_image) or (
            use_design and self._wants_image_generation(request.prompt)
        )
        active_client = (request.client_name or "").strip()
        client_binding = (
            f"Active workspace client: {active_client} (id={request.client_id}). "
            "Use only this client. Never rename it or substitute another business such as P&J TECH."
            if active_client
            else (
                "No active workspace client is selected. Do not invent or substitute a client name "
                "(including P&J TECH or any platform default). Ask the user to select a client."
            )
        )
        system = (
            "You are CAMPEX AI Campaign Assistant and a careful Meta Ads analyst. Use only facts and "
            "metrics actually supplied or returned by tools. Never invent prices, returns, approvals, amenities, "
            "availability, conversions, ROAS, or legal claims. Explicitly flag missing information. "
            "Use Meta tools only for live account or campaign questions. This chat exposes read-only tools; "
            "never create, pause, activate, update, or delete advertising objects. Campaign publishing is a "
            "separate user-confirmed action. For copy requests, return Summary and Suggested copy sections. "
            f"Write copy in {'natural Telugu' if request.language == 'te' else 'English'}.\n"
            f"{client_binding}\n\n"
            f"{self._tool_rules}\n\n"
            "Default audit guidance:\n"
            f"{self._audit_skill}"
        )
        if selected_skill:
            system += (
                f"\n\nActive skill ({selected_skill.name}):\n"
                f"{selected_skill.body[:12_000]}"
            )
        if use_design or want_image:
            system += "\n\nAd creative design guidance:\n" + self._skills.design_skill_text()
            if want_image:
                system += (
                    "\nThe platform will generate a new ad image from your Image prompt section. "
                    "Always include a detailed Image prompt."
                )
        if not meta_access_token:
            system += (
                "\nNo Meta Business connection is available for this request. "
                "Do not imply access to live ad data; ask the user to connect Meta under Channels."
            )

        text_user = (
            f"Mode: {'ad design' if use_design or want_image else 'campaign assist'}\n"
            f"Generate image: {'yes' if want_image else 'no'}\n"
            f"Active workspace client: {request.client_name or 'not selected'}\n"
            f"Active workspace client id: {request.client_id or 'not selected'}\n"
            f"Campaign: {request.campaign_name}\nObjective: {request.objective}\n"
            f"Selected channels: {', '.join(request.platforms)}\n"
            f"Selected ad account: {request.ad_account_id or 'not selected'}\n"
            f"Active skill: {(selected_skill.name if selected_skill else 'none')}\n"
            f"User request: {request.prompt}\n\nCurrent ad content:\n{request.content or 'No draft copy supplied.'}\n"
            "Tool routing: if the user names a specific ad, call get_ad_performance with exact ad_name "
            "and date_preset last_7d unless another range is requested. For account-level metrics, call "
            "get_meta_performance_report. Do not call get_campaigns/get_ad_sets/get_ads unless required."
        )
        if request.images:
            names = ", ".join(image.name for image in request.images)
            text_user += (
                f"\n\nUploaded reference images: {names}. Use them as visual inspiration for design notes "
                "and the Image prompt; the generated creative should be a polished feed ad, not a raw photo copy."
            )

        user_content: str | list[dict[str, Any]]
        if request.images:
            parts: list[dict[str, Any]] = [{"type": "text", "text": text_user}]
            for image in request.images:
                parts.append({
                    "type": "image_url",
                    "image_url": {
                        "url": f"data:{image.mime_type};base64,{image.data_base64}",
                    },
                })
            user_content = parts
        else:
            user_content = text_user

        headers = {"Content-Type": "application/json"}
        api_key = settings.campaign_ai_api_key
        if api_key:
            headers["Authorization"] = f"Bearer {api_key}"

        skill_id = selected_skill.id if selected_skill else None
        try:
            timeout = max(settings.ai_timeout_seconds, settings.ai_image_timeout_seconds if want_image else 0)
            async with httpx.AsyncClient(timeout=timeout, transport=self._transport) as client:
                if meta_access_token and self._meta_mcp is not None and not want_image:
                    try:
                        result = await self._assist_with_meta_tools(
                            client, headers, system, user_content, meta_access_token, skill_id=skill_id,
                        )
                    except CampaignAssistantUnavailable as exc:
                        # Copy assist should still work when Meta tools are unhealthy.
                        logger.warning("Meta tool assist failed (%s); falling back to copy-only", exc)
                        system += (
                            "\nMeta Ads tools failed for this request. Answer from the supplied draft only "
                            "and say live Meta metrics were unavailable."
                        )
                        result = await self._complete_without_tools(
                            client, headers, system, user_content, skill_id=skill_id,
                        )
                else:
                    # Skip Meta tool loop for image generation turns so the model focuses on creative output.
                    result = await self._complete_without_tools(
                        client, headers, system, user_content, skill_id=skill_id,
                    )
                if want_image:
                    return await self._attach_generated_image(client, headers, result, request)
                return result
        except httpx.HTTPError as exc:
            logger.warning("Campaign AI request failed (%s)", exc.__class__.__name__)
            raise CampaignAssistantUnavailable("Campaign AI endpoint could not be reached") from exc

    async def _complete_without_tools(
        self,
        client: httpx.AsyncClient,
        headers: dict[str, str],
        system: str,
        user: str | list[dict[str, Any]],
        *,
        skill_id: str | None = None,
    ) -> CampaignAssistResponse:
        payload = {
            "model": self._settings.ai_model,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
            "temperature": 0.4,
            "max_tokens": 1400,
        }
        response = await self._post_completion(client, headers, payload)
        return self._response(self._assistant_text(response), [], skill_id=skill_id)

    async def _assist_with_meta_tools(
        self,
        client: httpx.AsyncClient,
        headers: dict[str, str],
        system: str,
        user: str | list[dict[str, Any]],
        meta_access_token: str,
        *,
        skill_id: str | None = None,
    ) -> CampaignAssistResponse:
        if not meta_access_token.strip() or self._meta_mcp is None:
            return await self._complete_without_tools(client, headers, system, user, skill_id=skill_id)

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
                        "max_tokens": 1400,
                    }
                    response = await self._post_completion(client, headers, payload)
                    message = response["choices"][0].get("message") or {}
                    calls = message.get("tool_calls") or []
                    if not calls:
                        return self._response(self._assistant_text(response), tools_used, skill_id=skill_id)

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
                                        detail = str(exc).strip() or type(exc).__name__
                                        tool_text = f"Tool error from {name}: {detail}"
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

    def _response(
        self,
        reply: str,
        tools_used: list[str],
        *,
        skill_id: str | None = None,
        generated_images: list[GeneratedAssistImage] | None = None,
        image_error: str | None = None,
    ) -> CampaignAssistResponse:
        return CampaignAssistResponse(
            reply=reply,
            provider=self._settings.ai_provider,
            model=self._settings.ai_model,
            suggested_copy=_suggested_copy(reply),
            suggested_headline=_section(reply, "Suggested headline"),
            suggested_cta=_section(reply, "CTA"),
            design_notes=_section(reply, "Design notes"),
            skill_id=skill_id,
            tools_used=tools_used,
            generated_images=generated_images or [],
            image_error=image_error,
        )

    @staticmethod
    def _wants_image_generation(prompt: str) -> bool:
        text = prompt.lower()
        triggers = (
            "generate image",
            "generate an image",
            "create image",
            "create an image",
            "generate ad",
            "create ad creative",
            "generate creative",
            "design an ad",
            "make an ad image",
            "render",
            "dall-e",
            "image generation",
        )
        return any(trigger in text for trigger in triggers)

    def _image_prompt_from_reply(self, reply: str, request: CampaignAssistRequest) -> str:
        explicit = _section(reply, "Image prompt")
        if explicit:
            return explicit[:3500]
        headline = _section(reply, "Suggested headline") or request.campaign_name
        copy = _suggested_copy(reply) or request.content or request.prompt
        cta = _section(reply, "CTA") or "Learn more"
        client = (request.client_name or "").strip()
        return (
            f"Photorealistic Facebook/Instagram feed ad creative for a real-estate campaign"
            f"{f' for {client}' if client else ''}. "
            f"Headline concept: {headline}. Primary message: {copy}. CTA concept: {cta}. "
            "Clean modern lifestyle property photography, soft natural light, generous negative space "
            "for text, mobile-first composition, no logos, no tiny unreadable text, no watermark."
        )[:3500]

    async def _attach_generated_image(
        self,
        client: httpx.AsyncClient,
        headers: dict[str, str],
        result: CampaignAssistResponse,
        request: CampaignAssistRequest,
    ) -> CampaignAssistResponse:
        settings = self._settings
        if not settings.ai_image_enabled:
            return result.model_copy(update={
                "image_error": "Image generation is disabled on the server.",
            })
        if settings.ai_provider != "openai" and "openai.com" not in settings.ai_base_url:
            # Compatible endpoints may not support /images/generations.
            if not settings.campaign_ai_api_key and settings.ai_provider != "openai":
                return result.model_copy(update={
                    "image_error": "Image generation requires an OpenAI-compatible Images API key.",
                })
        if not settings.campaign_ai_api_key and settings.ai_provider == "openai":
            return result.model_copy(update={
                "image_error": "Image generation is not configured (missing API key).",
            })

        prompt = self._image_prompt_from_reply(result.reply, request)
        payload = {
            "model": settings.ai_image_model,
            "prompt": prompt,
            "size": settings.ai_image_size,
            "n": 1,
            "response_format": "b64_json",
        }
        if settings.ai_image_model.startswith("dall-e-3"):
            payload["quality"] = "standard"

        try:
            response = await client.post(
                f"{settings.ai_base_url.rstrip('/')}/images/generations",
                json=payload,
                headers=headers,
                timeout=settings.ai_image_timeout_seconds,
            )
        except httpx.HTTPError as exc:
            logger.warning("Image generation request failed (%s)", type(exc).__name__)
            return result.model_copy(update={
                "image_error": "Image generation endpoint could not be reached.",
            })

        if response.is_error:
            detail = "Image generation failed."
            try:
                body = response.json()
                err = body.get("error") if isinstance(body, dict) else None
                if isinstance(err, dict) and isinstance(err.get("message"), str):
                    detail = err["message"]
                elif isinstance(body, dict) and isinstance(body.get("detail"), str):
                    detail = body["detail"]
            except ValueError:
                detail = f"Image generation returned HTTP {response.status_code}"
            logger.warning("Image generation failed (HTTP %s)", response.status_code)
            return result.model_copy(update={"image_error": detail})

        try:
            body = response.json()
        except ValueError:
            return result.model_copy(update={"image_error": "Image generation returned invalid JSON."})

        data = body.get("data") if isinstance(body, dict) else None
        if not isinstance(data, list) or not data:
            return result.model_copy(update={"image_error": "Image generation returned no image data."})
        first = data[0] if isinstance(data[0], dict) else {}
        b64 = first.get("b64_json")
        if not isinstance(b64, str) or not b64.strip():
            # Some providers return a URL only.
            url = first.get("url")
            if isinstance(url, str) and url.startswith("http"):
                try:
                    downloaded = await client.get(url, timeout=settings.ai_image_timeout_seconds)
                except httpx.HTTPError:
                    return result.model_copy(update={"image_error": "Could not download generated image."})
                if downloaded.is_error or not downloaded.content:
                    return result.model_copy(update={"image_error": "Could not download generated image."})
                b64 = base64.b64encode(downloaded.content).decode("ascii")
            else:
                return result.model_copy(update={"image_error": "Image generation returned no image payload."})

        generated = GeneratedAssistImage(
            mime_type="image/png",
            data_base64=b64.strip(),
            prompt=prompt,
        )
        note = "\n\nGenerated ad creative image is attached below."
        return result.model_copy(update={
            "reply": result.reply + note,
            "generated_images": [generated],
            "image_error": None,
        })
