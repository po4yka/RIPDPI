---
name: rust-android-build
description: Android-specific Rust build, verification, and packaging — per-target 16 KiB page alignment, size-optimized release profile, ELF symbol allowlist, .so size budgets, NDK 29 specifics. Use when modifying .cargo/config.toml for Android targets, the workspace [profile.release] / [profile.android-jni] block, or when verifying a built .so before release.
---

# Rust Android Build -- RIPDPI

## Purpose

RIPDPI ships 4 Android ABIs (arm64-v8a, armeabi-v7a, x86_64, x86). The `ripdpi.android.rust-native` convention plugin invokes plain `cargo build --locked` with the NDK linker for each ABI; `cargo-ndk` is not part of the build path. This skill codifies the build-and-verify discipline: which `rustflags` go where, how to verify 16 KiB alignment per ABI, the ELF symbol allowlist, size budgets per ABI, and NDK 29 specifics.

## When to consult

- Editing `native/rust/.cargo/config.toml` for any `*-linux-android*` target.
- Modifying the `[profile.release]` or `[profile.android-jni]` block in workspace `Cargo.toml`.
- Auditing a built `libripdpi.so` / `libripdpi-tunnel.so` before release.
- Reviewing a Gradle convention-plugin change in `build-logic/`.
- Investigating a Play Console rejection citing 16 KiB alignment, native crashes, or symbol issues.

## 16 KiB page-size alignment

### Status quo

Play Store has required 16 KiB-aligned `.so` files for new and updated apps targeting Android 15+ since 1 November 2025. NDK r28+ (RIPDPI pins NDK r29 = `29.0.14206865`) compiles 16 KiB-aligned by default. `.cargo/config.toml` reinforces this for every Android Cargo target.

### Per-ABI rustflags

`native/rust/.cargo/config.toml` should contain:

```toml
[target.aarch64-linux-android]
rustflags = [
    "-C", "link-arg=-Wl,-z,max-page-size=16384",
    "-C", "link-arg=-Wl,--build-id=sha1",
    "-C", "force-frame-pointers=yes",
]

[target.x86_64-linux-android]
rustflags = [
    "-C", "link-arg=-Wl,-z,max-page-size=16384",
    "-C", "link-arg=-Wl,--build-id=sha1",
    "-C", "force-frame-pointers=yes",
]

[target.armv7-linux-androideabi]
rustflags = [
    "-C", "link-arg=-Wl,-z,max-page-size=16384",
    "-C", "link-arg=-Wl,--build-id=sha1",
    "-C", "force-frame-pointers=yes",
]

[target.i686-linux-android]
rustflags = [
    "-C", "link-arg=-Wl,-z,max-page-size=16384",
    "-C", "link-arg=-Wl,--build-id=sha1",
    "-C", "force-frame-pointers=yes",
]
```

The committed policy intentionally applies the same alignment, build-id, and frame-pointer flags to all four Android targets. Keep this block byte-for-byte aligned with `.cargo/config.toml`.

### Verification per ABI

```bash
NDK_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$(uname | tr '[:upper:]' '[:lower:]')-x86_64/bin"

# arm64-v8a — Align column must be 0x4000 for all LOAD segments
"$NDK_BIN/llvm-readelf" -lW app/build/intermediates/.../arm64-v8a/libripdpi.so \
  | awk '/LOAD/ {print $NF}' \
  | sort -u
# Expected: 0x4000

# armv7-linux-androideabi — project policy also requires 0x4000
"$NDK_BIN/llvm-readelf" -lW app/build/intermediates/.../armeabi-v7a/libripdpi.so \
  | awk '/LOAD/ {print $NF}' | sort -u
# Expected: 0x4000
```

Merged-native-library verification:

```bash
python3 scripts/ci/verify_native_elfs.py \
  --lib-dir app/build/intermediates/merged_native_libs/githubFullDebug/mergeGithubFullDebugNativeLibs/out/lib
# The gate checks the merged JNI library tree; it does not open or validate an APK/AAB archive.
```

Pre-release gate: a CI step should fail if `0x4000` is missing from `arm64-v8a` or `x86_64` LOAD segments.

### Common traps

- A transitive C dep (typically `ring` or `boring-sys`) that compiles without the `-z` flags. Verify via `llvm-readelf -d libripdpi.so | grep DT_NEEDED` to enumerate, then re-build the dep with explicit `CFLAGS=-Wl,-z,max-page-size=16384`.
- `mmap(addr, size, ...)` calls in vendor C code with `size` not 16 KiB aligned. The kernel rounds up; the C code then assumes its smaller original size. Audit any `mmap` in dependencies.
- A `#define PAGE_SIZE 4096` somewhere in a vendor C dep. NDK r29 explicitly REMOVED `PAGE_SIZE` from `unistd.h` for arm64-v8a/x86_64 to force the audit. If your build fails on `PAGE_SIZE` undefined, that is correct — the C code must call `sysconf(_SC_PAGESIZE)`.

## Size-optimized release profile

Workspace `Cargo.toml`:

