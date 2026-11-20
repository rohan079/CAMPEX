"""Explains deterministic performance findings and answers questions about them.

The lead service computes every metric. This service only phrases them, and a deterministic grounding check
rejects any answer that states a number not present in the supplied context.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from decimal import Decimal
from typing import Any

from services.llm_providers import LlmAdapter, ProviderUnavailable, parse_json

RULES = """Rules you must follow:
- Use ONLY the numbers in CONTEXT. Quote them as given (you may round to fewer decimals or show a ratio as a
  percentage). Never calculate new figures, totals, forecasts or percentages that are not in CONTEXT.
- If CONTEXT does not contain what is needed, say plainly that the data is unavailable and what would be needed.
- Keep sources distinct: Meta-reported metrics, Campex-tracked events/leads, CRM-verified qualified leads,
  site visits and bookings, and manually entered revenue. Say which kind a figure is when it matters.
- Mention relevant data notes (missing data, provisional days, small samples, currency or time-zone caveats).
- Anything about future results is an estimate; say so. Never promise outcomes.
- Recommend reviewing changes in Ads Manager; never claim a campaign was changed."""

NUMBER = re.compile(r"(?<![\w.])-?\d[\d,]*(?:\.\d+)?\s*(?:k|K|L|lakh|lakhs|cr|crore|crores|M|mn|x|X|×)?(?![\w])")
SCALES = {"k": 1e3, "l": 1e5, "lakh": 1e5, "lakhs": 1e5, "cr": 1e7, "crore": 1e7, "crores": 1e7, "m": 1e6, "mn": 1e6}


@dataclass
class Grounding:
    grounded: bool
    unsupported: list[str] = field(default_factory=list)


def _context_numbers(value: Any, out: set[float]) -> None:
    if isinstance(value, bool) or value is None:
        return
    if isinstance(value, (int, float, Decimal)):
        out.add(float(value))
        return
    if isinstance(value, str):
        for match in NUMBER.finditer(value):
            n = _to_number(match.group(0))
            if n is not None:
                out.update(n)
        try:
            out.add(float(value))
        except ValueError:
            pass
        return
    if isinstance(value, dict):
        for v in value.values():
            _context_numbers(v, out)
        return
    if isinstance(value, (list, tuple)):
        for v in value:
            _context_numbers(v, out)


def _to_number(token: str) -> list[float] | None:
    t = token.strip()
    m = re.match(r"(-?\d[\d,]*(?:\.\d+)?)\s*([A-Za-z×]*)$", t)
    if not m:
        return None
    base = float(m.group(1).replace(",", ""))
    suffix = m.group(2).lower()
    if suffix and suffix in SCALES:
        return [base * SCALES[suffix], base]
    return [base]


def allowed_numbers(*sources: Any) -> set[float]:
    values: set[float] = set()
    for source in sources:
        _context_numbers(source, values)
    expanded = set(values)
    for v in values:
        expanded.add(abs(v))
        if abs(v) <= 10:  # ratios shown as percentages
            expanded.add(v * 100)
            expanded.add(abs(v) * 100)
    expanded.update(float(i) for i in range(0, 11))  # small counts ("3 campaigns", "top 5")
    return expanded


def check_grounding(text: str, allowed: set[float]) -> Grounding:
    """Every number in the text must match an allowed value within rounding tolerance."""
    unsupported: list[str] = []
    for match in NUMBER.finditer(text):
        token = match.group(0).strip()
        candidates = _to_number(token)
        if not candidates:
            continue
        if any(_matches(c, allowed) for c in candidates):
            continue
        unsupported.append(token)
    return Grounding(not unsupported, sorted(set(unsupported)))


def _matches(n: float, allowed: set[float]) -> bool:
    for a in allowed:
        tolerance = max(0.006, abs(a) * 0.006)
        if abs(abs(n) - abs(a)) <= tolerance:
            return True
        # rounding to whole numbers or one decimal of the allowed value
        if abs(abs(n) - round(abs(a))) < 1e-9 or abs(abs(n) - round(abs(a), 1)) < 1e-9:
            return True
    return False


class PerformanceIntelligenceService:
    def __init__(self, adapter: LlmAdapter):
        self._adapter = adapter

    async def _grounded(self, system: str, user: str, allowed: set[float], *, max_tokens: int = 1800) -> tuple[str, Grounding, Any]:
        result = await self._adapter.complete(system, user, max_tokens=max_tokens)
        check = check_grounding(result.text, allowed)
        if not check.grounded:
            retry_user = (user + "\n\nYour previous answer used numbers that are not in CONTEXT: "
                          + ", ".join(check.unsupported) + ". Rewrite it using only numbers from CONTEXT.")
            result = await self._adapter.complete(system, retry_user, max_tokens=max_tokens)
            check = check_grounding(result.text, allowed)
        return result.text, check, result

    async def ask(self, question: str, context: dict[str, Any]) -> dict[str, Any]:
        system = ("You are Campex's performance analyst for real-estate advertisers. Answer the question in at most "
                  "200 words, with the key figures and a short recommended next step.\n" + RULES)
        user = "CONTEXT:\n" + json.dumps(context, default=str, ensure_ascii=False) + "\n\nQUESTION:\n" + question
        text, check, result = await self._grounded(system, user, allowed_numbers(context, question))
        return {
            "answer": text,
            "grounded": check.grounded,
            "unsupported_numbers": check.unsupported,
            "data_gaps": [str(n) for n in context.get("data_notes", [])],
            "provider": result.provider,
            "model": result.model,
        }

    async def explain(self, task: str, context: dict[str, Any], items: list[dict[str, Any]]) -> dict[str, Any]:
        system = ("You explain deterministic marketing findings to an advertiser in plain language. For each item "
                  "write 2-4 sentences: what happened, why it matters, and what to review next. Return JSON: "
                  '{"explanations": [{"id": "...", "text": "..."}]}.\n' + RULES)
        user = "CONTEXT:\n" + json.dumps(context, default=str, ensure_ascii=False) + "\n\nITEMS (" + task + "):\n" \
            + json.dumps(items, default=str, ensure_ascii=False)
        schema = {
            "type": "object",
            "properties": {"explanations": {"type": "array", "items": {
                "type": "object",
                "properties": {"id": {"type": "string"}, "text": {"type": "string"}},
                "required": ["id", "text"], "additionalProperties": False}}},
            "required": ["explanations"], "additionalProperties": False,
        }
        result = await self._adapter.complete(system, user, schema=schema, max_tokens=3000)
        body = parse_json(result.text)
        ids = {str(i.get("id")) for i in items}
        explanations = []
        for entry in body.get("explanations", []):
            if not isinstance(entry, dict) or str(entry.get("id")) not in ids:
                continue
            item = next(i for i in items if str(i.get("id")) == str(entry.get("id")))
            check = check_grounding(str(entry.get("text", "")), allowed_numbers(context, item))
            explanations.append({"id": str(entry["id"]), "text": str(entry.get("text", "")).strip(),
                                 "grounded": check.grounded, "unsupported_numbers": check.unsupported})
        return {"explanations": explanations, "provider": result.provider, "model": result.model}

    async def creative_suggestions(self, body: dict[str, Any]) -> dict[str, Any]:
        system = ("You write ad-copy test variants for a real-estate advertiser. Use the observation and current "
                  "creative. Do not invent prices, sizes, amenities, discounts or availability: if a fact is not "
                  "given, write a placeholder like [price range] for the advertiser to fill in. Return JSON with "
                  "exactly 3 headlines (max 40 characters), exactly 3 primary_texts (max 300 characters), one cta "
                  "(a Meta call-to-action such as LEARN_MORE, SIGN_UP, CONTACT_US, BOOK_NOW, GET_QUOTE), "
                  "audience_messaging, 2-3 concepts and 2-3 image_prompts for an image model (no text in images).")
        user = "INPUT:\n" + json.dumps(body, default=str, ensure_ascii=False)
        schema = {
            "type": "object",
            "properties": {
                "headlines": {"type": "array", "items": {"type": "string"}},
                "primary_texts": {"type": "array", "items": {"type": "string"}},
                "cta": {"type": "string"},
                "audience_messaging": {"type": "string"},
                "concepts": {"type": "array", "items": {"type": "string"}},
                "image_prompts": {"type": "array", "items": {"type": "string"}},
            },
            "required": ["headlines", "primary_texts", "cta", "audience_messaging", "concepts", "image_prompts"],
            "additionalProperties": False,
        }
        result = await self._adapter.complete(system, user, schema=schema, max_tokens=2500)
        data = parse_json(result.text)
        headlines = [str(h).strip() for h in data.get("headlines", []) if str(h).strip()][:3]
        texts = [str(t).strip() for t in data.get("primary_texts", []) if str(t).strip()][:3]
        if len(headlines) < 3 or len(texts) < 3:
            raise ProviderUnavailable("The AI did not return three headlines and three primary texts")
        copy = " ".join(headlines + texts + [str(data.get("audience_messaging", ""))])
        check = check_grounding(copy, allowed_numbers(body))
        return {
            "headlines": headlines,
            "primary_texts": texts,
            "cta": str(data.get("cta", "")).strip() or "LEARN_MORE",
            "audience_messaging": str(data.get("audience_messaging", "")).strip(),
            "concepts": [str(c).strip() for c in data.get("concepts", [])][:3],
            "image_prompts": [str(p).strip() for p in data.get("image_prompts", [])][:3],
            "unverified_numbers": check.unsupported,
            "status": "draft",
            "provider": result.provider,
            "model": result.model,
        }
