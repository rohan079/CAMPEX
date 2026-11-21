"""Private FastMCP server for the Meta Marketing API."""

from __future__ import annotations

import json
import logging
import re
from datetime import date
from typing import Any, Literal

import httpx
from fastmcp import FastMCP

from config import Settings, get_settings

logger = logging.getLogger(__name__)

INSIGHT_FIELDS = (
    "date_start,date_stop,spend,impressions,reach,clicks,ctr,cpc,cpm,frequency,actions,action_values,cost_per_action_type"
)
ID_PATTERN = re.compile(r"^(?:act_)?[0-9]{1,32}$")
MUTATING_TOOLS = frozenset({"create_paused_campaign", "set_campaign_status", "delete_campaign"})


class MetaAdsToolError(RuntimeError):
    """An actionable Meta API error with no access-token or request-URL details."""


class MetaAdsGraphApi:
    def __init__(self, settings: Settings, transport: httpx.AsyncBaseTransport | None = None):
        self._base_url = f"https://graph.facebook.com/{settings.meta_graph_api_version}"
        self._transport = transport

    async def request(
        self,
        method: str,
        edge: str,
        access_token: str,
        *,
        params: dict[str, str] | None = None,
        form: dict[str, str] | None = None,
    ) -> dict[str, Any]:
        token = self._require_token(access_token)
        try:
            async with httpx.AsyncClient(timeout=25, transport=self._transport) as client:
                response = await client.request(
                    method,
                    f"{self._base_url}/{edge.lstrip('/')}",
                    params=params,
                    data=form,
                    headers={"Authorization": f"Bearer {token}"},
                )
        except httpx.HTTPError as exc:
            logger.warning("Meta Graph request failed (%s)", type(exc).__name__)
            raise MetaAdsToolError("Meta could not be reached. Retry the request shortly.") from exc

        try:
            payload = response.json()
        except ValueError as exc:
            logger.warning("Meta Graph returned non-JSON content (HTTP %s)", response.status_code)
            raise MetaAdsToolError("Meta returned an unreadable response.") from exc

        if not isinstance(payload, dict):
            raise MetaAdsToolError("Meta returned an unexpected response shape.")
        if not response.is_success or "error" in payload:
            error = payload.get("error") if isinstance(payload.get("error"), dict) else {}
            graph_code = error.get("code")
            logger.warning("Meta Graph rejected a request (HTTP %s, code %s)", response.status_code, graph_code)
            if graph_code == 190:
                raise MetaAdsToolError("Meta access was revoked or expired. Reconnect the Meta account.")
            if response.status_code == 429 or graph_code in {4, 17, 32, 613}:
                raise MetaAdsToolError("Meta rate limit reached. Retry later.")
            raise MetaAdsToolError(
                f"Meta rejected the request (HTTP {response.status_code}, code {graph_code}). "
                "Check the account permissions and request parameters."
            )
        return payload

    async def list_edge(
        self,
        edge: str,
        access_token: str,
        fields: str,
        limit: int,
        extra: dict[str, str] | None = None,
    ) -> list[dict[str, Any]]:
        page_limit = max(1, min(limit, 100))
        params = {"fields": fields, "limit": str(page_limit)}
        if extra:
            params.update(extra)
        result: list[dict[str, Any]] = []
        after: str | None = None
        while len(result) < page_limit:
            if after:
                params["after"] = after
            payload = await self.request("GET", edge, access_token, params=params)
            rows = payload.get("data", [])
            if not isinstance(rows, list):
                raise MetaAdsToolError("Meta returned an unexpected list response.")
            result.extend(row for row in rows if isinstance(row, dict))
            paging = payload.get("paging")
            cursors = paging.get("cursors", {}) if isinstance(paging, dict) else {}
            after = cursors.get("after") if isinstance(cursors, dict) else None
            if not after or not isinstance(paging, dict) or not paging.get("next"):
                break
        return result[:page_limit]

    @staticmethod
    def account_id(value: str) -> str:
        normalized = value.strip()
        if not ID_PATTERN.fullmatch(normalized):
            raise MetaAdsToolError("Ad account ID must be a numeric Meta account ID.")
        return normalized if normalized.startswith("act_") else f"act_{normalized}"

    @staticmethod
    def object_id(value: str) -> str:
        normalized = value.strip()
        if not normalized.isdigit() or len(normalized) > 32:
            raise MetaAdsToolError("Meta object ID must be numeric.")
        return normalized

    @staticmethod
    def _require_token(value: str) -> str:
        if not value or not value.strip():
            raise MetaAdsToolError("Connect Meta Business under Channels before using Meta Ads tools.")
        return value.strip()

    @staticmethod
    def insights_params(
        date_preset: str,
        date_from: str | None,
        date_to: str | None,
        breakdown: str,
    ) -> dict[str, str]:
        params = {"fields": INSIGHT_FIELDS}
        if date_from or date_to:
            if not date_from or not date_to:
                raise MetaAdsToolError("Provide both date_from and date_to for a custom reporting period.")
            try:
                start = date.fromisoformat(date_from)
                end = date.fromisoformat(date_to)
            except ValueError as exc:
                raise MetaAdsToolError("Dates must use YYYY-MM-DD format.") from exc
            if end < start:
                raise MetaAdsToolError("date_to must be on or after date_from.")
            params["time_range"] = json.dumps({"since": date_from, "until": date_to})
        else:
            allowed = {"today", "yesterday", "last_7d", "last_14d", "last_30d", "this_month", "last_month"}
            if date_preset not in allowed:
                raise MetaAdsToolError("Unsupported date preset.")
            params["date_preset"] = date_preset
        if breakdown:
            if breakdown not in {"publisher_platform", "platform_position", "country"}:
                raise MetaAdsToolError("Unsupported Insights breakdown.")
            params["breakdowns"] = breakdown
        return params


