#!/usr/bin/env python3
"""
tools/migrate_tasks.py

One-shot migration of RIPDPI docs/tasks/ monolithic bucket files into
per-task Obsidian notes under docs/tasks/issues/<slug>.md.

Usage:
    python tools/migrate_tasks.py --dry-run   # print report, write no files
    python tools/migrate_tasks.py --apply     # write all output files
"""

from __future__ import annotations

import argparse
import re
import sys
import unicodedata
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
TASKS_DIR = REPO_ROOT / "docs" / "tasks"
ISSUES_DIR = TASKS_DIR / "issues"
REPORT_PATH = REPO_ROOT / "migration-report.md"
WARNINGS_PATH = REPO_ROOT / "migration-warnings.md"

BUCKET_FILES = ["epics.md", "active.md", "blocked.md", "backlog.md"]

PRIORITY_GLYPH_MAP = {
    "\U0001f53a": "critical",  # 🔺
    "⏫": "high",          # ⏫
    "\U0001f53c": "medium",    # 🔼
    "\U0001f53d": "low",       # 🔽
}

AREA_REMAP: dict[str, str] = {
    "advanced-routing-rules-and": "routing",
    "amneziawg-outbound-support": "outbound",
    "android": "android",
    "boot-autostart-and-session": "service",
    "ci": "ci",
    "cloudflare-publish-hardening": "relay",
    "composable-transport-layer-parity": "transport",
    "control-plane-hardening": "engine",
    "direct-mode-diagnostic-state": "diagnostics",
    "direct-mode-transport-policy": "diagnostics",
    "dns": "dns",
    "encrypted-dns-and-https": "dns",
    "epic": "epic",
    "extended-outbound-protocol-support": "outbound",
    "fail-closed-android-vpn": "vpn",
    "localization-expansion": "ui",
    "native-hotspot-decomposition": "service",
    "nekobox-subscription-and-profile": "outbound",
    "orchestration-test-posture": "testing",
    "owned-stack-mode-with": "diagnostics",
    "privacy-preserving-strategy-learner": "service",
    "qr-code-and-clipboard": "ui",
    "remove-cloudflare-from-critical": "relay",
    "runtime-lifecycle-and-supervisors": "service",
    "rust-native": "rust-native",
    "semantic-tls-first-flight": "transport",
    "settings-backup-and-restore": "data",
    "system-http-proxy-service": "proxy",
    "testing": "testing",
    "vpn-fleet-testing-matrix": "testing",
    "xray-vpn-client-mode": "outbound",
}

# Old-format section headers that convert to ## headings (kept in body)
BODY_SECTION_HEADERS = {
    "Objective", "Context", "Acceptance criteria", "Required verification",
    "Definition of done", "Summary", "Plan reference", "Current status",
    "Audit citation", "Implementation note", "Blocker", "Stop gate", "Links",
    "Goal", "Why now", "Key decisions", "Scope", "Ship definition",
    "Child tasks", "Dependencies", "Risks / open questions",
    "Risks", "Background", "Risk",
}

# Old-format headers that are stripped (content moved to frontmatter or dropped)
STRIP_SECTION_HEADERS = {"Owner", "Subsystem", "Required reviewers"}


@dataclass
class TaskRecord:
    title: str
    raw_area: str
    area: str
    status: str
    priority: str
    poy_id: str
    source_file: str
    is_epic: bool = False

    # Extracted from body
    owner: str = ""
    created: str = "2026-05-04"
    updated: str = "2026-05-04"
    parent_poy: str = ""
    parent_slug: str = ""
    blocks_poy: list[str] = field(default_factory=list)
    blocked_by_poy: list[str] = field(default_factory=list)
    blocks_slugs: list[str] = field(default_factory=list)
    blocked_by_slugs: list[str] = field(default_factory=list)

    # Cleaned body (list of strings, ready to join with \n)
    clean_body: list[str] = field(default_factory=list)

    # Generated
    slug: str = ""


