#!/usr/bin/env python3
"""Generate annotated PDF guides from screenshots of the RIPDPI Android app.

Captures screenshots via ADB, annotates them with arrows/circles/brackets
using Pillow, and assembles an A4 PDF with fpdf2.

Usage:
    python3 scripts/guide/generate_guide.py \
        --spec scripts/guide/specs/user-guide.yaml \
        --output build/guide/ripdpi-user-guide.pdf
"""

from __future__ import annotations

import argparse
import math
import subprocess
import sys
import time
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path
from typing import Any

import yaml
from fpdf import FPDF
from PIL import Image, ImageDraw, ImageFont

# ---------------------------------------------------------------------------
# Dataclasses
# ---------------------------------------------------------------------------

AUTOMATION_PREFIX = "com.poyka.ripdpi.automation"
ACTIVITY = "com.poyka.ripdpi/.activities.MainActivity"
REMOTE_SCREENSHOT = "/sdcard/guide_screenshot.png"


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

    return GuideSpec(
        title=raw.get("title", "RIPDPI Guide"),
        pages=pages,
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
# Annotation renderer (Pillow)
# ---------------------------------------------------------------------------

ANNOTATION_COLOR = (255, 0, 0)  # Red
LABEL_BG = (0, 0, 0, 180)  # Semi-transparent black
LABEL_FG = (255, 255, 255)  # White


def _find_font(size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    candidates = [
        "/System/Library/Fonts/Helvetica.ttc",
        "/System/Library/Fonts/SFNSMono.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/TTF/DejaVuSans.ttf",
    ]
    for path in candidates:
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def _draw_label(
    img: Image.Image,
    draw: ImageDraw.ImageDraw,
    x: float,
    y: float,
    text: str,
    font: ImageFont.FreeTypeFont | ImageFont.ImageFont,
    padding: int = 6,
) -> None:
    if not text:
        return
    bbox = draw.textbbox((0, 0), text, font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    rx = int(x - padding)
    ry = int(y - th - padding * 2)
    # Keep label within image bounds
    rx = max(0, min(rx, img.width - tw - padding * 2))
    ry = max(0, ry)
    # Draw background
    overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    overlay_draw = ImageDraw.Draw(overlay)
    overlay_draw.rounded_rectangle(
        [rx, ry, rx + tw + padding * 2, ry + th + padding * 2],
        radius=4,
        fill=LABEL_BG,
    )
    img.paste(Image.alpha_composite(img.convert("RGBA"), overlay).convert("RGB"))
    # Redraw on composited image
    draw = ImageDraw.Draw(img)
    draw.text((rx + padding, ry + padding), text, fill=LABEL_FG, font=font)


def _draw_arrowhead(
    draw: ImageDraw.ImageDraw,
    x0: float,
    y0: float,
    x1: float,
    y1: float,
    size: float,
) -> None:
    angle = math.atan2(y1 - y0, x1 - x0)
    spread = math.pi / 6  # 30 degrees
    points = [
        (x1, y1),
        (x1 - size * math.cos(angle - spread), y1 - size * math.sin(angle - spread)),
        (x1 - size * math.cos(angle + spread), y1 - size * math.sin(angle + spread)),
    ]
    draw.polygon(points, fill=ANNOTATION_COLOR)


def _draw_arrow(
    img: Image.Image,
    draw: ImageDraw.ImageDraw,
    ann: Annotation,
    line_w: int,
    font: ImageFont.FreeTypeFont | ImageFont.ImageFont,
) -> None:
    w, h = img.size
    x0, y0 = ann.from_pt[0] * w, ann.from_pt[1] * h
    x1, y1 = ann.to_pt[0] * w, ann.to_pt[1] * h
    draw.line([(x0, y0), (x1, y1)], fill=ANNOTATION_COLOR, width=line_w)
    _draw_arrowhead(draw, x0, y0, x1, y1, size=line_w * 4)
    _draw_label(img, draw, x0, y0, ann.label, font)


def _draw_circle(
    img: Image.Image,
    draw: ImageDraw.ImageDraw,
    ann: Annotation,
    line_w: int,
    font: ImageFont.FreeTypeFont | ImageFont.ImageFont,
) -> None:
    w, h = img.size
    cx, cy = ann.center[0] * w, ann.center[1] * h
    r = ann.radius * min(w, h)
    draw.ellipse(
        [cx - r, cy - r, cx + r, cy + r],
        outline=ANNOTATION_COLOR,
        width=line_w,
    )
    _draw_label(img, draw, cx - r, cy - r, ann.label, font)


def _draw_bracket(
    img: Image.Image,
    draw: ImageDraw.ImageDraw,
    ann: Annotation,
    line_w: int,
    font: ImageFont.FreeTypeFont | ImageFont.ImageFont,
) -> None:
    w, h = img.size
    y_top = ann.y_range[0] * h
    y_bot = ann.y_range[1] * h
    tick = 20
    if ann.side == "right":
        x = w - 30
        draw.line([(x, y_top), (x, y_bot)], fill=ANNOTATION_COLOR, width=line_w)
        draw.line([(x - tick, y_top), (x, y_top)], fill=ANNOTATION_COLOR, width=line_w)
        draw.line([(x - tick, y_bot), (x, y_bot)], fill=ANNOTATION_COLOR, width=line_w)
        label_x = x - tick - 10
    else:
        x = 30
        draw.line([(x, y_top), (x, y_bot)], fill=ANNOTATION_COLOR, width=line_w)
        draw.line([(x, y_top), (x + tick, y_top)], fill=ANNOTATION_COLOR, width=line_w)
        draw.line([(x, y_bot), (x + tick, y_bot)], fill=ANNOTATION_COLOR, width=line_w)
        label_x = x + tick + 10
    label_y = (y_top + y_bot) / 2
    _draw_label(img, draw, label_x, label_y, ann.label, font)


def annotate_image(img_path: Path, annotations: list[Annotation], output_path: Path) -> Path:
    img = Image.open(img_path).convert("RGB")
    draw = ImageDraw.Draw(img)
    w = img.size[0]
    line_w = max(4, w // 180)
    font_size = max(16, w // 40)
    font = _find_font(font_size)

    for ann in annotations:
        if ann.type == "arrow":
            _draw_arrow(img, draw, ann, line_w, font)
        elif ann.type == "circle":
            _draw_circle(img, draw, ann, line_w, font)
        elif ann.type == "bracket":
            _draw_bracket(img, draw, ann, line_w, font)
        # Re-acquire draw after label compositing
        draw = ImageDraw.Draw(img)

    img.save(output_path)
    return output_path


# ---------------------------------------------------------------------------
# PDF builder (fpdf2)
# ---------------------------------------------------------------------------


class GuidePDF(FPDF):
    def footer(self) -> None:
        self.set_y(-15)
        self.set_font("Helvetica", "I", 8)
        self.cell(0, 10, f"Page {self.page_no()}/{{nb}}", align="C")


def build_pdf(
    spec: GuideSpec,
    annotated_pages: list[tuple[PageSpec, Path]],
    output_path: Path,
) -> Path:
    pdf = GuidePDF(orientation="P", unit="mm", format="A4")
    pdf.set_auto_page_break(auto=True, margin=20)
    pdf.alias_nb_pages()

    # Title page
    pdf.add_page()
    pdf.set_font("Helvetica", "B", 28)
    pdf.ln(60)
    pdf.cell(0, 15, spec.title, ln=True, align="C")
    pdf.set_font("Helvetica", "", 12)
    pdf.ln(10)
    pdf.cell(0, 10, f"Generated {date.today().isoformat()}", ln=True, align="C")

    # Content pages
    for page, img_path in annotated_pages:
        pdf.add_page()
        pdf.set_font("Helvetica", "B", 16)
        pdf.cell(0, 10, page.title, ln=True)
        pdf.ln(3)

        # Image: scale to page width with margins (170mm effective on A4)
        img = Image.open(img_path)
        img_w, img_h = img.size
        display_w = 170  # mm
        display_h = display_w * (img_h / img_w)
        # Cap height to leave room for description
        max_img_h = 200  # mm
        if display_h > max_img_h:
            display_h = max_img_h
            display_w = display_h * (img_w / img_h)

        pdf.image(str(img_path), x=20, w=display_w, h=display_h)
        pdf.ln(5)

        if page.description:
            pdf.set_font("Helvetica", "", 11)
            pdf.multi_cell(0, 6, page.description)

    output_path.parent.mkdir(parents=True, exist_ok=True)
    pdf.output(str(output_path))
    return output_path


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
        help="Skip screenshot capture, re-annotate from cached screenshots",
    )
    parser.add_argument(
        "--pages",
        type=str,
        default=None,
        help="Comma-separated list of page IDs to include (default: all)",
    )
    args = parser.parse_args()

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

    build_dir = args.output.parent
    screenshots_dir = build_dir / "screenshots"
    annotated_dir = build_dir / "annotated"
    screenshots_dir.mkdir(parents=True, exist_ok=True)
    annotated_dir.mkdir(parents=True, exist_ok=True)

    # Phase 1: Capture screenshots
    if not args.skip_capture:
        # Verify ADB is available
        try:
            result = subprocess.run(
                ["adb", "devices"], capture_output=True, text=True, check=True,
            )
            lines = [l for l in result.stdout.strip().split("\n")[1:] if l.strip()]
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

    # Phase 2: Annotate screenshots
    annotated_pages: list[tuple[PageSpec, Path]] = []
    for page in spec.pages:
        screenshot = screenshots_dir / f"{page.id}.png"
        if not screenshot.exists():
            print(f"[{page.id}] WARNING: Screenshot not found at {screenshot}, skipping")
            continue

        annotated = annotated_dir / f"{page.id}.png"
        if page.annotations:
            print(f"[{page.id}] Annotating ({len(page.annotations)} annotations)...")
            annotate_image(screenshot, page.annotations, annotated)
        else:
            # No annotations -- copy original
            Image.open(screenshot).save(annotated)
            print(f"[{page.id}] No annotations, using original")
        annotated_pages.append((page, annotated))

    if not annotated_pages:
        print("No pages to include in PDF.")
        sys.exit(1)

    # Phase 3: Build PDF
    print(f"Building PDF ({len(annotated_pages)} pages)...")
    build_pdf(spec, annotated_pages, args.output)
    print(f"PDF saved to: {args.output}")


if __name__ == "__main__":
    main()