def create_meta_ads_mcp(
    settings: Settings | None = None,
    transport: httpx.AsyncBaseTransport | None = None,
) -> FastMCP:
    config = settings or get_settings()
    graph = MetaAdsGraphApi(config, transport)
    mcp = FastMCP(
        "CAMPEX Meta Ads",
        instructions=(
            "Use only the authenticated Meta account supplied by CAMPEX. Read tools return current Graph API data. "
            "Mutation tools create paused objects or require an explicit confirmation argument. Never expose credentials."
        ),
    )

    @mcp.tool
    async def get_business_accounts(access_token: str, limit: int = 50) -> list[dict[str, Any]]:
        """List Meta business accounts granted to the connected user."""
        return await graph.list_edge("me/businesses", access_token, "id,name", limit)

    @mcp.tool
    async def get_ad_accounts(access_token: str, limit: int = 50) -> list[dict[str, Any]]:
        """List the connected user's authorized Meta ad accounts."""
        return await graph.list_edge(
            "me/adaccounts", access_token, "id,name,account_id,account_status,currency,timezone_name", limit
        )

    @mcp.tool
    async def get_ad_account_details(ad_account_id: str, access_token: str) -> dict[str, Any]:
        """Get metadata for an ad account already authorized by the connected user."""
        account = graph.account_id(ad_account_id)
        return await graph.request(
            "GET", account, access_token,
            params={"fields": "id,name,account_id,account_status,currency,timezone_name,spend_cap,amount_spent"},
        )

    @mcp.tool
    async def get_facebook_pages(access_token: str, limit: int = 100) -> list[dict[str, Any]]:
        """List Pages the connected Meta user can access. Page tokens are never requested."""
        return await graph.list_edge("me/accounts", access_token, "id,name,category", limit)

    @mcp.tool
    async def get_instagram_accounts(access_token: str, limit: int = 100) -> list[dict[str, Any]]:
        """List Instagram professional accounts linked to authorized Facebook Pages."""
        pages = await graph.list_edge(
            "me/accounts", access_token,
            "id,name,instagram_business_account{id,username,name}", limit,
        )
        accounts: list[dict[str, Any]] = []
        for page in pages:
            account = page.get("instagram_business_account")
            if isinstance(account, dict) and account.get("id"):
                accounts.append({**account, "page_id": page.get("id"), "page_name": page.get("name")})
        return accounts

    @mcp.tool
    async def get_campaigns(
        ad_account_id: str,
        access_token: str,
        limit: int = 50,
        status_filter: str = "",
    ) -> list[dict[str, Any]]:
        """List campaigns for one of the connected user's ad accounts."""
        account = graph.account_id(ad_account_id)
        rows = await graph.list_edge(
            f"{account}/campaigns", access_token,
            "id,name,objective,status,effective_status,daily_budget,lifetime_budget,start_time,stop_time,created_time,updated_time",
            limit,
        )
        if status_filter:
            normalized = status_filter.upper()
            rows = [row for row in rows if normalized in {
                str(row.get("status", "")).upper(), str(row.get("effective_status", "")).upper()
            }]
        return rows

    @mcp.tool
    async def find_campaigns_by_name(
        ad_account_id: str,
        name_contains: str,
        access_token: str,
        limit: int = 25,
    ) -> list[dict[str, Any]]:
        """Read-only: campaigns in one ad account whose name contains a fragment (used to reconcile publishes)."""
        fragment = name_contains.strip()
        if len(fragment) < 4 or len(fragment) > 100:
            raise MetaAdsToolError("Name fragment must be 4-100 characters.")
        account = graph.account_id(ad_account_id)
        rows = await graph.list_edge(
            f"{account}/campaigns", access_token,
            "id,name,objective,status,effective_status,created_time",
            limit,
            {"filtering": json.dumps([{"field": "name", "operator": "CONTAIN", "value": fragment}])},
        )
        # Meta's CONTAIN filter is case-insensitive; keep only exact fragment matches.
        return [row for row in rows if fragment in str(row.get("name", ""))]

    @mcp.tool
    async def get_campaign_details(campaign_id: str, access_token: str) -> dict[str, Any]:
        """Get campaign status, objective, budgets, and dates."""
        return await graph.request(
            "GET", graph.object_id(campaign_id), access_token,
            params={"fields": "id,name,objective,status,effective_status,daily_budget,lifetime_budget,start_time,stop_time,created_time,updated_time"},
        )

    @mcp.tool
    async def get_ad_sets(
        ad_account_id: str,
        access_token: str,
        campaign_id: str = "",
        limit: int = 50,
    ) -> list[dict[str, Any]]:
        """List ad sets, optionally for one authorized campaign."""
        account = graph.account_id(ad_account_id)
        edge = f"{graph.object_id(campaign_id)}/adsets" if campaign_id else f"{account}/adsets"
        return await graph.list_edge(
            edge, access_token,
            "id,name,campaign_id,status,effective_status,daily_budget,lifetime_budget,optimization_goal,billing_event,start_time,end_time,targeting",
            limit,
        )

    @mcp.tool
    async def get_adset_details(adset_id: str, access_token: str) -> dict[str, Any]:
        """Get status, budget, optimization, schedule, and targeting for one ad set."""
        return await graph.request(
            "GET", graph.object_id(adset_id), access_token,
            params={"fields": "id,name,campaign_id,status,effective_status,daily_budget,lifetime_budget,optimization_goal,billing_event,start_time,end_time,targeting"},
        )

    @mcp.tool
    async def get_ads(
        ad_account_id: str,
        access_token: str,
        campaign_id: str = "",
        adset_id: str = "",
        limit: int = 50,
    ) -> list[dict[str, Any]]:
        """List ads, optionally filtered by an authorized campaign or ad set."""
        account = graph.account_id(ad_account_id)
        if adset_id:
            edge = f"{graph.object_id(adset_id)}/ads"
        elif campaign_id:
            edge = f"{graph.object_id(campaign_id)}/ads"
        else:
            edge = f"{account}/ads"
        return await graph.list_edge(
            edge, access_token,
            "id,name,campaign_id,adset_id,status,effective_status,created_time,updated_time,creative{id,name,object_story_spec}",
            limit,
        )

    @mcp.tool
    async def get_ad_details(ad_id: str, access_token: str) -> dict[str, Any]:
        """Get status and creative metadata for one ad."""
        return await graph.request(
            "GET", graph.object_id(ad_id), access_token,
            params={"fields": "id,name,campaign_id,adset_id,status,effective_status,created_time,updated_time,creative{id,name,object_story_spec}"},
        )

    @mcp.tool
    async def get_ad_creatives(ad_account_id: str, access_token: str, limit: int = 50) -> list[dict[str, Any]]:
        """List ad creative metadata for an authorized ad account."""
        return await graph.list_edge(
            f"{graph.account_id(ad_account_id)}/adcreatives",
            access_token,
            "id,name,object_story_spec,thumbnail_url,image_hash,created_time",
            limit,
        )

    @mcp.tool
    async def get_ad_creative(creative_id: str, access_token: str) -> dict[str, Any]:
        """Read copy, destination, and image metadata for one ad creative."""
        return await graph.request(
            "GET", graph.object_id(creative_id), access_token,
            params={"fields": "id,name,object_story_spec,thumbnail_url,image_hash,created_time"},
        )

    @mcp.tool
    async def create_campaign(
        ad_account_id: str,
        name: str,
        objective: Literal["OUTCOME_AWARENESS", "OUTCOME_TRAFFIC", "OUTCOME_LEADS", "OUTCOME_SALES"],
        access_token: str,
        confirmed: bool,
    ) -> dict[str, Any]:
        """Create a Meta campaign in PAUSED status. Requires explicit confirmation."""
        if not confirmed:
            raise MetaAdsToolError("Explicit user confirmation is required before creating a campaign.")
        response = await graph.request(
            "POST", f"{graph.account_id(ad_account_id)}/campaigns", access_token,
            form={"name": name.strip(), "objective": objective, "status": "PAUSED", "special_ad_categories": "[]"},
        )
        return {"campaign_id": response.get("id"), "status": "PAUSED"}

    @mcp.tool
    async def update_campaign(
        campaign_id: str,
        access_token: str,
        confirmed: bool,
        name: str = "",
        status: Literal["PAUSED", "ACTIVE", "ARCHIVED"] | None = None,
    ) -> dict[str, Any]:
        """Update campaign name and/or status. Only the fields given are sent. Requires explicit confirmation."""
        if not confirmed:
            raise MetaAdsToolError("Explicit user confirmation is required before updating a campaign.")
        form: dict[str, str] = {}
        if status:
            form["status"] = status
        if name.strip():
            form["name"] = name.strip()
        if not form:
            raise MetaAdsToolError("Nothing to update.")
        response = await graph.request("POST", graph.object_id(campaign_id), access_token, form=form)
        return {"campaign_id": campaign_id, "status": status, "success": response.get("success", True)}

    @mcp.tool
    async def get_campaign_insights(
        campaign_id: str,
        access_token: str,
        date_preset: str = "last_14d",
        date_from: str = "",
        date_to: str = "",
        breakdown: Literal["publisher_platform", "platform_position", "country", ""] = "",
    ) -> list[dict[str, Any]]:
        """Read campaign spend, reach, impressions, clicks, CTR, CPC, CPM, frequency, actions, and value."""
        params = graph.insights_params(date_preset, date_from or None, date_to or None, breakdown)
        return await graph.list_edge(
            f"{graph.object_id(campaign_id)}/insights", access_token, INSIGHT_FIELDS, 100, params
        )

    @mcp.tool
    async def get_adset_insights(
        adset_id: str,
        access_token: str,
        date_preset: str = "last_14d",
        date_from: str = "",
        date_to: str = "",
        breakdown: Literal["publisher_platform", "platform_position", "country", ""] = "",
    ) -> list[dict[str, Any]]:
        """Read performance insights for one ad set."""
        params = graph.insights_params(date_preset, date_from or None, date_to or None, breakdown)
        return await graph.list_edge(
            f"{graph.object_id(adset_id)}/insights", access_token, INSIGHT_FIELDS, 100, params
        )

    @mcp.tool
    async def get_ad_insights(
        ad_id: str,
        access_token: str,
        date_preset: str = "last_14d",
        date_from: str = "",
        date_to: str = "",
        breakdown: Literal["publisher_platform", "platform_position", "country", ""] = "",
    ) -> list[dict[str, Any]]:
        """Read performance insights for one ad."""
        params = graph.insights_params(date_preset, date_from or None, date_to or None, breakdown)
        return await graph.list_edge(
            f"{graph.object_id(ad_id)}/insights", access_token, INSIGHT_FIELDS, 100, params
        )

    @mcp.tool
    async def get_ad_performance(
        ad_account_id: str,
        access_token: str,
        ad_name: str = "",
        ad_id: str = "",
        date_preset: str = "last_7d",
        date_from: str = "",
        date_to: str = "",
    ) -> dict[str, Any]:
        """Preferred tool for one ad's reach, impressions, spend, clicks, CTR, CPC, and CPM.

        Resolve the ad by exact name (EQUAL match) unless ad_id is supplied, then query insights
        for that individual ad ID. Defaults to last_7d. Do not invent metrics when Meta returns
        empty rows or an API error.
        """
        account = graph.account_id(ad_account_id)
        resolved_id = ad_id.strip()
        resolved_name = ad_name.strip()
        matched: dict[str, Any] | None = None

        if resolved_id:
            matched = await graph.request(
                "GET",
                graph.object_id(resolved_id),
                access_token,
                params={"fields": "id,name,campaign_id,adset_id,status,effective_status"},
            )
            resolved_name = str(matched.get("name") or resolved_name)
        else:
            if not resolved_name:
                raise MetaAdsToolError("Provide an exact ad_name or an ad_id for ad performance.")
            filtering = json.dumps([{
                "field": "name",
                "operator": "EQUAL",
                "value": resolved_name,
            }])
            ads = await graph.list_edge(
                f"{account}/ads",
                access_token,
                "id,name,campaign_id,adset_id,status,effective_status",
                25,
                {"filtering": filtering},
            )
            exact = [row for row in ads if str(row.get("name") or "") == resolved_name]
            if not exact:
                return {
                    "status": "not_found",
                    "message": f'No ad with the exact name "{resolved_name}" was found in {account}.',
                    "ad_account_id": account,
                    "ad_name": resolved_name,
                    "ad_id": None,
                    "period": {
                        "date_preset": date_preset if not (date_from or date_to) else None,
                        "date_from": date_from or None,
                        "date_to": date_to or None,
                    },
                    "metrics": [],
                    "totals": None,
                }
            if len(exact) > 1:
                return {
                    "status": "ambiguous",
                    "message": (
                        f'Multiple ads share the exact name "{resolved_name}". '
                        "Pass ad_id to query a specific ad."
                    ),
                    "ad_account_id": account,
                    "ad_name": resolved_name,
                    "matches": [
                        {"id": row.get("id"), "name": row.get("name"), "status": row.get("effective_status")}
                        for row in exact
                    ],
                    "metrics": [],
                    "totals": None,
                }
            matched = exact[0]
            resolved_id = str(matched.get("id") or "")
            if not resolved_id:
                raise MetaAdsToolError("Meta returned an ad without an id.")

        params = graph.insights_params(date_preset, date_from or None, date_to or None, "")
        rows = await graph.list_edge(
            f"{graph.object_id(resolved_id)}/insights",
            access_token,
            INSIGHT_FIELDS,
            100,
            params,
        )
        period = {
            "date_preset": date_preset if not (date_from or date_to) else None,
            "date_from": date_from or None,
            "date_to": date_to or None,
        }
        scope = {
            "ad_account_id": account,
            "ad_id": resolved_id,
            "ad_name": resolved_name or (matched or {}).get("name"),
            "campaign_id": (matched or {}).get("campaign_id"),
            "adset_id": (matched or {}).get("adset_id"),
            "status": (matched or {}).get("effective_status") or (matched or {}).get("status"),
        }
        if not rows:
            return {
                "status": "empty",
                "message": f"No metrics were returned for ad {resolved_id} in the selected period.",
                "scope": scope,
                "period": period,
                "metrics": [],
                "totals": None,
            }

        def _num(row: dict[str, Any], key: str) -> float:
            raw = row.get(key)
            try:
                return float(raw) if raw is not None and raw != "" else 0.0
            except (TypeError, ValueError):
                return 0.0

        totals = {"spend": 0.0, "impressions": 0.0, "reach": 0.0, "clicks": 0.0}
        for row in rows:
            totals["spend"] += _num(row, "spend")
            totals["impressions"] += _num(row, "impressions")
            totals["reach"] += _num(row, "reach")
            totals["clicks"] += _num(row, "clicks")
        impressions = totals["impressions"]
        clicks = totals["clicks"]
        spend = totals["spend"]
        totals["ctr"] = (clicks / impressions * 100.0) if impressions else 0.0
        totals["cpc"] = (spend / clicks) if clicks else 0.0
        totals["cpm"] = (spend / impressions * 1000.0) if impressions else 0.0
        all_zero = all(value == 0.0 for value in (
            totals["spend"], totals["impressions"], totals["reach"], totals["clicks"],
        ))
        return {
            "status": "zero_metrics" if all_zero else "ok",
            "message": (
                "Meta returned rows, but all key metrics are zero for this ad and period."
                if all_zero
                else "Meta ad performance metrics returned successfully."
            ),
            "scope": scope,
            "period": period,
            "metrics": rows,
            "totals": totals,
        }

    @mcp.tool
    async def get_account_insights(
        ad_account_id: str,
        access_token: str,
        date_preset: str = "last_14d",
        date_from: str = "",
        date_to: str = "",
        breakdown: Literal["publisher_platform", "platform_position", "country", ""] = "",
    ) -> list[dict[str, Any]]:
        """Read account-level Meta advertising performance insights."""
        params = graph.insights_params(date_preset, date_from or None, date_to or None, breakdown)
        return await graph.list_edge(
            f"{graph.account_id(ad_account_id)}/insights", access_token, INSIGHT_FIELDS, 100, params
        )

    @mcp.tool
    async def get_meta_performance_report(
        ad_account_id: str,
        access_token: str,
        date_preset: str = "last_14d",
        date_from: str = "",
        date_to: str = "",
        campaign_id: str = "",
        level: Literal["account", "campaign"] = "account",
        breakdown: Literal["publisher_platform", "platform_position", "country", ""] = "",
    ) -> dict[str, Any]:
        """Preferred tool for reach, impressions, spend, clicks, CTR, CPC, and CPM.

        Defaults to last_14d. Use account-level insights when that answers the question.
        Pass campaign_id (and level=campaign) only for campaign-specific analysis.
        Distinguishes empty Meta responses from rows that contain zero metrics.
        """
        account = graph.account_id(ad_account_id)
        params = graph.insights_params(date_preset, date_from or None, date_to or None, breakdown)
        period = {
            "date_preset": date_preset if not (date_from or date_to) else None,
            "date_from": date_from or None,
            "date_to": date_to or None,
        }
        target_campaign = campaign_id.strip()
        if level == "campaign" or target_campaign:
            if not target_campaign:
                raise MetaAdsToolError("campaign_id is required for campaign-level performance reports.")
            edge = f"{graph.object_id(target_campaign)}/insights"
            scope = {"level": "campaign", "campaign_id": target_campaign, "ad_account_id": account}
        else:
            edge = f"{account}/insights"
            scope = {"level": "account", "ad_account_id": account}

        rows = await graph.list_edge(edge, access_token, INSIGHT_FIELDS, 100, params)
        if not rows:
            return {
                "status": "empty",
                "message": "No metrics were returned for the selected account/period.",
                "scope": scope,
                "period": period,
                "metrics": [],
                "totals": None,
            }

        def _num(row: dict[str, Any], key: str) -> float:
            raw = row.get(key)
            try:
                return float(raw) if raw is not None and raw != "" else 0.0
            except (TypeError, ValueError):
                return 0.0

        totals = {
            "spend": 0.0,
            "impressions": 0.0,
            "reach": 0.0,
            "clicks": 0.0,
        }
        for row in rows:
            totals["spend"] += _num(row, "spend")
            totals["impressions"] += _num(row, "impressions")
            totals["reach"] += _num(row, "reach")
            totals["clicks"] += _num(row, "clicks")
        impressions = totals["impressions"]
        clicks = totals["clicks"]
        spend = totals["spend"]
        totals["ctr"] = (clicks / impressions * 100.0) if impressions else 0.0
        totals["cpc"] = (spend / clicks) if clicks else 0.0
        totals["cpm"] = (spend / impressions * 1000.0) if impressions else 0.0
        all_zero = all(value == 0.0 for value in (
            totals["spend"], totals["impressions"], totals["reach"], totals["clicks"],
        ))
        return {
            "status": "zero_metrics" if all_zero else "ok",
            "message": (
                "Meta returned rows, but all key metrics are zero for this period."
                if all_zero
                else "Meta performance metrics returned successfully."
            ),
            "scope": scope,
            "period": period,
            "metrics": rows,
            "totals": totals,
        }

    @mcp.tool
    async def create_paused_campaign(
        ad_account_id: str,
        name: str,
        objective: Literal["leads", "traffic", "sales", "awareness"],
        location: str,
        age_min: int,
        age_max: int,
        platforms: list[str],
        access_token: str,
        confirmed: bool,
        daily_budget_minor: int = 50000,
    ) -> dict[str, Any]:
        """Create a PAUSED Meta campaign and ad set; create a paused link ad when a Page is selected."""
        if not confirmed:
            raise MetaAdsToolError("Explicit user confirmation is required before creating a campaign.")
        if daily_budget_minor < 100:
            raise MetaAdsToolError("Daily budget must be at least 100 minor currency units.")
        if age_min < 18 or age_max > 65 or age_max < age_min:
            raise MetaAdsToolError("Age range must be between 18 and 65, with maximum age not below minimum age.")
        account = graph.account_id(ad_account_id)

        # Verify the account belongs to the token before mutating anything.
        allowed_accounts = await graph.list_edge(
            "me/adaccounts", access_token, "id,account_id", 100
        )
        if account not in {row.get("id") for row in allowed_accounts}:
            raise MetaAdsToolError("Selected ad account is not available to the connected Meta user.")

        matches = await graph.list_edge(
            "search", access_token, "key,name,type,country_code", 5,
            {
                "type": "adgeolocation",
                "location_types": json.dumps(["city", "region", "country"]),
                "q": location.strip(),
            },
        )
        if not matches:
            raise MetaAdsToolError("Meta could not match the target location. Choose a more specific city, region, or country.")
        exact_matches = [
            row for row in matches
            if str(row.get("name", "")).strip().casefold() == location.strip().casefold()
        ]
        if len(exact_matches) == 1:
            match = exact_matches[0]
        elif len(matches) == 1:
            match = matches[0]
        else:
            suggestions = ", ".join(str(row.get("name", "")) for row in matches if row.get("name"))
            raise MetaAdsToolError(f"Target location is ambiguous; refine it to one of: {suggestions}")
        geo_type = match.get("type") or "city"
        geo_key = str(match.get("key", ""))
        if not geo_key:
            raise MetaAdsToolError("Meta returned no location key for the selected target.")
        if geo_type == "country":
            geo_locations = {"countries": [match.get("country_code") or geo_key]}
        elif geo_type == "region":
            geo_locations = {"regions": [{"key": geo_key}]}
        else:
            geo_locations = {"cities": [{"key": geo_key}]}

        publisher_platforms = sorted({
            "facebook" if platform in {"facebook", "whatsapp_ads"} else "instagram"
            for platform in platforms
            if platform in {"facebook", "instagram", "whatsapp_ads"}
        }) or ["facebook"]
        targeting: dict[str, Any] = {
            "age_min": age_min,
            "age_max": age_max,
            "geo_locations": geo_locations,
            "publisher_platforms": publisher_platforms,
        }
        if "facebook" in publisher_platforms:
            targeting["facebook_positions"] = ["feed", "story"]
        if "instagram" in publisher_platforms:
            targeting["instagram_positions"] = ["stream", "story", "reels"]

        objectives = {
            "leads": "OUTCOME_LEADS",
            "traffic": "OUTCOME_TRAFFIC",
            "sales": "OUTCOME_SALES",
            "awareness": "OUTCOME_AWARENESS",
        }
        meta_objective = objectives[objective]
        campaign = await graph.request(
            "POST", f"{account}/campaigns", access_token,
            form={
                "name": name.strip(),
                "objective": meta_objective,
                "status": "PAUSED",
                "special_ad_categories": "[]",
            },
        )
        campaign_id = str(campaign.get("id", ""))
        if not campaign_id:
            raise MetaAdsToolError("Meta did not return a campaign ID; no ad set was created.")

        optimization_goals = {
            "leads": "LEAD_GENERATION",
            "traffic": "LINK_CLICKS",
            "sales": "OFFSITE_CONVERSIONS",
            "awareness": "REACH",
        }
        try:
            ad_set = await graph.request(
                "POST", f"{account}/adsets", access_token,
                form={
                    "name": f"{name.strip()} - audience",
                    "campaign_id": campaign_id,
                    "daily_budget": str(daily_budget_minor),
                    "billing_event": "IMPRESSIONS",
                    "optimization_goal": optimization_goals[objective],
                    "bid_strategy": "LOWEST_COST_WITHOUT_CAP",
                    "targeting": json.dumps(targeting),
                    "status": "PAUSED",
                },
            )
        except MetaAdsToolError as exc:
            return {
                "campaign_id": campaign_id,
                "ad_set_id": None,
                "ad_id": None,
                "ad_account_id": account,
                "status": "PAUSED",
                "warning": f"Campaign created paused; ad set creation failed: {exc}",
            }

        ad_set_id = str(ad_set.get("id", "")) or None
        numeric_account = account.removeprefix("act_")
        return {
            "campaign_id": campaign_id,
            "ad_set_id": ad_set_id,
            "ad_id": None,
            "ad_account_id": account,
            "status": "PAUSED",
            "ads_manager_url": f"https://www.facebook.com/adsmanager/manage/campaigns?act={numeric_account}&selected_campaign_ids={campaign_id}",
            "warning": "Campaign and ad set are paused. The dashboard image is not uploaded as a Meta creative yet; create the ad in Ads Manager or use a supported creative upload flow.",
        }

    @mcp.tool
    async def set_campaign_status(
        campaign_id: str,
        status: Literal["PAUSED", "ACTIVE", "ARCHIVED"],
        access_token: str,
        confirmed: bool,
    ) -> dict[str, Any]:
        """Change campaign delivery status. Every change requires explicit user confirmation."""
        if not confirmed:
            raise MetaAdsToolError("Explicit user confirmation is required before changing campaign status.")
        campaign = await graph.request(
            "POST", graph.object_id(campaign_id), access_token, form={"status": status}
        )
        return {"campaign_id": campaign_id, "status": status, "success": campaign.get("success", True)}

    @mcp.tool
    async def update_adset_status(
        adset_id: str,
        status: Literal["PAUSED", "ACTIVE", "ARCHIVED"],
        access_token: str,
        confirmed: bool,
    ) -> dict[str, Any]:
        """Pause or activate an ad set. Requires explicit user confirmation."""
        if not confirmed:
            raise MetaAdsToolError("Explicit user confirmation is required before changing ad-set status.")
        response = await graph.request(
            "POST", graph.object_id(adset_id), access_token, form={"status": status}
        )
        return {"adset_id": adset_id, "status": status, "success": response.get("success", True)}

    @mcp.tool
    async def update_ad_set(
        adset_id: str,
        access_token: str,
        confirmed: bool,
        status: Literal["PAUSED", "ACTIVE", "ARCHIVED"] | None = None,
        daily_budget_minor: int | None = None,
    ) -> dict[str, Any]:
        """Update ad-set status and/or daily budget; only the fields given are sent. Requires user confirmation."""
        if not confirmed:
            raise MetaAdsToolError("Explicit user confirmation is required before updating an ad set.")
        form: dict[str, str] = {}
        if status:
            form["status"] = status
        if daily_budget_minor is not None:
            if daily_budget_minor < 100:
                raise MetaAdsToolError("Daily budget must be at least 100 minor currency units.")
            form["daily_budget"] = str(daily_budget_minor)
        if not form:
            raise MetaAdsToolError("Nothing to update.")
        response = await graph.request("POST", graph.object_id(adset_id), access_token, form=form)
        return {"adset_id": adset_id, "status": status, "success": response.get("success", True)}

    @mcp.tool
    async def update_ad_status(
        ad_id: str,
        status: Literal["PAUSED", "ACTIVE", "ARCHIVED"],
        access_token: str,
        confirmed: bool,
    ) -> dict[str, Any]:
        """Pause or activate an ad. Requires explicit user confirmation."""
        if not confirmed:
            raise MetaAdsToolError("Explicit user confirmation is required before changing ad status.")
        response = await graph.request(
            "POST", graph.object_id(ad_id), access_token, form={"status": status}
        )
        return {"ad_id": ad_id, "status": status, "success": response.get("success", True)}

    @mcp.tool
    async def create_ad_set(
        ad_account_id: str,
        campaign_id: str,
        name: str,
        daily_budget_minor: int,
        optimization_goal: str,
        targeting: dict[str, Any],
        access_token: str,
        confirmed: bool,
    ) -> dict[str, Any]:
        """Create a PAUSED ad set for an authorized account. Requires explicit confirmation."""
        if not confirmed:
            raise MetaAdsToolError("Explicit user confirmation is required before creating an ad set.")
        account = graph.account_id(ad_account_id)
        response = await graph.request(
            "POST", f"{account}/adsets", access_token,
            form={
                "name": name.strip(),
                "campaign_id": graph.object_id(campaign_id),
                "daily_budget": str(max(daily_budget_minor, 100)),
                "billing_event": "IMPRESSIONS",
                "optimization_goal": optimization_goal,
                "bid_strategy": "LOWEST_COST_WITHOUT_CAP",
                "targeting": json.dumps(targeting),
                "status": "PAUSED",
            },
        )
        return {"adset_id": response.get("id"), "status": "PAUSED"}

    @mcp.tool
    async def create_ad(
        ad_account_id: str,
        adset_id: str,
        creative_id: str,
        name: str,
        access_token: str,
        confirmed: bool,
    ) -> dict[str, Any]:
        """Create a PAUSED ad from an existing creative. Requires explicit confirmation."""
        if not confirmed:
            raise MetaAdsToolError("Explicit user confirmation is required before creating an ad.")
        account = graph.account_id(ad_account_id)
        response = await graph.request(
            "POST", f"{account}/ads", access_token,
            form={
                "name": name.strip(),
                "adset_id": graph.object_id(adset_id),
                "creative": json.dumps({"creative_id": graph.object_id(creative_id)}),
                "status": "PAUSED",
            },
        )
        return {"ad_id": response.get("id"), "status": "PAUSED"}

    @mcp.tool
    async def create_ad_creative(
        ad_account_id: str,
        page_id: str,
        image_hash: str,
        name: str,
        link_url: str,
        message: str,
        headline: str,
        call_to_action_type: Literal["LEARN_MORE", "SIGN_UP", "CONTACT_US", "MESSAGE_PAGE"],
        access_token: str,
        confirmed: bool,
    ) -> dict[str, Any]:
        """Create a link-ad creative from an existing Meta image hash. Requires explicit confirmation."""
        if not confirmed:
            raise MetaAdsToolError("Explicit user confirmation is required before creating an ad creative.")
        account = graph.account_id(ad_account_id)
        page = graph.object_id(page_id)
        if not image_hash.strip():
            raise MetaAdsToolError("An uploaded Meta image hash is required to create this ad creative.")
        if not link_url.startswith("https://"):
            raise MetaAdsToolError("Ad destination must be an HTTPS URL.")
        story = {
            "page_id": page,
            "link_data": {
                "image_hash": image_hash.strip(),
                "message": message.strip(),
                "name": headline.strip(),
                "link": link_url,
                "call_to_action": {"type": call_to_action_type, "value": {"link": link_url}},
            },
        }
        response = await graph.request(
            "POST", f"{account}/adcreatives", access_token,
            form={"name": name.strip(), "object_story_spec": json.dumps(story)},
        )
        return {"creative_id": response.get("id"), "name": name.strip()}

    STATE_FIELDS = {
        "campaign": "id,name,account_id,status,effective_status,daily_budget,lifetime_budget",
        "adset": "id,name,account_id,campaign_id,status,effective_status,daily_budget,lifetime_budget",
        "ad": "id,name,account_id,campaign_id,adset_id,status,effective_status",
    }

    @mcp.tool
    async def get_object_state(level: Literal["campaign", "adset", "ad"], object_id: str, access_token: str) -> dict[str, Any]:
        """Read-only: status and budget of one campaign, ad set or ad (used for before/after verification)."""
        return await graph.request("GET", graph.object_id(object_id), access_token,
                                   params={"fields": STATE_FIELDS[level]})

    @mcp.tool
    async def apply_object_change(
        level: Literal["campaign", "adset", "ad"],
        object_id: str,
        access_token: str,
        confirmed: bool,
        status: Literal["PAUSED", "ACTIVE"] | None = None,
        daily_budget_minor: int | None = None,
    ) -> dict[str, Any]:
        """Apply one approved change: status and/or daily budget. Sends only the given fields; never creates or deletes.
        Only the Campex execution engine calls this, after a recorded human approval."""
        if not confirmed:
            raise MetaAdsToolError("Explicit user confirmation is required before changing a live object.")
        form: dict[str, str] = {}
        if status:
            form["status"] = status
        if daily_budget_minor is not None:
            if level == "ad":
                raise MetaAdsToolError("Ads have no budget; change the ad set or campaign budget.")
            if daily_budget_minor < 100:
                raise MetaAdsToolError("Daily budget must be at least 100 minor currency units.")
            form["daily_budget"] = str(daily_budget_minor)
        if not form:
            raise MetaAdsToolError("Nothing to change.")
        response = await graph.request("POST", graph.object_id(object_id), access_token, form=form)
        return {"object_id": object_id, "applied": form, "success": bool(response.get("success", True))}

    @mcp.tool
    async def delete_campaign(campaign_id: str, access_token: str, confirmed: bool) -> dict[str, Any]:
        """Permanently delete a campaign. Requires explicit user confirmation."""
        if not confirmed:
            raise MetaAdsToolError("Explicit user confirmation is required before deleting a campaign.")
        response = await graph.request("DELETE", graph.object_id(campaign_id), access_token)
        return {"campaign_id": campaign_id, "success": response.get("success", False)}

    return mcp
