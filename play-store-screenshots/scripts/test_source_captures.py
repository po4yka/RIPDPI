"""Integrity and freshness failure paths. Test bytes are never published frames."""
import importlib.util
import json
import struct
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch, Mock
from xml.etree import ElementTree

from source_captures import FRAME_SIZE, DENSITY_DPI, DISPLAY_PROFILES, DISPLAY_OVERRIDES, DNS_BOTTOM_FOCUS_PIXELS, display_profile, STATUS_BAR, HOME_SCROLL_PIXELS, LOCALES, SCREENS, input_hashes, inputs_sha256, sha256

spec = importlib.util.spec_from_file_location("validator", Path(__file__).with_name("validate-source-captures.py"))
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)


capture_spec = importlib.util.spec_from_file_location("capture_android", Path(__file__).with_name("capture-android.py"))
capture = importlib.util.module_from_spec(capture_spec)
capture_spec.loader.exec_module(capture)


class SourceCaptureTests(unittest.TestCase):
    def test_relay_bottom_focus_waits_for_stable_complete_content(self):
        def tree(at_end):
            preset = '' if at_end else '<node resource-id="mode-editor-relay-chip-ru-mobile-relay" bounds="[136,214][546,290]" />'
            tls_top, advanced_top = (221, 1521) if at_end else (303, 1603)
            return ElementTree.fromstring(
                '<hierarchy><node scrollable="true" bounds="[62,210][1018,1611]">'
                f'{preset}<node resource-id="mode-editor-relay-section-tls-transports" bounds="[136,{tls_top}][944,{tls_top + 396}]" />'
                f'<node resource-id="mode-editor-advanced" bounds="[107,{advanced_top}][973,{advanced_top + 77}]" />'
                '</node></hierarchy>')
        device = capture.CaptureDevice("emulator-5556")
        device.tree = Mock(side_effect=[tree(False), tree(True), tree(True), tree(True)])
        device.scroll = Mock()
        device.focus_relay_bottom()
        self.assertEqual(device.tree.call_count, 4)
        self.assertEqual(device.scroll.call_count, 3)
        for call in device.scroll.call_args_list:
            self.assertEqual(call.args, (600,))
            self.assertEqual(call.kwargs, {"x": 70})

    def test_relay_bottom_focus_rejects_stable_clipped_sections(self):
        for preset, advanced in ((None, (1603, 1680)), ((180, 256), (1521, 1598))):
            with self.subTest(preset=preset, advanced=advanced):
                preset_xml = (f'<node resource-id="mode-editor-relay-chip-ru-mobile-relay" bounds="[136,{preset[0]}][546,{preset[1]}]" />'
                              if preset else '')
                device = capture.CaptureDevice("emulator-5556")
                device.tree = Mock(return_value=ElementTree.fromstring(
                    '<hierarchy><node scrollable="true" bounds="[62,210][1018,1611]">'
                    f'{preset_xml}<node resource-id="mode-editor-relay-section-tls-transports" bounds="[136,221][944,617]" />'
                    f'<node resource-id="mode-editor-advanced" bounds="[107,{advanced[0]}][973,{advanced[1]}]" />'
                    '</node></hierarchy>'))
                device.scroll = Mock()
                with self.assertRaisesRegex(RuntimeError, "clipped section"):
                    device.focus_relay_bottom()

    def test_relay_bottom_focus_has_a_bounded_wait(self):
        device = capture.CaptureDevice("emulator-5556")
        device.tree = Mock(side_effect=[ElementTree.fromstring(
            '<hierarchy><node scrollable="true" bounds="[62,210][1018,1611]">'
            '<node resource-id="mode-editor-relay-section-tls-transports" bounds="[136,221][944,617]" />'
            f'<node resource-id="mode-editor-advanced" bounds="[107,{1521 + offset}][973,{1598 + offset}]" />'
            '</node></hierarchy>') for offset in range(8)])
        device.scroll = Mock()
        with self.assertRaisesRegex(RuntimeError, "eight observations"):
            device.focus_relay_bottom()
        self.assertEqual(device.tree.call_count, 8)

    def test_relay_bottom_focus_rejects_a_present_preset_without_bounds(self):
        device = capture.CaptureDevice("emulator-5556")
        device.tree = Mock(return_value=ElementTree.fromstring(
            '<hierarchy><node scrollable="true" bounds="[62,210][1018,1611]">'
            '<node resource-id="mode-editor-relay-chip-ru-mobile-relay" />'
            '<node resource-id="mode-editor-relay-section-tls-transports" bounds="[136,221][944,617]" />'
            '<node resource-id="mode-editor-advanced" bounds="[107,1521][973,1598]" />'
            '</node></hierarchy>'))
        device.scroll = Mock()
        with self.assertRaisesRegex(RuntimeError, "clipped section"):
            device.focus_relay_bottom()

    def test_relay_frame_keeps_the_last_recommended_preset_whole(self):
        device = capture.CaptureDevice("emulator-5556")
        device.locale = "en"
        device.tree = Mock(return_value=ElementTree.fromstring(
            '<hierarchy><node resource-id="config-mode-proxy" />'
            '<node checkable="true" clickable="true" checked="true" /></hierarchy>'))
        device.tap = Mock()
        device.align = Mock()
        device.focus_relay_bottom = Mock()
        device.show_relay_fields()
        device.align.assert_called_once_with("mode-editor-relay-chip-ru-mobile-relay")
        device.focus_relay_bottom.assert_not_called()
        device.locale = "zh-CN"
        device.show_relay_fields()
        device.focus_relay_bottom.assert_called_once_with()

    def test_relay_alignment_swipes_outside_the_visible_port_field(self):
        def tree(chip_top):
            return ElementTree.fromstring(
                '<hierarchy><node scrollable="true" bounds="[62,210][1018,1611]">'
                f'<node resource-id="last-chip" bounds="[136,{chip_top}][546,{chip_top + 76}]" />'
                '<node class="android.widget.EditText" text="11980" bounds="[136,1417][944,1494]" />'
                '</node></hierarchy>')
        device = capture.CaptureDevice("emulator-5556")
        device.tree = Mock(side_effect=[tree(225), tree(214)])
        device.adb = Mock(return_value="Physical density: 360\nOverride density: 255")
        with patch.object(capture.time, "sleep"):
            device.align("last-chip")
        device.adb.assert_any_call("shell", "input", "swipe", "70", "1450", "70", "1426", "900")

    def test_relay_alignment_uses_the_actual_header_height(self):
        for viewport_top in (160, 207):
            with self.subTest(viewport_top=viewport_top):
                def tree(section_top):
                    return ElementTree.fromstring(
                        f'<hierarchy><node scrollable="true" bounds="[0,{viewport_top}][1080,1630]">'
                        f'<node resource-id="relay-section" bounds="[128,{section_top}][980,1500]" />'
                        '</node></hierarchy>')
                device = capture.CaptureDevice("emulator-5556")
                device.tree = Mock(side_effect=[tree(262), tree(viewport_top + 4)])
                device.scroll = Mock()
                device.align("relay-section")
                device.scroll.assert_called_once_with(262 - viewport_top - 4, x=8)

    def test_relay_alignment_rejects_a_missing_scroll_viewport(self):
        device = capture.CaptureDevice("emulator-5556")
        device.tree = Mock(return_value=ElementTree.fromstring(
            '<hierarchy><node resource-id="relay-section" bounds="[128,262][980,1500]" /></hierarchy>'))
        device.scroll = Mock()
        with self.assertRaisesRegex(RuntimeError, "viewport is missing"):
            device.align("relay-section")
        device.scroll.assert_not_called()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.manifest = self.root / "source-capture.json"
        self.inputs = {"app/src/main/res/values/strings.xml": "a" * 64}
        images = {}
        for locale in LOCALES:
            for name, (_, source) in SCREENS.items():
                # Header-only fixtures exercise our hash and IHDR contract, not PNG decoding.
                data = b"\x89PNG\r\n\x1a\n" + b"\0\0\0\rIHDR" + struct.pack(">II", *FRAME_SIZE) + f"{locale}/{name}".encode()
                paths = [f"play-store-screenshots/public/screenshots/{locale}/{source}.png", f"docs/screenshots/ui/{locale}/{name}.png"]
                if locale == "en":
                    paths.append(f"play-store-screenshots/public/screenshots/{source}.png")
                for path in paths:
                    output = self.root / path
                    output.parent.mkdir(parents=True, exist_ok=True)
                    output.write_bytes(data)
                    images[path] = sha256(output)
        self.data = {"schemaVersion": 1, "locales": list(LOCALES), "builtFromRevision": "a" * 40,
                     "apk": {"variant": "githubFullDebug", "sha256": "b" * 64},
                     "libXray": {"manifestSha256": "c" * 64, "aarSha256": "d" * 64},
                     "device": {"screen": "1080x1800", "densityDpi": DENSITY_DPI, "api": 37},
                     "state": {"servicePreset": "live", "displayProfiles": DISPLAY_PROFILES, "statusBar": STATUS_BAR, "homeScrollPixels": HOME_SCROLL_PIXELS}, "runReceipts": {"vpn": {"service": "RipDpiVpnService", "transport": "VPN CONNECTED; owner com.poyka.ripdpi", "observedAtUtc": "2026-10-10T00:00:00+00:00", "stoppedNormallyAtUtc": "2026-10-10T00:00:01+00:00"}, "diagnostics": {"id": "new", "previousSessionId": "old", "profileId": "default", "status": "completed", "clickedAt": 1000, "startedAt": 2000, "finishedAt": 3000, "resultCount": 21, "reportBytes": 1024, "reportSha256": "f" * 64, "outcomes": {"healthy": 21}}},
                     "theme": "light", "routes": {name: route for name, (route, _) in SCREENS.items()},
                     "uiInputs": self.inputs, "uiInputsSha256": inputs_sha256(self.inputs), "images": images}
        self.data["runReceipts"]["homeMeasurements"] = {locale: {"sampleCount": 2, "observedAtUtc": "2026-10-10T00:00:00+00:00", "contentScrollPixels": HOME_SCROLL_PIXELS[locale], "observedContentScrollPixels": HOME_SCROLL_PIXELS[locale]} for locale in LOCALES}
        self.data["runReceipts"]["permissions"] = {"api": 37, "granted": list(capture.runtime_permissions(37)), "observedAtUtc": "2026-10-10T00:00:00+00:00"}
        self.data["runReceipts"]["displayFrames"] = {f"{locale}/{name}": {**display_profile(locale, name), "physicalDensityDpi": DENSITY_DPI, "screen": "1080x1800"} for locale in LOCALES for name in DISPLAY_PROFILES}
        self.data["state"].update({"localeDisplayOverrides": DISPLAY_OVERRIDES, "dnsBottomFocusPixels": DNS_BOTTOM_FOCUS_PIXELS})
        self.data["runReceipts"]["dnsFocus"] = {locale: {"requestedBottomScrollPixels": offset,
            "observedBottomScrollPixels": offset, "observedAtUtc": "2026-10-10T00:00:00+00:00"}
            for locale, offset in DNS_BOTTOM_FOCUS_PIXELS.items()}
        self.save()

    def save(self):
        self.manifest.write_text(json.dumps(self.data))

    def validate(self, inputs=None):
        with patch.object(validator, "input_hashes", return_value=inputs or self.inputs):
            return validator.validate(self.manifest, self.root)

    def test_a_changed_actual_locale_stops_capture(self):
        device = capture.CaptureDevice("emulator-5556")
        device.adb = Mock(return_value="Locales for com.poyka.ripdpi are [en]")
        with self.assertRaisesRegex(RuntimeError, "No frame was saved"):
            device.verify_locale("ru")
        device.verify_locale("en")

    def test_relay_recapture_cannot_change_the_apk_producer_receipt(self):
        self.data["runReceipts"]["fullBatchCapturedAtUtc"] = "2026-10-10T00:00:00+00:00"
        self.data["runReceipts"]["frameRecaptures"] = {"fa/relay": {
            "observedAtUtc": "2026-10-10T00:00:00+00:00", "apkSha256": self.data["apk"]["sha256"],
            "builtFromRevision": self.data["builtFromRevision"], "captureToolRevision": "1" * 40,
            "previousSha256": "2" * 64, "sha256": self.data["images"]["play-store-screenshots/public/screenshots/fa/relay.png"],
            "fullBatchCapturedAtUtc": "2026-10-10T00:00:00+00:00", "previousManifestSha256": "3" * 64}}
        self.save()
        self.assertEqual(self.validate(), [])
        self.data["runReceipts"]["frameRecaptures"]["fa/relay"]["apkSha256"] = "0" * 64
        self.save()
        self.assertTrue(any("truthful producer" in error for error in self.validate()))

    def test_unapplied_complete_relay_viewports_are_rejected(self):
        for locale in ("en", "fa", "zh-CN"):
            with self.subTest(locale=locale):
                self.data["runReceipts"]["displayFrames"][f"{locale}/relay"] = {
                    **DISPLAY_PROFILES["relay"], "physicalDensityDpi": DENSITY_DPI, "screen": "1080x1800"}
                self.save()
                self.assertTrue(any("per-frame density" in error for error in self.validate()))
                self.data["runReceipts"]["displayFrames"][f"{locale}/relay"] = {
                    **display_profile(locale, "relay"), "physicalDensityDpi": DENSITY_DPI, "screen": "1080x1800"}

    def test_unobserved_dns_focus_is_rejected(self):
        self.data["runReceipts"]["dnsFocus"]["ru"]["observedBottomScrollPixels"] = 0
        self.save()
        self.assertTrue(any("DNS editor viewport" in error for error in self.validate()))

    def test_unapplied_locale_display_override_is_rejected(self):
        self.data["runReceipts"]["displayFrames"]["fa/backup"]["densityDpi"] = DENSITY_DPI
        self.save()
        self.assertTrue(any("per-frame density" in error for error in self.validate()))

    def test_unobserved_home_scroll_is_rejected(self):
        self.data["runReceipts"]["homeMeasurements"]["fa"]["observedContentScrollPixels"] = 0
        self.save()
        self.assertTrue(any("Home content viewport" in error for error in self.validate()))

    def test_real_localized_home_sample_counts_are_required(self):
        for template, text, count in (("%1$d RTT samples", "2 RTT samples", 2),
                                      ("Образцов RTT: %1$d", "Образцов RTT: 3", 3),
                                      ("%1$d نمونهٔ RTT", "۲ نمونهٔ RTT", 2),
                                      ("%1$d RTT samples", "0 RTT samples", 0),
                                      ("%1$d RTT samples", "Traffic total 2 kB", 0)):
            tree = capture.ElementTree.Element("hierarchy")
            capture.ElementTree.SubElement(tree, "node", {"text": text})
            self.assertEqual(capture.home_sample_count(tree, template), count)
        self.data["runReceipts"]["homeMeasurements"]["en"]["sampleCount"] = 0
        self.save()
        self.assertTrue(any("Home RTT" in error for error in self.validate()))

    def test_demo_status_metadata_is_rejected(self):
        self.data["state"]["statusBar"] = "Android demo mode: 12:00"
        self.save()
        self.assertTrue(any("native Android status bar" in error for error in self.validate()))

    def test_capture_clears_demo_without_setting_synthetic_status_indicators(self):
        device = capture.CaptureDevice("emulator-5556")
        device.adb = Mock()
        device.clear_statusbar_demo()
        self.assertEqual(device.adb.call_args_list, [
            unittest.mock.call("shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", "exit"),
            unittest.mock.call("shell", "settings", "put", "global", "sysui_demo_allowed", "0"),
        ])

    def test_normal_disconnect_confirms_the_armed_control_before_polling(self):
        device = capture.CaptureDevice("emulator-5556")
        button = Mock()
        device.tree = Mock(return_value=button)
        device.tag = Mock(return_value=button)
        device.tap = Mock()
        device.adb = Mock(return_value="No owned VPN")
        device.receipts["vpn"] = {}
        with patch.object(capture.time, "sleep"):
            device.disconnect_live()
        self.assertEqual(device.tap.call_args_list, [unittest.mock.call(button), unittest.mock.call(button)])
        self.assertIn("stoppedNormallyAtUtc", device.receipts["vpn"])

    def test_only_a_new_completed_real_scan_is_accepted(self):
        receipt = {"id": "new", "startedAt": 2000, "finishedAt": 3000,
                   "status": "completed", "resultCount": 21, "reportBytes": 1024}
        self.assertTrue(capture.is_new_completed_scan(receipt, "old", 1500))
        self.assertFalse(capture.is_new_completed_scan(receipt, "new", 1500))
        self.assertFalse(capture.is_new_completed_scan(receipt, "old", 2500))
        for field, value in (("finishedAt", None), ("status", "failed"), ("resultCount", 0), ("reportBytes", 0)):
            self.assertFalse(capture.is_new_completed_scan(dict(receipt, **{field: value}), "old", 1500))

    def test_missing_or_stale_runtime_receipts(self):
        self.data["runReceipts"]["diagnostics"]["id"] = "old"
        self.save()
        self.assertTrue(any("new completed diagnostic" in error for error in self.validate()))
        self.data["runReceipts"] = {}
        self.save()
        self.assertTrue(any("real owned VPN" in error for error in self.validate()))

    def test_runtime_permission_contract_requires_local_network_on_api_37(self):
        self.assertEqual(capture.runtime_permissions(36), ("android.permission.POST_NOTIFICATIONS",))
        self.assertIn("android.permission.ACCESS_LOCAL_NETWORK", capture.runtime_permissions(37))

    def test_wrong_frame_display_and_missing_real_permissions_are_rejected(self):
        self.data["runReceipts"]["displayFrames"]["ru/relay"]["densityDpi"] = 360
        self.data["runReceipts"]["permissions"]["granted"].remove("android.permission.ACCESS_LOCAL_NETWORK")
        self.save()
        errors = self.validate()
        self.assertTrue(any("per-frame" in error for error in errors))
        self.assertTrue(any("runtime permission" in error for error in errors))

    def test_valid_snapshot(self):
        self.assertEqual(self.validate(), [])

    def test_missing_and_tampered_frame(self):
        target = self.root / "docs/screenshots/ui/ru/home.png"
        target.write_bytes(b"changed")
        self.assertTrue(any("Missing or changed" in error for error in self.validate()))
        target.unlink()
        self.assertTrue(any("Missing or changed" in error for error in self.validate()))

    def test_locale_reuse_and_wrong_dimensions(self):
        source = "play-store-screenshots/public/screenshots/ru/home-light.png"
        self.data["images"][source] = self.data["images"]["play-store-screenshots/public/screenshots/en/home-light.png"]
        self.save()
        self.assertTrue(any("reuse another locale" in error for error in self.validate()))
        target = self.root / "docs/screenshots/ui/de/relay.png"
        data = target.read_bytes()
        target.write_bytes(data[:16] + struct.pack(">II", 1080, 2400) + data[24:])
        self.data["images"][str(target.relative_to(self.root))] = sha256(target)
        self.save()
        self.assertTrue(any("uncropped" in error for error in self.validate()))

    def test_changed_ui_inputs_and_invalid_manifest(self):
        changed = dict(self.inputs, **{"app/src/main/res/values/strings.xml": "e" * 64})
        self.assertTrue(any("Android UI inputs changed" in error for error in self.validate(changed)))
        self.manifest.write_text("invalid json")
        self.assertTrue(any("Cannot read" in error for error in self.validate()))

    def test_hash_needs_no_commit_history_and_excludes_tests(self):
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        for path in ("app/src/main/res/values/strings.xml", "app/src/test/example.kt"):
            output = self.root / path
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text("current")
        subprocess.run(["git", "add", "app"], cwd=self.root, check=True)
        snapshot = input_hashes(self.root)
        self.assertEqual(set(snapshot), {"app/src/main/res/values/strings.xml"})
        ui = self.root / "app/src/main/res/values/strings.xml"
        ui.write_text("new")
        self.assertNotEqual(inputs_sha256(snapshot), inputs_sha256(input_hashes(self.root)))


if __name__ == "__main__":
    unittest.main()