```toml
[profile.android-jni]
inherits = "release"
opt-level = "z"           # size > speed for Android distribution
lto = "fat"
codegen-units = 1
panic = "unwind"          # JNI needs unwind for catch_unwind
strip = "none"
debug = "line-tables-only"

[profile.android-jni-dev]
inherits = "dev"
opt-level = 1
panic = "unwind"
debug = "line-tables-only"  # symbols for on-device profiling
```

Build through the convention plugin so Cargo, linker, ABI, and packaging inputs
stay identical to the app build:

```bash
./gradlew :core:engine:buildRustNativeLibs \
  -Pripdpi.localNativeAbis=arm64-v8a
```

What each flag does:
- `--gc-sections` — dead-code elimination at link time. ~5–10% size reduction.
- `--icf=all` — identical code folding. Multiple identical functions (common with generics post-monomorphization) collapse to one. ~5% reduction.

For an additional 20–40% size reduction at the cost of losing panic info:

```bash
RUSTFLAGS="..." cargo +nightly ndk -t arm64-v8a build \
  --profile android-jni \
  -Z build-std=std,panic_abort \
  -Z build-std-features=panic_immediate_abort
```

`panic_immediate_abort` strips the `core::fmt::Arguments` machinery and unwind tables. Keep a separate `release-with-symbols` profile for nightly CI soak (with `panic = "unwind"` and full debug info) so when a crash happens the team has a reproducible binary.

## ELF symbol allowlist

The only symbols that should be exported from `libripdpi.so` / `libripdpi-tunnel.so`:

- `JNI_OnLoad` / `JNI_OnUnload`
- `Java_*` (JNI method exports following the JNI naming convention)
- System symbols: `_init`, `_fini`, `__cxa_finalize` (linker-generated)

Verify:

```bash
llvm-objdump -T app/build/intermediates/.../arm64-v8a/libripdpi.so \
  | awk '/ DF / && !/^Java_/ && !/JNI_On/ && !/__cxa/ && !/_init/ && !/_fini/ {print}'
# Expected output: empty
```

Any unexpected symbol is ABI leak — a `pub fn` somewhere in the workspace marked `#[unsafe(no_mangle)]` without the `Java_*` prefix. These leak the Rust ABI to anyone who can `dlopen` your library.

Keep the exported-symbol allowlist enforced by `scripts/ci/verify_native_elfs.py`; do not document linker flags that are absent from `.cargo/config.toml`.

## .so size budgets

The authoritative per-ABI baselines are in
`scripts/ci/native-size-baseline.json`. `scripts/ci/verify_native_sizes.py`
permits at most 128 KiB growth per tracked library and total growth up to the
tighter of 2% or 256 KiB. Do not copy byte counts into this skill.

Audit a regression:

```bash
cd native/rust
cargo bloat --locked --profile android-jni --target aarch64-linux-android --crates -n 30
cargo bloat --locked --profile android-jni --target aarch64-linux-android -n 30   # by function
```

Common culprits:
- A new monomorphized generic explosion. Use the inner-function pattern (see `rust-performance` skill).
- A new transitive dependency. Check `cargo tree --locked -p ripdpi-android` diff.
- LTO regression — verify `lto = "fat"` is still active.

## NDK 29 specifics

NDK r29 (RIPDPI's pin) changed:

- `PAGE_SIZE` macro removed for arm64-v8a / x86_64 when 16 KiB mode is active. Use `sysconf(_SC_PAGESIZE)`.
- LLVM toolchain bumped. Codegen may shift `.so` size by 1–3% versus r28; rebaseline after any NDK bump.
- Some Binder headers removed (not part of NDK ABI). If a C dep includes `binder.h` from outside NDK, it must vendor the headers or fail.
- `lldb.sh` fixes — debugging cross-compiled Rust improves but no agent-visible change.

When bumping NDK in a future PR:
1. Update `native/rust/rust-toolchain.toml` if Rust MSRV needs adjusting (NDK r29 supports rustc 1.78+).
2. Rebuild all 4 ABIs, run `llvm-readelf -lW` per `.so`, confirm alignment.
3. Re-run size verification and, when justified, update `scripts/ci/native-size-baseline.json` in a separate commit.
4. Run `android-test-runner` against the new NDK on every API level (28, 33, 34, 35).

## Cargo + Gradle integration

RIPDPI uses a Gradle convention plugin (`ripdpi.android.rust-native`) that calls `cargo build --locked` per ABI in parallel, with per-ABI `CARGO_TARGET_DIR` to avoid lock contention. The plugin sets `CARGO_TARGET_<TRIPLE>_LINKER` to the NDK's clang.

Do NOT switch back to `cargo-ndk` CLI in build scripts without consulting `cargo-workflows` skill — the Gradle plugin's per-ABI parallelism is faster than cargo-ndk's sequential mode.

## Related skills

- `cargo-workflows` — workspace structure, Cargo.lock discipline, edition migration.
- `rust-performance` — flamegraphs, cargo-bloat, monomorphization audit.
- `rust-android-jni` — JNI export naming, `EnvUnowned::with_env` pattern.
- `native-verifier` agent — automates ELF inspection and size gate.
- `rust-toolchain-pin.md` rule — channel and components governance.
