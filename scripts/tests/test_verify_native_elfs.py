from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.ci import verify_native_elfs


class VerifyNativeElfsTest(unittest.TestCase):
    def write_gradle_properties(self, repo_root: Path, extra_lines: str = "") -> None:
        (repo_root / "gradle.properties").write_text(
            "\n".join(
                [
                    "ripdpi.nativeAbis=armeabi-v7a,arm64-v8a,x86,x86_64",
                    "ripdpi.localNativeAbisDefault=arm64-v8a",
                    extra_lines.strip(),
                ],
            ).strip()
            + "\n",
            encoding="utf-8",
        )

    def test_resolved_expected_abis_prefers_local_default_for_local_debug_build(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            self.write_gradle_properties(repo_root)
            lib_dir = repo_root / "app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib"

            with patch.dict(os.environ, {}, clear=False):
                expected = verify_native_elfs.resolved_expected_abis(repo_root, lib_dir, None)

            self.assertEqual({"arm64-v8a"}, expected)

    def test_resolved_expected_abis_uses_full_set_for_ci_builds(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            self.write_gradle_properties(repo_root)
            lib_dir = repo_root / "app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib"

            with patch.dict(os.environ, {"CI": "true"}, clear=False):
                expected = verify_native_elfs.resolved_expected_abis(repo_root, lib_dir, None)

            self.assertEqual({"armeabi-v7a", "arm64-v8a", "x86", "x86_64"}, expected)

    def test_resolved_expected_abis_honors_local_override_property(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            self.write_gradle_properties(repo_root, "ripdpi.localNativeAbis=x86_64")
            lib_dir = repo_root / "app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib"

            with patch.dict(os.environ, {}, clear=False):
                expected = verify_native_elfs.resolved_expected_abis(repo_root, lib_dir, None)

            self.assertEqual({"x86_64"}, expected)

    def test_verify_allows_extra_abi_directories_for_other_dependencies(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            lib_dir = Path(temp_dir)
            arm64_dir = lib_dir / "arm64-v8a"
            extra_dir = lib_dir / "x86_64"
            arm64_dir.mkdir()
            extra_dir.mkdir()

            for lib_name in verify_native_elfs.EXPECTED_NEEDED:
                (arm64_dir / lib_name).write_bytes(b"stub")

            def fake_inspect_elf(elf_path: Path, _objdump_path: str) -> tuple[set[str], list[int]]:
                return verify_native_elfs.EXPECTED_NEEDED[elf_path.name], [verify_native_elfs.REQUIRED_PAGE_ALIGNMENT]

            with patch("scripts.ci.verify_native_elfs.inspect_elf", side_effect=fake_inspect_elf):
                verify_native_elfs.verify(lib_dir, {"arm64-v8a"}, "objdump")


if __name__ == "__main__":
    unittest.main()
