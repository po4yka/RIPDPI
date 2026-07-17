from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
RUST_NATIVE_PLUGIN = ROOT / "build-logic/convention/src/main/kotlin/ripdpi.android.rust-native.gradle.kts"


class RustNativeTaskLayoutTest(unittest.TestCase):
    def test_native_tasks_share_one_cargo_target_root(self) -> None:
        source = RUST_NATIVE_PLUGIN.read_text(encoding="utf-8")

        for task_name in (
            "buildRustNativeLibs",
            "buildRustRootHelper",
            "buildRustNaiveProxy",
            "buildRustCloudflareOrigin",
        ):
            task_start = source.index(f'("{task_name}")')
            task_end = source.find("\nval buildRust", task_start + 1)
            task_source = source[task_start : task_end if task_end != -1 else None]
            self.assertIn("cargoTargetDir.set(rustNativeLibsBuildDir)", task_source)

    def test_release_profile_splits_symbols_with_pinned_ndk_tools(self) -> None:
        source = RUST_NATIVE_PLUGIN.read_text(encoding="utf-8")

        self.assertIn('resolve("llvm-objcopy")', source)
        self.assertIn('resolve("llvm-strip")', source)
        self.assertIn('"--only-keep-debug"', source)
        self.assertIn('"--strip-all"', source)
        self.assertIn('"--remove-section=.debug_gdb_scripts"', source)
        self.assertIn('"--add-gnu-debuglink=', source)
        self.assertIn('cargoProfileName == "android-jni"', source)
        self.assertIn("debugSymbolsDir.set(generatedNativeSymbolsDir)", source)

    def test_release_helpers_are_stripped_without_symbol_sidecars(self) -> None:
        source = RUST_NATIVE_PLUGIN.read_text(encoding="utf-8")

        for task_name in ("buildRustRootHelper", "buildRustNaiveProxy", "buildRustCloudflareOrigin"):
            task_start = source.index(f'("{task_name}")')
            task_end = source.find("\nval buildRust", task_start + 1)
            task_source = source[task_start : task_end if task_end != -1 else None]
            self.assertIn("stripReleaseOutputs.set(true)", task_source)
            self.assertNotIn("debugSymbolsDir.set", task_source)


if __name__ == "__main__":
    unittest.main()
