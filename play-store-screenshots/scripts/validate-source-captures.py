#!/usr/bin/env python3
"""Check device-frame integrity and UI input freshness, including shallow CI."""
from __future__ import annotations

import json
import sys
import struct
import re
from pathlib import Path

from source_captures import FRAME_SIZE, DENSITY_DPI, DISPLAY_PROFILES, STATUS_BAR, LOCALES, MANIFEST, ROOT, SCREENS, input_hashes, inputs_sha256, sha256


def validate(manifest_path: Path = MANIFEST, root: Path = ROOT) -> list[str]:
    if not manifest_path.is_file():
        return ["Missing source-capture.json. Capture the Android frames first."]
    try:
        manifest = json.loads(manifest_path.read_text())
    except (ValueError, OSError) as error:
        return [f"Cannot read the source manifest: {error}"]
    errors = []
    if manifest.get("schemaVersion") != 1:
        errors.append("Unsupported source-capture schema.")
    if manifest.get("locales") != list(LOCALES):
        errors.append("Capture all seven marketing locales.")
    if not re.fullmatch(r"[a-f0-9]{40}", manifest.get("builtFromRevision", "")):
        errors.append("Missing build revision provenance.")
    if manifest.get("apk", {}).get("variant") != "githubFullDebug":
        errors.append("The capture APK must be githubFullDebug.")
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
    displays = receipts.get("displayFrames", {})
    expected_displays = {f"{locale}/{name}": {**profile, "physicalDensityDpi": DENSITY_DPI, "screen": "1080x1800"}
                         for locale in LOCALES for name, profile in DISPLAY_PROFILES.items()}
    if displays != expected_displays or manifest.get("state", {}).get("displayProfiles") != DISPLAY_PROFILES:
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
