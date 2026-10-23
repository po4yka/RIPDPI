#!/usr/bin/env python3
"""Build and capture the actual app on a dedicated emulator. No result fixtures."""
from __future__ import annotations

import argparse
import copy
import json
import os
import re
import shutil
import sqlite3
import tempfile
import hashlib
import subprocess
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from xml.etree import ElementTree

from source_captures import FRAME_SIZE, DENSITY_DPI, DISPLAY_PROFILES, DISPLAY_OVERRIDES, DNS_BOTTOM_FOCUS_PIXELS, BACKUP_SCROLL_PIXELS, display_profile, STATUS_BAR, HOME_SCROLL_PIXELS, LOCALES, MANIFEST, PROJECT, ROOT, SCREENS, input_hashes, inputs_sha256, sha256

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


class RelayLayoutError(RuntimeError):
    """A real relay frame could not satisfy the unchanged layout contract."""


class CaptureDevice:
    def __init__(self, serial: str) -> None:
        if not serial.startswith("emulator-"):
            raise ValueError("Use a dedicated emulator. This command resets app state.")
        self.serial = serial
        self.receipts = {}
        self.tree_sequence = 0
        self.tree_nonce = uuid.uuid4().hex

    def adb(self, *args: str, raw: bool = False) -> bytes | str:
        return run(["adb", "-s", self.serial, *args], raw=raw)

    def verify_locale(self, expected: str) -> None:
        actual = self.adb("shell", "cmd", "locale", "get-app-locales", PACKAGE)
        if f"[{expected}]" not in actual:
            raise RuntimeError(f"The actual Android locale does not match {expected}. No frame was saved.")

    def tree(self) -> ElementTree.Element:
        # uiautomator can return success while a recreating Activity has no idle
        # hierarchy. Never read yesterday's dump or silently accept malformed XML.
        for attempt in range(3):
            self.tree_sequence += 1
            path = f"/sdcard/ripdpi-docs-{self.tree_nonce}-{self.tree_sequence}.xml"
            dump_output = self.adb("shell", "uiautomator", "dump", path)
            try:
                raw = self.adb("exec-out", "cat", path)
            except subprocess.CalledProcessError as error:
                decode = lambda value: value.decode(errors="replace") if isinstance(value, bytes) else (value or "")
                stdout, stderr = decode(error.stdout), decode(error.stderr)
                self.receipts.setdefault("hierarchyRetries", []).append({
                    "attempt": attempt + 1, "path": path, "dumpOutput": dump_output,
                    "rawResponse": stdout, "stderr": stderr, "returncode": error.returncode})
                # A successful dump command can still fail to create a file when
                # no idle hierarchy exists. Offline/transport errors are fatal.
                if error.returncode != 1 or "No such file or directory" not in stdout + stderr:
                    raise
                if attempt == 2:
                    raise RuntimeError("Three fresh UI hierarchy acquisitions failed; no frame accepted.") from error
                time.sleep(1)
                continue
            try:
                tree = ElementTree.fromstring(raw)
                if tree.tag != "hierarchy":
                    raise ElementTree.ParseError("Expected a UI hierarchy root")
            except ElementTree.ParseError as error:
                self.receipts.setdefault("hierarchyRetries", []).append({
                    "attempt": attempt + 1, "path": path, "dumpOutput": dump_output,
                    "rawResponse": raw, "error": str(error)})
                if attempt == 2:
                    raise RuntimeError("Three fresh UI hierarchy acquisitions failed; no frame accepted.") from error
                time.sleep(1)
            else:
                return tree
        raise AssertionError("Unreachable hierarchy retry state")

    @staticmethod
    def tag(tree: ElementTree.Element, name: str) -> ElementTree.Element | None:
        return next((node for node in tree.iter("node") if node.get("resource-id") == name), None)

    @staticmethod
    def bounds(node: ElementTree.Element) -> list[int]:
        return list(map(int, re.findall(r"-?\d+", node.get("bounds", ""))))

    def tap(self, node: ElementTree.Element | None) -> None:
        if node is None:
            raise RuntimeError("The expected control is missing.")
        x1, y1, x2, y2 = self.bounds(node)
        self.adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        time.sleep(0.6)

    def scroll(self, delta: int, *, x: int = 540) -> int:
        density = int(re.findall(r"density: ([0-9]+)", self.adb("shell", "wm", "density"))[-1])
        slop = round(density * 8 / 160)
        step = max(-600, min(600, delta + (slop if delta > 0 else -slop)))
        self.adb("shell", "input", "swipe", str(x), "1450" if step > 0 else "750", str(x), str(1450 - step) if step > 0 else str(750 - step), "900")
        time.sleep(0.8)
        return step

    def show_route(self, name: str, route: str, *, reset: bool = False) -> None:
        locale_state = self.adb("shell", "cmd", "locale", "get-app-locales", PACKAGE)
        self.locale = re.search(r"\[([a-zA-Z-]+)\]", locale_state)[1]
        profile = display_profile(self.locale, name)
        self.adb("shell", "am", "force-stop", PACKAGE)
        self.adb("shell", "wm", "density", str(profile["densityDpi"]) if profile["densityDpi"] != DENSITY_DPI else "reset")
        self.adb("shell", "settings", "put", "system", "font_scale", str(profile["fontScale"]))
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
        elif name == "backup":
            self.focus_backup()

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
        # Keep the preceding scan action behind the fixed header, with the result heading visible.
        self.align(f"diagnostics-session-{self.receipts['diagnostics']['id']}", 470)

    @staticmethod
    def viewport_bounds(tree: ElementTree.Element, node: ElementTree.Element) -> list[int]:
        parents = {child: parent for parent in tree.iter() for child in parent}
        ancestor = parents.get(node)
        while ancestor is not None:
            if ancestor.get("scrollable") == "true":
                bounds = CaptureDevice.bounds(ancestor)
                if len(bounds) != 4 or bounds[3] <= bounds[1] or bounds[2] <= bounds[0] + 8:
                    raise RuntimeError("The actual capture viewport bounds are invalid. No frame was saved.")
                return bounds
            ancestor = parents.get(ancestor)
        raise RuntimeError("The actual capture viewport is missing. No frame was saved.")

    def align(self, tag_name: str, top: int | None = None, attempts: int = 32) -> None:
        observations = []
        unchanged = 0
        previous_bounds = None
        boundary_probe = False
        self.receipts.setdefault("alignment", {})[tag_name] = observations
        for _ in range(attempts):
            tree = self.tree()
            node = self.tag(tree, tag_name)
            if node is None:
                observations.append({"missing": True})
                intrinsic = self.receipts.get("relayIntrinsic", {}).get(getattr(self, "locale", ""), {})
                if tag_name == "mode-editor-relay-chip-ru-mobile-relay" and "height" in intrinsic.get("mode-editor-advanced", {}):
                    # Measurement deliberately ends below this known preset.
                    self.scroll(-400, x=intrinsic["mode-editor-advanced"]["viewport"][0] + 8)
                else:
                    self.scroll(500)
                continue
            viewport = self.viewport_bounds(tree, node) if top is None else None
            target_top = top if top is not None else viewport[1] + 4
            bounds = self.bounds(node)
            delta = bounds[1] - target_top
            reference = self.receipts.get("relayIntrinsic", {}).get(getattr(self, "locale", ""), {}).get(tag_name, {})
            expected_height = reference.get("height")
            complete_height = expected_height is None or bounds[3] - bounds[1] >= expected_height
            if viewport is not None and not complete_height and bounds[1] == viewport[1]:
                # Clipped accessibility bounds hide how far the real top is above
                # the viewport. Use its independently measured full height.
                delta = -(expected_height - (bounds[3] - bounds[1]) + target_top - viewport[1])
            observations.append({"bounds": bounds, "viewport": viewport,
                                 "targetTop": target_top, "delta": delta, "expectedHeight": expected_height})
            if abs(delta) <= 4 and complete_height:
                return
            geometry = (self.bounds(node), viewport)
            unchanged = unchanged + 1 if geometry == previous_bounds else 0
            if boundary_probe and geometry == previous_bounds:
                raise RelayLayoutError(f"Actual scroll boundary prevents complete alignment: {tag_name}")
            boundary_probe = viewport is not None and unchanged >= 2
            previous_bounds = geometry
            kwargs = {}
            if viewport is not None:
                # The editor's scroll padding is outside inputs, including the local port.
                kwargs["x"] = viewport[0] + 8
            stroke = self.scroll((600 if delta > 0 else -600) if boundary_probe else delta, **kwargs)
            observations[-1]["swipePixels"] = stroke if isinstance(stroke, int) else None
        raise RelayLayoutError(f"Could not align a complete capture section: {tag_name}")

    def save_failure_diagnostics(self, directory: Path, error: Exception | None) -> None:
        """Observe before cleanup changes density or recreates the activity."""
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "capture-state.json").write_text(json.dumps(
            {"outcome": "failed" if error else "validated", "error": str(error) if error else None, "runReceipts": self.receipts}, indent=2) + "\n")
        commands = {
            "frame-before-cleanup.png": ("exec-out", "screencap", "-p"),
            "window-before-cleanup.txt": ("shell", "dumpsys", "window"),
            "display-before-cleanup.txt": ("shell", "wm", "density"),
        }
        for name, command in commands.items():
            try:
                (directory / name).write_bytes(self.adb(*command, raw=True))
            except Exception as diagnostic_error:
                print(f"Could not collect {name}: {diagnostic_error}", flush=True)
        try:
            ElementTree.ElementTree(self.tree()).write(directory / "ui-before-cleanup.xml")
        except Exception as diagnostic_error:
            print(f"Could not collect pre-cleanup UI: {diagnostic_error}", flush=True)

    def measure_relay_intrinsic_sizes(self) -> None:
        """Measure boundary controls fully inside the same real scroll viewport.

        UIAutomator truncates bounds at clipping edges. Interior observations
        provide independent full sizes before the final screenshot alignment.
        """
        measured = {}
        self.receipts.setdefault("relayIntrinsic", {})[self.locale] = measured
        for name in ("mode-editor-relay-chip-ru-mobile-relay", "mode-editor-advanced"):
            previous = None
            stable = 0
            observations = []
            measured[name] = {"observations": observations}
            for _ in range(24):
                tree = self.tree()
                node = self.tag(tree, name)
                if node is None:
                    viewport_node = next((n for n in tree.iter("node") if n.get("scrollable") == "true"), None)
                    if viewport_node is None:
                        raise RuntimeError("Missing real editor viewport during intrinsic measurement.")
                    viewport = self.bounds(viewport_node)
                    if len(viewport) != 4:
                        raise RuntimeError("Invalid real editor viewport during intrinsic measurement.")
                    observations.append({"missing": True, "viewport": viewport})
                    self.scroll(400, x=viewport[0] + 8)
                    previous, stable = None, 0
                    continue
                bounds = self.bounds(node)
                viewport = self.viewport_bounds(tree, node)
                observations.append({"bounds": bounds, "viewport": viewport})
                if len(bounds) != 4:
                    raise RuntimeError("Invalid control bounds during intrinsic measurement.")
                interior = (viewport[0] + 4 <= bounds[0] < bounds[2] <= viewport[2] - 4
                            and viewport[1] + 4 <= bounds[1] < bounds[3] <= viewport[3] - 4)
                if interior:
                    geometry = (bounds, viewport)
                    stable = stable + 1 if geometry == previous else 1
                    previous = geometry
                    if stable >= 3:
                        measured[name].update({"width": bounds[2] - bounds[0], "height": bounds[3] - bounds[1],
                                               "bounds": bounds, "viewport": viewport, "unchangedObservations": stable})
                        break
                    time.sleep(0.5)
                else:
                    self.scroll(-250 if bounds[1] < viewport[1] + 4 else 250, x=viewport[0] + 8)
                    previous, stable = None, 0
            else:
                raise RelayLayoutError(f"Could not measure complete interior control: {name}")

    def verify_relay_sections(self) -> None:
        names = ["mode-editor-relay-section-" + section for section in
                 ("tls-transports", "proxy-transports", "quic-transports", "pt-relays", "tor")]
        names.extend(("mode-editor-relay-chip-ru-mobile-relay", "mode-editor-advanced"))
        previous = None
        stable = 0
        for _ in range(8):
            tree = self.tree()
            observed = {}
            for name in names:
                node = self.tag(tree, name)
                if node is None:
                    raise RelayLayoutError(f"The complete relay section is missing: {name}")
                bounds = self.bounds(node)
                viewport = self.viewport_bounds(tree, node)
                if not (len(bounds) == 4 and viewport[0] <= bounds[0] < bounds[2] <= viewport[2]
                        and viewport[1] <= bounds[1] < bounds[3] <= viewport[3]):
                    raise RelayLayoutError(f"The relay section is clipped: {name}")
                if name == "mode-editor-relay-chip-ru-mobile-relay" and abs(bounds[1] - viewport[1] - 4) > 4:
                    raise RelayLayoutError("The complete relay preset lost its required top alignment.")
                if name in ("mode-editor-relay-chip-ru-mobile-relay", "mode-editor-advanced"):
                    reference = self.receipts.get("relayIntrinsic", {}).get(self.locale, {}).get(name, {})
                    if "width" not in reference or "height" not in reference:
                        raise RuntimeError("Full interior relay control dimensions were not observed.")
                    width, height = bounds[2] - bounds[0], bounds[3] - bounds[1]
                    if not (reference["width"] <= width <= reference["width"] + 1
                            and reference["height"] <= height <= reference["height"] + 1):
                        raise RelayLayoutError(f"The relay control is truncated relative to its full interior size: {name}")
                observed[name] = {"bounds": bounds, "viewport": viewport}
            stable = stable + 1 if observed == previous else 1
            if stable >= 3:
                self.receipts.setdefault("relaySections", {})[self.locale] = {
                    "unchangedObservations": stable, "sections": observed}
                return
            previous = observed
            time.sleep(0.5)
        raise RelayLayoutError("The complete relay sections did not settle within eight observations.")

    def enable_relay_for_capture(self) -> None:
        tag_name = "mode-editor-relay-enabled"
        previous = None
        stable = 0
        taps = 0
        observations = []
        self.receipts.setdefault("relaySwitch", {})[self.locale] = observations
        for _ in range(24):
            tree = self.tree()
            matches = [node for node in tree.iter("node") if node.get("resource-id") == tag_name]
            if len(matches) > 1:
                raise RuntimeError("The relay enable switch is ambiguous.")
            if not matches:
                self.scroll(400)
                previous, stable = None, 0
                continue
            switch = matches[0]
            bounds = self.bounds(switch)
            viewport = self.viewport_bounds(tree, switch)
            if any(switch.get(key) != "true" for key in ("enabled", "checkable", "clickable")):
                raise RuntimeError("The tagged relay switch is not an enabled control.")
            whole = (len(bounds) == 4 and viewport[0] <= bounds[0] < bounds[2] <= viewport[2]
                     and viewport[1] <= bounds[1] < bounds[3] <= viewport[3])
            geometry = (bounds, viewport, switch.get("checked"))
            observations.append({"bounds": bounds, "viewport": viewport, "checked": switch.get("checked")})
            if len(bounds) != 4:
                raise RuntimeError("The tagged relay switch has invalid bounds.")
            if not whole:
                self.scroll(250 if bounds[3] > viewport[3] else -250, x=viewport[0] + 8)
                previous, stable = None, 0
                continue
            stable = stable + 1 if geometry == previous else 1
            previous = geometry
            if stable < 3:
                time.sleep(0.5)
                continue
            if switch.get("checked") == "true":
                return
            if switch.get("checked") != "false" or taps >= 3:
                raise RuntimeError("The relay switch did not become checked after observed taps.")
            self.tap(switch)
            taps += 1
            # Never blindly toggle again: require a fresh stable unchecked readback.
            previous, stable = None, 0
        raise RuntimeError("The relay switch did not settle in a complete visible viewport.")

    def show_relay_fields(self) -> None:
        self.tap(self.tag(self.tree(), "config-mode-proxy"))
        self.enable_relay_for_capture()
        self.measure_relay_intrinsic_sizes()
        self.align("mode-editor-relay-chip-ru-mobile-relay")
        self.verify_relay_sections()

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
        before = self.bounds(self.tag(self.tree(), "dns-custom-save"))[1]
        offset = DNS_BOTTOM_FOCUS_PIXELS[self.locale]
        if offset:
            self.scroll(-offset)
        after = self.bounds(self.tag(self.tree(), "dns-custom-save"))[1]
        observed = after - before
        if abs(observed - offset) > 4:
            raise RuntimeError(f"The actual DNS editor did not reach its complete viewport: {self.locale}, requested {offset}px, observed {observed}px.")
        self.receipts.setdefault("dnsFocus", {})[self.locale] = {
            "requestedBottomScrollPixels": offset, "observedBottomScrollPixels": observed,
            "observedAtUtc": datetime.now(timezone.utc).isoformat()}

    def focus_backup(self) -> None:
        offset = BACKUP_SCROLL_PIXELS[self.locale]
        before = self.bounds(self.tag(self.tree(), "backup-import-button"))[1]
        if offset:
            self.scroll(offset)
        after = self.bounds(self.tag(self.tree(), "backup-import-button"))[1]
        observed = before - after
        if abs(observed - offset) > 4:
            raise RuntimeError("The actual backup controls did not reach their complete viewport.")
        self.receipts.setdefault("backupFocus", {})[self.locale] = {
            "requestedScrollPixels": offset, "observedScrollPixels": observed,
            "observedAtUtc": datetime.now(timezone.utc).isoformat()}

    @staticmethod
    def locale_migration_complete(package_state: str) -> bool:
        enabled = False
        for line in package_state.splitlines():
            value = line.strip()
            if value == "enabledComponents:":
                enabled = True
            elif enabled and value == "androidx.appcompat.app.AppLocalesMetadataHolderService":
                return True
            elif value.endswith(":"):
                enabled = False
        return False

    def initialize_calibration_app(self) -> None:
        """Let first-install AppCompat locale migration finish before shell locales.

        Observe its enabled metadata component; never mutate that marker or the
        app's secure-window policy. This does not start a VPN or claim a scan.
        """
        self.adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.activities.MainActivity",
                 "--ez", f"{PACKAGE}.automation.ENABLED", "true",
                 "--ez", f"{PACKAGE}.automation.RESET_STATE", "true",
                 "--ez", f"{PACKAGE}.automation.DISABLE_MOTION", "true",
                 "--es", f"{PACKAGE}.automation.START_ROUTE", "home",
                 "--es", f"{PACKAGE}.automation.PERMISSION_PRESET", "granted",
                 "--es", f"{PACKAGE}.automation.SERVICE_PRESET", "live",
                 "--es", f"{PACKAGE}.automation.DATA_PRESET", "settings_ready")
        previous = None
        stable = 0
        observations = []
        self.receipts["calibrationInitialization"] = observations
        for _ in range(30):
            tree = self.tree()
            home = self.tag(tree, "connection-actuator-button") is not None
            migrated = self.locale_migration_complete(self.adb("shell", "dumpsys", "package", PACKAGE))
            locale = self.adb("shell", "cmd", "locale", "get-app-locales", PACKAGE)
            observations.append({"homeReady": home, "localeMigrationComplete": migrated, "localeReadback": locale})
            state = (home, migrated, locale)
            stable = stable + 1 if state == previous else 1
            if home and migrated and stable >= 3:
                self.adb("shell", "am", "force-stop", PACKAGE)
                return
            previous = state
            time.sleep(1)
        raise RuntimeError("Initial Home and AppCompat locale migration did not settle before calibration.")

    def approve_vpn_consent(self) -> None:
        """Accept only Android's real VPN dialog on an explicitly disposable emulator."""
        self.adb("shell", "cmd", "locale", "set-app-locales", PACKAGE, "--locales", "en")
        self.adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.activities.MainActivity",
                 "--ez", f"{PACKAGE}.automation.ENABLED", "true",
                 "--ez", f"{PACKAGE}.automation.RESET_STATE", "true",
                 "--ez", f"{PACKAGE}.automation.DISABLE_MOTION", "true",
                 "--es", f"{PACKAGE}.automation.START_ROUTE", "home",
                 "--es", f"{PACKAGE}.automation.PERMISSION_PRESET", "granted",
                 "--es", f"{PACKAGE}.automation.SERVICE_PRESET", "live",
                 "--es", f"{PACKAGE}.automation.DATA_PRESET", "settings_ready")
        for _ in range(30):
            if self.tag(self.tree(), "connection-actuator-button") is not None:
                break
            time.sleep(1)
        else:
            raise RuntimeError("The settings seed did not finish before normal VPN consent.")
        # The debug permission preset intentionally intercepts consent. Keep its
        # persisted settings, but restart without automation for the real OS flow.
        self.adb("shell", "am", "force-stop", PACKAGE)
        self.adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.activities.MainActivity")
        for _ in range(30):
            button = self.tag(self.tree(), "connection-actuator-button")
            if button is not None:
                self.tap(button)
                break
            time.sleep(1)
        else:
            raise RuntimeError("The real Home connection control did not appear for VPN consent.")
        rationale_accepted = False
        for _ in range(30):
            dialog = self.tree()
            rationale = self.tag(dialog, "vpn-permission-dialog-continue")
            if rationale is not None and rationale.get("package") == PACKAGE and not rationale_accepted:
                self.tap(rationale)
                rationale_accepted = True
                time.sleep(1)
                continue
            button = next((node for node in dialog.iter("node")
                           if node.get("package") == "com.android.vpndialogs"
                           and node.get("resource-id") == "android:id/button1"
                           and node.get("clickable") == "true"), None)
            if button is not None:
                self.tap(button)
                break
            time.sleep(1)
        else:
            raise RuntimeError("Android's normal VPN consent dialog did not appear; no permission was changed.")
        for _ in range(10):
            if "ACTIVATE_VPN: allow" in self.adb("shell", "cmd", "appops", "get", PACKAGE, "ACTIVATE_VPN"):
                self.receipts["vpnConsent"] = {
                    "method": "Normal Android VPN consent dialog accepted on disposable emulator",
                    "observedAtUtc": datetime.now(timezone.utc).isoformat()}
                return
            time.sleep(1)
        raise RuntimeError("Android did not record the normal VPN consent.")

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
        "capturedWithRevision": run(["git", "rev-parse", "HEAD"]),
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
                  "motion": "disabled", "displayProfiles": DISPLAY_PROFILES, "localeDisplayOverrides": DISPLAY_OVERRIDES, "dnsBottomFocusPixels": DNS_BOTTOM_FOCUS_PIXELS, "backupScrollPixels": BACKUP_SCROLL_PIXELS, "homeScrollPixels": HOME_SCROLL_PIXELS, "statusBar": STATUS_BAR,
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


