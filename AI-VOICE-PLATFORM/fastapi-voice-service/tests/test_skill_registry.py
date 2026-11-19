from pathlib import Path

from services.skill_registry import SkillRegistry, sanitize_skill_body


def test_sanitize_removes_ryze_connector_section():
    body = """# Skill

## What it does
Helps with ads.

## Data access (Ryze MCP)

Connect to https://connector.get-ryze.ai/mcp

## When to use
Always.
"""
    cleaned = sanitize_skill_body(body)
    assert "Ryze" not in cleaned
    assert "get-ryze" not in cleaned
    assert "Helps with ads." in cleaned
    assert "Always." in cleaned


def test_upload_list_and_delete(tmp_path: Path):
    bundled = tmp_path / "bundled" / "meta-ads-audit"
    bundled.mkdir(parents=True)
    (bundled / "SKILL.md").write_text(
        "---\nname: meta-ads-audit\ndescription: Audit Meta ads\n---\n\nDo an audit.\n",
        encoding="utf-8",
    )
    registry = SkillRegistry(bundled_root=tmp_path / "bundled", uploads_root=tmp_path / "uploads")
    listed = registry.list_skills("owner@example.com")
    assert any(item.id == "meta-ads-audit" for item in listed)

    saved = registry.save_upload(
        "owner@example.com",
        "---\nname: My Custom\ndescription: Custom playbook\n---\n\nUse careful claims.\n",
    )
    assert saved.source == "uploaded"
    assert saved.id.startswith("custom-")
    doc = registry.get_skill(saved.id, "owner@example.com")
    assert doc is not None
    assert "careful claims" in doc.body
    assert registry.delete_upload("owner@example.com", saved.id)
    assert registry.get_skill(saved.id, "owner@example.com") is None
