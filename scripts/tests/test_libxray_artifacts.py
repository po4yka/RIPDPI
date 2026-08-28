from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import struct
import tempfile
import tomllib
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("libxray_artifacts", ROOT / "scripts/native/libxray_artifacts.py")
ARTIFACTS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ARTIFACTS)


def elf_fixture(abi: str, alignment: int = 16384) -> bytes:
    elf_class, machine = ARTIFACTS.MACHINES[abi]
    payload = bytearray(128)
    payload[:6] = b"\x7fELF" + bytes([elf_class, 1])
    struct.pack_into("<H", payload, 18, machine)
    if elf_class == 2:
        struct.pack_into("<Q", payload, 32, 64)
        struct.pack_into("<HH", payload, 54, 56, 1)
        struct.pack_into("<Q", payload, 112, alignment)
    else:
        struct.pack_into("<I", payload, 28, 64)
        struct.pack_into("<HH", payload, 42, 32, 1)
        struct.pack_into("<I", payload, 92, alignment)
    struct.pack_into("<I", payload, 64, 1)
    return bytes(payload)


def artifact_fixture(directory: Path) -> dict:
    # Parser-only fixture. Real AAR creation and JNI execution are separate gates.
    versions, properties, patches = ARTIFACTS.policy()
    metadata = {"schemaVersion": 2, "channel": "stable", "libxray": versions["libxray"],
                "libxrayCommit": patches["libxrayCommit"], "xrayCore": versions["xray-core"],
                "xrayCoreModuleSum": patches["xrayCoreModuleSum"], "gomobile": versions["gomobile"],
                "grpc": patches["grpc"], "grpcModuleSum": patches["grpcModuleSum"],
                "minSdk": properties["ripdpi.minSdk"], "ndkVersion": properties["ripdpi.nativeNdkVersion"],
                "patchManifestSha256": ARTIFACTS.digest(ARTIFACTS.PATCHES / "manifest.json"),
                "buildRecipeSha256": ARTIFACTS.digest(ROOT / "scripts/native/build-libxray.sh"),
                "goVersion": "go1.27.0", "abis": ["arm64-v8a"]}
    with zipfile.ZipFile(directory / "libxray.aar", "w") as archive:
        archive.writestr("jni/arm64-v8a/libgojni.so", elf_fixture("arm64-v8a"))
        archive.writestr("classes.jar", b"unit-api")
    for name, key in (("build-go.mod", "goModSha256"), ("build-go.sum", "goSumSha256")):
        (directory / name).write_bytes(b"unit-module")
        metadata[key] = ARTIFACTS.digest(directory / name)
    metadata["aarSha256"] = ARTIFACTS.digest(directory / "libxray.aar")
    (directory / "libxray-artifact.json").write_text(json.dumps(metadata))
    return metadata


class LibXrayArtifactTest(unittest.TestCase):
    def test_go_provenance_uses_the_compiled_module_toolchain(self) -> None:
        with tempfile.TemporaryDirectory() as temporary, patch.object(ARTIFACTS.subprocess, "check_output", return_value="go1.27.0\n") as go:
            directory = Path(temporary)
            sources = directory / "sources"
            sources.mkdir()
            (sources / "go.mod").write_text("module unit\ngo 1.27.0\n")
            (sources / "go.sum").write_text("")
            (directory / "libxray.aar").write_bytes(b"unit-artifact")
            ARTIFACTS.create(directory, sources, "arm64-v8a")
            go.assert_called_once_with(["go", "env", "GOVERSION"], cwd=sources, text=True)

    def test_unattested_archive_is_rejected(self) -> None:
        # This is a malformed packaging fixture, never native runtime evidence.
        versions = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())["versions"]
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)
            metadata = {
                "channel": "stable", "libxray": versions["libxray"],
                "xrayCore": versions["xray-core"], "gomobile": versions["gomobile"],
                "ndkVersion": "29.0.14206865",
            }
            (target / "libxray-artifact.json").write_text(json.dumps(metadata))
            with zipfile.ZipFile(target / "libxray.aar", "w") as archive:
                for abi in ("armeabi-v7a", "arm64-v8a", "x86", "x86_64"):
                    archive.writestr(f"jni/{abi}/libgojni.so", b"unattested-not-an-elf")
            result = subprocess.run(
                ["bash", str(ROOT / "scripts/native/verify-libxray-artifacts.sh")],
                env=dict(os.environ, RIPDPI_XRAY_AAR_DIR=directory), capture_output=True, text=True, check=False,
            )
            self.assertNotEqual(0, result.returncode, result.stdout)


    def test_coherent_metadata_and_partial_abi_are_explicit(self) -> None:
        with tempfile.TemporaryDirectory() as temporary, patch.object(ARTIFACTS, "inspect_api") as api:
            directory = Path(temporary)
            expected = artifact_fixture(directory)
            self.assertEqual(expected, ARTIFACTS.verify(directory, "arm64-v8a"))
            api.assert_called_once_with(b"unit-api")
            with self.assertRaisesRegex(ValueError, "ABI coverage"):
                ARTIFACTS.verify(directory)
            with self.assertRaisesRegex(ValueError, "expected ABI"):
                ARTIFACTS.verify(directory, "arm64-v8a", release=True)

    def test_modified_aar_is_rejected_before_java_or_native_checks(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            artifact_fixture(directory)
            with (directory / "libxray.aar").open("ab") as archive:
                archive.write(b"tampering")
            with self.assertRaisesRegex(ValueError, "content digest mismatch"):
                ARTIFACTS.verify(directory, "arm64-v8a")

    def test_unknown_channel_and_stale_patch_provenance_are_rejected(self) -> None:
        for key, value in (("channel", "unknown"), ("patchManifestSha256", "0" * 64),
                           ("buildRecipeSha256", "0" * 64), ("minSdk", "1")):
            with self.subTest(key=key), tempfile.TemporaryDirectory() as temporary, patch.object(ARTIFACTS, "inspect_api"):
                directory = Path(temporary)
                metadata = artifact_fixture(directory)
                metadata[key] = value
                (directory / "libxray-artifact.json").write_text(json.dumps(metadata))
                with self.assertRaisesRegex(ValueError, "provenance mismatch"):
                    ARTIFACTS.verify(directory, "arm64-v8a")

    def test_elf_machine_alignment_and_header_bounds_are_checked(self) -> None:
        for abi in ARTIFACTS.MACHINES:
            with self.subTest(abi=abi):
                ARTIFACTS.inspect_elf(elf_fixture(abi), abi)
                with self.assertRaisesRegex(ValueError, "16 KiB"):
                    ARTIFACTS.inspect_elf(elf_fixture(abi, 4096), abi)
                with self.assertRaisesRegex(ValueError, "program headers"):
                    ARTIFACTS.inspect_elf(elf_fixture(abi)[:64], abi)
        with self.assertRaisesRegex(ValueError, "machine mismatch"):
            wrong = bytearray(elf_fixture("arm64-v8a"))
            struct.pack_into("<H", wrong, 18, 62)
            ARTIFACTS.inspect_elf(wrong, "arm64-v8a")

    def test_symlink_artifact_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            artifact_fixture(directory)
            (directory / "libxray.aar").rename(directory / "other.aar")
            (directory / "libxray.aar").symlink_to("other.aar")
            with self.assertRaisesRegex(ValueError, "non-symlink"):
                ARTIFACTS.verify(directory, "arm64-v8a")


if __name__ == "__main__":
    unittest.main()
