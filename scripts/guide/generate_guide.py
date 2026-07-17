#!/usr/bin/env python3
"""Generate annotated PDF guides from screenshots of the RIPDPI Android app.

Captures high-resolution screenshots via ADB from an emulator or physical
device, composites them into a Pixel device frame, then uses Typst to render
an A4 PDF with vector annotations, table of contents, and themed layout.

Usage:
    python3 scripts/guide/generate_guide.py \
        --spec scripts/guide/specs/user-guide.yaml \
        --output build/guide/ripdpi-user-guide.pdf

    # Auto-launch emulator:
    python3 scripts/guide/generate_guide.py \
        --spec scripts/guide/specs/user-guide.yaml \
        --emulator

    # Skip framing for quick iteration:
    python3 scripts/guide/generate_guide.py \
        --spec scripts/guide/specs/user-guide.yaml \
        --no-frame --skip-capture
"""

from __future__ import annotations

import argparse
import hashlib
import html
import json
import struct
import subprocess
import sys
import time
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path
from typing import Any
from xml.etree import ElementTree

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
TEMPLATES_DIR = Path(__file__).parent / "templates"

# Default AVD name for --emulator mode
GUIDE_AVD_NAME = "Pixel_9_Pro_XL"

# SDK skin for device frame compositing
SKIN_NAME = "pixel_9_pro_xl"
# Layout from SDK skin: screen placed at (57, 56) in a 1466x3101 frame
FRAME_SCREEN_OFFSET = (57, 56)
FRAME_SCREEN_SIZE = (1344, 2992)
CAPTURE_THEMES = ("dark", "light")
THEME_LABELS = {
    "dark": "Dark",
    "light": "Light",
}


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
    expected_root: str | None = None
    required_elements: list[str] = field(default_factory=list)
    flow_from: str | None = None
    flow_from_explicit: bool = False
    flow_label: str = ""
    state: str = ""
    annotations: list[Annotation] = field(default_factory=list)
    # Per-page preset overrides (None = use defaults)
    permission_preset: str | None = None
    service_preset: str | None = None
    data_preset: str | None = None
    settle_ms: int | None = None


@dataclass
class RouteExclusion:
    route: str
    prerequisite: str
    reason: str


@dataclass
class FlowSection:
    title: str
    subtitle: str
    page_ids: list[str]


@dataclass
class GuideSpec:
    title: str
    pages: list[PageSpec]
    subtitle: str = ""
    theme: Theme = field(default_factory=Theme)
    flow_title: str = "Current user flow"
    route_exclusions: list[RouteExclusion] = field(default_factory=list)
    flow_sections: list[FlowSection] = field(default_factory=list)
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
                expected_root=p.get("expected_root"),
                required_elements=list(p.get("required_elements", [])),
                flow_from=p.get("flow_from"),
                flow_from_explicit="flow_from" in p,
                flow_label=p.get("flow_label", ""),
                state=p.get("state", ""),
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

    route_exclusions = [
        RouteExclusion(
            route=item["route"].strip(),
            prerequisite=item["prerequisite"].strip(),
            reason=item["reason"].strip(),
        )
        for item in raw.get("route_contract", {}).get("exclusions", [])
    ]
    flow_sections = [
        FlowSection(
            title=item["title"].strip(),
            subtitle=item.get("subtitle", "").strip(),
            page_ids=list(item.get("pages", [])),
        )
        for item in raw.get("flow_sections", [])
    ]

    spec = GuideSpec(
        title=raw.get("title", "RIPDPI Guide"),
        pages=pages,
        subtitle=raw.get("subtitle", ""),
        theme=theme,
        flow_title=raw.get("flow_title", "Current user flow"),
        route_exclusions=route_exclusions,
        flow_sections=flow_sections,
        permission_preset=defaults.get("permission_preset", "granted"),
        service_preset=defaults.get("service_preset", "idle"),
        data_preset=defaults.get("data_preset", "settings_ready"),
        settle_ms=defaults.get("settle_ms", 1500),
    )
    validate_spec(spec)
    return spec


def _duplicates(values: list[str]) -> list[str]:
    seen: set[str] = set()
    duplicates: set[str] = set()
    for value in values:
        if value in seen:
            duplicates.add(value)
        seen.add(value)
    return sorted(duplicates)


def validate_spec(spec: GuideSpec) -> None:
    page_ids = [page.id for page in spec.pages]
    duplicate_page_ids = _duplicates(page_ids)
    if duplicate_page_ids:
        raise ValueError(f"Duplicate page IDs: {duplicate_page_ids}")

    page_id_set = set(page_ids)
    unknown_parents = sorted({
        page.flow_from
        for page in spec.pages
        if page.flow_from and page.flow_from not in page_id_set
    })
    if unknown_parents:
        raise ValueError(f"Unknown flow_from page IDs: {unknown_parents}")

    exclusion_routes = [item.route for item in spec.route_exclusions]
    duplicate_exclusions = _duplicates(exclusion_routes)
    if duplicate_exclusions:
        raise ValueError(f"Duplicate route exclusions: {duplicate_exclusions}")
    invalid_exclusions = [
        item.route
        for item in spec.route_exclusions
        if not item.route or not item.prerequisite or not item.reason
    ]
    if invalid_exclusions:
        raise ValueError(f"Route exclusions require route, prerequisite, and reason: {invalid_exclusions}")
    covered_routes = {page.route for page in spec.pages}
    overlap = sorted(covered_routes.intersection(exclusion_routes))
    if overlap:
        raise ValueError(f"Routes cannot be both captured and excluded: {overlap}")

    if spec.flow_sections:
        section_page_ids = [page_id for section in spec.flow_sections for page_id in section.page_ids]
        duplicate_section_ids = _duplicates(section_page_ids)
        missing_section_ids = sorted(page_id_set.difference(section_page_ids))
        unknown_section_ids = sorted(set(section_page_ids).difference(page_id_set))
        if duplicate_section_ids or missing_section_ids or unknown_section_ids:
            raise ValueError(
                "Flow sections must partition pages exactly: "
                f"duplicates={duplicate_section_ids}, missing={missing_section_ids}, "
                f"unknown={unknown_section_ids}",
            )


