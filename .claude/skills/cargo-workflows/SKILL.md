---
name: cargo-workflows
description: Cargo workspace, feature flags, native builds, dependency audits, and Android cross-compilation.
---

# Cargo Workflows -- RIPDPI

## Project layout

```text
native/rust/
  Cargo.toml              # Virtual workspace manifest (40 crates as of 2026-04)
  Cargo.lock              # Checked in -- reproducible builds
  .cargo/config.toml      # Per-target rustflags for Android NDK
  .config/nextest.toml    # nextest profiles (default + ci)
  deny.toml               # cargo-deny policy
  crates/
    ripdpi-android/       # cdylib -- JNI entry point (libripdpi.so)
    ripdpi-tunnel-android/# cdylib -- JNI tunnel entry point (libripdpi-tunnel.so)
    ripdpi-warp-android/  # cdylib -- JNI WARP entry point (libripdpi-warp.so)
    ripdpi-relay-android/ # cdylib -- JNI relay entry point (libripdpi-relay.so)
    ripdpi-cli/           # Host-only CLI binary
    ripdpi-bench/         # Criterion benchmarks
    ... (34 more library crates)
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
- **Advisories**: one explicit ignore — `RUSTSEC-2024-0436` (`paste` proc-macro, unmaintained, no runtime risk). See `rust-security` skill for RUSTSEC triage SLA.

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

## Rust Edition 2024 migration

Edition 2024 stabilized in Rust 1.85.0 (Feb 2025) — see <https://blog.rust-lang.org/2025/02/20/Rust-1.85.0/>. The workspace is currently on edition 2021 (check `rustfmt.toml:edition` and per-crate `Cargo.toml`). Migrate **one leaf crate at a time** — do not bump the workspace-wide edition in a single commit.

### Per-crate migration workflow

```bash
cd native/rust
# Pick a leaf crate (no other workspace crate depends on it, e.g. ripdpi-cli or ripdpi-bench).
cd crates/<leaf-crate>
cargo fix --edition
# Inspect diff: cargo fix may edit .rs files in place.
git diff
# Bump the crate's Cargo.toml:
#   edition = "2024"
# Run per-crate lint + test to verify:
cargo clippy -p <leaf-crate> --all-targets -- -D warnings
cargo nextest run -p <leaf-crate>
```

### Breaking changes that bite

- **Stricter `unsafe` in `extern` blocks.** Every item declared in `extern "C" { ... }` or `extern "system" { ... }` now requires explicit `unsafe {}` at the declaration site. `ripdpi-runtime/platform/linux.rs` (83 unsafe blocks) and the JNI adapter crates must be reviewed carefully. The `#[unsafe(no_mangle)]` syntax (used in JNI entry points) is already 2024-style.
- **`gen` keyword reserved.** Any identifier named `gen` needs renaming before migration. Check for `let gen = …`, `fn gen(…)`, `mod gen`.
- **Precise-capturing `impl Trait`.** Functions returning `impl Trait` that should capture only a subset of input lifetimes now need `use<'a, T>` syntax. Most affected: the tokio task spawners in `ripdpi-runtime` that return `impl Future + Send + 'static`. `cargo fix --edition` usually handles this but check the diff.
- **`if let` / `while let` chains stabilize.** No breaking change, but existing nested `if let Some(…) = … { if let Some(…) = … { … } }` patterns can be collapsed to `if let Some(…) = … && let Some(…) = … { … }`. Do not do this in the migration commit — keep the migration diff surgical.
- **`async || {}` closures** are now stable. You don't have to rewrite `|| async { … }` patterns, but new code should prefer the closure form. Do not mass-rewrite — violates `rust-unsafe` surgical-changes discipline.

### Migration order

Leaf crates first (no internal dependents). Suggested order:

1. `ripdpi-bench`, `ripdpi-cli` — host-only, low blast radius.
2. `ripdpi-desync`, `ripdpi-packets`, `ripdpi-ipfrag` — pure-logic crates under `#![forbid(unsafe_code)]`.
3. `ripdpi-tls-profiles`, `ripdpi-config`, `ripdpi-failure-classifier` — cross-crate consumers but still leaf-ish.
4. `ripdpi-runtime` — large, has the 83 unsafe blocks; allocate at least a day for the `extern` block review.
5. JNI adapter crates (`ripdpi-android`, `ripdpi-tunnel-android`, `ripdpi-warp-android`, `ripdpi-relay-android`) — last, because they depend on everything else and most affected by the stricter `extern` rules.

### Don't bump rustfmt edition

`rustfmt.toml:edition = "2021"` controls how rustfmt formats code. Bump it only AFTER every crate is on edition 2024 and the workspace builds. Bumping it early re-formats edition-2021 code with edition-2024 rules, producing spurious diffs.

## Related skills

- `rust-debugging` -- GDB/LLDB, async debugging, backtraces
- `rust-security` -- cargo-audit, cargo-deny, supply chain safety
- `rust-build-times` -- cargo-timings, sccache, Cranelift, LTO tuning
- `rust-profiling` -- flamegraphs, cargo-bloat, Criterion benchmarks
- `rust-unsafe` -- unsafe code review, JNI safety patterns
- `rust-panic-safety` -- `.unwrap()` / `.expect()` policy (1,727 call sites to grandfather through edition migration)
