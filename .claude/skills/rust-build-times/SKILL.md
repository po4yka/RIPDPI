---
name: rust-build-times
description: >
  Reduce Rust compilation time: cargo-timings build profiling, sccache,
  workspace splitting, thin-vs-fat LTO, Android NDK cross-compilation.
  Triggers: slow Rust compile, cargo-timings, sccache, LTO,
  NDK build times.
---

# Rust Build Times

## Purpose

Guide agents through diagnosing and improving Rust compilation speed: `cargo-timings` for build profiling, `sccache` for caching (especially across Android NDK targets), workspace crate splitting, LTO configuration trade-offs, and linker selection for host and cross builds.

## Triggers

- "My Rust project takes too long to compile"
- "How do I profile which crates are slow to build?"
- "How do I set up sccache for Rust?"
- "Should I use thin LTO or fat LTO?"
- "Android cross-compilation is slow"
- "How do I speed up builds across 4 NDK targets?"

## Workflow

### 1. Diagnose with cargo-timings

```bash
# Build with timing report
cargo build --timings
# Opens target/cargo-timings/cargo-timing.html

# For release builds
cargo build --release --timings

# Key things to look for in the timing report:
# - Long sequential chains (no parallelism)
# - Individual crates taking > 10s (candidates for optimization)
# - Proc-macro crates blocking everything downstream
```

```bash
# cargo-llvm-lines -- count LLVM IR lines per function (monomorphization bloat)
cargo install cargo-llvm-lines
cargo llvm-lines --release | head -20
```
### 2. sccache -- compilation caching

sccache is critical for this project: 23 crates x 4 Android targets = massive redundant work without caching.

```bash
# Install
cargo install sccache  # or: brew install sccache

# Configure for Rust builds (.cargo/config.toml or env)
export RUSTC_WRAPPER=sccache

# Check cache stats (hit rate should be >80% on rebuild)
sccache --show-stats
```

```yaml
# GitHub Actions -- use mozilla-actions/sccache-action@v0.0.9
- uses: mozilla-actions/sccache-action@v0.0.9
  env:
    RUSTC_WRAPPER: sccache
```

### 3. Android NDK cross-compilation build times

This project cross-compiles to 4 Android targets: `aarch64-linux-android`, `armv7-linux-androideabi`, `i686-linux-android`, `x86_64-linux-android`. This 4x multiplier is the biggest build time factor.

**sccache across targets:** Most crate compilations differ only by target triple. sccache deduplicates effectively across targets for pure-Rust crates (no C/FFI deps). Ensure `RUSTC_WRAPPER=sccache` is set for all target builds.

**Parallel target builds in CI:**

```yaml
# Build targets in parallel CI jobs rather than sequentially
strategy:
  matrix:
    target:
      - aarch64-linux-android
      - armv7-linux-androideabi
      - i686-linux-android
      - x86_64-linux-android
# Each job builds one target; wall-clock time = 1 target build
```

**Build only needed targets during development:**

```bash
# Dev: build only arm64 (most common emulator/device)
cargo build --target aarch64-linux-android

# CI/release: all 4 targets
for target in aarch64-linux-android armv7-linux-androideabi \
              i686-linux-android x86_64-linux-android; do
  cargo build --release --target "$target"
done
```

**NDK linker:** Android NDK ships its own `lld` linker. Do not substitute `mold` or other linkers for Android targets.

### 4. Workspace splitting for parallelism

This project already has 23 crates. Key rules for maintaining parallelism:

```bash
# Visualize dependency graph
cargo tree | head -30
cargo tree --depth 1          # top-level deps only
cargo tree --prefix depth     # show depth for each crate

# Check how many crates compile in parallel
cargo build --timings         # timeline shows parallelism
```

Rules for effective workspace splitting:
- Break circular dependencies first
- Separate proc-macros into their own crate (they block everything)
- Keep frequently-changed code isolated (less cache invalidation)

### 5. LTO configuration

Current project config: `lto = "thin"`, `codegen-units = 1`, `strip = "symbols"`, `panic = "abort"` for release. This is well-optimized.

```toml
# Profile reference (from this project's Cargo.toml)
[profile.release]
lto = "thin"         # good perf, much faster than "fat"
codegen-units = 1    # best optimization (disables parallel codegen)
strip = "symbols"    # smaller binaries
panic = "abort"      # smaller binaries, no unwinding overhead

[profile.android-jni]
inherits = "release"
opt-level = "z"      # size-optimized for Android
panic = "unwind"     # JNI requires unwinding

[profile.dev]
debug = "line-tables-only"  # faster than full debug info
```

LTO comparison:

| Setting | Link time | Runtime perf | Use when |
|---------|-----------|-------------|---------|
| `lto = false` | Fast | Baseline | Dev builds |
| `lto = "thin"` | Moderate | +5-15% | Most release builds |
| `lto = "fat"` | Slow | +15-30% | Maximum performance |
| `codegen-units = 1` | Slowest | Best | With LTO for release |

### 6. Linkers

**Host builds (macOS/Linux dev):**

```bash
# lld -- LLVM's linker (faster than GNU ld, widely available)
# .cargo/config.toml
[target.x86_64-unknown-linux-gnu]
rustflags = ["-C", "link-arg=-fuse-ld=lld"]

# mold -- fastest linker, Linux ELF only (not for macOS or Android)
# .cargo/config.toml (Linux host builds only)
[target.x86_64-unknown-linux-gnu]
linker = "clang"
rustflags = ["-C", "link-arg=-fuse-ld=mold"]
```

**macOS:** The default Apple linker is adequate. No special configuration needed.

**Android targets:** NDK provides its own `lld`. Do not override the linker for `*-linux-android*` targets.

Linker speed comparison (host builds, large project):
- GNU ld: baseline
- lld: ~2x faster
- mold: ~5-10x faster (Linux ELF only)

### 7. Other quick wins

```toml
# Reduce debug info level (faster dev builds)
[profile.dev]
debug = "line-tables-only"   # already configured in this project

# Split debug info (reduces linker input on macOS)
[profile.dev]
split-debuginfo = "unpacked"
```

```bash
# Disable incremental compilation (sometimes faster for full rebuilds)
CARGO_INCREMENTAL=0 cargo build

# Heavy proc-macros (serde, tokio) -- keep versions pinned to avoid recompilation
```

## Related skills

- Use `rust-profiling` for flamegraphs, cargo-llvm-lines, cargo-bloat analysis
- Use `cargo-workflows` for workspace and profile configuration
- Use `rust-cross` for cross-compilation setup and toolchain configuration
- Use `linkers-lto` for LTO internals and linker flag details
