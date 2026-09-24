# Rust-Native Plugin Internals

Stable mechanics for `build-logic/convention/src/main/kotlin/ripdpi.android.rust-native.gradle.kts`. The artifact set itself is volatile and lives in the plugin source, not here -- see section 4 of `../SKILL.md` for where to read it.

## Cargo Invocation

`BuildRustNativeLibsTask` (a `@CacheableTask`) builds the `.so`/executable artifact groups:

1. Validates all requested ABIs have installed Rust targets (`rustup target list --installed`).
2. Resolves the NDK toolchain bin directory for the host platform (linux-x86_64, darwin-arm64, etc.).
3. Builds all ABIs **in parallel** using a thread pool capped to available CPUs.
4. For each ABI, sets environment variables: `CC_<target>`, `AR_<target>`, `CARGO_TARGET_<target>_LINKER`, `CARGO_TARGET_<target>_AR`, `CARGO_TARGET_DIR`.
5. Runs `cargo build --manifest-path ... -p <package> --locked --target <triple> --profile <profile> --jobs <n>`.
6. Copies output artifacts to the plugin's generated-output directories (`build/generated/jniLibs/<abi>/` for `.so` files; separate generated directories for root-helper, naive-proxy, Cloudflare-origin, and pluggable-transport assets).

`BuildPluggableTransportAssetsTask` follows the same shape but builds from `native/pluggable-transports/sources.json` via git/go/cargo tooling instead of a plain Cargo artifact spec.

## ABI Mapping

| Android ABI | Rust Target Triple | Clang Target Prefix |
|---|---|---|
| `armeabi-v7a` | `armv7-linux-androideabi` | `armv7a-linux-androideabi` |
| `arm64-v8a` | `aarch64-linux-android` | `aarch64-linux-android` |
| `x86` | `i686-linux-android` | `i686-linux-android` |
| `x86_64` | `x86_64-linux-android` | `x86_64-linux-android` |

## Profile Selection (`NativeBuildPolicy.kt`)

- **CI or release-like builds**: uses `ripdpi.nativeCargoProfile` (default: `android-jni`).
- **Local dev builds**: uses `ripdpi.localNativeCargoProfileDefault` (default: `android-jni-dev`) for faster iteration.
- **ABI narrowing**: local builds default to the host ABI (`ripdpi.localNativeAbisDefault`), CI and release builds compile the full ABI set from `ripdpi.nativeAbis`.

The detection logic is `resolvedNativeCargoProfile()` and `resolvedNativeAbis()` in `NativeBuildPolicy.kt`. A build is considered "release-like" (`isReleaseLikeBuild()`) if any requested task name contains "release", "bundle", or "publish", or matches one of the project's known release-producing aggregate task paths.

## Task Wiring

The plugin hooks its build tasks into AGP's packaging pipeline by making every task whose name matches `^merge.+JniLibFolders$`, `^copy.+JniLibsProjectOnly$`, or `^merge.+NativeLibs$` depend on `buildRustNativeLibs`, and every task matching `^merge.+Assets$` depend on the root-helper, naive-proxy, Cloudflare-origin, and pluggable-transport-asset build tasks. `preBuild` depends on all of them directly. This wiring is skipped entirely when `-Pripdpi.skipNativeBuild=true` is passed (used by CI's unit-test and Roborazzi jobs, which don't need native libraries), so Android-only changes don't transitively pull in the Rust build.

## Cache-Input Tracking

Every Rust-native task's `@InputFiles` (`nativeSources` / `rustSources`) tracks, automatically:

- The workspace manifest (`native/rust/Cargo.toml`), `Cargo.lock`, and `rust-toolchain.toml`.
- The entire `.cargo/` directory and the entire `vendor/` directory.
- The entire `native/rust/crates/` tree (excluding `target/` and `.git/`), via a `fileTree` walk.

There is no per-crate allowlist to keep in sync with the dependency graph -- any change under `native/rust/crates/` is picked up automatically. If a Rust-native task runs unexpectedly or is skipped when a rebuild was expected, check `Cargo.lock` for a dependency change first, then the profile-selection logic above.
