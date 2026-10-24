from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import struct
import sys
import tempfile
import tomllib
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("libxray_artifacts", ROOT / "scripts/native/libxray_artifacts.py")
ARTIFACTS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ARTIFACTS)


def elf_fixture(abi: str, alignment: int = 16384, go_version: str = "go1.27.3") -> bytes:
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
    names = b"\0.shstrtab\0.go.buildinfo\0"
    version = go_version.encode()
    build_info = b"\xff Go buildinf:" + bytes([8 if elf_class == 2 else 4, 2]) + bytes(16) + bytes([len(version)]) + version
    section_size = 64 if elf_class == 2 else 40
    payload.extend(bytes(section_size * 3) + names + build_info)
    if elf_class == 2:
        struct.pack_into("<Q", payload, 40, 128)
        struct.pack_into("<HHH", payload, 58, section_size, 3, 1)
        section_format, offset_position = "<QQ", 24
    else:
        struct.pack_into("<I", payload, 32, 128)
        struct.pack_into("<HHH", payload, 46, section_size, 3, 1)
        section_format, offset_position = "<II", 16
    data_offset = 128 + section_size * 3
    for index, name, offset, size in ((1, 1, data_offset, len(names)), (2, 11, data_offset + len(names), len(build_info))):
        section = 128 + index * section_size
        struct.pack_into("<II", payload, section, name, 3 if index == 1 else 1)
        struct.pack_into(section_format, payload, section + offset_position, offset, size)
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
                "goVersion": "go1.27.3", "abis": ["arm64-v8a"]}
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

    def test_old_compiler_metadata_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary, patch.object(ARTIFACTS, "inspect_api"):
            directory = Path(temporary)
            metadata = artifact_fixture(directory)
            metadata["goVersion"] = "go1.27.2"
            (directory / "libxray-artifact.json").write_text(json.dumps(metadata))
            with self.assertRaisesRegex(ValueError, "Go toolchain"):
                ARTIFACTS.verify(directory, "arm64-v8a")

    def test_embedded_old_compiler_is_rejected_despite_matching_digests(self) -> None:
        with tempfile.TemporaryDirectory() as temporary, patch.object(ARTIFACTS, "inspect_api"):
            directory = Path(temporary)
            metadata = artifact_fixture(directory)
            with zipfile.ZipFile(directory / "libxray.aar", "w") as archive:
                archive.writestr("jni/arm64-v8a/libgojni.so", elf_fixture("arm64-v8a", go_version="go1.27.2"))
                archive.writestr("classes.jar", b"unit-api")
            metadata["aarSha256"] = ARTIFACTS.digest(directory / "libxray.aar")
            (directory / "libxray-artifact.json").write_text(json.dumps(metadata))
            with self.assertRaisesRegex(ValueError, "embedded Go toolchain"):
                ARTIFACTS.verify(directory, "arm64-v8a")

    def test_embedded_compiler_checks_all_abis_and_malformed_sections(self) -> None:
        for abi in ARTIFACTS.MACHINES:
            with self.subTest(abi=abi):
                payload = elf_fixture(abi)
                ARTIFACTS.inspect_go_version(payload, abi, "go1.27.3")
                with self.assertRaisesRegex(ValueError, "embedded Go toolchain"):
                    ARTIFACTS.inspect_go_version(elf_fixture(abi, go_version="go1.27.2"), abi, "go1.27.3")
                wide = payload[4] == 2
                for offset, replacement, message in (
                    (40 if wide else 32, b"\xff" * (8 if wide else 4), "section headers"),
                    (payload.index(b".go.buildinfo"), b".no.buildinfo", "missing or duplicate"),
                    (payload.index(b"\xff Go buildinf:"), b"broken", "invalid Go build"),
                    (payload.index(b"\xff Go buildinf:") + 32, b"\xff", "version length"),
                ):
                    malformed = bytearray(payload)
                    malformed[offset:offset + len(replacement)] = replacement
                    with self.assertRaisesRegex(ValueError, message):
                        ARTIFACTS.inspect_go_version(malformed, abi, "go1.27.3")
                with self.assertRaisesRegex(ValueError, "section bounds"):
                    ARTIFACTS.inspect_go_version(payload[:-1], abi, "go1.27.3")
                duplicate = bytearray(payload)
                struct.pack_into("<I", duplicate, 128, 11)
                struct.pack_into("<QQ" if wide else "<II", duplicate, 128 + (24 if wide else 16),
                                 len(payload) - (33 + len("go1.27.3")), 33 + len("go1.27.3"))
                with self.assertRaisesRegex(ValueError, "missing or duplicate"):
                    ARTIFACTS.inspect_go_version(duplicate, abi, "go1.27.3")

    def test_build_preflight_checks_compiler_and_supported_host(self) -> None:
        versions, properties, _ = ARTIFACTS.policy()
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            ndk = directory / "ndk"
            ndk.mkdir()
            (ndk / "source.properties").write_text("Pkg.Revision = " + properties["ripdpi.nativeNdkVersion"] + "\n")
            for name in ("gomobile", "gobind"):
                tool = directory / name
                tool.write_text("#!/bin/sh\nexit 90\n")
                tool.chmod(0o755)
            go = directory / "go"
            go.write_text(f"#!{sys.executable}\n" +
                          "import os,sys\n" +
                          "if sys.argv[1] == 'env': print(os.environ['TEST_GO_' + sys.argv[2]])\n" +
                          f"else: print('v{versions['gomobile']}')\n")
            go.chmod(0o755)
            for version, host, arch, expected in (("go1.27.3", "darwin", "arm64", 0),
                                                   ("go1.27.3", "linux", "amd64", 0),
                                                   ("go1.27.3", "linux", "arm64", 69),
                                                   ("go1.27.2", "darwin", "arm64", 69)):
                with self.subTest(version=version, host=host, arch=arch):
                    environment = dict(os.environ, PATH=str(directory) + os.pathsep + os.environ["PATH"],
                                       ANDROID_NDK_HOME=str(ndk), TEST_GO_GOVERSION=version,
                                       TEST_GO_GOSUMDB="sum.golang.org", TEST_GO_GOHOSTOS=host,
                                       TEST_GO_GOHOSTARCH=arch)
                    result = subprocess.run(["bash", str(ROOT / "scripts/native/build-libxray.sh"), "--check-toolchain"],
                                            env=environment, capture_output=True, text=True, timeout=30)
                    self.assertEqual(expected, result.returncode, result.stderr)

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
