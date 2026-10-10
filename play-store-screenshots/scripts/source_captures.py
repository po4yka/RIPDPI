"""Shared source-capture paths and content hashes. No Git history is needed."""
from __future__ import annotations

import hashlib
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROJECT = ROOT / "play-store-screenshots"
MANIFEST = PROJECT / "public/screenshots/source-capture.json"
LOCALES = ("en", "ru", "es", "de", "fr", "fa", "zh-CN")
SCREENS = {"home": ("home", "home-light"), "diagnostics": ("diagnostics", "diagnostics"), "relay": ("mode_editor", "relay")}
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
