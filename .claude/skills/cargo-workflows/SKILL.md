---
name: cargo-workflows
description: Cargo workflow skill for the RIPDPI Rust workspace (23 crates, Android NDK cross-compilation). Use when managing the workspace, feature flags, build scripts, Gradle-Cargo integration, cargo-deny, cargo-audit, nextest, CI caching, or cross-compilation. Activates on queries about Cargo.toml, native builds, JNI libraries, cdylib crates, or dependency auditing.
---

# Cargo Workflows -- RIPDPI

## Project layout

```text
native/rust/
  Cargo.toml              # Virtual workspace manifest (23 crates)
  Cargo.lock              # Checked in -- reproducible builds
  .cargo/config.toml      # Per-target rustflags for Android NDK
  .config/nextest.toml    # nextest profiles (default + ci)
  deny.toml               # cargo-deny policy
  crates/
    ripdpi-android/       # cdylib -- JNI entry point (libripdpi.so)
    ripdpi-tunnel-android/# cdylib -- JNI tunnel entry point (libripdpi-tunnel.so)
    ripdpi-cli/           # Host-only CLI binary
    ripdpi-bench/         # Criterion benchmarks
    ... (19 more library crates)
```

## Android NDK cross-compilation

### How it works (no cargo-ndk)

This project does NOT use `cargo-ndk`. Instead, a custom Gradle convention plugin
(`ripdpi.android.rust-native`) invokes `cargo build` directly with per-ABI
environment variables pointing to NDK clang linkers and `llvm-ar`.

Key file: `build-logic/convention/src/main/kotlin/ripdpi.android.rust-native.gradle.kts`

### Target ABIs and Rust triples

| Android ABI     | Rust target                  | Clang target prefix          |
|-----------------|------------------------------|------------------------------|
| arm64-v8a       | aarch64-linux-android        | aarch64-linux-android        |
| armeabi-v7a     | armv7-linux-androideabi      | armv7a-linux-androideabi     |
| x86_64          | x86_64-linux-android         | x86_64-linux-android         |
| x86             | i686-linux-android           | i686-linux-android           |

### Required Rust targets

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi \
    x86_64-linux-android i686-linux-android
```

### Cargo profiles for Android

```toml
# Cargo.toml -- custom profiles
[profile.android-jni]       # Release: opt-level "z", panic = "unwind"
inherits = "release"

[profile.android-jni-dev]   # Dev: opt-level 1, panic = "unwind"
inherits = "dev"
```

The active profile is selected by Gradle property `ripdpi.nativeCargoProfile`.
Local dev overrides via `ripdpi.localNativeCargoProfileDefault` and
`ripdpi.localNativeAbisDefault` in `gradle.properties` or `local.properties`.

### .cargo/config.toml (cross-compilation flags)

All four Android targets share the same rustflags:
- `-C link-arg=-Wl,-z,max-page-size=16384` (Android 15+ 16 KiB page size)
- `-C force-frame-pointers=yes` (profiling / crash symbolication)

Linkers are NOT configured here -- the Gradle task sets `CARGO_TARGET_<TRIPLE>_LINKER`
environment variables pointing to NDK clang at build time.

## Gradle <-> Cargo integration

### Build flow

1. Gradle task `buildRustNativeLibs` (registered by `ripdpi.android.rust-native` plugin)
2. Runs `cargo build --locked --target <triple> --profile <profile> -p ripdpi-android -p ripdpi-tunnel-android`
3. Builds all ABIs in parallel (one thread per ABI, capped at CPU count)
4. Each ABI gets its own `CARGO_TARGET_DIR` to avoid lock contention
5. Copies `libripdpi_android.so` -> `libripdpi.so` and `libripdpi_tunnel_android.so` -> `libripdpi-tunnel.so`
6. Output lands in `build/generated/jniLibs/<abi>/` and is wired into Android `jniLibs` source set
7. Task is wired into `merge*JniLibFolders`, `copy*JniLibsProjectOnly`, `merge*NativeLibs`, and `preBuild`

### Building locally

```bash
# Full Android build (builds Rust + Kotlin + APK)
./gradlew :core:engine:assembleDebug

