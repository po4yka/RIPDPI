"""Deterministic controller tests, never publish mocked evidence as captures."""
import copy
import json
import struct
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch
from xml.etree import ElementTree

from test_source_captures import capture


def switch_tree(checked="false", top=300, tag="mode-editor-relay-enabled", enabled="true", duplicate=False):
    node = f'<node resource-id="{tag}" bounds="[100,{top}][900,{top + 80}]" enabled="{enabled}" clickable="true" checkable="true" checked="{checked}" />'
    return ElementTree.fromstring('<hierarchy><node scrollable="true" bounds="[50,200][1030,1670]">' + node * (2 if duplicate else 1) + '</node></hierarchy>')


class RelaySwitchTests(unittest.TestCase):
    def device(self, trees):
        device = capture.CaptureDevice("emulator-5556")
        device.locale = "en"
        device.tree = Mock(side_effect=trees)
        device.tap = Mock()
        device.scroll = Mock()
        return device

    @patch.object(capture.time, "sleep")
    def test_waits_for_stable_bounds_and_checked_readback(self, _sleep):
        device = self.device([switch_tree(top=top) for top in (500, 400, 300, 300, 300)] + [switch_tree("true")] * 3)
        device.enable_relay_for_capture()
        device.tap.assert_called_once()
        self.assertEqual(device.bounds(device.tap.call_args.args[0])[1], 300)
        self.assertEqual(device.tree.call_count, 8)

    @patch.object(capture.time, "sleep")
    def test_missed_tap_retries_only_after_stable_unchecked_readback(self, _sleep):
        device = self.device([switch_tree()] * 6 + [switch_tree("true")] * 3)
        device.enable_relay_for_capture()
        self.assertEqual(device.tap.call_count, 2)

    @patch.object(capture.time, "sleep")
    def test_already_enabled_does_not_toggle_off(self, _sleep):
        device = self.device([switch_tree("true")] * 3)
        device.enable_relay_for_capture()
        device.tap.assert_not_called()

    @patch.object(capture.time, "sleep")
    def test_repeated_missed_taps_fail_closed(self, _sleep):
        device = self.device([switch_tree()] * 24)
        with self.assertRaisesRegex(RuntimeError, "did not become checked"):
            device.enable_relay_for_capture()
        self.assertEqual(device.tap.call_count, 3)
        self.assertEqual(device.tree.call_count, 12)

    @patch.object(capture.time, "sleep")
    def test_lookalike_missing_disabled_and_duplicate_never_tapped(self, _sleep):
        for tree in (switch_tree(tag="unrelated"), switch_tree(enabled="false"), switch_tree(duplicate=True)):
            device = self.device([tree] * 24)
            with self.assertRaises(RuntimeError):
                device.enable_relay_for_capture()
            device.tap.assert_not_called()
            self.assertLessEqual(device.tree.call_count, 24)

    @patch.object(capture.time, "sleep")
    def test_partial_control_scrolled_before_tap(self, _sleep):
        device = self.device([switch_tree(top=1650)] + [switch_tree()] * 3 + [switch_tree("true")] * 3)
        device.enable_relay_for_capture()
        device.scroll.assert_called_once_with(250, x=58)
        device.tap.assert_called_once()

    def test_boundary_probe_never_accepts_unaligned_geometry(self):
        device = self.device([])
        device.tree = Mock(return_value=ElementTree.fromstring('<hierarchy><node scrollable="true" bounds="[50,200][1030,1670]"><node resource-id="chip" bounds="[100,283][900,359]" /></node></hierarchy>'))
        with self.assertRaisesRegex(capture.RelayLayoutError, "scroll boundary"):
            device.align("chip")
        self.assertEqual(device.tree.call_count, 4)
        device.scroll.assert_any_call(600, x=58)

    @patch.object(capture.time, "sleep")
    def test_whole_but_shifted_preset_is_rejected(self, _sleep):
        names = ["mode-editor-relay-section-" + section for section in
                 ("tls-transports", "proxy-transports", "quic-transports", "pt-relays", "tor")]
        names.extend(("mode-editor-relay-chip-ru-mobile-relay", "mode-editor-advanced"))
        tree = ElementTree.fromstring('<hierarchy><node scrollable="true" bounds="[50,200][1030,1670]">' +
            ''.join(f'<node resource-id="{name}" bounds="[100,230][900,1500]" />' for name in names) +
            '</node></hierarchy>')
        device = self.device([tree])
        with self.assertRaisesRegex(capture.RelayLayoutError, "lost its required top alignment"):
            device.verify_relay_sections()
        self.assertNotIn("relaySections", device.receipts)


