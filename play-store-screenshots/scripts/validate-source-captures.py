#!/usr/bin/env python3
"""Check device-frame integrity and UI input freshness, including shallow CI."""
from __future__ import annotations

import json
import sys
import struct
import re
from pathlib import Path

from source_captures import FRAME_SIZE, DENSITY_DPI, DISPLAY_PROFILES, DISPLAY_OVERRIDES, DNS_CONTROLS, BACKUP_CONTROLS, RELAY_EDGE_CONTROLS, RELAY_SECTIONS, display_profile, STATUS_BAR, HOME_SCROLL_PIXELS, LOCALES, MANIFEST, ROOT, SCREENS, VARIANT, SOURCE_SCHEMA_VERSION, input_hashes, inputs_sha256, sha256, whole_control_in_viewport, matches_control_size


def complete_control_bounds(observed: object, controls: tuple[str, ...]) -> bool:
    return (isinstance(observed, dict) and set(observed) == set(controls)
            and all(isinstance(control, dict)
                    and whole_control_in_viewport(control.get("bounds"), control.get("viewport"))
                    for control in observed.values()))


def complete_viewports(receipts: object, controls: tuple[str, ...]) -> bool:
    return (isinstance(receipts, dict) and set(receipts) == set(LOCALES)
            and all(isinstance(receipt, dict) and receipt.get("observedAtUtc")
                    and complete_control_bounds(receipt.get("controls"), controls)
                    for receipt in receipts.values()))


def complete_relay_viewports(relays: object, sizes: object) -> bool:
    if not (isinstance(relays, dict) and isinstance(sizes, dict) and set(relays) == set(sizes) == set(LOCALES)):
        return False
    for locale, receipt in relays.items():
        if not isinstance(receipt, dict) or receipt.get("unchangedObservations") != 3:
            return False
        sections = receipt.get("sections")
        references = sizes[locale]
        if (not complete_control_bounds(sections, RELAY_SECTIONS + RELAY_EDGE_CONTROLS)
                or not isinstance(references, dict) or set(references) != set(RELAY_EDGE_CONTROLS)):
            return False
        for tag, reference in references.items():
            if not isinstance(reference, dict) or reference.get("unchangedObservations") != 3:
                return False
            bounds, viewport = reference.get("bounds"), reference.get("viewport")
            if not whole_control_in_viewport(bounds, viewport):
                return False
            width, height = bounds[2] - bounds[0], bounds[3] - bounds[1]
            if (reference.get("width") != width or reference.get("height") != height
                    or not matches_control_size(sections[tag]["bounds"], width, height)):
                return False
    return True


