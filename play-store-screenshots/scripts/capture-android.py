#!/usr/bin/env python3
"""Build and capture the actual app on a dedicated emulator. No result fixtures."""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path
from xml.etree import ElementTree

from source_captures import LOCALES, MANIFEST, PROJECT, ROOT, SCREENS, input_hashes, inputs_sha256, sha256

PACKAGE = "com.poyka.ripdpi"
VARIANT = "githubFullDebug"


def run(args: list[str], *, raw: bool = False) -> bytes | str:
    result = subprocess.run(args, cwd=ROOT, check=True, capture_output=True, timeout=120)
    return result.stdout if raw else result.stdout.decode().strip()


class CaptureDevice:
    def __init__(self, serial: str) -> None:
        if not serial.startswith("emulator-"):
            raise ValueError("Use a dedicated emulator. This command resets app state.")
        self.serial = serial

    def adb(self, *args: str, raw: bool = False) -> bytes | str:
        return run(["adb", "-s", self.serial, *args], raw=raw)

    def tree(self) -> ElementTree.Element:
        self.adb("shell", "uiautomator", "dump", "/sdcard/ripdpi-docs.xml")
        return ElementTree.fromstring(self.adb("exec-out", "cat", "/sdcard/ripdpi-docs.xml"))

    @staticmethod
    def tag(tree: ElementTree.Element, name: str) -> ElementTree.Element | None:
        return next((node for node in tree.iter("node") if node.get("resource-id") == name), None)

    @staticmethod
    def bounds(node: ElementTree.Element) -> list[int]:
        return list(map(int, re.findall(r"\d+", node.get("bounds", ""))))

    def tap(self, node: ElementTree.Element | None) -> None:
        if node is None:
            raise RuntimeError("The expected control is missing.")
        x1, y1, x2, y2 = self.bounds(node)
        self.adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        time.sleep(0.6)

    def scroll(self, delta: int) -> None:
        step = max(-600, min(600, delta))
        self.adb("shell", "input", "swipe", "672", "2050", "672", str(2050 - step), "900")
        time.sleep(0.8)

    def show_route(self, name: str, route: str) -> None:
        self.adb("shell", "am", "force-stop", PACKAGE)
        args = ["shell", "am", "start", "-W", "-n", f"{PACKAGE}/.activities.MainActivity"]
        for suffix in ("ENABLED", "RESET_STATE", "DISABLE_MOTION"):
            args.extend(("--ez", f"{PACKAGE}.automation.{suffix}", "true"))
        for suffix, value in (
            ("START_ROUTE", route), ("PERMISSION_PRESET", "granted"),
            ("SERVICE_PRESET", "idle"), ("DATA_PRESET", "settings_ready"), ("THEME", "light"),
        ):
            args.extend(("--es", f"{PACKAGE}.automation.{suffix}", value))
        self.adb(*args)
        time.sleep(3)
        expected = {"home": "home-screen", "diagnostics": "diagnostics-screen", "relay": "mode_editor-screen"}[name]
        tree = self.tree()
        if self.tag(tree, expected) is None:
            raise RuntimeError(f"The app did not open {route}. No frame was saved.")
        if name == "diagnostics":
            self.tap(self.tag(tree, "diagnostics-section-scan"))
            self.show_scan_action()
        elif name == "relay":
            self.show_relay_fields()
        time.sleep(1)

    def show_scan_action(self) -> None:
        for _ in range(8):
            tree = self.tree()
            action = self.tag(tree, "diagnostics-scan-run-raw")
            navigation = self.tag(tree, "bottom-nav-bar")
            if action is not None and navigation is not None:
                _, top, _, bottom = self.bounds(action)
                if bottom - top >= 140 and bottom <= self.bounds(navigation)[1] - 12:
                    return
            self.scroll(250)
        raise RuntimeError("The Scan action is not fully visible. No frame was saved.")

    def show_relay_fields(self) -> None:
        switch = None
        for _ in range(10):
            switch = next((node for node in self.tree().iter("node")
                           if node.get("checkable") == "true" and node.get("clickable") == "true"
                           and not node.get("resource-id")), None)
            if switch is not None:
                break
            self.scroll(600)
        if switch is None:
            raise RuntimeError("The relay switch is missing.")
        if switch.get("checked") == "false":
            self.tap(switch)
        for _ in range(10):
            field = self.tag(self.tree(), "mode-editor-relay-profile-id")
            if field is None:
                self.scroll(400)
                continue
            y = self.bounds(field)[1]
            if 950 <= y <= 1100:
                break
            self.scroll(y - 1000)
        field = self.tag(self.tree(), "mode-editor-relay-profile-id")
        if field is None or not 850 <= self.bounds(field)[1] <= 1200:
            raise RuntimeError("The relay frame does not show the expected fields.")

    def demo_mode(self, enabled: bool) -> None:
        self.adb("shell", "settings", "put", "global", "sysui_demo_allowed", "1" if enabled else "0")
        self.adb("shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", "enter" if enabled else "exit")
        if enabled:
            for extras in (
                ("clock", "-e", "hhmm", "1200"),
                ("battery", "-e", "level", "100", "-e", "plugged", "false"),
                ("notifications", "-e", "visible", "false"),
            ):
                self.adb("shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", *extras)


