"""Call prompts. The long texts live in .txt files so they can be edited without touching code."""

from __future__ import annotations

import re
from functools import lru_cache
from pathlib import Path

SUPPORTED_LANGUAGES = ("en", "te")

_DIR = Path(__file__).parent

_RELATIONSHIP = {
    ("en", False): "The person previously contacted us on WhatsApp and explicitly agreed to receive this call.",
    ("en", True): (
        "The person added their phone number on our ad and has not replied to our WhatsApp message."
    ),
    ("te", False): "ఈ వ్యక్తి WhatsAppలో మమ్మల్ని సంప్రదించి ఈ కాల్‌కు స్పష్టంగా అంగీకరించారు.",
    ("te", True): (
        "ఈ వ్యక్తి మా ప్రకటనలో తన ఫోన్ నంబర్ ఇచ్చారు, కానీ మా WhatsApp సందేశానికి ఇంకా స్పందించలేదు."
    ),
}

_AD_LINE = {
    "en": " Mention the ad {headline}.",
    "te": " ప్రకటన పేరు చెప్పండి: {headline}.",
}

_NO_OFFER = {
    "en": "No offer details configured. Ask about the customer's needs and do not invent details.",
    "te": "ఆఫర్ వివరాలు ఇవ్వలేదు. కస్టమర్ అవసరాల గురించి అడగండి, వివరాలు కల్పించవద్దు.",
}

_CONTROL_CHARS = re.compile(r"[\x00-\x1f\x7f]+")
_KB_CONTROL_CHARS = re.compile(r"[\x00-\x08\x0b-\x1f\x7f]+")


def normalize_language(code: str | None) -> str:
    lowered = (code or "").strip().lower()
    return lowered if lowered in SUPPORTED_LANGUAGES else "en"


def clean(text: str | None, limit: int) -> str:
    """Single-line, length-capped text. Names and ad copy come from the public internet."""
    collapsed = _CONTROL_CHARS.sub(" ", text or "")
    return " ".join(collapsed.split())[:limit]


def clean_knowledge_base(text: str | None, limit: int = 12000) -> str:
    """Keep curated facts readable while stripping controls and bounding prompt size."""
    value = _KB_CONTROL_CHARS.sub(" ", text or "")
    lines = [" ".join(line.split()) for line in value.splitlines()]
    return "\n".join(line for line in lines if line)[:limit]


@lru_cache(maxsize=None)
def _template(name: str) -> str:
    return (_DIR / name).read_text(encoding="utf-8")


def build_agent_instructions(
    *,
    language: str,
    first_name: str | None,
    business_name: str,
    business_description: str | None,
    ad_headline: str | None = None,
    ad_body: str | None = None,
    follow_up: bool = False,
    knowledge_base: str | None = None,
) -> str:
    language = normalize_language(language)
    business = clean(business_name, 120) or "our business"
    description = clean(business_description, 2000) or _NO_OFFER[language]
    name = clean(first_name, 80)
    headline = clean(ad_headline, 300)
    body = clean(ad_body, 2000)
    knowledge = clean_knowledge_base(knowledge_base)
    if not knowledge:
        knowledge = _NO_OFFER[language]

    ad_context = ""
    if headline or body:
        ad_context = _template(f"ad_context_{language}.txt").format(
            ad_headline=headline or business,
            ad_body=body or description,
        )

    if language == "te":
        who = f"మీరు {name}తో మాట్లాడుతున్నారు." if name else "మీరు ఒక కస్టమర్‌తో మాట్లాడుతున్నారు."
    else:
        who = name or "there"

    return _template(f"agent_{language}.txt").format(
        business_name=business,
        description=description,
        knowledge_base=knowledge,
        ad_context=ad_context,
        who=who,
        relationship=_RELATIONSHIP[(language, follow_up)],
    )


def build_greeting(*, language: str, business_name: str, ad_headline: str | None = None) -> str:
    language = normalize_language(language)
    headline = clean(ad_headline, 300)
    return _template(f"greeting_{language}.txt").format(
        business_name=clean(business_name, 120) or "our business",
        ad_line=_AD_LINE[language].format(headline=headline) if headline else "",
    ).strip()