def slugify(text: str, prefix: str = "", max_len: int = 76) -> str:
    # Strip "Epic - " or "Epic — " prefix (handled via prefix arg)
    text = re.sub(r"^Epic\s*[-–]\s*", "", text, flags=re.IGNORECASE)
    # Normalize unicode
    text = unicodedata.normalize("NFKD", text)
    text = text.encode("ascii", "ignore").decode("ascii")
    # Lowercase, replace non-alphanumeric sequences with hyphens
    text = re.sub(r"[^a-z0-9]+", "-", text.lower())
    text = text.strip("-")
    # Apply prefix
    if prefix:
        text = f"{prefix}-{text}"
    # Truncate at word boundary
    if len(text) > max_len:
        truncated = text[:max_len]
        last_dash = truncated.rfind("-")
        text = truncated[:last_dash] if last_dash > 0 else truncated
    return text


def parse_task_line(line: str) -> dict | None:
    if not line.startswith("- [ ] #task "):
        return None
    m = re.search(
        r"#task (.+?) #repo/RIPDPI #area/([a-z0-9-]+) #status/([a-z]+)(?:\s+#blocked)?\s+([🔺⏫🔼🔽])",
        line,
    )
    if not m:
        return None

    title = m.group(1).strip()
    raw_area = m.group(2).strip()
    status = m.group(3).strip()
    glyph = m.group(4).strip()

    poy_m = re.search(r"\[paperclip:(POY-\d+)\]", line)
    poy_id = poy_m.group(1) if poy_m else ""

    return {
        "title": title,
        "raw_area": raw_area,
        "area": AREA_REMAP.get(raw_area, raw_area),
        "status": status,
        "priority": PRIORITY_GLYPH_MAP.get(glyph, "medium"),
        "poy_id": poy_id,
        "is_epic": bool(re.match(r"^Epic\s*[-–]", title, re.IGNORECASE)),
    }


def _extract_poy_list(text: str) -> list[str]:
    """Extract POY-N identifiers from a comma-separated string, ignoring trailing descriptions."""
    result = []
    for part in text.split(","):
        m = re.match(r"\s*(POY-\d+)", part.strip())
        if m:
            result.append(m.group(1))
    return result


def extract_body_fields(body_lines: list[str]) -> dict:
    """Extract structured metadata and produce a clean body for the note."""
    owner = ""
    created = "2026-05-04"
    updated = "2026-05-04"
    parent_poy = ""
    blocks_poy: list[str] = []
    blocked_by_poy: list[str] = []
    clean: list[str] = []

    in_obsidian_meta = False
    skip_section: str | None = None  # name of old-format section being skipped

    def deindent(raw: str) -> str:
        if raw.startswith("    "):
            return raw[4:]
        if raw.startswith("  "):
            return raw[2:]
        return raw

    i = 0
    while i < len(body_lines):
        raw = body_lines[i]
        line = deindent(raw).rstrip()

        # ── Metadata bullet lines ──────────────────────────────────────────
        # Paperclip + owner
        m = re.match(r"^- Paperclip: POY-\d+ · assigned to:\s*(.*)", line)
        if m:
            val = m.group(1).strip()
            if val.lower() not in ("unassigned", ""):
                owner = val
            i += 1
            continue

        # Parent (bullet form)
        m = re.match(r"^- Parent:\s*(POY-\d+)", line)
        if m and not parent_poy:
            parent_poy = m.group(1)
            i += 1
            continue

        # Blocks
        m = re.match(r"^- Blocks:\s*(.*)", line)
        if m:
            blocks_poy = _extract_poy_list(m.group(1))
            i += 1
            continue

        # Blocked by
        m = re.match(r"^- Blocked by:\s*(.*)", line)
        if m:
            blocked_by_poy = _extract_poy_list(m.group(1))
            i += 1
            continue

        # Obsidian migration comment
        if "<!-- migrated from obsidian -->" in line:
            in_obsidian_meta = True
            i += 1
            continue

        # Obsidian metadata bullets: - **key:** value
        if in_obsidian_meta:
            m_date = re.match(r"^- \*\*dateCreated:\*\*\s*(.+)", line)
            if m_date:
                created = m_date.group(1).strip()
                i += 1
                continue
            m_mod = re.match(r"^- \*\*dateModified:\*\*\s*(.+)", line)
            if m_mod:
                updated = m_mod.group(1).strip()
                i += 1
                continue
            if re.match(r"^- \*\*(area|tags|source|epic|owner):\*\*", line):
                i += 1
                continue
            # First non-meta line ends the obsidian block
            in_obsidian_meta = False
            # Fall through to process this line normally

        # ── Old-format section headers (e.g. "Objective:") ────────────────
        # These appear as a plain line like "Acceptance criteria:" (no leading #)
        old_hdr_m = re.match(r"^([A-Z][A-Za-z /]+):\s*$", line)
        if old_hdr_m:
            section = old_hdr_m.group(1)
            if section in BODY_SECTION_HEADERS:
                if skip_section:
                    skip_section = None
                clean.append(f"## {section}")
                i += 1
                continue
            elif section in STRIP_SECTION_HEADERS:
                # If it's Owner and we haven't found an owner yet,
                # peek at next non-empty line
                if section == "Owner" and not owner:
                    i += 1
                    while i < len(body_lines):
                        peek = deindent(body_lines[i]).rstrip()
                        if peek:
                            owner = peek.strip().rstrip(".")
                            i += 1
                            break
                        i += 1
                else:
                    # Skip this header and its content block
                    skip_section = section
                    i += 1
                continue
            # Unknown section header — keep it
            skip_section = None

        # Old-format "Parent: POY-N." as a standalone line
        if not parent_poy:
            m = re.match(r"^Parent:\s*(POY-\d+)", line)
            if m:
                parent_poy = m.group(1)
                i += 1
                continue

        # If we're inside a stripped section, skip until next section header
        if skip_section:
            if old_hdr_m or (line.startswith("##")):
                skip_section = None
                # Fall through — don't skip this line
            else:
                i += 1
                continue

        # ── Emit line ─────────────────────────────────────────────────────
        clean.append(line)
        i += 1

    # Strip leading/trailing blank lines
    while clean and not clean[0].strip():
        clean.pop(0)
    while clean and not clean[-1].strip():
        clean.pop()

    return {
        "owner": owner,
        "created": created,
        "updated": updated,
        "parent_poy": parent_poy,
        "blocks_poy": blocks_poy,
        "blocked_by_poy": blocked_by_poy,
        "clean_body": clean,
    }


