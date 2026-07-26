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

            for lib_name in verify_native_elfs.EXPECTED_NATIVE_LIBRARIES:
                (arm64_dir / lib_name).write_bytes(b"stub")

            def fake_inspect_elf(elf_path: Path, _objdump_path: str) -> tuple[set[str], list[int]]:
                return verify_native_elfs.EXPECTED_NEEDED.get(elf_path.name, set()), [verify_native_elfs.REQUIRED_PAGE_ALIGNMENT]

            with (
                patch("scripts.ci.verify_native_elfs.inspect_elf", side_effect=fake_inspect_elf),
                patch("scripts.ci.verify_native_elfs.inspect_defined_function_exports", return_value=set()),
            ):
                verify_native_elfs.verify(lib_dir, {"arm64-v8a"}, "objdump")

    def test_verify_requires_all_packaged_jni_libraries(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            lib_dir = Path(temp_dir)
            arm64_dir = lib_dir / "arm64-v8a"
            arm64_dir.mkdir()
            for lib_name in verify_native_elfs.EXPECTED_NATIVE_LIBRARIES[:-1]:
                (arm64_dir / lib_name).write_bytes(b"stub")

            with (
                patch(
                    "scripts.ci.verify_native_elfs.inspect_elf",
                    side_effect=lambda elf_path, _objdump: (
                        verify_native_elfs.EXPECTED_NEEDED.get(elf_path.name, set()),
                        [verify_native_elfs.REQUIRED_PAGE_ALIGNMENT],
                    ),
                ),
                patch("scripts.ci.verify_native_elfs.inspect_defined_function_exports", return_value=set()),
            ):
                with self.assertRaisesRegex(ValueError, "libripdpi-tunnel.so"):
                    verify_native_elfs.verify(lib_dir, {"arm64-v8a"}, "objdump")

    def test_parse_defined_function_exports_ignores_imports(self) -> None:
        output = """
0000000000000000      DF *UND*  0000000000000000 strlen
0000000000608560 g    DF .text  0000000000000150 JNI_OnLoad
00000000006086b0 g    DF .text  0000000000002e3c Java_com_example_Native_call
00000000006d87a4 g    DF .text  0000000000000010 blake3_compress_in_place_portable
"""
        completed = type("Completed", (), {"stdout": output})()
        with patch("scripts.ci.verify_native_elfs.subprocess.run", return_value=completed):
            exports = verify_native_elfs.inspect_defined_function_exports(Path("lib.so"), "objdump")

        self.assertEqual(
            {
                "JNI_OnLoad",
                "Java_com_example_Native_call",
                "blake3_compress_in_place_portable",
            },
            exports,
        )

    def test_verify_rejects_unexpected_defined_function_export(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            lib_dir = Path(temp_dir)
            arm64_dir = lib_dir / "arm64-v8a"
            arm64_dir.mkdir()
            for lib_name in verify_native_elfs.EXPECTED_NATIVE_LIBRARIES:
                (arm64_dir / lib_name).write_bytes(b"stub")

            with (
                patch(
                    "scripts.ci.verify_native_elfs.inspect_elf",
                    side_effect=lambda elf_path, _objdump: (
                        verify_native_elfs.EXPECTED_NEEDED.get(elf_path.name, set()),
                        [verify_native_elfs.REQUIRED_PAGE_ALIGNMENT],
                    ),
                ),
                patch(
                    "scripts.ci.verify_native_elfs.inspect_defined_function_exports",
                    side_effect=lambda elf_path, _objdump: (
                        {"blake3_compress_in_place_portable"} if elf_path.name == "libripdpi-relay.so" else set()
                    ),
                ),
            ):
                with self.assertRaisesRegex(ValueError, "blake3_compress_in_place_portable"):
                    verify_native_elfs.verify(lib_dir, {"arm64-v8a"}, "objdump")

    def test_discover_default_lib_dirs_finds_flavored_debug_outputs(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            github_dir = (
                repo_root
                / "app/build/intermediates/merged_native_libs/githubFullDebug/mergeGithubFullDebugNativeLibs/out/lib"
            )
            play_dir = repo_root / "app/build/intermediates/merged_native_libs/playFullDebug/mergePlayFullDebugNativeLibs/out/lib"
            github_dir.mkdir(parents=True)
            play_dir.mkdir(parents=True)

            expected = [github_dir, play_dir]
            actual = verify_native_elfs.discover_default_lib_dirs(repo_root)

            self.assertEqual(expected, actual)


if __name__ == "__main__":
    unittest.main()
