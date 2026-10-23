"""Synthetic local fixtures only: no Android/native payload is executed or published."""
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import zipfile

import capture_build as build


class CaptureBuildTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bundle = self.root / "sealed"
        self.apk = self.root / "original.apk"
        self.xray = self.root / "producer"
        self.xray.mkdir()
        (self.root / "app").mkdir()
        (self.root / "app/input.kt").write_text("original input")
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("lib/x86_64/libfixture.so", b"synthetic fixture, not executable")
        with zipfile.ZipFile(self.xray / "libxray.aar", "w") as archive:
            archive.writestr("jni/x86_64/libgojni.so", b"synthetic fixture, not executable")
        (self.xray / "build-go.mod").write_text("module fixture")
        (self.xray / "build-go.sum").write_text("fixture sum")
        producer = {"schemaVersion": 2, "abis": ["x86_64"]}
        for name, key in (("libxray.aar", "aarSha256"), ("build-go.mod", "goModSha256"), ("build-go.sum", "goSumSha256")):
            producer[key] = build.sha256(self.xray / name)
        (self.xray / "libxray-artifact.json").write_text(json.dumps(producer))
        self.revision = "a" * 40
        self.tracked = "app/input.kt\0"
        self.commands = patch.object(build, "command", side_effect=lambda args, root: self.revision if "rev-parse" in args else self.tracked)
        self.commands.start()
        self.addCleanup(self.commands.stop)
        self.tools = patch.object(build, "toolchains", return_value={"java": "17", "rustc": "1", "go": "1"})
        self.tools_mock = self.tools.start()
        self.addCleanup(self.tools.stop)

    def seal(self):
        return build.seal(self.bundle, self.apk, self.xray, self.root)

    def verify(self):
        return build.verify(self.bundle, self.root)

    def test_original_revision_preserved_after_capture_only_commit(self):
        self.seal()
        self.revision = "b" * 40
        result = self.verify()
        self.assertEqual(result["builtFromRevision"], "a" * 40)
        self.assertEqual(result["apk"].read_bytes(), self.apk.read_bytes())
        for name in build.XRAY_FILES:
            self.assertEqual((result["xray"] / name).read_bytes(), (self.xray / name).read_bytes())

    def test_original_revision_must_match_trusted_run(self):
        self.seal()
        build.verify(self.bundle, self.root, expected_revision=self.revision)
        with self.assertRaisesRegex(ValueError, "trusted producer run"):
            build.verify(self.bundle, self.root, expected_revision="b" * 40)

    def test_working_input_change_rejected(self):
        self.seal()
        (self.root / "app/input.kt").write_text("changed without commit")
        with self.assertRaisesRegex(ValueError, "inputs changed"):
            self.verify()

    def test_tracked_input_added_rejected(self):
        self.seal()
        (self.root / "app/added.kt").write_text("new")
        self.tracked += "app/added.kt\0"
        with self.assertRaisesRegex(ValueError, "inputs changed"):
            self.verify()

    def test_untracked_generated_files_ignored(self):
        self.seal()
        (self.root / "app/generated.kt").write_text("not tracked")
        self.verify()

    def test_missing_tracked_input_rejected(self):
        self.seal()
        (self.root / "app/input.kt").unlink()
        with self.assertRaisesRegex(ValueError, "missing regular file"):
            self.verify()

    def test_payload_tamper_rejected(self):
        self.seal()
        (self.bundle / "app.apk").write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            self.verify()

    def test_unexpected_file_or_directory_rejected(self):
        self.seal()
        extra = self.bundle / "extra"
        extra.mkdir()
        with self.assertRaisesRegex(ValueError, "unexpected bundle directory"):
            self.verify()
        extra.rmdir()
        extra.write_text("extra")
        with self.assertRaisesRegex(ValueError, "unexpected or missing"):
            self.verify()

    def test_missing_required_payload_rejected(self):
        self.seal()
        for name in ("app.apk", "xray/libxray-artifact.json", "xray/libxray.aar"):
            path = self.bundle / name
            data = path.read_bytes()
            path.unlink()
            with self.assertRaisesRegex(ValueError, "unexpected or missing"):
                self.verify()
            path.write_bytes(data)

    def test_manifest_path_escape_rejected(self):
        self.seal()
        manifest = self.bundle / build.MANIFEST
        metadata = json.loads(manifest.read_text())
        metadata["files"]["../original.apk"] = build.sha256(self.apk)
        manifest.write_text(json.dumps(metadata))
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            self.verify()
        for name in ("../escape", "/absolute", "app/../escape", "app\\escape", "app//escape"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                build.safe_path(self.root, name)

    def test_symlink_leaf_and_ancestor_rejected(self):
        self.seal()
        path = self.bundle / "app.apk"
        path.unlink()
        path.symlink_to(self.apk)
        with self.assertRaisesRegex(ValueError, "symlink"):
            self.verify()
        alias = self.root / "alias"
        alias.symlink_to(self.bundle, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "symlink"):
            build.verify(alias, self.root)

    def test_toolchain_change_and_unavailable_tool_rejected(self):
        self.seal()
        self.tools_mock.return_value = {"java": "21"}
        with self.assertRaisesRegex(ValueError, "toolchains changed"):
            self.verify()
        self.tools_mock.side_effect = FileNotFoundError("rustc")
        with self.assertRaises(FileNotFoundError):
            self.verify()

    def test_existing_bundle_never_overwritten(self):
        self.seal()
        before = (self.bundle / build.MANIFEST).read_bytes()
        with self.assertRaises(FileExistsError):
            self.seal()
        self.assertEqual((self.bundle / build.MANIFEST).read_bytes(), before)

    def test_apk_requires_native_x86_64_payload(self):
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("lib/arm64-v8a/libfixture.so", b"synthetic")
        with self.assertRaisesRegex(ValueError, "APK has no x86_64"):
            self.seal()
        self.assertFalse(self.bundle.exists())

    def test_xray_manifest_content_must_match(self):
        (self.xray / "build-go.mod").write_text("tampered")
        with self.assertRaisesRegex(ValueError, "Xray producer hash mismatch"):
            self.seal()

    def test_cached_git_inventory_only(self):
        with patch.object(build, "command", return_value=self.tracked) as run:
            build.input_hashes(self.root)
        args = run.call_args.args[0]
        self.assertIn("--cached", args)
        self.assertNotIn("--others", args)
        for name in ("app", "core", "native", "build-logic", "gradle", "scripts/native", ".github/actions/build-xray"):
            self.assertIn(name, args)

    def test_toolchain_capture_uses_actual_versions_and_native_cwd(self):
        self.tools.stop()
        versions = [Mock(stdout="", stderr='openjdk version "21.0.1"\n'),
                    Mock(stdout="", stderr='openjdk version "17.0.1"\n'),
                    Mock(stdout="rustc 1.98.1\nhost: x86_64-unknown-linux-gnu\n", stderr=""),
                    Mock(stdout="go version go1.27.2 linux/amd64\n", stderr="")]
        self.addCleanup(patch.stopall)
        patch.dict(os.environ, {"JAVA_HOME_21_X64": "/fixture/jdk21"}).start()
        patch.object(build, "android_packages", return_value={"ndk/fixture": "digest"}).start()
        with patch.object(build.subprocess, "run", side_effect=versions) as run:
            result = build.toolchains(self.root)
        self.assertEqual(result["go"], "go version go1.27.2 linux/amd64")
        self.assertEqual(run.call_args_list[2].kwargs["cwd"], self.root / "native/rust")
        with patch.object(build.subprocess, "run", return_value=Mock(stdout="", stderr="")):
            with self.assertRaisesRegex(ValueError, "unknown java21"):
                build.toolchains(self.root)

    def test_installed_android_packages_are_bound_to_content(self):
        sdk = self.root / "sdk"
        (self.root / "gradle.properties").write_text(
            "ripdpi.compileSdk=37\nripdpi.compileSdkMinor=1\n"
            "ripdpi.nativeNdkVersion=29.0.14206865\nripdpi.nativeCmakeVersion=3.31.6\n")
        names = ["platforms/android-37.1", "ndk/29.0.14206865", "cmake/3.31.6", "build-tools/36.0.0"]
        for name in names:
            path = sdk / name / "source.properties"
            path.parent.mkdir(parents=True)
            path.write_text("Pkg.Revision=fixture")
        with patch.dict(os.environ, {"ANDROID_SDK_ROOT": str(sdk)}):
            initial = build.android_packages(self.root)
            self.assertEqual(set(initial), set(names))
            (sdk / names[1] / "source.properties").write_text("Pkg.Revision=changed")
            self.assertNotEqual(build.android_packages(self.root), initial)
            (sdk / names[0] / "source.properties").unlink()
            with self.assertRaisesRegex(ValueError, "missing regular file"):
                build.android_packages(self.root)

    def test_capture_recipe_and_daemon_config_are_tracked(self):
        self.assertIn(".github/workflows/play-store-screenshots.yml", build.INPUT_PATHS)
        self.assertIn("play-store-screenshots/scripts/capture_build.py", build.INPUT_PATHS)
        self.assertIn("gradle", build.INPUT_PATHS)


if __name__ == "__main__":
    unittest.main()
