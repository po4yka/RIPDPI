#!/usr/bin/env python3
"""Build and capture the actual app on a dedicated emulator. No result fixtures."""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sqlite3
import tempfile
import hashlib
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path
from xml.etree import ElementTree

from source_captures import FRAME_SIZE, DENSITY_DPI, DISPLAY_PROFILES, STATUS_BAR, HOME_SCROLL_PIXELS, LOCALES, MANIFEST, PROJECT, ROOT, SCREENS, input_hashes, inputs_sha256, sha256

PACKAGE = "com.poyka.ripdpi"
VARIANT = "githubFullDebug"


def is_new_completed_scan(receipt: dict | None, previous_id: str | None, clicked_at: int) -> bool:
    return bool(receipt and receipt["id"] != previous_id and receipt["startedAt"] >= clicked_at
                and receipt["finishedAt"] is not None and receipt["status"] == "completed"
                and receipt["resultCount"] > 0 and receipt["reportBytes"] > 0)


def runtime_permissions(api: int) -> tuple[str, ...]:
    return ("android.permission.POST_NOTIFICATIONS",) + (("android.permission.ACCESS_LOCAL_NETWORK",) if api >= 37 else ())


def run(args: list[str], *, raw: bool = False) -> bytes | str:
    result = subprocess.run(args, cwd=ROOT, check=True, capture_output=True, timeout=120)
    return result.stdout if raw else result.stdout.decode().strip()


def home_sample_count(tree: ElementTree.Element, template: str) -> int:
    pattern = re.escape(template).replace(re.escape("%1$d"), r"(\d+)")
    counts = []
    for node in tree.iter("node"):
        text = re.sub(r"[\u200e\u200f\u061c]", "", node.get("text", ""))
        match = re.fullmatch(pattern, text)
        if match:
            counts.append(int(match[1]))
    return max(counts, default=0)