def parse_bucket_file(path: Path) -> list[tuple[str, list[str]]]:
    """
    Returns list of (task_header_line, body_lines) tuples.
    body_lines are raw (not deindented).
    """
    lines = path.read_text(encoding="utf-8").splitlines()
    tasks: list[tuple[str, list[str]]] = []
    current_header: str | None = None
    current_body: list[str] = []

    for line in lines:
        if line.startswith("- [ ] #task "):
            if current_header is not None:
                tasks.append((current_header, current_body))
            current_header = line
            current_body = []
        elif current_header is not None:
            current_body.append(line)

    if current_header is not None:
        tasks.append((current_header, current_body))

    return tasks


def dedupe_slug(base_slug: str, seen: set[str]) -> str:
    slug = base_slug
    counter = 2
    while slug in seen:
        slug = f"{base_slug}-{counter}"
        counter += 1
    seen.add(slug)
    return slug


def priority_glyph(priority: str) -> str:
    return {"critical": "🔺", "high": "⏫", "medium": "🔼", "low": "🔽"}.get(priority, "🔼")


def build_note(rec: TaskRecord) -> str:
    glyph = priority_glyph(rec.priority)
    blocks_yaml = (
        "[" + ", ".join(rec.blocks_slugs) + "]"
        if rec.blocks_slugs
        else "[]"
    )
    blocked_by_yaml = (
        "[" + ", ".join(rec.blocked_by_slugs) + "]"
        if rec.blocked_by_slugs
        else "[]"
    )
    parent_yaml = rec.parent_slug if rec.parent_slug else "null"

    frontmatter = f"""\
---
title: {rec.title}
type: {"epic" if rec.is_epic else "task"}
status: {rec.status}
area: {rec.area}
priority: {rec.priority}
owner: {rec.owner if rec.owner else "unassigned"}
parent: {parent_yaml}
blocks: {blocks_yaml}
blocked_by: {blocked_by_yaml}
created: {rec.created}
updated: {rec.updated}
---"""

    # Canonical task line — epics keep "Epic — " prefix in the line but
    # area is the canonical area (usually "epic")
    canonical = (
        f"- [ ] #task {rec.title} #repo/RIPDPI "
        f"#area/{rec.area} #status/{rec.status} {glyph}"
    )

    body = "\n".join(rec.clean_body)

    parts = [frontmatter, "", canonical]
    if body:
        parts.extend(["", body])

    return "\n".join(parts) + "\n"