# ---------------------------------------------------------------------------
# ADB layer
# ---------------------------------------------------------------------------


def _adb_cmd(device: str | None) -> list[str]:
    cmd = ["adb"]
    if device:
        cmd += ["-s", device]
    return cmd


def _run_adb(device: str | None, args: list[str], **kwargs: Any) -> subprocess.CompletedProcess[str]:
    cmd = _adb_cmd(device) + args
    return subprocess.run(cmd, capture_output=True, text=True, check=True, **kwargs)


def _run_adb_bytes(device: str | None, args: list[str]) -> bytes:
    """Run an ADB command and return raw stdout bytes."""
    cmd = _adb_cmd(device) + args
    result = subprocess.run(cmd, capture_output=True, check=True)
    return result.stdout


def _run_adb_best_effort(device: str | None, args: list[str]) -> subprocess.CompletedProcess[str]:
    cmd = _adb_cmd(device) + args
    return subprocess.run(cmd, capture_output=True, text=True, check=False)


def grant_runtime_permissions(device: str | None) -> None:
    """Pre-grant runtime permissions that can otherwise cover captures with system dialogs."""
    package = "com.poyka.ripdpi"
    permissions = [
        "android.permission.CAMERA",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.ACCESS_COARSE_LOCATION",
    ]
    for permission in permissions:
        _run_adb_best_effort(device, ["shell", "pm", "grant", package, permission])
    appops = {
        "CAMERA": "allow",
        "POST_NOTIFICATION": "allow",
        "COARSE_LOCATION": "allow",
    }
    for op, mode in appops.items():
        _run_adb_best_effort(device, ["shell", "appops", "set", package, op, mode])


def dismiss_runtime_permission_dialogs(device: str | None, max_attempts: int = 3) -> None:
    """Drain Android permission dialogs that may already be visible on the device."""
    allow_texts = (
        "While using the app",
        "Only this time",
        "Allow",
        "Разрешить",
    )
    for _ in range(max_attempts):
        result = _run_adb_best_effort(device, ["exec-out", "uiautomator", "dump", "/dev/tty"])
        xml = _extract_xml(result.stdout)
        if not any(text in xml for text in allow_texts):
            return
        if "While using the app" in xml:
            _run_adb_best_effort(device, ["shell", "input", "tap", "540", "1160"])
        elif "Only this time" in xml:
            _run_adb_best_effort(device, ["shell", "input", "tap", "540", "1290"])
        else:
            _run_adb_best_effort(device, ["shell", "input", "tap", "540", "1160"])
        time.sleep(0.5)


def setup_demo_mode(device: str | None) -> None:
    """Enable Android demo mode for a clean, consistent status bar."""
    _run_adb(device, ["shell", "settings", "put", "global", "sysui_demo_allowed", "1"])
    demo = "com.android.systemui.demo"
    broadcasts: list[list[str]] = [
        # Clock: 12:00
        ["-e", "command", "clock", "-e", "hhmm", "1200"],
        # Battery: 100%, not charging
        ["-e", "command", "battery", "-e", "level", "100", "-e", "plugged", "false"],
        # WiFi: full signal
        ["-e", "command", "network", "-e", "wifi", "show", "-e", "level", "4"],
        # Mobile: hidden
        ["-e", "command", "network", "-e", "mobile", "show", "-e", "datatype", "none", "-e", "level", "4"],
        # No notifications
        ["-e", "command", "notifications", "-e", "visible", "false"],
        # Hide misc status icons
        ["-e", "command", "status", "-e", "volume", "hide", "-e", "alarm", "hide",
         "-e", "sync", "hide", "-e", "tty", "hide", "-e", "eri", "hide",
         "-e", "mute", "hide", "-e", "speakerphone", "hide"],
    ]
    for extra_args in broadcasts:
        _run_adb(device, ["shell", "am", "broadcast", "-a", demo] + extra_args)


def teardown_demo_mode(device: str | None) -> None:
    """Disable Android demo mode and restore normal status bar."""
    _run_adb(device, ["shell", "am", "broadcast",
                       "-a", "com.android.systemui.demo",
                       "-e", "command", "exit"])
    _run_adb(device, ["shell", "settings", "put", "global", "sysui_demo_allowed", "0"])


def adb_launch_route(
    route: str,
    spec: GuideSpec,
    page: PageSpec,
    device: str | None,
    theme: str | None = None,
) -> None:
    perm = page.permission_preset or spec.permission_preset
    svc = page.service_preset or spec.service_preset
    data = page.data_preset or spec.data_preset

    _run_adb(device, ["shell", "am", "force-stop", "com.poyka.ripdpi"])
    grant_runtime_permissions(device)
    dismiss_runtime_permission_dialogs(device)
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
    if theme:
        args += ["--es", f"{AUTOMATION_PREFIX}.THEME", theme]
    _run_adb(device, args)


def adb_screenshot(output_path: Path, device: str | None) -> Path:
    """Capture screenshot via exec-out (streaming, no temp file on device)."""
    png_bytes = _run_adb_bytes(device, ["exec-out", "screencap", "-p"])
    output_path.write_bytes(png_bytes)
    return output_path


