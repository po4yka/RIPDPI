from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
RUST_NATIVE_PLUGIN = ROOT / "build-logic/convention/src/main/kotlin/ripdpi.android.rust-native.gradle.kts"
NATIVE_BUILD_POLICY = ROOT / "build-logic/convention/src/main/kotlin/NativeBuildPolicy.kt"
BORING_SYS_BUILD_SCRIPT = ROOT / "native/rust/vendor/boring-sys/build/main.rs"


class RustNativeTaskLayoutTest(unittest.TestCase):
    def test_native_tasks_use_pinned_cmake_and_reject_ambient_overrides(self) -> None:
        source = RUST_NATIVE_PLUGIN.read_text(encoding="utf-8")

        self.assertIn("abstract val cmakeVersion: Property<String>", source)
        self.assertIn('resolve("cmake/$version/bin/cmake")', source)
        self.assertIn("android sdk install cmake/$version", source)
        self.assertNotIn("maxByOrNull { it.name }", source)
        self.assertNotIn("using system cmake", source)
        self.assertIn("rejectAmbientCargoOverrides", source)
        for key in (
            '"RUSTFLAGS"',
            '"CARGO_ENCODED_RUSTFLAGS"',
            '"RUSTC"',
            '"RUSTC_WORKSPACE_WRAPPER"',
            '"CARGO_PROFILE_"',
            '"CARGO_BUILD_"',
            '"CARGO_TARGET_"',
        ):
            self.assertIn(key, source)
        self.assertIn("RUSTC_WRAPPER remains supported", source)
        self.assertIn('.filterNot { it == "CARGO_TARGET_DIR" }', source)

    def test_boring_ssl_cleanup_invalidates_cargo_without_watching_generated_output(
        self,
    ) -> None:
        plugin = RUST_NATIVE_PLUGIN.read_text(encoding="utf-8")
        build_script = BORING_SYS_BUILD_SCRIPT.read_text(encoding="utf-8")

        self.assertNotIn('cargo:rerun-if-changed={}", bssl_dir.display()', build_script)
        self.assertIn(
            "val cargoBuildScriptDir = cacheFile.parentFile.parentFile.parentFile",
            plugin,
        )
        self.assertIn(
            'cargoBuildScriptDir.name.startsWith("boring-sys-")',
            plugin,
        )
        self.assertIn(
            "fileSystemOperations.delete { delete(cargoBuildScriptDir) }",
            plugin,
        )
        self.assertNotIn("fileSystemOperations.delete { delete(outDir) }", plugin)

    def test_root_and_app_aggregate_builds_use_release_native_policy(self) -> None:
        source = NATIVE_BUILD_POLICY.read_text(encoding="utf-8")

        self.assertIn("aggregateReleaseTaskPaths", source)
        self.assertIn('removePrefix(":")', source)
        for task_path in ("assemble", "build", "app:assemble", "app:build"):
            self.assertIn(f'"{task_path}"', source)
        self.assertIn("isReleaseProducingAppAggregate", source)
        self.assertIn('startsWith("app:assemble")', source)
        self.assertIn('!contains("debug")', source)

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

    def test_multi_output_transport_packages_one_shared_upstream(self) -> None:
        source = RUST_NATIVE_PLUGIN.read_text(encoding="utf-8")

        self.assertIn('"${source.sourceBinaryName}.upstream"', source)
        self.assertIn('sourceBuildLauncherScript("$outputName.upstream")', source)
        self.assertIn('"upstreamBinary" to packagedUpstreamBinary?.name', source)
        self.assertIn('"upstreamSha256" to packagedUpstreamBinary?.let(::sha256)', source)
        self.assertEqual(1, source.count("copyIfChanged(builtBinary, upstreamBinary)"))

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

    def test_prebuilt_pluggable_transports_are_digest_bound_and_verified(self) -> None:
        source = RUST_NATIVE_PLUGIN.read_text(encoding="utf-8")
        task_start = source.index("abstract class BuildPluggableTransportAssetsTask")
        task_end = source.index("val rustNativeAbis", task_start)
        task_source = source[task_start:task_end]

        self.assertIn("abstract val prebuiltSourceDir: DirectoryProperty", task_source)
        self.assertIn("abstract val prebuiltManifestSha256: Property<String>", task_source)
        self.assertIn("copyFromVerifiedPrebuilt", task_source)
        self.assertIn("metadata/pluggable-transports.json", task_source)
        self.assertIn("manifest digest does not match", task_source)
        self.assertIn("launcher digest mismatch", task_source)
        self.assertIn("upstream digest mismatch", task_source)
        self.assertIn("verifyPrebuiltSources", task_source)
        self.assertIn("unexpected pinned revision", task_source)
        self.assertIn("unexpected source set", task_source)
        self.assertIn("recordsByKey.keys.containsAll(expectedRecords)", task_source)
        self.assertIn('record["mode"] == "source"', task_source)
        self.assertIn("ripdpi.prebuiltPluggableTransportAssetsDir", source)
        self.assertIn("ripdpi.prebuiltPluggableTransportAssetsManifestSha256", source)


if __name__ == "__main__":
    unittest.main()