class CalibrationTests(unittest.TestCase):
    def run_calibration(self, directory, *, failures=(), wrong_size=False):
        device = Mock()
        device.receipts = {"buildBundle": {"builtFromRevision": "original", "manifestSha256": "sealed"}}
        state = {"locale": "en", "attempt": 0}
        def adb(*args):
            if args[:4] == ("shell", "cmd", "locale", "set-app-locales"):
                state["locale"] = args[-1]
            profile = capture.display_profile(state["locale"], "relay")
            return {("shell", "wm", "density"): f'Physical density: 360\nOverride density: {profile["densityDpi"]}',
                    ("shell", "wm", "size"): "Physical size: 1080x1800",
                    ("shell", "settings", "get", "system", "font_scale"): str(profile["fontScale"]),
                    ("shell", "cmd", "locale", "get-app-locales", capture.PACKAGE): f'[{state["locale"]}]'}.get(args, "")
        device.adb.side_effect = adb
        def show(*args, **kwargs):
            index = state["attempt"]
            state["attempt"] += 1
            if index < len(failures) and failures[index]:
                raise failures[index]
        device.show_route.side_effect = show
        def evidence(path, error):
            path.mkdir(parents=True)
            (path / "frame-before-cleanup.png").write_bytes(b'\x89PNG\r\n\x1a\n' + b'\0' * 8 + struct.pack('>II', 1 if wrong_size else 1080, 1800))
            (path / "ui-before-cleanup.xml").write_text('<hierarchy/>')
            for name in ("window-before-cleanup.txt", "display-before-cleanup.txt"):
                (path / name).write_text("observed test evidence")
        device.save_failure_diagnostics.side_effect = evidence
        apk = directory / "test.apk"
        apk.write_bytes(b"unit test only")
        candidates = {locale: (270, 271) for locale in capture.LOCALES}
        with patch.object(capture, "RELAY_CALIBRATION_DENSITIES", candidates), patch.object(capture, "run", return_value="capture-revision"), patch.object(capture, "input_hashes", return_value={"input": "hash"}), patch.object(capture, "record_manifest") as manifest:
            result = capture.calibrate_relay_profiles(device, directory / "diagnostics", apk, "original", {"input": "hash"})
            manifest.assert_not_called()
        return result, device

    def test_two_real_trials_required_and_profiles_restored(self):
        before = copy.deepcopy(capture.DISPLAY_OVERRIDES)
        with tempfile.TemporaryDirectory() as tmp:
            result, device = self.run_calibration(Path(tmp), failures=[capture.RelayLayoutError("clipped")])
        self.assertTrue(result["complete"])
        self.assertEqual(len(result["trials"]), 15)
        self.assertEqual(result["selectedProfiles"]["en"]["densityDpi"], 271)
        self.assertEqual(result["builtFromRevision"], "original")
        self.assertEqual(result["captureToolRevision"], "capture-revision")
        self.assertEqual(capture.DISPLAY_OVERRIDES, before)
        self.assertEqual(device.save_failure_diagnostics.call_count, 15)

    def test_fatal_state_error_is_recorded_and_aborts_not_next_candidate(self):
        before = copy.deepcopy(capture.DISPLAY_OVERRIDES)
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(RuntimeError, "switch failed"):
                self.run_calibration(Path(tmp), failures=[RuntimeError("switch failed")])
            report = json.loads((Path(tmp) / "diagnostics/calibration.json").read_text())
            self.assertEqual(len(report["trials"]), 1)
            self.assertFalse(report["complete"])
            self.assertIn("displayReadback", report["trials"][0])
        self.assertEqual(capture.DISPLAY_OVERRIDES, before)

    def test_finite_exhaustion_is_failure_without_selection(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(RuntimeError, "Finite relay calibration exhausted"):
                self.run_calibration(Path(tmp), failures=[capture.RelayLayoutError("clipped")] * 14)
            report = json.loads((Path(tmp) / "diagnostics/calibration.json").read_text())
            self.assertEqual(len(report["trials"]), 14)
            self.assertEqual(report["selectedProfiles"], {})

    def test_wrong_raw_dimensions_fail_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(RuntimeError, "PNG dimensions"):
                self.run_calibration(Path(tmp), wrong_size=True)


if __name__ == "__main__":
    unittest.main()
