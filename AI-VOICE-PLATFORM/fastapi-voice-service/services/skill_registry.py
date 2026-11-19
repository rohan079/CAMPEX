"""Bundled and user-uploaded campaign assistant skills (SKILL.md)."""

from __future__ import annotations

import hashlib
import logging
import re
import uuid
from dataclasses import dataclass
from pathlib import Path

logger = logging.getLogger(__name__)

FRONTMATTER_RE = re.compile(r"^---\s*\n(.*?)\n---\s*\n(.*)$", re.DOTALL)
RYZE_SECTION_RE = re.compile(
    r"(?is)\n##\s*Data access\s*\(Ryze MCP\).*?(?=\n##\s|\Z)",
)
MAX_SKILL_CHARS = 80_000
MAX_UPLOADS_PER_OWNER = 40

# Prefer Meta / creative skills in the picker; the rest remain available by id.
FEATURED_SKILL_IDS = frozenset({
    "meta-ads-audit",
    "ad-copy-variant-generator",
    "creative-fatigue-detection",
    "competitor-creative-analysis",
    "wasted-spend-finder",
    "budget-scenario-planner",
    "pacing-monitor",
    "client-report-narratives",
    "campaign-naming-convention-builder",
    "ab-test-setup-and-analysis",
    "frequency-cap-recommendations",
    "roas-forecasting",
    "anomaly-detection",
    "account-structure-review",
})


@dataclass(frozen=True)
class SkillSummary:
    id: str
    name: str
    description: str
    source: str  # bundled | uploaded
    featured: bool


@dataclass(frozen=True)
class SkillDocument:
    id: str
    name: str
    description: str
    source: str
    body: str


def _owner_dir_name(owner_email: str) -> str:
    digest = hashlib.sha256(owner_email.strip().lower().encode("utf-8")).hexdigest()[:24]
    return digest


def _parse_frontmatter(raw: str) -> tuple[dict[str, str], str]:
    match = FRONTMATTER_RE.match(raw.strip())
    meta: dict[str, str] = {}
    body = raw
    if match:
        for line in match.group(1).splitlines():
            if ":" not in line:
                continue
            key, value = line.split(":", 1)
            meta[key.strip().lower()] = value.strip().strip("\"'")
        body = match.group(2)
    return meta, body


def sanitize_skill_body(body: str) -> str:
    """Drop third-party connector setup so the model uses Campex Meta tools only."""
    cleaned = RYZE_SECTION_RE.sub("\n", body)
    cleaned = re.sub(r"(?is)https?://connector\.get-ryze\.ai\S*", "", cleaned)
    cleaned = re.sub(r"(?is)https?://www\.get-ryze\.ai\S*", "", cleaned)
    return cleaned.strip()


