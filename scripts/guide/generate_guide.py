#!/usr/bin/env python3
"""Generate annotated PDF guides from screenshots of the RIPDPI Android app.

Captures screenshots via ADB, then uses Typst to render an A4 PDF with
vector annotations (arrows, circles, brackets), table of contents, and
themed layout.

Usage:
    python3 scripts/guide/generate_guide.py \
        --spec scripts/guide/specs/user-guide.yaml \
        --output build/guide/ripdpi-user-guide.pdf
"""

from __future__ import annotations

import argparse
import json
import struct
import subprocess
import sys
import time
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path
from typing import Any

import yaml

# ---------------------------------------------------------------------------
# Dataclasses
# ---------------------------------------------------------------------------


def _rgb_to_hex(rgb: tuple[int, int, int]) -> str:
    return f"#{rgb[0]:02X}{rgb[1]:02X}{rgb[2]:02X}"


def _hex_to_rgb(hex_str: str) -> tuple[int, int, int]:
    h = hex_str.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))


@dataclass
class Theme:
    primary: tuple[int, int, int] = (27, 94, 32)       # #1B5E20 dark green
    accent: tuple[int, int, int] = (230, 81, 0)        # #E65100 deep orange
    text: tuple[int, int, int] = (33, 33, 33)          # #212121
    muted: tuple[int, int, int] = (117, 117, 117)      # #757575
    background: tuple[int, int, int] = (250, 250, 250)  # #FAFAFA


AUTOMATION_PREFIX = "com.poyka.ripdpi.automation"
ACTIVITY = "com.poyka.ripdpi/.activities.MainActivity"
REMOTE_SCREENSHOT = "/sdcard/guide_screenshot.png"
TEMPLATES_DIR = Path(__file__).parent / "templates"


@dataclass
class Annotation:
    type: str  # "arrow", "circle", "bracket"
    label: str = ""
    # arrow
    from_pt: tuple[float, float] = (0.0, 0.0)
    to_pt: tuple[float, float] = (0.0, 0.0)
    # circle
    center: tuple[float, float] = (0.0, 0.0)
    radius: float = 0.0
    # bracket
    y_range: tuple[float, float] = (0.0, 0.0)
    side: str = "right"


@dataclass
class PageSpec:
    id: str
    title: str
    route: str
    description: str = ""
    scroll_to: str | None = None
    annotations: list[Annotation] = field(default_factory=list)
    # Per-page preset overrides (None = use defaults)
    permission_preset: str | None = None
    service_preset: str | None = None
    data_preset: str | None = None
    settle_ms: int | None = None


@dataclass
class GuideSpec:
    title: str
    pages: list[PageSpec]
    subtitle: str = ""
    theme: Theme = field(default_factory=Theme)
    # Defaults
    permission_preset: str = "granted"
    service_preset: str = "idle"
    data_preset: str = "settings_ready"
    settle_ms: int = 1500


def _parse_annotation(raw: dict[str, Any]) -> Annotation:
    ann_type = raw["type"]
    label = raw.get("label", "")
    if ann_type == "arrow":
        return Annotation(
            type="arrow",
            label=label,
            from_pt=tuple(raw["from"]),
            to_pt=tuple(raw["to"]),
        )
    if ann_type == "circle":
        return Annotation(
            type="circle",
            label=label,
            center=tuple(raw["center"]),
            radius=raw.get("radius", 0.05),
        )
    if ann_type == "bracket":
        return Annotation(
            type="bracket",
            label=label,
            y_range=tuple(raw["y_range"]),
            side=raw.get("side", "right"),
        )
    raise ValueError(f"Unknown annotation type: {ann_type}")


def load_spec(path: Path) -> GuideSpec:
    with open(path) as f:
        raw = yaml.safe_load(f)

    defaults = raw.get("defaults", {})
    pages: list[PageSpec] = []
    for p in raw["pages"]:
        annotations = [_parse_annotation(a) for a in p.get("annotations", [])]
        pages.append(
            PageSpec(
                id=p["id"],
                title=p["title"],
                route=p["route"],
                description=p.get("description", "").strip(),
                scroll_to=p.get("scroll_to"),
                annotations=annotations,
                permission_preset=p.get("permission_preset"),
                service_preset=p.get("service_preset"),
                data_preset=p.get("data_preset"),
                settle_ms=p.get("settle_ms"),
            )
        )

    theme_raw = raw.get("theme", {})
    theme = Theme()
    for key in ("primary", "accent", "text", "muted", "background"):
        if key in theme_raw:
            setattr(theme, key, _hex_to_rgb(theme_raw[key]))

    return GuideSpec(
        title=raw.get("title", "RIPDPI Guide"),
        pages=pages,
        subtitle=raw.get("subtitle", ""),
        theme=theme,
        permission_preset=defaults.get("permission_preset", "granted"),
        service_preset=defaults.get("service_preset", "idle"),
        data_preset=defaults.get("data_preset", "settings_ready"),
        settle_ms=defaults.get("settle_ms", 1500),
    )


