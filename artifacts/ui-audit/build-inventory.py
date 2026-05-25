#!/usr/bin/env python3
"""Build Phase-1 UI inventory for the RIPDPI UI/UX audit.

Walks the :app source tree and emits a JSON record describing every
@Preview composable, every NavHost destination, every Glance widget, and
the design-system token surface — the things the audit must cover.

Output: artifacts/ui-audit/inventory.json
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
APP_SRC = REPO / "app/src/main/kotlin"

PREVIEW_ANNOT = re.compile(
    r"^[\s]*@(Preview|PreviewLightDark|PreviewFontScale|PreviewScreenSizes|"
    r"PreviewDynamicColors|RipDpiPreview)\b"
)
FUN_DECL = re.compile(r"^[\s]*(private\s+|internal\s+|public\s+)?fun\s+([A-Za-z0-9_]+)\s*\(")
COMPOSABLE_ANNOT = re.compile(r"^[\s]*@Composable\b")
NAV_DEST = re.compile(r"composable\(\s*route\s*=\s*\"([^\"]+)\"")
COMPOSABLE_FUN = re.compile(r"^[\s]*@Composable[\s\S]*?fun\s+([A-Za-z0-9_]+)\s*\(")


def collect_previews() -> list[dict]:
    """For each .kt file, find every @Preview-decorated function name.

    Walks lines; when a Preview annotation is seen we set a flag, then keep
    skipping subsequent annotation lines until we hit `fun NAME(...)` — that
    name is the preview function. One file may have many."""
    previews: list[dict] = []
    for kt in sorted(APP_SRC.rglob("*.kt")):
        try:
            text = kt.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        lines = text.splitlines()
        i = 0
        while i < len(lines):
            preview_match = PREVIEW_ANNOT.match(lines[i])
            if preview_match:
                j = i + 1
                guard = 0
                while j < len(lines) and guard < 25:
                    if lines[j].lstrip().startswith("@") or not lines[j].strip():
                        j += 1
                        guard += 1
                        continue
                    m = FUN_DECL.match(lines[j])
                    if m:
                        previews.append({
                            "file": str(kt.relative_to(REPO)),
                            "function": m.group(2),
                            "annotation": preview_match.group(1),
                            "line": i + 1,
                        })
                    break
                i = j
            else:
                i += 1
    return previews


def collect_nav_destinations() -> list[dict]:
    dests: list[dict] = []
    for kt in sorted(APP_SRC.rglob("*.kt")):
        try:
            text = kt.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        for m in NAV_DEST.finditer(text):
            dests.append({
                "file": str(kt.relative_to(REPO)),
                "route": m.group(1),
            })
    return dests


def collect_screen_composables() -> list[dict]:
    """Composables named *Screen or *Route — common RIPDPI screen pattern."""
    out: list[dict] = []
    name_pat = re.compile(r"^fun\s+([A-Z][A-Za-z0-9_]*(Screen|Route))\s*\(")
    for kt in sorted(APP_SRC.rglob("*.kt")):
        try:
            text = kt.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        lines = text.splitlines()
        for idx, line in enumerate(lines):
            m = name_pat.match(line.lstrip())
            if m:
                # Confirm preceding line within 6 has @Composable.
                window = "\n".join(lines[max(0, idx - 6): idx])
                if "@Composable" in window:
                    out.append({
                        "file": str(kt.relative_to(REPO)),
                        "name": m.group(1),
                        "line": idx + 1,
                    })
    # Dedupe by (file, name).
    seen = set()
    uniq = []
    for s in out:
        k = (s["file"], s["name"])
        if k in seen:
            continue
        seen.add(k)
        uniq.append(s)
    return uniq


def collect_widget_receivers() -> list[dict]:
    out: list[dict] = []
    receiver_pat = re.compile(r"^class\s+([A-Za-z0-9_]+WidgetReceiver)\b")
    for kt in sorted(APP_SRC.rglob("*.kt")):
        try:
            text = kt.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        for line in text.splitlines():
            m = receiver_pat.match(line.lstrip())
            if m:
                out.append({
                    "file": str(kt.relative_to(REPO)),
                    "class": m.group(1),
                })
    return out


def collect_design_token_files() -> list[str]:
    theme_dir = APP_SRC / "com/poyka/ripdpi/ui/theme"
    if not theme_dir.is_dir():
        return []
    return sorted(str(p.relative_to(REPO)) for p in theme_dir.rglob("*.kt"))


def main() -> int:
    out = {
        "_schema": "ripdpi-ui-audit-inventory-v1",
        "_generated_at": Path(__file__).stat().st_mtime,
        "previews": collect_previews(),
        "screen_composables": collect_screen_composables(),
        "nav_destinations": collect_nav_destinations(),
        "widget_receivers": collect_widget_receivers(),
        "theme_files": collect_design_token_files(),
    }
    out["summary"] = {
        "preview_functions": len(out["previews"]),
        "preview_source_files": len({p["file"] for p in out["previews"]}),
        "screen_composables": len(out["screen_composables"]),
        "nav_destinations": len(out["nav_destinations"]),
        "widget_receivers": len(out["widget_receivers"]),
        "theme_files": len(out["theme_files"]),
    }
    target = REPO / "artifacts/ui-audit/inventory.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(out, indent=2))
    print(json.dumps(out["summary"], indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
