"""Installation intent survives partial installation without claiming unrelated apps."""

import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "ci"))
import tempfile
import unittest
from unittest.mock import patch
from android_owned_installation import OwnedInstallation, PACKAGES


class PackageAbsenceTests(unittest.TestCase):
    def owner(self):
        return OwnedInstallation(
            ["adb", "-s", "owned"],
            Path("/unused/receipt"),
            {},
            Path("/unused/apksigner"),
        )

    def test_missing_package_exit_one_requires_successful_absence_listing(self):
        for code in (0, 1):
            with (
                self.subTest(code=code),
                patch(
                    "android_owned_installation.subprocess.run",
                    side_effect=[
                        subprocess.CompletedProcess([], code, "", ""),
                        subprocess.CompletedProcess([], 0, "", ""),
                    ],
                ) as execute,
            ):
                self.assertIsNone(self.owner().installed_digest(PACKAGES["app"]))
                self.assertEqual(2, execute.call_count)

    def test_failed_path_for_existing_package_is_not_absence(self):
        with patch(
            "android_owned_installation.subprocess.run",
            side_effect=[
                subprocess.CompletedProcess([], 1, "", ""),
                subprocess.CompletedProcess([], 0, "package:com.poyka.ripdpi\n", ""),
            ],
        ):
            with self.assertRaises(RuntimeError):
                self.owner().installed_digest(PACKAGES["app"])

    def test_device_error_is_not_absence(self):
        with patch(
            "android_owned_installation.subprocess.run",
            return_value=subprocess.CompletedProcess(
                [], 1, "", "error: device offline"
            ),
        ) as execute:
            with self.assertRaises(RuntimeError):
                self.owner().installed_digest(PACKAGES["app"])
            self.assertEqual(1, execute.call_count)

    def test_randomized_android_package_path_is_hashed_without_rewriting(self):
        path = "/data/app/~~random-token==/com.poyka.ripdpi-package-token==/base.apk"
        payload = b"exact installed APK bytes"
        with (
            patch(
                "android_owned_installation.subprocess.run",
                return_value=subprocess.CompletedProcess(
                    [], 0, f"package:{path}\n", ""
                ),
            ),
            patch("android_owned_installation.subprocess.Popen") as launch,
        ):
            launch.return_value.stdout = io.BytesIO(payload)
            launch.return_value.wait.return_value = 0
            launch.return_value.poll.return_value = 0
            self.assertEqual(
                hashlib.sha256(payload).hexdigest(),
                self.owner().installed_digest(PACKAGES["app"]),
            )
            launch.assert_called_once_with(
                ["adb", "-s", "owned", "exec-out", "cat", path],
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
            )

    def test_unsafe_package_paths_remain_rejected(self):
        for path in (
            "/data/app/~~token/unsafe;command/base.apk",
            "/sdcard/~~token/com.poyka.ripdpi/base.apk",
        ):
            with (
                self.subTest(path=path),
                patch(
                    "android_owned_installation.subprocess.run",
                    return_value=subprocess.CompletedProcess(
                        [], 0, f"package:{path}\n", ""
                    ),
                ),
                patch("android_owned_installation.subprocess.Popen") as launch,
            ):
                with self.assertRaisesRegex(
                    RuntimeError, "one exact installed base APK"
                ):
                    self.owner().installed_digest(PACKAGES["app"])
                launch.assert_not_called()


class OwnershipTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.artifacts = {}
        for kind in PACKAGES:
            path = self.root / (kind + ".apk")
            path.write_bytes(kind.encode())
            self.artifacts[kind] = {
                "path": str(path),
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            }
        self.installed = dict.fromkeys(PACKAGES.values())
        self.signer = patch(
            "android_owned_installation.subprocess.run",
            return_value=subprocess.CompletedProcess(
                [], 0, "Signer #1 certificate SHA-256 digest: " + "a" * 64 + "\n"
            ),
        )
        self.verify = self.signer.start()
        self.addCleanup(self.signer.stop)

    def owner(self):
        owner = OwnedInstallation(
            ["adb", "-s", "owned-serial"],
            self.root / "intent.json",
            {"apks": self.artifacts},
            Path("/test/apksigner"),
        )
        owner.installed_digest = lambda package: self.installed[package]
        return owner

    def test_rejects_existing_unrelated_app_before_intent(self):
        self.installed[PACKAGES["app"]] = "b" * 64
        with self.assertRaisesRegex(RuntimeError, "unrelated"):
            self.owner().prepare()
        self.assertFalse((self.root / "intent.json").exists())
        self.verify.assert_not_called()

    def test_intent_exists_before_first_install(self):
        self.owner().prepare()
        state = json.loads((self.root / "intent.json").read_text())
        self.assertFalse(any(p["installed"] for p in state["packages"].values()))
        self.assertEqual(0, (self.root / "intent.json").stat().st_mode & 0o077)

    def test_crash_after_app_install_before_confirmation_reconciles(self):
        self.owner().prepare()
        self.installed[PACKAGES["app"]] = self.artifacts["app"]["sha256"]
        recovered = self.owner()
        recovered.prepare()
        recovered.confirm("app")
        self.assertTrue(recovered.state["packages"]["app"]["installed"])
        self.assertFalse(recovered.state["packages"]["test"]["installed"])

    def test_crash_after_both_installs_before_confirmation_reconciles(self):
        self.owner().prepare()
        for kind, package in PACKAGES.items():
            self.installed[package] = self.artifacts[kind]["sha256"]
        recovered = self.owner()
        recovered.prepare()
        for kind in PACKAGES:
            recovered.confirm(kind)
        self.assertTrue(
            all(p["installed"] for p in recovered.state["packages"].values())
        )

    def test_intervening_replacement_is_not_owned(self):
        self.owner().prepare()
        self.installed[PACKAGES["app"]] = "c" * 64
        with self.assertRaisesRegex(RuntimeError, "unrelated"):
            self.owner().prepare()

    def test_interrupted_upgrade_accepts_exact_previous_apk(self):
        self.owner().prepare()
        self.installed[PACKAGES["app"]] = self.artifacts["app"]["sha256"]
        path = Path(self.artifacts["app"]["path"])
        path.write_bytes(b"new-app")
        self.artifacts["app"]["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
        self.owner().prepare()
        self.owner().prepare()

    def test_other_serial_cannot_reuse_intent(self):
        self.owner().prepare()
        owner = self.owner()
        owner.adb[-1] = "different"
        with self.assertRaisesRegex(ValueError, "device mismatch"):
            owner.prepare()

    def test_crashed_temporary_write_does_not_wedge_reconciliation(self):
        self.owner().prepare()
        self.installed[PACKAGES["app"]] = self.artifacts["app"]["sha256"]
        stale = self.root / "intent.json.new"
        stale.write_text("partial")
        stale.chmod(0o600)
        recovered = self.owner()
        recovered.prepare()
        recovered.confirm("app")
        self.assertTrue(recovered.state["packages"]["app"]["installed"])
        self.assertEqual("partial", stale.read_text())

    def test_confirm_rejects_changed_apk(self):
        owner = self.owner()
        owner.prepare()
        self.installed[PACKAGES["app"]] = "d" * 64
        with self.assertRaisesRegex(RuntimeError, "does not match"):
            owner.confirm("app")

    def test_signature_mismatch_rejects_before_intent(self):
        self.signer.stop()
        with patch(
            "android_owned_installation.subprocess.run",
            side_effect=[
                subprocess.CompletedProcess(
                    [], 0, "Signer #1 certificate SHA-256 digest: " + v * 64 + "\n"
                )
                for v in ("a", "b")
            ],
        ):
            with self.assertRaisesRegex(ValueError, "signing identities differ"):
                self.owner().prepare()
        self.assertFalse((self.root / "intent.json").exists())


if __name__ == "__main__":
    unittest.main()