# ---------------------------------------------------------------------------
# ADB layer
# ---------------------------------------------------------------------------


def _run_adb(device: str | None, args: list[str], **kwargs: Any) -> subprocess.CompletedProcess[str]:
    cmd = ["adb"]
    if device:
        cmd += ["-s", device]
    cmd += args
    return subprocess.run(cmd, capture_output=True, text=True, check=True, **kwargs)


def adb_launch_route(
    route: str,
    spec: GuideSpec,
    page: PageSpec,
    device: str | None,
) -> None:
    perm = page.permission_preset or spec.permission_preset
    svc = page.service_preset or spec.service_preset
    data = page.data_preset or spec.data_preset

    args = [
        "shell", "am", "start", "-n", ACTIVITY,
        "--ez", f"{AUTOMATION_PREFIX}.ENABLED", "true",
        "--ez", f"{AUTOMATION_PREFIX}.RESET_STATE", "true",
        "--ez", f"{AUTOMATION_PREFIX}.DISABLE_MOTION", "true",
        "--es", f"{AUTOMATION_PREFIX}.START_ROUTE", route,
        "--es", f"{AUTOMATION_PREFIX}.PERMISSION_PRESET", perm,
        "--es", f"{AUTOMATION_PREFIX}.SERVICE_PRESET", svc,
        "--es", f"{AUTOMATION_PREFIX}.DATA_PRESET", data,
    ]
    _run_adb(device, args)


def adb_screenshot(output_path: Path, device: str | None) -> Path:
    _run_adb(device, ["shell", "screencap", "-p", REMOTE_SCREENSHOT])
    _run_adb(device, ["pull", REMOTE_SCREENSHOT, str(output_path)])
    _run_adb(device, ["shell", "rm", REMOTE_SCREENSHOT])
    return output_path


def adb_scroll_to(element_id: str, device: str | None, max_swipes: int = 10) -> bool:
    for _ in range(max_swipes):
        result = _run_adb(device, ["shell", "uiautomator", "dump", "/dev/stdout"])
        if element_id in result.stdout:
            return True
        _run_adb(device, ["shell", "input", "swipe", "540", "1600", "540", "800", "300"])
        time.sleep(0.3)
    return False


# ---------------------------------------------------------------------------
# PNG dimensions (no PIL needed)
# ---------------------------------------------------------------------------


def _png_dimensions(path: Path) -> tuple[int, int]:
    """Read width and height from a PNG file's IHDR chunk."""
    with open(path, "rb") as f:
        header = f.read(24)
    if header[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"Not a PNG file: {path}")
    width, height = struct.unpack(">II", header[16:24])
    return width, height


# ---------------------------------------------------------------------------
# JSON data writer (bridge between YAML spec and Typst)
# ---------------------------------------------------------------------------


def _annotation_to_dict(ann: Annotation) -> dict[str, Any]:
    d: dict[str, Any] = {"type": ann.type, "label": ann.label}
    if ann.type == "arrow":
        d["from"] = list(ann.from_pt)
        d["to"] = list(ann.to_pt)
    elif ann.type == "circle":
        d["center"] = list(ann.center)
        d["radius"] = ann.radius
    elif ann.type == "bracket":
        d["y_range"] = list(ann.y_range)
        d["side"] = ann.side
    return d


def write_guide_data(
    spec: GuideSpec,
    screenshots_dir: Path,
    output_json: Path,
    root: Path,
) -> list[str]:
    """Write guide-data.json for Typst. Returns list of missing page IDs."""
    missing: list[str] = []
    pages_data: list[dict[str, Any]] = []

    for page in spec.pages:
        screenshot = screenshots_dir / f"{page.id}.png"
        if not screenshot.exists():
            missing.append(page.id)
            continue

        px_w, px_h = _png_dimensions(screenshot)
        # Path relative to Typst --root, prefixed with / for root resolution
        rel_screenshot = "/" + str(screenshot.relative_to(root))

        pages_data.append({
            "id": page.id,
            "title": page.title,
            "description": page.description,
            "screenshot": rel_screenshot,
            "pixel_width": px_w,
            "pixel_height": px_h,
            "annotations": [_annotation_to_dict(a) for a in page.annotations],
        })

    data = {
        "title": spec.title,
        "subtitle": spec.subtitle,
        "generated_date": date.today().isoformat(),
        "theme": {
            "primary": _rgb_to_hex(spec.theme.primary),
            "accent": _rgb_to_hex(spec.theme.accent),
            "text": _rgb_to_hex(spec.theme.text),
            "muted": _rgb_to_hex(spec.theme.muted),
            "background": _rgb_to_hex(spec.theme.background),
        },
        "pages": pages_data,
    }

    output_json.parent.mkdir(parents=True, exist_ok=True)
    with open(output_json, "w") as f:
        json.dump(data, f, indent=2)

    return missing


# ---------------------------------------------------------------------------
# Typst compilation
# ---------------------------------------------------------------------------