def record_manifest(device: CaptureDevice, apk: Path, xray_artifacts: Path, build_revision: str, inputs: dict[str, str]) -> None:
    if input_hashes() != inputs:
        raise RuntimeError("Android inputs changed during the build or capture. Repeat the capture from a stable tree.")
    images = {}
    for locale in LOCALES:
        for name, (_, source_name) in SCREENS.items():
            for path in (
                PROJECT / f"public/screenshots/{locale}/{source_name}.png",
                ROOT / f"docs/screenshots/ui/{locale}/{name}.png",
            ):
                images[str(path.relative_to(ROOT))] = sha256(path)
    for _, source_name in SCREENS.values():
        path = PROJECT / f"public/screenshots/{source_name}.png"
        images[str(path.relative_to(ROOT))] = sha256(path)
    metadata = {
        "schemaVersion": 1,
        "capturedAtUtc": datetime.now(timezone.utc).isoformat(),
        "builtFromRevision": build_revision,
        "apk": {"variant": VARIANT, "filename": apk.name, "sha256": sha256(apk)},
        "libXray": {"manifestSha256": sha256(xray_artifacts / "libxray-artifact.json"),
                    "aarSha256": sha256(xray_artifacts / "libxray.aar"), "verification": "verify-libxray-artifacts.sh --release passed"},
        "device": {"kind": "dedicated emulator", "avd": device.adb("emu", "avd", "name").splitlines()[0],
                   "model": device.adb("shell", "getprop", "ro.product.model"),
                   "api": int(device.adb("shell", "getprop", "ro.build.version.sdk")),
                   "abi": device.adb("shell", "getprop", "ro.product.cpu.abi"),
                   "screen": "1344x2992", "densityDpi": 480},
        "theme": "light", "locales": list(LOCALES),
        "routes": {name: route for name, (route, _) in SCREENS.items()},
        "state": {"permissionPreset": "granted", "servicePreset": "idle", "dataPreset": "settings_ready",
                  "motion": "disabled", "statusBar": "Android demo mode: 12:00, battery 100%, notifications hidden",
                  "home": "disconnected; actual setup advisory is visible",
                  "diagnostics": "Scan tab before a run; scrolled when needed to show the action; no measured results",
                  "relay": "editor scrolled to relay fields; enabled as an unsaved edit; no credentials or connection"},
        "limitations": "App UI illustrations. Permission state uses the debug automation contract. No network, VPN, server or physical-device acceptance is claimed.",
        "uiInputsSha256": inputs_sha256(inputs), "uiInputs": inputs, "images": images,
    }
    MANIFEST.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Dedicated 1344x2992, 480 dpi emulator; its app state is reset")
    parser.add_argument("--xray-artifacts", required=True, type=Path, help="Real producer artifacts; the release verifier must pass")
    args = parser.parse_args()
    device = CaptureDevice(args.serial)
    if device.adb("shell", "wm", "size") != "Physical size: 1344x2992" or device.adb("shell", "wm", "density") != "Physical density: 480":
        parser.error("Use a dedicated Pixel 10 Pro XL emulator at 1344x2992, 480 dpi; no resize or crop is applied.")
    abi = device.adb("shell", "getprop", "ro.product.cpu.abi")
    if abi not in ("arm64-v8a", "x86_64"):
        parser.error("Use an arm64-v8a or x86_64 emulator.")
    xray_artifacts = args.xray_artifacts.resolve()
    subprocess.run(["bash", "scripts/native/verify-libxray-artifacts.sh", "--release"],
                   cwd=ROOT, env={**os.environ, "RIPDPI_XRAY_AAR_DIR": str(xray_artifacts)}, check=True)
    revision = run(["git", "rev-parse", "HEAD"])
    inputs = input_hashes()
    subprocess.run(["./gradlew", ":app:assembleGithubFullDebug", f"-Pripdpi.localNativeAbis={abi}",
                    f"-Pripdpi.prebuiltXrayAarDir={xray_artifacts}"], cwd=ROOT, check=True)
    apk = ROOT / f"app/build/outputs/apk/githubFull/debug/app-github-full-{abi}-debug.apk"
    device.adb("install", "-r", str(apk))
    device.adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
    device.adb("shell", "cmd", "uimode", "night", "no")
    for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        device.adb("shell", "settings", "put", "global", setting, "0")
    device.adb("shell", "settings", "put", "system", "screen_off_timeout", "1800000")
    device.adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    device.adb("shell", "input", "keyevent", "KEYCODE_MENU")
    device.demo_mode(True)
    try:
        for locale in LOCALES:
            device.adb("shell", "cmd", "locale", "set-app-locales", PACKAGE, "--locales", locale)
            actual_locale = device.adb("shell", "cmd", "locale", "get-app-locales", PACKAGE)
            if f"[{locale}]" not in actual_locale:
                raise RuntimeError(f"The Android locale did not change to {locale}.")
            for name, (route, source_name) in SCREENS.items():
                device.show_route(name, route)
                path = PROJECT / f"public/screenshots/{locale}/{source_name}.png"
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(device.adb("exec-out", "screencap", "-p", raw=True))
                direct = ROOT / f"docs/screenshots/ui/{locale}/{name}.png"
                direct.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, direct)
                if locale == "en":
                    shutil.copyfile(path, PROJECT / f"public/screenshots/{source_name}.png")
                print(f"Captured {locale}/{name}", flush=True)
        record_manifest(device, apk, xray_artifacts, revision, inputs)
    finally:
        device.demo_mode(False)
    print("Inspect all frames, then run bun run capture:prod. These frames do not prove network acceptance.")


if __name__ == "__main__":
    main()