def validate(manifest_path: Path = MANIFEST, root: Path = ROOT) -> list[str]:
    if not manifest_path.is_file():
        return ["Missing source-capture.json. Capture the Android frames first."]
    try:
        manifest = json.loads(manifest_path.read_text())
    except (ValueError, OSError) as error:
        return [f"Cannot read the source manifest: {error}"]
    errors = []
    if manifest.get("schemaVersion") != SOURCE_SCHEMA_VERSION:
        errors.append("Unsupported source-capture schema.")
    if manifest.get("locales") != list(LOCALES):
        errors.append("Capture all seven marketing locales.")
    if not re.fullmatch(r"[a-f0-9]{40}", manifest.get("builtFromRevision", "")):
        errors.append("Missing build revision provenance.")
    if manifest.get("apk", {}).get("variant") != VARIANT:
        errors.append(f"The capture APK must be {VARIANT}.")
    for section, key in (("apk", "sha256"), ("libXray", "manifestSha256"), ("libXray", "aarSha256")):
        if not re.fullmatch(r"[a-f0-9]{64}", manifest.get(section, {}).get(key, "")):
            errors.append(f"Missing producer hash: {section}.{key}")
    if manifest.get("theme") != "light" or manifest.get("routes") != {name: route for name, (route, _) in SCREENS.items()}:
        errors.append("Unexpected capture theme or routes.")
    if manifest.get("device", {}).get("screen") != "1080x1800" or manifest.get("device", {}).get("densityDpi") != DENSITY_DPI:
        errors.append("Expected the physical 1080x1800, 360 dpi capture device.")
    if manifest.get("state", {}).get("servicePreset") != "live":
        errors.append("Capture the real service state, not a connection fixture.")
    if manifest.get("state", {}).get("statusBar") != STATUS_BAR:
        errors.append("Use the native Android status bar without synthetic demo indicators.")
    receipts = manifest.get("runReceipts", {})
    apk_producer = receipts.get("apkProducer", {})
    abi = manifest.get("device", {}).get("abi")
    if (abi not in ("arm64-v8a", "x86_64") or apk_producer.get("abi") != abi
            or apk_producer.get("variant") != VARIANT
            or apk_producer.get("filename") != f"app-play-{abi}-debug.apk"
            or not re.fullmatch(r"[a-f0-9]{64}", apk_producer.get("gradleOutputMetadataSha256", ""))):
        errors.append("Missing the verified Gradle Play APK producer receipt.")
    for frame, receipt in receipts.get("frameRecaptures", {}).items():
        path = f"play-store-screenshots/public/screenshots/{frame}.png"
        if (frame not in ("fa/relay", "zh-CN/relay") or not receipt.get("observedAtUtc")
                or receipt.get("apkSha256") != manifest.get("apk", {}).get("sha256")
                or receipt.get("builtFromRevision") != manifest.get("builtFromRevision")
                or not re.fullmatch(r"[a-f0-9]{40}", receipt.get("captureToolRevision", ""))
                or not re.fullmatch(r"[a-f0-9]{64}", receipt.get("previousSha256", ""))
                or not receipt.get("fullBatchCapturedAtUtc")
                or receipt.get("fullBatchCapturedAtUtc") != receipts.get("fullBatchCapturedAtUtc")
                or not re.fullmatch(r"[a-f0-9]{64}", receipt.get("previousManifestSha256", ""))
                or receipt.get("sha256") != manifest.get("images", {}).get(path)):
            errors.append("Missing truthful producer and hash receipts for a recaptured relay frame.")
    vpn = receipts.get("vpn", {})
    diagnostic = receipts.get("diagnostics", {})
    if (vpn.get("service") != "RipDpiVpnService" or vpn.get("transport") != "VPN CONNECTED; owner com.poyka.ripdpi"
            or not vpn.get("observedAtUtc") or not vpn.get("stoppedNormallyAtUtc")):
        errors.append("Missing a real owned VPN connection receipt.")
    if (not diagnostic.get("id") or diagnostic.get("id") == diagnostic.get("previousSessionId")
            or diagnostic.get("profileId") != "default" or diagnostic.get("status") != "completed"
            or diagnostic.get("resultCount", 0) <= 0 or diagnostic.get("reportBytes", 0) <= 0
            or not re.fullmatch(r"[a-f0-9]{64}", diagnostic.get("reportSha256", ""))
            or diagnostic.get("clickedAt", 0) <= 0
            or diagnostic.get("startedAt", 0) < diagnostic.get("clickedAt", 0)
            or (diagnostic.get("finishedAt") or 0) < diagnostic.get("startedAt", 1)
            or sum(diagnostic.get("outcomes", {}).values()) != diagnostic.get("resultCount")):
        errors.append("Missing a new completed diagnostic receipt with real outcomes and report hash.")
    permission_receipt = receipts.get("permissions", {})
    expected_permissions = ["android.permission.POST_NOTIFICATIONS"]
    if manifest.get("device", {}).get("api", 0) >= 37:
        expected_permissions.append("android.permission.ACCESS_LOCAL_NETWORK")
    if (permission_receipt.get("api") != manifest.get("device", {}).get("api")
            or permission_receipt.get("granted") != expected_permissions or not permission_receipt.get("observedAtUtc")):
        errors.append("Missing actual Android runtime permission receipts.")
    measurements = receipts.get("homeMeasurements", {})
    if set(measurements) != set(LOCALES) or any(value.get("sampleCount", 0) < 2 or not value.get("observedAtUtc") for value in measurements.values()):
        errors.append("Missing actual Home RTT measurement readiness receipts.")
    if (manifest.get("state", {}).get("homeScrollPixels") != HOME_SCROLL_PIXELS
            or any(value.get("contentScrollPixels") != HOME_SCROLL_PIXELS.get(locale)
                   or abs(value.get("observedContentScrollPixels", -1000) - HOME_SCROLL_PIXELS.get(locale, 0)) > 4
                   for locale, value in measurements.items())):
        errors.append("Missing actual Home content viewport receipts.")
    displays = receipts.get("displayFrames", {})
    expected_displays = {f"{locale}/{name}": {**display_profile(locale, name), "physicalDensityDpi": DENSITY_DPI, "screen": "1080x1800"}
                         for locale in LOCALES for name in DISPLAY_PROFILES}
    if not complete_relay_viewports(receipts.get("relaySections"), receipts.get("relayIntrinsic")):
        errors.append("Missing actual relay editor viewport receipts.")
    if not complete_viewports(receipts.get("dnsFocus", {}), DNS_CONTROLS):
        errors.append("Missing actual DNS editor viewport receipts.")
    if not complete_viewports(receipts.get("backupFocus", {}), BACKUP_CONTROLS):
        errors.append("Missing actual backup controls viewport receipts.")
    if (displays != expected_displays or manifest.get("state", {}).get("displayProfiles") != DISPLAY_PROFILES
            or manifest.get("state", {}).get("localeDisplayOverrides") != DISPLAY_OVERRIDES):
        errors.append("Missing actual per-frame density and font-scale receipts.")
    inputs = input_hashes(root)
    if inputs_sha256(manifest.get("uiInputs", {})) != manifest.get("uiInputsSha256"):
        errors.append("The recorded UI input list does not match its digest.")
    if inputs_sha256(inputs) != manifest.get("uiInputsSha256"):
        previous = manifest.get("uiInputs", {})
        changed = sorted(path for path in inputs.keys() | previous.keys() if inputs.get(path) != previous.get(path))
        errors.append("Android UI inputs changed after capture: " + ", ".join(changed[:8]))
    images = manifest.get("images", {})
    expected = {
        f"play-store-screenshots/public/screenshots/{locale}/{source_name}.png"
        for locale in LOCALES for _, source_name in SCREENS.values()
    } | {
        f"docs/screenshots/ui/{locale}/{name}.png" for locale in LOCALES for name in SCREENS
    } | {
        f"play-store-screenshots/public/screenshots/{source_name}.png" for _, source_name in SCREENS.values()
    }
    if set(images) != expected:
        errors.append("The source-frame manifest must cover every raw frame and README copy.")
    for path in sorted(expected):
        image = root / path
        if not image.is_file() or sha256(image) != images.get(path):
            errors.append(f"Missing or changed device frame: {path}")
        elif len(image.read_bytes()) < 24 or image.read_bytes()[:8] != b"\x89PNG\r\n\x1a\n" or struct.unpack(">II", image.read_bytes()[16:24]) != FRAME_SIZE:
            errors.append(f"Expected an uncropped 1080x1800 Android frame: {path}")
    for locale in LOCALES:
        for name, (_, source_name) in SCREENS.items():
            raw = f"play-store-screenshots/public/screenshots/{locale}/{source_name}.png"
            direct = f"docs/screenshots/ui/{locale}/{name}.png"
            if images.get(raw) != images.get(direct):
                errors.append(f"README frame differs from its Android source: {locale}/{name}")
    for _, source_name in SCREENS.values():
        base = f"play-store-screenshots/public/screenshots/{source_name}.png"
        if images.get(base) != images.get(f"play-store-screenshots/public/screenshots/en/{source_name}.png"):
            errors.append(f"English alias differs from its source: {source_name}")
        hashes = [images.get(f"play-store-screenshots/public/screenshots/{locale}/{source_name}.png") for locale in LOCALES]
        if len(set(hashes)) != len(LOCALES):
            errors.append(f"Localized Android frames reuse another locale: {source_name}")
    return errors


def main() -> int:
    errors = validate()
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        print("Run python3 scripts/capture-android.py --serial <dedicated-emulator> --xray-artifacts <verified-aar-dir>; it builds the current APK. See README.md.", file=sys.stderr)
        return 1
    print("Source captures: 42 device frames, README copies, English aliases and Android UI input hashes match.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
