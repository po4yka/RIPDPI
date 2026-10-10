"""Shared source-capture paths and content hashes. No Git history is needed."""
from __future__ import annotations

import hashlib
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROJECT = ROOT / "play-store-screenshots"
MANIFEST = PROJECT / "public/screenshots/source-capture.json"
LOCALES = ("en", "ru", "es", "de", "fr", "fa", "zh-CN")
FRAME_SIZE = (1080, 1800)
DENSITY_DPI = 360
STATUS_BAR = "Native Android status bar: current time and actual service/network indicators"
SCREENS = {
    "home": ("home", "home-light"),
    "diagnostics": ("diagnostics", "diagnostics"),
    "relay": ("mode_editor", "relay"),
    "dns-settings": ("dns_settings", "dns-settings"),
    "strategies": ("strategy_config", "strategies"),
    "backup": ("backup_restore", "backup"),
}
INPUT_PATHS = (
    "app/src/main", "app/src/full", "app/src/debug", "app/src/github", "core",
    "app/build.gradle.kts", "gradle.properties", "gradle/libs.versions.toml",
    "build-logic/convention/src/main",
)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def input_hashes(root: Path = ROOT) -> dict[str, str]:
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z", "--", *INPUT_PATHS],
        cwd=root, check=True, capture_output=True,
    )
    paths = sorted(set(result.stdout.decode().strip("\0").split("\0")))
    return {
        path: sha256(root / path)
        for path in paths
        if path and (root / path).is_file()
        and not any(part.startswith(("test", "androidTest")) for part in Path(path).parts)
        and Path(path).suffix not in (".md", ".txt")
    }


def inputs_sha256(inputs: dict[str, str]) -> str:
    value = "".join(f"{path}\0{digest}\n" for path, digest in sorted(inputs.items()))
    return hashlib.sha256(value.encode()).hexdigest()

DISPLAY_PROFILES = {name: {"densityDpi": DENSITY_DPI, "fontScale": 1.0} for name in SCREENS}
DISPLAY_PROFILES["home"] = {"densityDpi": 290, "fontScale": 1.24}
DISPLAY_PROFILES["relay"] = {"densityDpi": 260, "fontScale": 1.5}

HOME_SCROLL_PIXELS = {locale: 20 if locale in ("en", "de", "fr", "fa") else 0 for locale in LOCALES}

BACKUP_DENSITIES = {"en": 480, "ru": 460, "es": 460, "de": 460, "fr": 460, "fa": 440, "zh-CN": 500}
DNS_DISPLAY_PROFILES = {
    "en": {"densityDpi": 360, "fontScale": 1.0},
    "ru": {"densityDpi": 300, "fontScale": 1.18},
    "es": {"densityDpi": 300, "fontScale": 1.18},
    "de": {"densityDpi": 300, "fontScale": 1.2},
    "fr": {"densityDpi": 300, "fontScale": 1.2},
    "fa": {"densityDpi": 360, "fontScale": 1.0},
    "zh-CN": {"densityDpi": 420, "fontScale": 0.8571429},
}
DISPLAY_OVERRIDES = {
    locale: {
        "backup": {"densityDpi": BACKUP_DENSITIES[locale], "fontScale": round(360 / BACKUP_DENSITIES[locale], 7)},
        "dns-settings": DNS_DISPLAY_PROFILES[locale],
    } for locale in LOCALES
}
DNS_BOTTOM_FOCUS_PIXELS = {locale: 0 if locale in ("fa", "zh-CN") else 45 for locale in LOCALES}


def display_profile(locale: str, name: str) -> dict:
    return DISPLAY_OVERRIDES.get(locale, {}).get(name, DISPLAY_PROFILES[name])