def show_capture_route(device: CaptureDevice, locale: str, name: str, route: str, *,
                       reset: bool, diagnostics: Path | None, failures: list[str]) -> bool:
    try:
        device.show_route(name, route, reset=reset)
    except RuntimeError as error:
        # Live connection/scan failures are not independent presentation failures.
        if name in ("home", "diagnostics"):
            raise
        failures.append(f"{locale}/{name}: {error}")
        if diagnostics:
            device.save_failure_diagnostics(diagnostics / "routes" / locale / name, error)
        print(f"Capture failed for {locale}/{name}: {error}", flush=True)
        return False
    return True


def require_complete_capture(failures: list[str]) -> None:
    if failures:
        raise RuntimeError("Incomplete source capture; no manifest written: " + "; ".join(failures))


# Finite diagnostic search only. Normal capture never changes committed profiles.
RELAY_CALIBRATION_DENSITIES = {
    "en": (271, 270, 272, 269, 273),
    **{locale: (267, 268, 266, 269, 265, 270, 264, 271, 263, 262, 261, 260, 259, 258, 257, 256, 255, 254, 253)
       for locale in ("ru", "es", "de", "fr")},
    "fa": (243, 242, 241, 240, 244, 245, 246, 247, 248, 249, 250, 251, 252),
    "zh-CN": (252, 251, 250, 253, 254, 255, 249, 248),
}


