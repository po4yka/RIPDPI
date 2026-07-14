---
name: rust-performance
description: Use when profiling native .so files on Android with simpleperf/Perfetto, generating host flamegraphs, measuring binary size with cargo-bloat, analyzing monomorphization bloat with cargo-llvm-lines, configuring LTO/codegen-units, tuning sccache for the 4-ABI build matrix, writing or reviewing Criterion microbenchmarks in ripdpi-bench, or investigating cross-compilation build-time regressions. Triggers on "flamegraph", "cargo-bloat", "binary size", "build time", "LTO", or any performance question.
---

# Rust Performance -- RIPDPI

## Project context

- 97 Rust crates at `native/rust/`, cross-compiled to 4 Android NDK targets
- Custom Cargo profiles in `native/rust/Cargo.toml`:
  - `android-jni` — release for APK: `opt-level="z"`, `panic="unwind"`, thin LTO, `codegen-units=1`, `strip=symbols`
  - `android-jni-dev` — dev for on-device debugging: `opt-level=1`, `debug="line-tables-only"`, `panic="unwind"`
  - `bench` — host benchmarks: `debug=false`, `lto="thin"`
- Benchmark crate: `native/rust/crates/ripdpi-bench/` with `config_parse` and `relay_throughput` benchmarks
- Criterion 0.8 (workspace dependency)

---

## Runtime Profiling

### 1. Android on-device profiling (primary workflow)

Host tools like `perf`, `heaptrack`, `DHAT` do not work for Android targets.
Use `simpleperf` or Perfetto instead.

#### simpleperf (CPU profiling)

```bash
# Push debug .so to device (built with android-jni-dev for symbols)
adb push target/aarch64-linux-android/android-jni-dev/libripdpi.so /data/local/tmp/

# Record while app runs (app must be debuggable or device rooted)
adb shell simpleperf record -p $(adb shell pidof com.poyka.ripdpi) \
    -g --duration 10 -o /data/local/tmp/perf.data

# Pull and convert to flamegraph
adb pull /data/local/tmp/perf.data .
simpleperf report-sample --protobuf perf.data -o perf.trace
# Or generate flamegraph directly:
simpleperf_report_lib.py -i perf.data --symfs . | flamegraph.pl > fg.svg
```

Android NDK ships `simpleperf` at `$ANDROID_NDK/simpleperf/`.

#### Perfetto (system-wide tracing)

```bash
adb shell perfetto -c - --txt -o /data/local/tmp/trace <<'EOF'
buffers { size_kb: 65536 }
data_sources { config {
    name: "linux.process_stats"
    target_buffer: 0
}}
data_sources { config {
    name: "linux.perf"
    target_buffer: 0
    perf_event_config {
        timebase { frequency: 999 }
        callstack_sampling { kernel_frames: true }
    }
}}
duration_ms: 10000
EOF

adb pull /data/local/tmp/trace .
# Open at https://ui.perfetto.dev
```

#### Reading Android profiles

- Use `android-jni-dev` profile for symbol info (`debug="line-tables-only"`)
- `android-jni` strips symbols — profiles will show raw addresses only
- For release builds, keep an unstripped copy: check `target/aarch64-linux-android/android-jni/libripdpi.so` before strip

### 2. Binary size analysis (cargo-bloat)

Always target `--profile android-jni` to match what ships in the APK.

```bash
cd native/rust

# Per-crate breakdown (what matters for APK)
cargo bloat --locked --profile android-jni --target aarch64-linux-android --crates

# Top 20 functions by size
cargo bloat --locked --profile android-jni --target aarch64-linux-android -n 20

# Compare before/after
cargo bloat --locked --profile android-jni --target aarch64-linux-android --crates > before.txt
# make changes
cargo bloat --locked --profile android-jni --target aarch64-linux-android --crates > after.txt
diff before.txt after.txt
```

#### .so stripping vs debug trade-offs

