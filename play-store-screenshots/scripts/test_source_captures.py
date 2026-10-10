"""Integrity and freshness failure paths. Test bytes are never published frames."""
import importlib.util
import json
import struct
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from source_captures import LOCALES, SCREENS, input_hashes, inputs_sha256, sha256

spec = importlib.util.spec_from_file_location("validator", Path(__file__).with_name("validate-source-captures.py"))
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)


class SourceCaptureTests(unittest.TestCase):
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
                data = b"\x89PNG\r\n\x1a\n" + b"\0\0\0\rIHDR" + struct.pack(">II", 1344, 2992) + f"{locale}/{name}".encode()
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
                     "theme": "light", "routes": {name: route for name, (route, _) in SCREENS.items()},
                     "uiInputs": self.inputs, "uiInputsSha256": inputs_sha256(self.inputs), "images": images}
        self.save()

    def save(self):
        self.manifest.write_text(json.dumps(self.data))

    def validate(self, inputs=None):
        with patch.object(validator, "input_hashes", return_value=inputs or self.inputs):
            return validator.validate(self.manifest, self.root)

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