def compile_typst(data_json: Path, output_pdf: Path, root: Path) -> None:
    """Invoke typst compile to render the guide PDF."""
    template = TEMPLATES_DIR / "guide.typ"
    # Typst resolves paths from --root when prefixed with /
    rel_data = "/" + str(data_json.relative_to(root))

    cmd = [
        "typst", "compile",
        "--root", str(root),
        "--input", f"data-path={rel_data}",
        str(template),
        str(output_pdf),
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"ERROR: Typst compilation failed:\n{result.stderr}", file=sys.stderr)
        sys.exit(1)


# ---------------------------------------------------------------------------
# Orchestrator
# ---------------------------------------------------------------------------


def capture_page(
    page: PageSpec,
    spec: GuideSpec,
    screenshots_dir: Path,
    device: str | None,
) -> Path:
    output = screenshots_dir / f"{page.id}.png"
    print(f"  Launching route: {page.route}")
    adb_launch_route(page.route, spec, page, device)
    settle = page.settle_ms or spec.settle_ms
    time.sleep(settle / 1000.0)

    if page.scroll_to:
        print(f"  Scrolling to: {page.scroll_to}")
        found = adb_scroll_to(page.scroll_to, device)
        if not found:
            print(f"  WARNING: Element '{page.scroll_to}' not found after scrolling")
        time.sleep(0.5)

    print(f"  Capturing screenshot -> {output.name}")
    adb_screenshot(output, device)
    return output


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Generate annotated PDF guide from RIPDPI app screenshots",
    )
    parser.add_argument(
        "--spec",
        type=Path,
        required=True,
        help="Path to the YAML guide spec file",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("build/guide/ripdpi-user-guide.pdf"),
        help="Output PDF path (default: build/guide/ripdpi-user-guide.pdf)",
    )
    parser.add_argument(
        "--device",
        type=str,
        default=None,
        help="ADB device serial (default: first available device)",
    )
    parser.add_argument(
        "--skip-capture",
        action="store_true",
        help="Skip screenshot capture, use cached screenshots",
    )
    parser.add_argument(
        "--pages",
        type=str,
        default=None,
        help="Comma-separated list of page IDs to include (default: all)",
    )
    args = parser.parse_args()

    # Verify typst is available
    try:
        subprocess.run(["typst", "--version"], capture_output=True, check=True)
    except FileNotFoundError:
        print("ERROR: typst not found. Install with: brew install typst")
        sys.exit(1)

    spec = load_spec(args.spec)
    print(f"Loaded spec: {spec.title} ({len(spec.pages)} pages)")

    # Filter pages if requested
    if args.pages:
        page_ids = set(args.pages.split(","))
        spec.pages = [p for p in spec.pages if p.id in page_ids]
        print(f"Filtered to {len(spec.pages)} pages: {[p.id for p in spec.pages]}")

    if not spec.pages:
        print("No pages to process.")
        sys.exit(1)

    # Resolve root for Typst (repo root, so both templates and build/ are accessible)
    root = Path(__file__).resolve().parent.parent.parent
    build_dir = args.output.resolve().parent
    screenshots_dir = build_dir / "screenshots"
    screenshots_dir.mkdir(parents=True, exist_ok=True)

    # Phase 1: Capture screenshots
    if not args.skip_capture:
        try:
            result = subprocess.run(
                ["adb", "devices"], capture_output=True, text=True, check=True,
            )
            lines = [ln for ln in result.stdout.strip().split("\n")[1:] if ln.strip()]
            if not lines:
                print("ERROR: No ADB devices found. Connect a device or start an emulator.")
                sys.exit(1)
            print(f"ADB devices: {len(lines)} connected")
        except FileNotFoundError:
            print("ERROR: adb not found. Install Android SDK platform-tools.")
            sys.exit(1)

        for page in spec.pages:
            print(f"[{page.id}] Capturing...")
            try:
                capture_page(page, spec, screenshots_dir, args.device)
            except subprocess.CalledProcessError as e:
                print(f"  ERROR: ADB command failed: {e.cmd}")
                print(f"  stderr: {e.stderr}")
                print(f"  Skipping page '{page.id}'")
    else:
        print("Skipping capture (using cached screenshots)")

    # Phase 2: Write JSON data for Typst
    data_json = build_dir / "guide-data.json"
    print("Writing guide data...")
    missing = write_guide_data(spec, screenshots_dir, data_json, root)
    for page_id in missing:
        print(f"[{page_id}] WARNING: Screenshot not found, skipping")

    # Check we have at least one page with a screenshot
    with open(data_json) as f:
        guide_data = json.load(f)
    if not guide_data["pages"]:
        print("No pages with screenshots to include in PDF.")
        sys.exit(1)

    # Phase 3: Compile PDF with Typst
    print(f"Compiling PDF ({len(guide_data['pages'])} pages)...")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    compile_typst(data_json, args.output, root)
    print(f"PDF saved to: {args.output}")


if __name__ == "__main__":
    main()