def adb_dump_ui(output_path: Path, device: str | None) -> str:
    """Dump the current UiAutomator tree and return the XML content."""
    result = _run_adb(device, ["exec-out", "uiautomator", "dump", "/dev/tty"])
    xml = _extract_xml(result.stdout)
    output_path.write_text(xml, encoding="utf-8")
    return xml


def _extract_xml(raw: str) -> str:
    start = raw.find("<?xml")
    if start == -1:
        start = raw.find("<hierarchy")
    if start == -1:
        return raw.strip()
    xml = raw[start:].strip()
    end = xml.rfind("</hierarchy>")
    if end == -1:
        return xml
    return xml[: end + len("</hierarchy>")]


def wait_for_ui_tree(
    page: PageSpec,
    ui_dump_path: Path,
    device: str | None,
    timeout_ms: int,
) -> tuple[str, PageCaptureResult]:
    """Wait until UiAutomator exposes the expected page selectors."""
    deadline = time.monotonic() + (timeout_ms / 1000.0)
    last_xml = ""
    last_result: PageCaptureResult | None = None

    while True:
        last_xml = adb_dump_ui(ui_dump_path, device)
        last_result = analyze_ui_tree(page, last_xml, ui_dump_path)
        if last_result.reachable:
            return last_xml, last_result
        if time.monotonic() >= deadline:
            return last_xml, last_result
        time.sleep(0.25)