# Rust-only (triggers Gradle's Rust task)
./gradlew :core:engine:buildRustNativeLibs

# Host-only (no Android NDK, for tests/benchmarks)
cd native/rust && cargo build -p ripdpi-cli
cd native/rust && cargo bench -p ripdpi-bench
```

## cdylib crates and JNI considerations

Two crates produce shared libraries loaded via `System.loadLibrary()`:
- `ripdpi-android` -> `libripdpi.so` (path-optimization engine)
- `ripdpi-tunnel-android` -> `libripdpi-tunnel.so` (VPN tunnel)

Key rules for cdylib JNI crates:
- `crate-type = ["cdylib"]` in `[lib]` -- produces `.so` for Android
- `panic = "unwind"` required (not `"abort"`) so JNI can catch panics
- Exported `#[no_mangle] pub extern "system" fn Java_...` entry points
- The `jni` crate (v0.22) provides `JNIEnv`, `JClass`, `JString` wrappers
- Clippy allows `missing_safety_doc` and `not_unsafe_ptr_arg_deref` workspace-wide for JNI/FFI

## Feature flags

```toml
# Example: ripdpi-android
[features]
loom = ["dep:loom"]   # Enable loom for concurrency testing
```

Feature rules:
- Features are additive -- once enabled anywhere in the dep graph, they stay on
- `resolver = "2"` prevents dev-dep features from leaking into regular deps
- Use `dep:optional_dep` syntax to avoid implicit feature creation

## Testing

```bash
# Run all workspace tests with nextest (preferred)
cd native/rust && cargo nextest run

# CI profile (retries=2, no fail-fast)
cd native/rust && cargo nextest run --profile ci

# Run single crate tests
cd native/rust && cargo nextest run -p ripdpi-packets

# Standard cargo test (for doc-tests, which nextest skips)
cd native/rust && cargo test --doc
```

nextest config at `native/rust/.config/nextest.toml`:
- `default` profile: fail-fast=true, slow-timeout=30s
- `ci` profile: retries=2, fail-fast=false

## Dependency auditing

```bash
cd native/rust

# Security advisory check
cargo audit

# Full policy check (licenses, bans, advisories, sources)
cargo deny check
```

### deny.toml policy (native/rust/deny.toml)

- **Licenses**: MIT, Apache-2.0, BSD-2/3-Clause, ISC, 0BSD, Zlib, Unicode-3.0, OpenSSL allowed
- **Bans**: multiple-versions=warn, wildcards=warn
- **Sources**: unknown registries denied, unknown git warned
- **Advisories**: no ignored advisories (empty ignore list)

## CI caching

```yaml
# Preferred: Swatinem/rust-cache
- uses: Swatinem/rust-cache@v2
  with:
    cache-on-failure: true
    workspaces: "native/rust -> target"

# Manual cache (use v4, not v3)
- uses: actions/cache@v4
  with:
    path: |
      ~/.cargo/registry/index/
      ~/.cargo/registry/cache/
      ~/.cargo/git/db/
      native/rust/target/
    key: ${{ runner.os }}-cargo-${{ hashFiles('native/rust/Cargo.lock') }}
```

## Workspace commands cheat sheet

```bash
cd native/rust

cargo check --workspace                # Type-check all crates
cargo clippy --workspace -- -D warnings # Lint (workspace lints in Cargo.toml)
cargo fmt --check                       # Format check
cargo build -p ripdpi-cli               # Build single crate
cargo tree --duplicates                 # Find duplicate deps
cargo tree -i serde                     # Who depends on serde?
cargo update -p tokio --precise 1.42.0  # Pin single dep version
cargo deny check                        # Run full deny policy
cargo audit                             # Security advisories only
```

## Related skills

- `rust-debugging` -- GDB/LLDB, async debugging, backtraces
- `rust-security` -- cargo-audit, cargo-deny, supply chain safety
- `rust-build-times` -- cargo-timings, sccache, Cranelift, LTO tuning
- `rust-profiling` -- flamegraphs, cargo-bloat, Criterion benchmarks
- `rust-unsafe` -- unsafe code review, JNI safety patterns