class SkillRegistry:
    def __init__(
        self,
        bundled_root: Path | None = None,
        uploads_root: Path | None = None,
        design_skill_path: Path | None = None,
    ):
        root = Path(__file__).resolve().parents[1]
        repo = root.parent
        self._bundled_root = bundled_root or (repo / "marketing-skills" / "skills")
        self._uploads_root = uploads_root or (root / "data" / "campaign_skills")
        self._design_skill_path = design_skill_path or (root / "prompts" / "ad_creative_design.txt")
        self._uploads_root.mkdir(parents=True, exist_ok=True)

    def design_skill_text(self) -> str:
        try:
            return self._design_skill_path.read_text(encoding="utf-8")
        except OSError:
            return (
                "When the user supplies ad images, analyze composition, hierarchy, contrast, "
                "and safe text placement. Propose headline, primary text, CTA, and layout notes. "
                "Never invent property facts."
            )

    def list_skills(self, owner_email: str | None = None) -> list[SkillSummary]:
        items: list[SkillSummary] = []
        if self._bundled_root.is_dir():
            for path in sorted(self._bundled_root.iterdir()):
                skill_file = path / "SKILL.md"
                if not path.is_dir() or not skill_file.is_file():
                    continue
                try:
                    meta, _ = _parse_frontmatter(skill_file.read_text(encoding="utf-8"))
                except OSError:
                    continue
                skill_id = path.name
                items.append(SkillSummary(
                    id=skill_id,
                    name=meta.get("name") or skill_id.replace("-", " ").title(),
                    description=meta.get("description") or "Marketing skill",
                    source="bundled",
                    featured=skill_id in FEATURED_SKILL_IDS,
                ))
        if owner_email:
            owner_dir = self._uploads_root / _owner_dir_name(owner_email)
            if owner_dir.is_dir():
                for path in sorted(owner_dir.iterdir()):
                    skill_file = path / "SKILL.md"
                    if not path.is_dir() or not skill_file.is_file():
                        continue
                    try:
                        meta, _ = _parse_frontmatter(skill_file.read_text(encoding="utf-8"))
                    except OSError:
                        continue
                    items.append(SkillSummary(
                        id=path.name,
                        name=meta.get("name") or path.name.replace("-", " ").title(),
                        description=meta.get("description") or "Uploaded skill",
                        source="uploaded",
                        featured=True,
                    ))
        items.sort(key=lambda item: (0 if item.featured else 1, item.name.lower()))
        return items

    def get_skill(self, skill_id: str, owner_email: str | None = None) -> SkillDocument | None:
        if not skill_id or ".." in skill_id or "/" in skill_id or "\\" in skill_id:
            return None
        candidates: list[tuple[str, Path]] = []
        bundled = self._bundled_root / skill_id / "SKILL.md"
        if bundled.is_file():
            candidates.append(("bundled", bundled))
        if owner_email:
            uploaded = self._uploads_root / _owner_dir_name(owner_email) / skill_id / "SKILL.md"
            if uploaded.is_file():
                candidates.append(("uploaded", uploaded))
        if not candidates:
            return None
        # Prefer uploaded overrides when ids collide.
        source, path = candidates[-1]
        try:
            raw = path.read_text(encoding="utf-8")
        except OSError:
            return None
        meta, body = _parse_frontmatter(raw)
        return SkillDocument(
            id=skill_id,
            name=meta.get("name") or skill_id.replace("-", " ").title(),
            description=meta.get("description") or "",
            source=source,
            body=sanitize_skill_body(body),
        )

    def save_upload(
        self,
        owner_email: str,
        markdown: str,
        *,
        name: str | None = None,
        description: str | None = None,
    ) -> SkillSummary:
        if not owner_email.strip():
            raise ValueError("Sign in required to upload skills")
        text = markdown.strip()
        if not text:
            raise ValueError("Skill file is empty")
        if len(text) > MAX_SKILL_CHARS:
            raise ValueError("Skill file is too large")
        meta, body = _parse_frontmatter(text)
        display_name = (name or meta.get("name") or "Custom skill").strip()[:120]
        display_description = (description or meta.get("description") or "Uploaded campaign skill").strip()[:400]
        owner_dir = self._uploads_root / _owner_dir_name(owner_email)
        owner_dir.mkdir(parents=True, exist_ok=True)
        existing = [path for path in owner_dir.iterdir() if path.is_dir()]
        if len(existing) >= MAX_UPLOADS_PER_OWNER:
            raise ValueError("Upload limit reached for this workspace")
        slug = re.sub(r"[^a-z0-9]+", "-", display_name.lower()).strip("-")[:40] or "skill"
        skill_id = f"custom-{slug}-{uuid.uuid4().hex[:8]}"
        target = owner_dir / skill_id
        target.mkdir(parents=True, exist_ok=False)
        front = (
            "---\n"
            f"name: {display_name}\n"
            f"description: {display_description}\n"
            "---\n\n"
        )
        (target / "SKILL.md").write_text(front + body.strip() + "\n", encoding="utf-8")
        logger.info("Uploaded campaign skill %s for owner digest", skill_id)
        return SkillSummary(
            id=skill_id,
            name=display_name,
            description=display_description,
            source="uploaded",
            featured=True,
        )

    def delete_upload(self, owner_email: str, skill_id: str) -> bool:
        if not skill_id.startswith("custom-") or ".." in skill_id or "/" in skill_id:
            return False
        target = self._uploads_root / _owner_dir_name(owner_email) / skill_id
        skill_file = target / "SKILL.md"
        if not skill_file.is_file():
            return False
        skill_file.unlink()
        try:
            target.rmdir()
        except OSError:
            pass
        return True