def build_query_view(title: str, status_filters: list[str], extra_filter: str = "") -> str:
    sections = []
    for status in status_filters:
        label = status.capitalize()
        filter_block = f"""\
## {label}

```tasks
not done
tag includes #task
tag includes #repo/RIPDPI
tag includes #status/{status}
sort by priority
group by tags
```"""
        sections.append(filter_block)

    body = "\n\n".join(sections)
    slug_hint = ", ".join(f"`#status/{s}`" for s in status_filters)
    view = f"""\
# {title}

> Live view of {slug_hint}. Edit per-task detail in `issues/<slug>.md`.

{body}
"""
    return view


def main() -> None:
    parser = argparse.ArgumentParser(description="Migrate RIPDPI task vault to per-task notes")
    parser.add_argument("--dry-run", action="store_true", help="Report only; write no files")
    parser.add_argument("--apply", action="store_true", help="Write all output files")
    args = parser.parse_args()

    if not args.dry_run and not args.apply:
        print("Specify --dry-run or --apply", file=sys.stderr)
        sys.exit(1)

    # ── Phase 1: parse all bucket files ────────────────────────────────────
    records: list[TaskRecord] = []
    seen_slugs: set[str] = set()

    for bucket_name in BUCKET_FILES:
        bucket_path = TASKS_DIR / bucket_name
        if not bucket_path.exists():
            print(f"WARNING: {bucket_name} not found, skipping", file=sys.stderr)
            continue

        raw_tasks = parse_bucket_file(bucket_path)
        for header, body_lines in raw_tasks:
            parsed = parse_task_line(header)
            if not parsed:
                print(f"WARNING: could not parse task line: {header[:80]}", file=sys.stderr)
                continue

            body_data = extract_body_fields(body_lines)

            prefix = "epic" if parsed["is_epic"] else ""
            base_slug = slugify(parsed["title"], prefix=prefix)
            slug = dedupe_slug(base_slug, seen_slugs)

            rec = TaskRecord(
                title=parsed["title"],
                raw_area=parsed["raw_area"],
                area=parsed["area"],
                status=parsed["status"],
                priority=parsed["priority"],
                poy_id=parsed["poy_id"],
                source_file=bucket_name,
                is_epic=parsed["is_epic"],
                owner=body_data["owner"],
                created=body_data["created"],
                updated=body_data["updated"],
                parent_poy=body_data["parent_poy"],
                blocks_poy=body_data["blocks_poy"],
                blocked_by_poy=body_data["blocked_by_poy"],
                clean_body=body_data["clean_body"],
                slug=slug,
            )
            records.append(rec)

    print(f"Parsed {len(records)} task records from {len(BUCKET_FILES)} bucket files.")

    # ── Phase 2: build lookup maps ──────────────────────────────────────────
    poy_to_slug: dict[str, str] = {}
    for rec in records:
        if rec.poy_id:
            poy_to_slug[rec.poy_id] = rec.slug

    epic_title_to_slug: dict[str, str] = {}
    for rec in records:
        if rec.is_epic:
            # Normalise title for matching: strip "Epic - " prefix
            title_norm = re.sub(r"^Epic\s*[-–]\s*", "", rec.title, flags=re.IGNORECASE).strip()
            epic_title_to_slug[title_norm.lower()] = rec.slug
            epic_title_to_slug[rec.title.lower()] = rec.slug

    # ── Phase 3: resolve cross-references ──────────────────────────────────
    warnings: list[str] = []

    for rec in records:
        # Resolve parent
        if rec.parent_poy:
            resolved = poy_to_slug.get(rec.parent_poy)
            if resolved:
                rec.parent_slug = resolved
            else:
                warnings.append(
                    f"{rec.slug}: unresolved parent {rec.parent_poy} "
                    f"(epic not in any bucket file)"
                )

        # Resolve blocks
        for poy in rec.blocks_poy:
            s = poy_to_slug.get(poy)
            if s:
                rec.blocks_slugs.append(s)
            else:
                rec.blocks_slugs.append(f"UNRESOLVED-{poy}")
                warnings.append(f"{rec.slug}: unresolved blocks reference {poy}")

        # Resolve blocked_by
        for poy in rec.blocked_by_poy:
            s = poy_to_slug.get(poy)
            if s:
                rec.blocked_by_slugs.append(s)
            else:
                rec.blocked_by_slugs.append(f"UNRESOLVED-{poy}")
                warnings.append(f"{rec.slug}: unresolved blocked_by reference {poy}")

    # ── Phase 4: report ────────────────────────────────────────────────────
    status_counts: dict[str, int] = defaultdict(int)
    area_counts: dict[str, int] = defaultdict(int)
    source_counts: dict[str, int] = defaultdict(int)
    epic_count = 0
    task_count = 0

    for rec in records:
        status_counts[rec.status] += 1
        area_counts[rec.area] += 1
        source_counts[rec.source_file] += 1
        if rec.is_epic:
            epic_count += 1
        else:
            task_count += 1

    report_lines = [
        "# Migration Report — RIPDPI task vault",
        "",
        f"Total records: {len(records)} ({task_count} tasks, {epic_count} epics)",
        "",
        "## By source file",
        "",
    ]
    for src, n in sorted(source_counts.items()):
        report_lines.append(f"- `{src}`: {n}")

    report_lines += ["", "## By status", ""]
    for status, n in sorted(status_counts.items(), key=lambda x: -x[1]):
        report_lines.append(f"- `{status}`: {n}")

    report_lines += ["", "## By canonical area", ""]
    for area, n in sorted(area_counts.items(), key=lambda x: -x[1]):
        report_lines.append(f"- `{area}`: {n}")

    report_lines += ["", "## Warnings", ""]
    if warnings:
        for w in warnings:
            report_lines.append(f"- {w}")
    else:
        report_lines.append("None.")

    report_text = "\n".join(report_lines) + "\n"
    print(report_text)

    # ── Phase 5: write (if --apply) ────────────────────────────────────────
    if args.dry_run:
        print("Dry-run complete. Use --apply to write files.")
        return

    # Create issues/ directory
    ISSUES_DIR.mkdir(parents=True, exist_ok=True)

    # Write per-task notes
    written = 0
    for rec in records:
        note_path = ISSUES_DIR / f"{rec.slug}.md"
        note_path.write_text(build_note(rec), encoding="utf-8")
        written += 1

    print(f"Wrote {written} notes to {ISSUES_DIR}")

    # Rewrite bucket files to query-only views
    active_view = build_query_view("Active — RIPDPI", ["doing", "review"])
    backlog_view = build_query_view("Backlog — RIPDPI", ["backlog"])
    blocked_view = build_query_view("Blocked — RIPDPI", ["blocked"])
    epics_view = """\
# Epics — RIPDPI

> Strategic epics. Child issues live in `issues/epic-*.md`. Edit per-epic detail there.

## All epics

```tasks
not done
tag includes #task
tag includes #repo/RIPDPI
tag includes #area/epic
sort by priority
group by tags
```
"""

    (TASKS_DIR / "active.md").write_text(active_view, encoding="utf-8")
    (TASKS_DIR / "backlog.md").write_text(backlog_view, encoding="utf-8")
    (TASKS_DIR / "blocked.md").write_text(blocked_view, encoding="utf-8")
    (TASKS_DIR / "epics.md").write_text(epics_view, encoding="utf-8")
    print("Rewrote active.md, backlog.md, blocked.md, epics.md to query-only views.")

    # Write migration report and warnings
    REPORT_PATH.write_text(report_text, encoding="utf-8")
    if warnings:
        warn_text = "# Migration Warnings\n\n" + "\n".join(f"- {w}" for w in warnings) + "\n"
        WARNINGS_PATH.write_text(warn_text, encoding="utf-8")
        print(f"Wrote {len(warnings)} warnings to {WARNINGS_PATH}")

    print("Migration complete.")


if __name__ == "__main__":
    main()
