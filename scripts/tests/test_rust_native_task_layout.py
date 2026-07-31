from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
RUST_NATIVE_PLUGIN = ROOT / "build-logic/convention/src/main/kotlin/ripdpi.android.rust-native.gradle.kts"
NATIVE_BUILD_POLICY = ROOT / "build-logic/convention/src/main/kotlin/NativeBuildPolicy.kt"


class RustNativeTaskLayoutTest(unittest.TestCase):
    def test_root_and_app_aggregate_builds_use_release_native_policy(self) -> None:
        source = NATIVE_BUILD_POLICY.read_text(encoding="utf-8")

        self.assertIn("aggregateReleaseTaskPaths", source)
        for task_path in ("assemble", ":assemble", "build", ":build", ":app:assemble", ":app:build"):
            self.assertIn(f'"{task_path}"', source)

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

    def test_rust_pluggable_transport_build_retries_transient_cargo_failures(self) -> None:
        source = RUST_NATIVE_PLUGIN.read_text(encoding="utf-8")
        function_start = source.index("private fun buildRustBinary(")
        function_end = source.index("private fun buildGoCgoEnvironment(", function_start)
        function_source = source[function_start:function_end]

        self.assertIn(
            'execWithRetry("Build ${source.id} Rust pluggable transport for $abi")',
            function_source,
        )
        self.assertIn('"--locked"', function_source)

    def test_rust_pluggable_transport_cache_tracks_workspace_source_closure(self) -> None:
        source = RUST_NATIVE_PLUGIN.read_text(encoding="utf-8")
        task_start = source.index('("buildPluggableTransportAssets")')
        task_source = source[task_start:]

        self.assertIn("abstract val rustSources: ConfigurableFileCollection", source)
        self.assertIn("rustSources.from(rustWorkspaceCrateSources())", task_source)
        self.assertIn('fileTree(rustWorkspaceDir.resolve("vendor"))', task_source)
        self.assertIn('rustSources.from(rustWorkspaceDir.resolve("Cargo.lock"))', task_source)
        self.assertIn('rustSources.from(rustWorkspaceDir.resolve("rust-toolchain.toml"))', task_source)
        self.assertIn('rustSources.from(rustWorkspaceDir.resolve(".cargo/config.toml"))', task_source)


if __name__ == "__main__":
    unittest.main()