class CaptureDevice:
    def __init__(self, serial: str) -> None:
        if not serial.startswith("emulator-"):
            raise ValueError("Use a dedicated emulator. This command resets app state.")
        self.serial = serial
        self.receipts = {}

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
        density = int(re.findall(r"density: ([0-9]+)", self.adb("shell", "wm", "density"))[-1])
        slop = round(density * 8 / 160)
        step = max(-600, min(600, delta + (slop if delta > 0 else -slop)))
        self.adb("shell", "input", "swipe", "540", "1450" if step > 0 else "750", "540", str(1450 - step) if step > 0 else str(750 - step), "900")
        time.sleep(0.8)

    def show_route(self, name: str, route: str, *, reset: bool = False) -> None:
        profile = DISPLAY_PROFILES.get(name, {"densityDpi": DENSITY_DPI, "fontScale": 1.0})
        self.adb("shell", "wm", "density", str(profile["densityDpi"]) if profile["densityDpi"] != DENSITY_DPI else "reset")
        self.adb("shell", "settings", "put", "system", "font_scale", str(profile["fontScale"]))
        self.adb("shell", "am", "force-stop", PACKAGE)
        args = ["shell", "am", "start", "-W", "-n", f"{PACKAGE}/.activities.MainActivity"]
        for suffix in ("ENABLED", "DISABLE_MOTION"):
            args.extend(("--ez", f"{PACKAGE}.automation.{suffix}", "true"))
        args.extend(("--ez", f"{PACKAGE}.automation.RESET_STATE", str(reset).lower()))
        for suffix, value in (
            ("START_ROUTE", route), ("PERMISSION_PRESET", "granted"),
            ("SERVICE_PRESET", "live"), ("DATA_PRESET", "settings_ready"), ("THEME", "light"),
        ):
            args.extend(("--es", f"{PACKAGE}.automation.{suffix}", value))
        self.adb(*args)
        time.sleep(3)
        expected = f"{route}-screen"
        tree = self.tree()
        if self.tag(tree, expected) is None:
            raise RuntimeError(f"The app did not open {route}. No frame was saved.")
        if name == "home":
            self.connect_live()
            self.focus_home_measurements()
        elif name == "diagnostics":
            self.show_completed_scan()
        elif name == "relay":
            self.show_relay_fields()
        elif name == "dns-settings":
            self.show_dns_editor()

        time.sleep(1)

    def connect_live(self) -> None:
        tree = self.tree()
        button = self.tag(tree, "connection-actuator-button")
        if button is None:
            raise RuntimeError("The real connection control is missing.")
        connectivity = self.adb("shell", "dumpsys", "connectivity")
        if "ni{VPN CONNECTED extra: VPN:com.poyka.ripdpi}" not in connectivity:
            self.tap(button)
        for _ in range(30):
            services = self.adb("shell", "dumpsys", "activity", "services", PACKAGE)
            connectivity = self.adb("shell", "dumpsys", "connectivity")
            if "RipDpiVpnService" in services and "ni{VPN CONNECTED extra: VPN:com.poyka.ripdpi}" in connectivity:
                self.receipts["vpn"] = {"observedAtUtc": datetime.now(timezone.utc).isoformat(),
                                        "service": "RipDpiVpnService", "transport": "VPN CONNECTED; owner com.poyka.ripdpi"}
                self.wait_for_home_measurements()
                return
            time.sleep(1)
        raise RuntimeError("A real VPN did not start. Grant Android VPN consent through the normal app first.")

    def wait_for_home_measurements(self) -> None:
        locale_state = self.adb("shell", "cmd", "locale", "get-app-locales", PACKAGE)
        locale = re.search(r"\[([a-zA-Z-]+)\]", locale_state)[1]
        self.home_locale = locale
        directory = "values" if locale == "en" else "values-" + locale.replace("-", "-r", 1)
        template = next(
            string.text for path in (ROOT / "app/src/main/res" / directory).glob("*.xml")
            for string in ElementTree.parse(path).getroot().findall("string")
            if string.get("name") == "home_quality_samples")
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            samples = home_sample_count(self.tree(), template)
            if samples >= 2:
                self.receipts.setdefault("homeMeasurements", {})[locale] = {
                    "sampleCount": samples, "observedAtUtc": datetime.now(timezone.utc).isoformat()}
                return
            time.sleep(2)
        raise RuntimeError("Real Home RTT measurements were not ready within one minute. No frame was saved.")

    def focus_home_measurements(self) -> None:
        offset = HOME_SCROLL_PIXELS[self.home_locale]
        before = self.bounds(self.tag(self.tree(), "connection-actuator-button"))[1]
        if offset:
            self.scroll(offset)
        after = self.bounds(self.tag(self.tree(), "connection-actuator-button"))[1]
        observed = before - after
        if abs(observed - offset) > 4:
            raise RuntimeError("The actual Home content did not reach its complete measurement viewport.")
        self.receipts["homeMeasurements"][self.home_locale].update({
            "contentScrollPixels": offset, "observedContentScrollPixels": observed})

    def disconnect_live(self) -> None:
        button = self.tag(self.tree(), "connection-actuator-button")
        # The connected actuator arms on the first click and confirms within 4 seconds.
        self.tap(button)
        self.tap(button)
        for _ in range(30):
            if "ni{VPN CONNECTED extra: VPN:com.poyka.ripdpi}" not in self.adb("shell", "dumpsys", "connectivity"):
                self.receipts["vpn"]["stoppedNormallyAtUtc"] = datetime.now(timezone.utc).isoformat()
                time.sleep(2)
                return
            time.sleep(1)
        raise RuntimeError("The normal Home disconnect did not stop the real VPN.")

    def latest_scan(self) -> dict | None:
        with tempfile.TemporaryDirectory(prefix="ripdpi-listing-scan-") as directory:
            path = Path(directory) / "diagnostics.db"
            for suffix in ("", "-wal"):
                Path(str(path) + suffix).write_bytes(self.adb("exec-out", "run-as", PACKAGE,
                                                            "cat", f"databases/diagnostics.db{suffix}", raw=True))
            with sqlite3.connect(path) as database:
                database.row_factory = sqlite3.Row
                row = database.execute("SELECT id, profileId, status, reportCompletionKind, reportTerminationReason, "
                                       "startedAt, finishedAt, reportJson FROM scan_sessions "
                                       "WHERE profileId = 'default' ORDER BY startedAt DESC LIMIT 1").fetchone()
                if row is None:
                    return None
                receipt = dict(row)
                report = receipt.pop("reportJson")
                receipt["reportBytes"] = len((report or "").encode())
                receipt["reportSha256"] = hashlib.sha256((report or "").encode()).hexdigest()
                receipt["resultCount"] = database.execute("SELECT count(*) FROM probe_results WHERE sessionId = ?",
                                                         (receipt["id"],)).fetchone()[0]
                receipt["outcomes"] = {row[0]: row[1] for row in database.execute(
                    "SELECT outcome, count(*) FROM probe_results WHERE sessionId = ? GROUP BY outcome", (receipt["id"],))}
                return receipt

    def show_completed_scan(self) -> None:
        if "diagnostics" not in self.receipts:
            previous = self.latest_scan()
            previous_id = previous["id"] if previous else None
            clicked_at = int(self.adb("shell", "date", "+%s")) * 1000
            self.tap(self.tag(self.tree(), "diagnostics-section-scan"))
            for _ in range(12):
                tree = self.tree()
                action = self.tag(tree, "diagnostics-scan-run-raw")
                if action is not None and self.bounds(action)[3] <= 1568:
                    self.tap(action)
                    break
                self.scroll(400)
            else:
                raise RuntimeError("The real Scan action is missing.")
            deadline = time.monotonic() + 300
            while time.monotonic() < deadline:
                receipt = self.latest_scan()
                if receipt and receipt["id"] != previous_id and receipt["status"] in ("failed", "cancelled"):
                    raise RuntimeError(f"The real scan did not complete: {receipt}")
                if is_new_completed_scan(receipt, previous_id, clicked_at):
                    receipt["previousSessionId"] = previous_id
                    receipt["clickedAt"] = clicked_at
                    self.receipts["diagnostics"] = receipt
                    break
                time.sleep(3)
            else:
                raise RuntimeError("The real diagnostic scan exceeded five minutes. No frame was saved.")
        self.tap(self.tag(self.tree(), "diagnostics-section-scan"))
        self.align(f"diagnostics-session-{self.receipts['diagnostics']['id']}", 590)

    def align(self, tag_name: str, top: int, attempts: int = 32) -> None:
        for _ in range(attempts):
            node = self.tag(self.tree(), tag_name)
            if node is None:
                self.scroll(500)
                continue
            delta = self.bounds(node)[1] - top
            if abs(delta) <= 4:
                return
            self.scroll(delta)
        raise RuntimeError(f"Could not align a complete capture section: {tag_name}")

    def show_relay_fields(self) -> None:
        self.tap(self.tag(self.tree(), "config-mode-proxy"))
        for _ in range(14):
            tree = self.tree()
            switch = next((node for node in tree.iter("node")
                           if node.get("checkable") == "true" and node.get("clickable") == "true"
                           and not node.get("resource-id")), None)
            if switch is not None:
                if switch.get("checked") == "false":
                    self.tap(switch)
                self.align("mode-editor-relay-section-tls-transports", 262)
                return
            self.scroll(550)
        raise RuntimeError("The relay editor switch is missing.")

    def show_dns_editor(self) -> None:
        for _ in range(16):
            self.adb("shell", "input", "swipe", "540", "1600", "540", "350", "120")
            time.sleep(0.5)
            if self.tag(self.tree(), "dns-custom-save") is not None:
                break
        else:
            raise RuntimeError("The actual custom DNS editor is missing.")
        # The last screen contains the full custom DoH and IPv6 cards.
        for _ in range(3):
            self.scroll(550)
        self.scroll(-45)

    def clear_statusbar_demo(self) -> None:
        # Keep Android's current time and real service/network indicators.
        self.adb("shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", "exit")
        self.adb("shell", "settings", "put", "global", "sysui_demo_allowed", "0")


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
                   "screen": "1080x1800", "densityDpi": DENSITY_DPI, "physicalDensityDpi": DENSITY_DPI},
        "theme": "light", "locales": list(LOCALES),
        "routes": {name: route for name, (route, _) in SCREENS.items()},
        "state": {"permissionPreset": "granted", "servicePreset": "live", "dataPreset": "settings_ready",
                  "motion": "disabled", "displayProfiles": DISPLAY_PROFILES, "homeScrollPixels": HOME_SCROLL_PIXELS, "statusBar": STATUS_BAR,
                  "home": "real VPN service started through the app; Android VPN consent granted normally",
                  "diagnostics": "Scan tab with a new completed direct-path scan; all observed outcomes preserved",
                  "relay": "proxy-mode editor showing supported relay transports; unsaved edit; no credentials or relay connection",
                  "dns-settings": "actual custom DoH editor prefilled from Cloudflare and IPv6 controls; no unsaved values applied",
                  "strategies": "actual built-in strategy editor with save, reload, import and export; no claimed strategy outcome",
                  "backup": "actual local backup export and restore controls",
                  "history": "first dedicated route clears previous history; later routes preserve the new scan"},
        "limitations": "App UI illustrations. Permission state uses the debug automation contract. No network, VPN, server or physical-device acceptance is claimed.",
        "runReceipts": device.receipts,
        "uiInputsSha256": inputs_sha256(inputs), "uiInputs": inputs, "images": images,
    }
    MANIFEST.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Dedicated 1080x1800, 360 dpi emulator; its app state is reset")
    parser.add_argument("--prebuilt-jni-libs", type=Path, help="Optional native outputs from the same source revision")
    parser.add_argument("--xray-artifacts", required=True, type=Path, help="Real producer artifacts; the release verifier must pass")
    args = parser.parse_args()
    device = CaptureDevice(args.serial)
    if "ACTIVATE_VPN: allow" not in device.adb("shell", "cmd", "appops", "get", PACKAGE, "ACTIVATE_VPN"):
        parser.error("Grant actual Android VPN consent through the normal app before capture.")
    device.adb("shell", "wm", "density", "reset")
    device.adb("shell", "settings", "put", "system", "font_scale", "1.0")
    if device.adb("shell", "wm", "size") != "Physical size: 1080x1800" or device.adb("shell", "wm", "density") != "Physical density: 360":
        parser.error("Use a dedicated emulator at physical 1080x1800, 360 dpi; no resize or crop is applied.")
    abi = device.adb("shell", "getprop", "ro.product.cpu.abi")
    if abi not in ("arm64-v8a", "x86_64"):
        parser.error("Use an arm64-v8a or x86_64 emulator.")
    xray_artifacts = args.xray_artifacts.resolve()
    subprocess.run(["bash", "scripts/native/verify-libxray-artifacts.sh", "--release"],
                   cwd=ROOT, env={**os.environ, "RIPDPI_XRAY_AAR_DIR": str(xray_artifacts)}, check=True)
    revision = run(["git", "rev-parse", "HEAD"])
    inputs = input_hashes()
    build_args = ["./gradlew", ":app:assembleGithubFullDebug", f"-Pripdpi.localNativeAbis={abi}",
                  f"-Pripdpi.prebuiltXrayAarDir={xray_artifacts}"]
    if args.prebuilt_jni_libs:
        build_args.append(f"-Pripdpi.prebuiltJniLibsDir={args.prebuilt_jni_libs.resolve()}")
    subprocess.run(build_args, cwd=ROOT, check=True)
    apk = ROOT / f"app/build/outputs/apk/githubFull/debug/app-github-full-{abi}-debug.apk"
    device.adb("install", "-r", str(apk))
    api = int(device.adb("shell", "getprop", "ro.build.version.sdk"))
    for permission in runtime_permissions(api):
        device.adb("shell", "pm", "grant", PACKAGE, permission)
    package_state = device.adb("shell", "dumpsys", "package", PACKAGE)
    if not all(f"{permission}: granted=true" in package_state for permission in runtime_permissions(api)):
        raise RuntimeError("Required real Android runtime permissions were not granted.")
    device.receipts["permissions"] = {"api": api, "granted": list(runtime_permissions(api)),
                                      "observedAtUtc": datetime.now(timezone.utc).isoformat()}
    device.adb("shell", "cmd", "uimode", "night", "no")
    for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        device.adb("shell", "settings", "put", "global", setting, "0")
    device.adb("shell", "settings", "put", "system", "screen_off_timeout", "1800000")
    device.adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    device.adb("shell", "input", "keyevent", "KEYCODE_MENU")
    device.clear_statusbar_demo()
    try:
        first = True
        for locale in LOCALES:
            device.adb("shell", "cmd", "locale", "set-app-locales", PACKAGE, "--locales", locale)
            actual_locale = device.adb("shell", "cmd", "locale", "get-app-locales", PACKAGE)
            if f"[{locale}]" not in actual_locale:
                raise RuntimeError(f"The Android locale did not change to {locale}.")
            for name, (route, source_name) in SCREENS.items():
                device.show_route(name, route, reset=first)
                first = False
                path = PROJECT / f"public/screenshots/{locale}/{source_name}.png"
                path.parent.mkdir(parents=True, exist_ok=True)
                observed_density = device.adb("shell", "wm", "density")
                density_values = list(map(int, re.findall(r"density: ([0-9]+)", observed_density)))
                profile = {"densityDpi": density_values[-1],
                           "fontScale": float(device.adb("shell", "settings", "get", "system", "font_scale"))}
                if profile != DISPLAY_PROFILES[name] or device.adb("shell", "wm", "size") != "Physical size: 1080x1800":
                    raise RuntimeError("The actual frame display does not match its capture profile.")
                device.receipts.setdefault("displayFrames", {})[f"{locale}/{name}"] = {
                    **profile, "physicalDensityDpi": density_values[0], "screen": "1080x1800"}
                path.write_bytes(device.adb("exec-out", "screencap", "-p", raw=True))
                direct = ROOT / f"docs/screenshots/ui/{locale}/{name}.png"
                direct.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, direct)
                if locale == "en":
                    shutil.copyfile(path, PROJECT / f"public/screenshots/{source_name}.png")
                print(f"Captured {locale}/{name}", flush=True)
                if name == "home":
                    device.disconnect_live()
        record_manifest(device, apk, xray_artifacts, revision, inputs)
    finally:
        device.clear_statusbar_demo()
        device.adb("shell", "wm", "density", "reset")
        device.adb("shell", "settings", "put", "system", "font_scale", "1.0")
    print("Inspect all frames, then run bun run capture:prod. These frames do not prove network acceptance.")


if __name__ == "__main__":
    main()