def calibrate_relay_profiles(device: CaptureDevice, directory: Path, apk: Path,
                             build_revision: str, inputs: dict[str, str]) -> dict:
    directory.mkdir(parents=True, exist_ok=True)
    report = {"kind": "diagnostic-only; not source captures", "complete": False,
              "builtFromRevision": build_revision, "captureToolRevision": run(["git", "rev-parse", "HEAD"]),
              "apkSha256": sha256(apk), "uiInputsSha256": inputs_sha256(inputs), "uiInputs": inputs,
              "buildBundle": device.receipts.get("buildBundle"),
              "candidates": RELAY_CALIBRATION_DENSITIES, "selectedProfiles": {}, "trials": []}
    saved_overrides = copy.deepcopy(DISPLAY_OVERRIDES)
    originals = {locale: dict(display_profile(locale, "relay")) for locale in LOCALES}
    try:
        for locale in LOCALES:
            device.adb("shell", "am", "force-stop", PACKAGE)
            device.adb("shell", "cmd", "locale", "set-app-locales", PACKAGE, "--locales", locale)
            device.verify_locale(locale)
            for density in RELAY_CALIBRATION_DENSITIES[locale]:
                profile = {**originals[locale], "densityDpi": density}
                DISPLAY_OVERRIDES[locale]["relay"] = profile
                passed = True
                # A candidate must establish the switch/layout twice from a fresh route.
                for repetition in range(2):
                    trial = {"locale": locale, "requestedProfile": profile, "repetition": repetition,
                             "observedAtUtc": datetime.now(timezone.utc).isoformat()}
                    for key in ("alignment", "relaySections", "relaySwitch", "relayIntrinsic", "calibrationTrial"):
                        device.receipts.pop(key, None)
                    trial_dir = directory / locale / f"{density}-{repetition}"
                    error = None
                    try:
                        device.show_route("relay", "mode_editor", reset=False)
                        observed = {"densityDpi": int(re.findall(r"density: ([0-9]+)", device.adb("shell", "wm", "density"))[-1]),
                                    "fontScale": float(device.adb("shell", "settings", "get", "system", "font_scale"))}
                        trial["observedProfile"] = observed
                        if observed != profile or device.adb("shell", "wm", "size") != "Physical size: 1080x1800":
                            raise RuntimeError("Calibration display readback differs from the requested physical profile.")
                        device.verify_locale(locale)
                    except Exception as caught:
                        error = caught
                        passed = False
                    trial["displayReadback"] = {
                        "density": device.adb("shell", "wm", "density"),
                        "size": device.adb("shell", "wm", "size"),
                        "fontScale": device.adb("shell", "settings", "get", "system", "font_scale"),
                        "locale": device.adb("shell", "cmd", "locale", "get-app-locales", PACKAGE),
                    }
                    trial.update({"passed": passed, "error": str(error) if error else None})
                    device.receipts["calibrationTrial"] = trial
                    device.save_failure_diagnostics(trial_dir, error)
                    raw = (trial_dir / "frame-before-cleanup.png").read_bytes()
                    if raw[:8] != b"\x89PNG\r\n\x1a\n" or len(raw) < 24:
                        raise RuntimeError("Calibration raw PNG evidence is missing or invalid.")
                    if (int.from_bytes(raw[16:20], "big"), int.from_bytes(raw[20:24], "big")) != FRAME_SIZE:
                        raise RuntimeError("Calibration raw PNG dimensions changed.")
                    ElementTree.parse(trial_dir / "ui-before-cleanup.xml")
                    for evidence in ("window-before-cleanup.txt", "display-before-cleanup.txt"):
                        if not (trial_dir / evidence).read_bytes():
                            raise RuntimeError("Calibration raw diagnostics are incomplete.")
                    report["trials"].append(trial)
                    (directory / "calibration.json").write_text(json.dumps(report, indent=2) + "\n")
                    print(f"Calibration {locale} {density} dpi trial {repetition + 1}: {'PASS' if passed else error}", flush=True)
                    if error is not None and not isinstance(error, RelayLayoutError):
                        raise error
                    if not passed:
                        break
                if passed:
                    report["selectedProfiles"][locale] = profile
                    break
        report["complete"] = set(report["selectedProfiles"]) == set(LOCALES)
        if input_hashes() != inputs:
            raise RuntimeError("Android inputs changed during layout calibration.")
        (directory / "calibration.json").write_text(json.dumps(report, indent=2) + "\n")
        if not report["complete"]:
            raise RuntimeError("Finite relay calibration exhausted without complete validated profiles.")
        return report
    finally:
        DISPLAY_OVERRIDES.clear()
        DISPLAY_OVERRIDES.update(saved_overrides)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Dedicated 1080x1800, 360 dpi emulator; its app state is reset")
    parser.add_argument("--prebuilt-jni-libs", type=Path, help="Optional native outputs from the same source revision")
    parser.add_argument("--xray-artifacts", type=Path, help="Real producer artifacts; the release verifier must pass")
    parser.add_argument("--approve-vpn-consent", action="store_true",
                        help="Accept the normal Android VPN dialog on this disposable emulator")
    parser.add_argument("--failure-diagnostics", type=Path, help="Keep raw partial frames and pre-cleanup failure evidence, never a source manifest")
    parser.add_argument("--build-bundle", type=Path, help="Verified sealed capture build; its original producer revision is preserved")
    parser.add_argument("--calibrate-relay", action="store_true", help="Diagnostic-only finite relay profile trials; never writes source frames or manifest")
    args = parser.parse_args()
    if args.calibrate_relay and not args.failure_diagnostics:
        parser.error("Calibration requires a diagnostics directory.")
    if not args.build_bundle and not args.xray_artifacts:
        parser.error("Provide a sealed build bundle or real Xray producer artifacts.")
    device = CaptureDevice(args.serial)
    device.adb("shell", "wm", "density", "reset")
    device.adb("shell", "settings", "put", "system", "font_scale", "1.0")
    if device.adb("shell", "wm", "size") != "Physical size: 1080x1800" or device.adb("shell", "wm", "density") != "Physical density: 360":
        parser.error("Use a dedicated emulator at physical 1080x1800, 360 dpi; no resize or crop is applied.")
    abi = device.adb("shell", "getprop", "ro.product.cpu.abi")
    if abi not in ("arm64-v8a", "x86_64"):
        parser.error("Use an arm64-v8a or x86_64 emulator.")
    inputs = input_hashes()
    if args.build_bundle:
        if abi != "x86_64":
            parser.error("Sealed capture builds require the original x86_64 emulator ABI.")
        from capture_build import verify
        bundle = verify(args.build_bundle)
        apk, xray_artifacts, revision = bundle["apk"], bundle["xray"], bundle["builtFromRevision"]
        device.receipts["buildBundle"] = {"builtFromRevision": revision,
                                           "manifestSha256": sha256(args.build_bundle / "capture-build.json")}
    else:
        xray_artifacts = args.xray_artifacts.resolve()
        revision = run(["git", "rev-parse", "HEAD"])
        build_args = ["./gradlew", ":app:assembleGithubFullDebug", f"-Pripdpi.nativeAbisOverride={abi}",
                      f"-Pripdpi.prebuiltXrayAarDir={xray_artifacts}"]
        if args.prebuilt_jni_libs:
            build_args.append(f"-Pripdpi.prebuiltJniLibsDir={args.prebuilt_jni_libs.resolve()}")
        subprocess.run(build_args, cwd=ROOT, check=True)
        apk = ROOT / f"app/build/outputs/apk/githubFull/debug/app-github-full-{abi}-debug.apk"
    subprocess.run(["bash", "scripts/native/verify-libxray-artifacts.sh", "--release"],
                   cwd=ROOT, env={**os.environ, "RIPDPI_XRAY_AAR_DIR": str(xray_artifacts)}, check=True)
    device.adb("install", "-r", str(apk))
    api = int(device.adb("shell", "getprop", "ro.build.version.sdk"))
    for permission in runtime_permissions(api):
        device.adb("shell", "pm", "grant", PACKAGE, permission)
    package_state = device.adb("shell", "dumpsys", "package", PACKAGE)
    if not all(f"{permission}: granted=true" in package_state for permission in runtime_permissions(api)):
        raise RuntimeError("Required real Android runtime permissions were not granted.")
    if not args.calibrate_relay and "ACTIVATE_VPN: allow" not in device.adb("shell", "cmd", "appops", "get", PACKAGE, "ACTIVATE_VPN"):
        if not args.approve_vpn_consent:
            parser.error("Grant actual Android VPN consent through the normal app before capture, or explicitly use --approve-vpn-consent on a disposable emulator.")
        device.approve_vpn_consent()
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
        if args.calibrate_relay:
            device.initialize_calibration_app()
            calibrate_relay_profiles(device, args.failure_diagnostics / "calibration", apk, revision, inputs)
            print("Calibration only: inspect proposed profiles and raw trials, then run fixed-profile capture.", flush=True)
            return
        first = True
        presentation_failures = []
        for locale in LOCALES:
            device.adb("shell", "am", "force-stop", PACKAGE)
            device.adb("shell", "cmd", "locale", "set-app-locales", PACKAGE, "--locales", locale)
            device.verify_locale(locale)
            for name, (route, source_name) in SCREENS.items():
                if not show_capture_route(device, locale, name, route, reset=first,
                                          diagnostics=args.failure_diagnostics, failures=presentation_failures):
                    continue
                first = False
                path = PROJECT / f"public/screenshots/{locale}/{source_name}.png"
                path.parent.mkdir(parents=True, exist_ok=True)
                observed_density = device.adb("shell", "wm", "density")
                density_values = list(map(int, re.findall(r"density: ([0-9]+)", observed_density)))
                profile = {"densityDpi": density_values[-1],
                           "fontScale": float(device.adb("shell", "settings", "get", "system", "font_scale"))}
                if profile != display_profile(locale, name) or device.adb("shell", "wm", "size") != "Physical size: 1080x1800":
                    raise RuntimeError("The actual frame display does not match its capture profile.")
                device.receipts.setdefault("displayFrames", {})[f"{locale}/{name}"] = {
                    **profile, "physicalDensityDpi": density_values[0], "screen": "1080x1800"}
                device.verify_locale(locale)
                path.write_bytes(device.adb("exec-out", "screencap", "-p", raw=True))
                if args.failure_diagnostics:
                    partial = args.failure_diagnostics / "partial-frames" / locale / f"{name}.png"
                    partial.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(path, partial)
                direct = ROOT / f"docs/screenshots/ui/{locale}/{name}.png"
                direct.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, direct)
                if locale == "en":
                    shutil.copyfile(path, PROJECT / f"public/screenshots/{source_name}.png")
                print(f"Captured {locale}/{name}", flush=True)
                if name == "home":
                    device.disconnect_live()
        require_complete_capture(presentation_failures)
        record_manifest(device, apk, xray_artifacts, revision, inputs)
    except Exception as error:
        if args.failure_diagnostics:
            try:
                device.save_failure_diagnostics(args.failure_diagnostics, error)
            except Exception as diagnostic_error:
                print(f"Could not save failure diagnostics: {diagnostic_error}", flush=True)
        raise
    finally:
        device.clear_statusbar_demo()
        device.adb("shell", "wm", "density", "reset")
        device.adb("shell", "settings", "put", "system", "font_scale", "1.0")
    print("Inspect all frames, then run bun run capture:prod. These frames do not prove network acceptance.")


if __name__ == "__main__":
    main()