| Setting | .so size | Debuggable | Notes |
|---------|----------|------------|-------|
| `strip = "symbols"` (current) | Smallest | No | Default for APK |
| `strip = "debuginfo"` | ~5-10% larger | Partial | Keeps symbol names for profiling |
| `strip = "none"` + `debug = 0` | ~10-15% larger | No | ELF symbols remain |
| `strip = "none"` + `debug = "line-tables-only"` | ~30-50% larger | Yes | For profiling sessions only |

### 3. Monomorphization bloat (cargo-llvm-lines)

```bash
cd native/rust

cargo llvm-lines --locked --release -p ripdpi-ws-tunnel | head -30
cargo llvm-lines --locked --release -p ripdpi-session | head -30
```

High `Copies` count = monomorphization expansion. Fix with the inner-function pattern:

```rust
// Before: monomorphized for every T
fn send<T: AsRef<[u8]>>(data: T) { send_inner(data.as_ref()) }

// After: thin generic wrapper + concrete inner (single copy)
fn send<T: AsRef<[u8]>>(data: T) { fn inner(data: &[u8]) { /* ... */ } inner(data.as_ref()) }
```

### 4. Criterion microbenchmarks (ripdpi-bench)

```bash
cd native/rust

# Run all benchmarks
cargo bench --locked -p ripdpi-bench

# Run specific benchmark
cargo bench --locked -p ripdpi-bench --bench relay_throughput

# Filter to specific function
cargo bench --locked -p ripdpi-bench -- "throughput/4096"

# Save baseline and compare
cargo bench --locked -p ripdpi-bench -- --save-baseline before
# make changes
cargo bench --locked -p ripdpi-bench -- --baseline before

# View HTML report
open target/criterion/report/index.html
```

See [references/cargo-flamegraph-setup.md](references/cargo-flamegraph-setup.md) for writing new benchmarks with throughput reporting and async support.

### 5. Host flamegraphs (cargo-flamegraph)

Works for host-target binaries and benchmarks only (not Android targets).

```bash
# Profile a benchmark on host
cargo flamegraph --locked --bench relay_throughput -p ripdpi-bench -- --bench

# macOS: requires DTrace + sudo
sudo cargo flamegraph --locked --bench relay_throughput -p ripdpi-bench -- --bench

# Linux: requires perf_event_paranoid <= 1
cargo flamegraph --locked --bin ripdpi-cli -- args
```

#### Reading flamegraphs

| Axis | Meaning |
|------|---------|
| X (width) | CPU time proportion (wider = hotter) — NOT time sequence |
| Y (height) | Call stack depth (bottom = entry point) |

| Pattern | Meaning | Action |
|---------|---------|--------|
| Wide plateau at top | Leaf hotspot | Optimize that function |
| Wide frame, tall narrow towers | Hot dispatch | Reduce call overhead |
| Unexpected `alloc`/`drop` frames | Excessive allocation | Pool or reuse buffers |
| Many `<closure>` frames | Closure overhead in tight loops | Extract to named function |

Differential flamegraphs: red = regression, blue = improvement.

### 6. Host-only profiling tools (brief reference)

These require running on the host, not on Android:

- **`perf stat`/`perf record`** — Linux only; use `RUSTFLAGS="-C force-frame-pointers=yes"` for better call graphs
- **`heaptrack`** — Linux heap profiler; `heaptrack ./target/release/binary`
- **`DHAT`** — Valgrind heap profiler; `valgrind --tool=dhat ./target/debug/binary`
- **DTrace** — macOS; used automatically by `cargo flamegraph --locked`

---

## Build-Time Optimization

### 7. Diagnose with cargo-timings

```bash
# Build with timing report (opens target/cargo-timings/cargo-timing.html)
cargo build --locked --timings
cargo build --locked --release --timings

# Key things to look for:
# - Long sequential chains (no parallelism)
# - Individual crates taking > 10s (candidates for optimization)
# - Proc-macro crates blocking everything downstream
```