def screenshot_has_app_content(path: Path) -> bool:
    """Return false for early captures that contain only system bars and a blank app surface."""
    from PIL import Image

    with Image.open(path) as image:
        rgb = image.convert("RGB")
        width, height = rgb.size
        # Ignore status/navigation bars and focus on the app content area.
        crop = rgb.crop((0, int(height * 0.12), width, int(height * 0.90)))
        pixels = crop.getdata()
        non_dark = 0
        varied_colors: set[tuple[int, int, int]] = set()
        for red, green, blue in pixels:
            if max(red, green, blue) > 42:
                non_dark += 1
                if len(varied_colors) < 128:
                    varied_colors.add((red // 16, green // 16, blue // 16))
            if non_dark > 2500 and len(varied_colors) > 1:
                return True
        return False


def capture_screenshot_when_ready(
    output_path: Path,
    device: str | None,
    max_attempts: int = 8,
) -> bool:
    """Capture after Compose has drawn real app content."""
    for attempt in range(max_attempts):
        adb_screenshot(output_path, device)
        if screenshot_has_app_content(output_path):
            return True
        if attempt < max_attempts - 1:
            time.sleep(0.35)
    return False


def adb_scroll_to(element_id: str, device: str | None, max_swipes: int = 10) -> bool:
    for _ in range(max_swipes):
        result = _run_adb(device, ["shell", "uiautomator", "dump", "/dev/stdout"])
        if element_id in result.stdout:
            return True
        _run_adb(device, ["shell", "input", "swipe", "540", "1600", "540", "800", "300"])
        time.sleep(0.3)
    return False


# ---------------------------------------------------------------------------
# Emulator management
# ---------------------------------------------------------------------------


def _find_sdk_path() -> Path:
    """Locate Android SDK. Checks env vars, then default macOS path."""
    for env_var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        val = __import__("os").environ.get(env_var)
        if val and Path(val).exists():
            return Path(val)
    default = Path.home() / "Library" / "Android" / "sdk"
    if default.exists():
        return default
    print("ERROR: Android SDK not found. Set ANDROID_HOME.")
    sys.exit(1)


def _find_emulator_binary() -> Path:
    sdk = _find_sdk_path()
    emulator = sdk / "emulator" / "emulator"
    if not emulator.exists():
        print(f"ERROR: emulator binary not found at {emulator}")
        sys.exit(1)
    return emulator


def launch_emulator(avd_name: str) -> subprocess.Popen[bytes]:
    """Launch an emulator AVD in the background. Returns the process handle."""
    emulator = _find_emulator_binary()
    proc = subprocess.Popen(
        [str(emulator), "-avd", avd_name,
         "-gpu", "host", "-no-audio", "-no-boot-anim"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    print(f"Emulator launched (PID {proc.pid}), waiting for boot...")
    # Wait for device to appear
    subprocess.run(["adb", "wait-for-device"], check=True, timeout=120)
    # Wait for boot to complete
    for _ in range(120):
        result = subprocess.run(
            ["adb", "shell", "getprop", "sys.boot_completed"],
            capture_output=True, text=True,
        )
        if result.stdout.strip() == "1":
            print("Emulator booted.")
            time.sleep(2)  # Extra settle time for SystemUI
            return proc
        time.sleep(1)
    print("WARNING: Emulator boot timeout, proceeding anyway.")
    return proc


# ---------------------------------------------------------------------------
# Device frame compositing
# ---------------------------------------------------------------------------


def _find_skin_dir() -> Path | None:
    """Locate the SDK skin directory for the Pixel device frame."""
    sdk = _find_sdk_path()
    skin_dir = sdk / "skins" / SKIN_NAME
    if skin_dir.exists() and (skin_dir / "back.webp").exists():
        return skin_dir
    return None


def frame_screenshot(screenshot_path: Path, output_path: Path) -> bool:
    """Composite a screenshot into a Pixel device frame using SDK skin assets.

    Returns True if framing succeeded, False if skin not found (keeps original).
    """
    from PIL import Image

    skin_dir = _find_skin_dir()
    if skin_dir is None:
        return False

    # Load assets
    frame_bg = Image.open(skin_dir / "back.webp").convert("RGBA")
    foreground_mask = Image.open(skin_dir / "mask.webp").convert("RGBA")
    screenshot = Image.open(screenshot_path).convert("RGBA")

    # Resize screenshot to match expected screen size if needed
    if screenshot.size != tuple(FRAME_SCREEN_SIZE):
        screenshot = screenshot.resize(FRAME_SCREEN_SIZE, Image.LANCZOS)

    # SDK skin masks are foreground cutouts: alpha is transparent where the screen
    # belongs and opaque around hardware/rounded-corner chrome.
    cutout_alpha = foreground_mask.getchannel("A")
    screen_alpha = cutout_alpha.point(lambda value: 255 - value)
    screenshot.putalpha(screen_alpha)

    # Composite: start with frame background, paste masked screenshot at offset
    result = frame_bg.copy()
    result.paste(screenshot, FRAME_SCREEN_OFFSET, mask=screenshot)
    result.alpha_composite(foreground_mask, FRAME_SCREEN_OFFSET)

    # Save as PNG (lossless)
    result.save(output_path, "PNG")
    return True


def optimize_screenshot(path: Path) -> None:
    """Losslessly rewrite PNG screenshots with maximum compression."""
    from PIL import Image

    with Image.open(path) as image:
        image.save(path, "PNG", optimize=True, compress_level=9)


# ---------------------------------------------------------------------------
# PNG dimensions
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
# UI audit metadata
# ---------------------------------------------------------------------------


@dataclass
class PageCaptureResult:
    page_id: str
    route: str
    expected_root: str
    reachable: bool
    missing_elements: list[str]
    node_count: int
    clickable_count: int
    enabled_count: int
    scrollable_count: int
    text_samples: list[str]
    ui_dump: str | None = None
    error: str | None = None
    theme_results: dict[str, PageCaptureResult] = field(default_factory=dict, repr=False)


def expected_root_for(page: PageSpec) -> str:
    return page.expected_root or f"{page.route}-screen"


def _node_matches(node: ElementTree.Element, selector: str) -> bool:
    values = [
        node.attrib.get("resource-id", ""),
        node.attrib.get("content-desc", ""),
        node.attrib.get("text", ""),
    ]
    return any(value == selector or value.endswith(f":id/{selector}") for value in values)


def analyze_ui_tree(page: PageSpec, ui_xml: str, ui_dump_path: Path) -> PageCaptureResult:
    expected_root = expected_root_for(page)
    try:
        root = ElementTree.fromstring(ui_xml)
    except ElementTree.ParseError as exc:
        return PageCaptureResult(
            page_id=page.id,
            route=page.route,
            expected_root=expected_root,
            reachable=False,
            missing_elements=[expected_root] + page.required_elements,
            node_count=0,
            clickable_count=0,
            enabled_count=0,
            scrollable_count=0,
            text_samples=[],
            ui_dump=str(ui_dump_path),
            error=f"Invalid UI XML: {exc}",
        )

    nodes = list(root.iter("node"))
    missing = []
    for selector in [expected_root] + page.required_elements:
        if not any(_node_matches(node, selector) for node in nodes):
            missing.append(selector)

    text_samples: list[str] = []
    for node in nodes:
        text = node.attrib.get("text") or node.attrib.get("content-desc") or ""
        text = text.strip()
        if text and text not in text_samples:
            text_samples.append(text)
        if len(text_samples) >= 8:
            break

    return PageCaptureResult(
        page_id=page.id,
        route=page.route,
        expected_root=expected_root,
        reachable=not missing,
        missing_elements=missing,
        node_count=len(nodes),
        clickable_count=sum(1 for node in nodes if node.attrib.get("clickable") == "true"),
        enabled_count=sum(1 for node in nodes if node.attrib.get("enabled") == "true"),
        scrollable_count=sum(1 for node in nodes if node.attrib.get("scrollable") == "true"),
        text_samples=text_samples,
        ui_dump=str(ui_dump_path),
    )


def aggregate_theme_results(
    page: PageSpec,
    results: list[tuple[str, PageCaptureResult]],
) -> PageCaptureResult:
    expected_root = expected_root_for(page)
    results_by_theme = {theme: result for theme, result in results}
    duplicate_themes = sorted(
        theme
        for theme in results_by_theme
        if sum(1 for candidate, _ in results if candidate == theme) > 1
    )
    missing_themes = [theme for theme in CAPTURE_THEMES if theme not in results_by_theme]
    unexpected_themes = sorted(set(results_by_theme) - set(CAPTURE_THEMES))
    if not results:
        return PageCaptureResult(
            page_id=page.id,
            route=page.route,
            expected_root=expected_root,
            reachable=False,
            missing_elements=[expected_root] + page.required_elements,
            node_count=0,
            clickable_count=0,
            enabled_count=0,
            scrollable_count=0,
            text_samples=[],
            error="No theme capture results were produced",
        )

    missing_elements = [
        f"{theme}: {selector}"
        for theme, result in results
        for selector in result.missing_elements
    ]
    missing_elements.extend(f"{theme}: capture result" for theme in missing_themes)
    errors = [
        f"{theme}: {result.error}"
        for theme, result in results
        if result.error
    ]
    if duplicate_themes:
        errors.append(f"Duplicate theme results: {', '.join(duplicate_themes)}")
    if unexpected_themes:
        errors.append(f"Unexpected theme results: {', '.join(unexpected_themes)}")
    text_samples: list[str] = []
    for _, result in results:
        if len(text_samples) >= 8:
            break
        for sample in result.text_samples:
            if sample not in text_samples:
                text_samples.append(sample)
            if len(text_samples) >= 8:
                break

    return PageCaptureResult(
        page_id=page.id,
        route=page.route,
        expected_root=expected_root,
        reachable=(
            not missing_themes
            and not duplicate_themes
            and not unexpected_themes
            and all(result.reachable for _, result in results)
        ),
        missing_elements=missing_elements,
        node_count=min(result.node_count for _, result in results),
        clickable_count=min(result.clickable_count for _, result in results),
        enabled_count=min(result.enabled_count for _, result in results),
        scrollable_count=min(result.scrollable_count for _, result in results),
        text_samples=text_samples,
        ui_dump=", ".join(
            f"{theme}: {result.ui_dump}"
            for theme, result in results
            if result.ui_dump
        ) or None,
        error="; ".join(errors) or None,
        theme_results=results_by_theme,
    )


def _mermaid_id(page_id: str) -> str:
    return "".join(ch if ch.isalnum() else "_" for ch in page_id)


def _mermaid_label(page: PageSpec) -> str:
    state = f"\\n{page.state}" if page.state else ""
    return f"{page.title}\\n{page.route}{state}"


def generate_mermaid(spec: GuideSpec) -> str:
    lines = ["flowchart TD"]
    for page in spec.pages:
        lines.append(f'  {_mermaid_id(page.id)}["{_mermaid_label(page)}"]')
    for index, page in enumerate(spec.pages):
        parent = _page_parent(spec, page, index)
        if parent:
            label = f'|{page.flow_label}|' if page.flow_label else ""
            lines.append(f"  {_mermaid_id(parent)} -->{label} {_mermaid_id(page.id)}")
    return "\n".join(lines) + "\n"


def write_mermaid(spec: GuideSpec, output_path: Path) -> str:
    mermaid = generate_mermaid(spec)
    output_path.write_text(mermaid, encoding="utf-8")
    return mermaid


def _svg_text_lines(text: str, max_chars: int) -> list[str]:
    lines: list[str] = []
    for raw_line in text.split("\n"):
        words = raw_line.split()
        current = ""
        for word in words:
            candidate = word if not current else f"{current} {word}"
            if len(candidate) <= max_chars:
                current = candidate
            else:
                if current:
                    lines.append(current)
                current = word
        if current:
            lines.append(current)
    return lines or [text]


def _page_parent(spec: GuideSpec, page: PageSpec, index: int) -> str:
    if page.flow_from_explicit:
        return page.flow_from or ""
    return page.flow_from or (spec.pages[index - 1].id if index > 0 else "")


def _flow_sections(spec: GuideSpec) -> list[tuple[str, str, list[str]]]:
    if spec.flow_sections:
        return [
            (section.title, section.subtitle, section.page_ids)
            for section in spec.flow_sections
        ]
    return [
        (
            spec.flow_title,
            f"{len(spec.pages)} screens and states",
            [page.id for page in spec.pages],
        )
    ]


def _render_flow_svg(
    title: str,
    subtitle: str,
    pages: list[PageSpec],
    edges: list[tuple[str, str, str]],
    output_path: Path,
) -> Path:
    columns = 1 if len(pages) <= 4 else 2
    node_w = 520
    node_h = 112
    x_gap = 88
    y_gap = 28
    margin = 56
    title_h = 76
    width = margin * 2 + columns * node_w + (columns - 1) * x_gap
    rows = (len(pages) + columns - 1) // columns
    height = margin * 2 + title_h + rows * node_h + (rows - 1) * y_gap

    positions: dict[str, tuple[int, int]] = {}
    for index, page in enumerate(pages):
        row = index // columns
        col = index % columns
        x = margin + col * (node_w + x_gap)
        y = margin + title_h + row * (node_h + y_gap)
        positions[page.id] = (x, y)

    def esc(value: str) -> str:
        return html.escape(value, quote=True)

    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        "<defs>",
        '<marker id="arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">',
        '<path d="M 0 0 L 10 5 L 0 10 z" fill="#111111"/>',
        "</marker>",
        "</defs>",
        '<rect width="100%" height="100%" fill="#ffffff"/>',
        f'<text x="{margin}" y="{margin + 24}" font-family="Helvetica, Arial, sans-serif" font-size="30" font-weight="700" fill="#111111">{esc(title)}</text>',
        f'<text x="{margin}" y="{margin + 54}" font-family="Helvetica, Arial, sans-serif" font-size="17" fill="#6f6f6f">{esc(subtitle)}</text>',
    ]

    parent_by_child = {child: (parent, label) for parent, child, label in edges}

    for index, page in enumerate(pages):
        x, y = positions[page.id]
        title_lines = _svg_text_lines(page.title, 28)[:2]
        route_lines = _svg_text_lines(page.route, 38)[:1]
        state = page.state
        parts.append(f'<rect x="{x}" y="{y}" width="{node_w}" height="{node_h}" rx="12" fill="#f7f7f7" stroke="#d9d9d9" stroke-width="2"/>')
        parts.append(f'<circle cx="{x + 28}" cy="{y + 28}" r="16" fill="#111111"/>')
        parts.append(f'<text x="{x + 28}" y="{y + 34}" text-anchor="middle" font-family="Helvetica, Arial, sans-serif" font-size="15" font-weight="700" fill="#ffffff">{index + 1}</text>')
        ty = y + 28
        for line in title_lines:
            parts.append(f'<text x="{x + 58}" y="{ty}" font-family="Helvetica, Arial, sans-serif" font-size="20" font-weight="700" fill="#111111">{esc(line)}</text>')
            ty += 23
        for line in route_lines:
            parts.append(f'<text x="{x + 58}" y="{ty}" font-family="Helvetica, Arial, sans-serif" font-size="14" fill="#6f6f6f">{esc(line)}</text>')
            ty += 18
        if page.id in parent_by_child:
            parent, label = parent_by_child[page.id]
            source = f"from {parent}"
            if label:
                source = f"{source} · {label}"
            parts.append(f'<text x="{x + 58}" y="{y + node_h - 34}" font-family="Helvetica, Arial, sans-serif" font-size="13" fill="#6f6f6f">{esc(source)}</text>')
        if state:
            parts.append(f'<text x="{x + 58}" y="{y + node_h - 14}" font-family="Helvetica, Arial, sans-serif" font-size="13" fill="#6f6f6f">{esc(state)}</text>')

    parts.append("</svg>")
    output_path.write_text("\n".join(parts), encoding="utf-8")
    return output_path


def write_flow_svgs(spec: GuideSpec, output_dir: Path, root: Path) -> list[dict[str, str]]:
    pages_by_id = {page.id: page for page in spec.pages}
    index_by_id = {page.id: index for index, page in enumerate(spec.pages)}
    rendered: list[dict[str, str]] = []

    for section_index, (title, subtitle, ids) in enumerate(_flow_sections(spec), start=1):
        section_pages = [pages_by_id[page_id] for page_id in ids if page_id in pages_by_id]
        section_ids = {page.id for page in section_pages}
        section_edges: list[tuple[str, str, str]] = []
        for page in section_pages:
            parent = _page_parent(spec, page, index_by_id[page.id])
            if parent in section_ids:
                section_edges.append((parent, page.id, page.flow_label))
        output_path = output_dir / f"user-flow-{section_index}.svg"
        _render_flow_svg(title, subtitle, section_pages, section_edges, output_path)
        rendered.append(
            {
                "title": title,
                "subtitle": subtitle,
                "path": "/" + str(output_path.relative_to(root)),
            }
        )
    return rendered


def write_flow_svg(spec: GuideSpec, output_path: Path) -> Path:
    """Render a readable single-section SVG for tests and standalone use."""
    section_pages = spec.pages[: min(len(spec.pages), 12)]
    section_ids = {page.id for page in section_pages}
    index_by_id = {page.id: index for index, page in enumerate(spec.pages)}
    edges = []
    for page in section_pages:
        parent = _page_parent(spec, page, index_by_id[page.id])
        if parent in section_ids:
            edges.append((parent, page.id, page.flow_label))
    return _render_flow_svg(spec.flow_title, f"{len(section_pages)} screens and states", section_pages, edges, output_path)


def _result_to_dict(
    result: PageCaptureResult,
    *,
    include_theme_results: bool = True,
) -> dict[str, Any]:
    data = {
        "page_id": result.page_id,
        "route": result.route,
        "expected_root": result.expected_root,
        "reachable": result.reachable,
        "missing_elements": result.missing_elements,
        "node_count": result.node_count,
        "clickable_count": result.clickable_count,
        "enabled_count": result.enabled_count,
        "scrollable_count": result.scrollable_count,
        "text_samples": result.text_samples,
        "ui_dump": result.ui_dump,
        "error": result.error,
    }
    if include_theme_results:
        data["theme_results"] = {
            theme: _result_to_dict(theme_result, include_theme_results=False)
            for theme, theme_result in result.theme_results.items()
        }
    return data


def _exclusion_to_dict(exclusion: RouteExclusion) -> dict[str, str]:
    return {
        "route": exclusion.route,
        "prerequisite": exclusion.prerequisite,
        "reason": exclusion.reason,
    }


def audit_contract_fingerprint(spec: GuideSpec) -> str:
    contract = {
        "capture_themes": list(CAPTURE_THEMES),
        "defaults": {
            "permission_preset": spec.permission_preset,
            "service_preset": spec.service_preset,
            "data_preset": spec.data_preset,
            "settle_ms": spec.settle_ms,
        },
        "pages": [
            {
                "id": page.id,
                "route": page.route,
                "expected_root": expected_root_for(page),
                "required_elements": page.required_elements,
                "scroll_to": page.scroll_to,
                "permission_preset": page.permission_preset,
                "service_preset": page.service_preset,
                "data_preset": page.data_preset,
                "settle_ms": page.settle_ms,
            }
            for page in spec.pages
        ],
        "exclusions": [_exclusion_to_dict(exclusion) for exclusion in spec.route_exclusions],
    }
    encoded = json.dumps(contract, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def _result_from_dict(item: dict[str, Any]) -> PageCaptureResult:
    return PageCaptureResult(
        page_id=item["page_id"],
        route=item["route"],
        expected_root=item["expected_root"],
        reachable=bool(item["reachable"]),
        missing_elements=list(item.get("missing_elements", [])),
        node_count=int(item.get("node_count", 0)),
        clickable_count=int(item.get("clickable_count", 0)),
        enabled_count=int(item.get("enabled_count", 0)),
        scrollable_count=int(item.get("scrollable_count", 0)),
        text_samples=list(item.get("text_samples", [])),
        ui_dump=item.get("ui_dump"),
        error=item.get("error"),
        theme_results={
            theme: _result_from_dict(theme_result)
            for theme, theme_result in item.get("theme_results", {}).items()
        },
    )


def load_cached_audit_results(
    audit_json: Path,
    spec: GuideSpec,
) -> list[PageCaptureResult]:
    if not audit_json.exists():
        return []
    raw = json.loads(audit_json.read_text(encoding="utf-8"))
    if raw.get("audit_contract_fingerprint") != audit_contract_fingerprint(spec):
        return []
    if raw.get("capture_themes") != list(CAPTURE_THEMES):
        return []
    results = [_result_from_dict(item) for item in raw.get("pages", [])]
    if any(set(result.theme_results) != set(CAPTURE_THEMES) for result in results):
        return []
    return results


def write_audit_results(
    results: list[PageCaptureResult],
    exclusions: list[RouteExclusion],
    output_path: Path,
    spec: GuideSpec,
) -> None:
    data = {
        "generated_date": date.today().isoformat(),
        "audit_contract_fingerprint": audit_contract_fingerprint(spec),
        "capture_themes": list(CAPTURE_THEMES),
        "pages": [_result_to_dict(result) for result in results],
        "exclusions": [_exclusion_to_dict(exclusion) for exclusion in exclusions],
    }
    output_path.write_text(json.dumps(data, indent=2), encoding="utf-8")


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
    screenshot_dirs: dict[str, Path],
    output_json: Path,
    root: Path,
    audit_results: list[PageCaptureResult],
    mermaid_path: Path,
    mermaid_code: str,
    flow_svg_path: Path,
    flow_svgs: list[dict[str, str]],
) -> list[str]:
    """Write guide-data.json for Typst. Returns list of missing page IDs."""
    missing: list[str] = []
    pages_data: list[dict[str, Any]] = []

    for page in spec.pages:
        screenshots: list[dict[str, Any]] = []
        for theme_name in CAPTURE_THEMES:
            theme_dir = screenshot_dirs.get(theme_name)
            if theme_dir is None:
                missing.append(f"{page.id} ({theme_name})")
                continue
            screenshot = theme_dir / f"{page.id}.png"
            if not screenshot.exists():
                missing.append(f"{page.id} ({theme_name})")
                continue

            px_w, px_h = _png_dimensions(screenshot)
            rel_screenshot = "/" + str(screenshot.relative_to(root))
            screenshots.append({
                "theme": theme_name,
                "label": THEME_LABELS.get(theme_name, theme_name.title()),
                "path": rel_screenshot,
                "pixel_width": px_w,
                "pixel_height": px_h,
            })

        if not screenshots:
            continue

        primary = screenshots[0]

        pages_data.append({
            "id": page.id,
            "title": page.title,
            "description": page.description,
            "screenshot": primary["path"],
            "screenshots": screenshots,
            "pixel_width": primary["pixel_width"],
            "pixel_height": primary["pixel_height"],
            "annotations": [_annotation_to_dict(a) for a in page.annotations],
            "route": page.route,
            "state": page.state,
            "expected_root": expected_root_for(page),
        })

    results_by_page = {result.page_id: result for result in audit_results}
    audit_pages = [_result_to_dict(results_by_page[page.id]) for page in spec.pages if page.id in results_by_page]
    reachable_count = sum(1 for result in audit_pages if result["reachable"])
    failed_count = len(audit_pages) - reachable_count
    audit_exclusions = [_exclusion_to_dict(item) for item in spec.route_exclusions]
    covered_route_count = len(
        {page.route for page in spec.pages}.union(item.route for item in spec.route_exclusions),
    )

    data = {
        "title": spec.title,
        "subtitle": spec.subtitle,
        "generated_date": date.today().isoformat(),
        "flow_title": spec.flow_title,
        "mermaid": {
            "path": "/" + str(mermaid_path.relative_to(root)),
            "code": mermaid_code,
            "svg": "/" + str(flow_svg_path.relative_to(root)),
            "sections": flow_svgs,
        },
        "audit": {
            "total": len(audit_pages),
            "coverage_total": covered_route_count,
            "reachable": reachable_count,
            "failed": failed_count,
            "excluded_count": len(audit_exclusions),
            "exclusions": audit_exclusions,
            "pages": audit_pages,
        },
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
    ui_dumps_dir: Path,
    device: str | None,
    theme: str | None = None,
) -> PageCaptureResult:
    output = screenshots_dir / f"{page.id}.png"
    ui_dump = ui_dumps_dir / f"{page.id}.xml"
    theme_suffix = f" ({theme})" if theme else ""
    print(f"  Launching route: {page.route}{theme_suffix}")
    adb_launch_route(page.route, spec, page, device, theme)
    settle = page.settle_ms or spec.settle_ms
    time.sleep(settle / 1000.0)

    if page.scroll_to:
        print(f"  Scrolling to: {page.scroll_to}")
        found = adb_scroll_to(page.scroll_to, device)
        if not found:
            print(f"  WARNING: Element '{page.scroll_to}' not found after scrolling")
        time.sleep(0.5)

    print(f"  Waiting for UI tree -> {ui_dump.name}")
    _, result = wait_for_ui_tree(page, ui_dump, device, max(settle, 5000))
    dismiss_runtime_permission_dialogs(device)
    print(f"  Capturing screenshot -> {output.name}")
    has_content = capture_screenshot_when_ready(output, device)
    if not has_content:
        print("  WARNING: Screenshot appears blank after retries")
        result.reachable = False
        result.error = "Screenshot remained blank after capture retries"
    optimize_screenshot(output)
    if result.reachable:
        print(f"  Reachable: {result.expected_root}")
    else:
        print(f"  WARNING: Missing selectors: {', '.join(result.missing_elements)}")
    return result


def audit_completion_errors(
    pages: list[PageSpec],
    results: list[PageCaptureResult],
    missing_screenshots: list[str],
) -> list[str]:
    page_ids = {page.id for page in pages}
    results_by_page = {result.page_id: result for result in results if result.page_id in page_ids}
    errors = [
        f"{page.id}: no audit result"
        for page in pages
        if page.id not in results_by_page
    ]
    errors.extend(
        f"{result.page_id}: {', '.join(result.missing_elements) or result.error or 'unreachable'}"
        for result in results_by_page.values()
        if not result.reachable
    )
    errors.extend(f"{page_id}: screenshot missing" for page_id in missing_screenshots)
    return errors


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
    parser.add_argument(
        "--no-frame",
        action="store_true",
        help="Skip device frame compositing (faster iteration)",
    )
    parser.add_argument(
        "--emulator",
        action="store_true",
        help=f"Auto-launch the {GUIDE_AVD_NAME} emulator for capture",
    )
    parser.add_argument(
        "--strict-audit",
        action="store_true",
        help="Exit non-zero when a page/theme/required selector or screenshot fails",
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
    ui_dumps_dir = build_dir / "ui-dumps"
    screenshot_dirs = {theme: screenshots_dir / theme for theme in CAPTURE_THEMES}
    ui_dump_dirs = {theme: ui_dumps_dir / theme for theme in CAPTURE_THEMES}
    if args.skip_capture and not any((screenshots_dir / theme).exists() for theme in CAPTURE_THEMES):
        screenshot_dirs = {"dark": screenshots_dir}
        ui_dump_dirs = {"dark": ui_dumps_dir}
    for directory in screenshot_dirs.values():
        directory.mkdir(parents=True, exist_ok=True)
    for directory in ui_dump_dirs.values():
        directory.mkdir(parents=True, exist_ok=True)
    audit_json = build_dir / "ui-audit.json"
    mermaid_path = build_dir / "user-flow.mmd"
    mermaid_code = write_mermaid(spec, mermaid_path)
    flow_svg_path = write_flow_svg(spec, build_dir / "user-flow.svg")
    flow_svgs = write_flow_svgs(spec, build_dir, root)

    emulator_proc = None
    audit_results: list[PageCaptureResult] = []

    # Phase 1: Capture screenshots
    if not args.skip_capture:
        # Auto-launch emulator if requested
        if args.emulator:
            emulator_proc = launch_emulator(GUIDE_AVD_NAME)

        try:
            result = subprocess.run(
                ["adb", "devices"], capture_output=True, text=True, check=True,
            )
            lines = [ln for ln in result.stdout.strip().split("\n")[1:] if ln.strip()]
            if not lines:
                print("ERROR: No ADB devices found. Connect a device or use --emulator.")
                sys.exit(1)
            print(f"ADB devices: {len(lines)} connected")
        except FileNotFoundError:
            print("ERROR: adb not found. Install Android SDK platform-tools.")
            sys.exit(1)

        # Enable demo mode for clean status bar
        print("Enabling demo mode (clean status bar)...")
        setup_demo_mode(args.device)
        print("Granting runtime permissions...")
        grant_runtime_permissions(args.device)
        dismiss_runtime_permission_dialogs(args.device)

        try:
            for page in spec.pages:
                print(f"[{page.id}] Capturing...")
                theme_results: list[tuple[str, PageCaptureResult]] = []
                for theme in CAPTURE_THEMES:
                    try:
                        result = capture_page(
                            page,
                            spec,
                            screenshot_dirs[theme],
                            ui_dump_dirs[theme],
                            args.device,
                            theme,
                        )
                        theme_results.append((theme, result))
                    except subprocess.CalledProcessError as e:
                        print(f"  ERROR: ADB command failed: {e.cmd}")
                        print(f"  stderr: {e.stderr}")
                        theme_results.append(
                            (
                                theme,
                                PageCaptureResult(
                                    page_id=page.id,
                                    route=page.route,
                                    expected_root=expected_root_for(page),
                                    reachable=False,
                                    missing_elements=[expected_root_for(page)] + page.required_elements,
                                    node_count=0,
                                    clickable_count=0,
                                    enabled_count=0,
                                    scrollable_count=0,
                                    text_samples=[],
                                    error=f"ADB command failed: {e.cmd}",
                                ),
                            )
                        )
                audit_results.append(aggregate_theme_results(page, theme_results))
        finally:
            print("Disabling demo mode...")
            try:
                teardown_demo_mode(args.device)
            except subprocess.CalledProcessError:
                pass  # Best-effort cleanup
        write_audit_results(audit_results, spec.route_exclusions, audit_json, spec)
    else:
        print("Skipping capture (using cached screenshots)")
        audit_results = load_cached_audit_results(audit_json, spec)
        if not audit_results:
            print(f"WARNING: No cached audit metadata found at {audit_json}")

    # Phase 1.5: Device frame compositing
    if not args.no_frame:
        print("Framing screenshots with Pixel device mockup...")
        framed_dir = build_dir / "framed"
        framed_dirs = {theme: framed_dir / theme for theme in screenshot_dirs}
        for directory in framed_dirs.values():
            directory.mkdir(parents=True, exist_ok=True)
        framed_any = False

        for theme, source_dir in screenshot_dirs.items():
            target_dir = framed_dirs[theme]
            for page in spec.pages:
                raw = source_dir / f"{page.id}.png"
                if not raw.exists():
                    continue
                framed = target_dir / f"{page.id}.png"
                if frame_screenshot(raw, framed):
                    framed_any = True
                    print(f"  [{page.id}] {theme} framed")
                else:
                    # Fallback: copy original if skin not found
                    import shutil
                    shutil.copy2(raw, framed)
                    print(f"  [{page.id}] {theme} skin not found, using original")

        if framed_any:
            screenshot_dirs = framed_dirs  # Point Typst at framed screenshots
            print(f"Using framed screenshots from {framed_dir.name}/")
        elif not args.skip_capture:
            print("WARNING: No device frame skin found, using raw screenshots")
    else:
        print("Skipping device framing (--no-frame)")

    # Phase 2: Write JSON data for Typst
    data_json = build_dir / "guide-data.json"
    print("Writing guide data...")
    missing = write_guide_data(spec, screenshot_dirs, data_json, root, audit_results, mermaid_path, mermaid_code, flow_svg_path, flow_svgs)
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

    # Clean up emulator if we launched it
    if emulator_proc is not None:
        print("Shutting down emulator...")
        subprocess.run(["adb", "emu", "kill"], capture_output=True)
        emulator_proc.wait(timeout=30)

    completion_errors = audit_completion_errors(spec.pages, audit_results, missing)
    if args.strict_audit and completion_errors:
        print("ERROR: Strict UI audit failed:", file=sys.stderr)
        for error in completion_errors:
            print(f"  - {error}", file=sys.stderr)
        sys.exit(2)


if __name__ == "__main__":
    main()