```bash
# cargo-llvm-lines — count LLVM IR lines per function (monomorphization bloat)
cargo install cargo-llvm-lines
cargo llvm-lines --locked --release | head -20
```

### 8. sccache — compilation caching

sccache is critical for this project: 97 crates × 4 Android targets = massive redundant work without caching.

```bash
# Install
cargo install sccache  # or: brew install sccache

# Configure for Rust builds (.cargo/config.toml or env)
export RUSTC_WRAPPER=sccache

# Check cache stats (hit rate should be >80% on rebuild)
sccache --show-stats
```

```yaml
# GitHub Actions
- uses: mozilla-actions/sccache-action@v0.0.9
  env:
    RUSTC_WRAPPER: sccache
```

### 9. Android NDK cross-compilation build times

This project cross-compiles to 4 Android targets: `aarch64-linux-android`, `armv7-linux-androideabi`, `i686-linux-android`, `x86_64-linux-android`. This 4× multiplier is the biggest build time factor.

**sccache across targets:** Most crate compilations differ only by target triple. sccache deduplicates effectively across targets for pure-Rust crates. Ensure `RUSTC_WRAPPER=sccache` is set for all target builds.

**Parallel target builds in CI:**

```yaml
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
cargo build --locked --target aarch64-linux-android

# CI/release: all 4 targets
for target in aarch64-linux-android armv7-linux-androideabi \
              i686-linux-android x86_64-linux-android; do
  cargo build --locked --release --target "$target"
done
```

**NDK linker:** Android NDK ships its own `lld`. Do not substitute `mold` or other linkers for Android targets.

### 10. Workspace splitting for parallelism

```bash
# Visualize dependency graph
cargo tree --locked | head -30
cargo tree --locked --depth 1
cargo tree --locked --prefix depth

# Check how many crates compile in parallel
cargo build --locked --timings  # timeline shows parallelism
```

Rules for effective workspace splitting:
- Break circular dependencies first
- Separate proc-macros into their own crate (they block everything)
- Keep frequently-changed code isolated (less cache invalidation)

### 11. LTO configuration

Current project config: `lto = "thin"`, `codegen-units = 1`, `strip = "symbols"`, `panic = "abort"` for release. This is well-optimized.

```toml
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

### 12. Linkers

**Host builds (macOS/Linux dev):**

```toml
# lld — LLVM's linker (faster than GNU ld)
[target.x86_64-unknown-linux-gnu]
rustflags = ["-C", "link-arg=-fuse-ld=lld"]

# mold — fastest linker, Linux ELF only (not for macOS or Android)
[target.x86_64-unknown-linux-gnu]
linker = "clang"
rustflags = ["-C", "link-arg=-fuse-ld=mold"]
```

**macOS:** The default Apple linker is adequate.

**Android targets:** NDK provides its own `lld`. Do not override the linker for `*-linux-android*` targets.

Linker speed comparison (host builds, large project): GNU ld → lld (~2×) → mold (~5-10×, Linux ELF only).

### 13. Other quick wins

```toml
# Reduce debug info level (faster dev builds)
[profile.dev]
debug = "line-tables-only"   # already configured in this project
split-debuginfo = "unpacked" # reduces linker input on macOS
```

```bash
# Disable incremental compilation (sometimes faster for full rebuilds)
CARGO_INCREMENTAL=0 cargo build --locked

# Heavy proc-macros (serde, tokio) — keep versions pinned to avoid recompilation
```

---

## References

- [references/cargo-flamegraph-setup.md](references/cargo-flamegraph-setup.md) — flamegraph setup and Criterion config
- Android NDK simpleperf docs: `$ANDROID_NDK/simpleperf/doc/`
- Perfetto UI: https://ui.perfetto.dev

## Related skills

- `cargo-workflows` — workspace management, feature flags, profiles
- `rust-discipline` — allocation anti-patterns on hot paths
