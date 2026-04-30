---
name: rust-profiling
description: Rust profiling for Android .so files, simpleperf, perfetto, bloat, benchmarks, and flamegraphs.
---

# Rust Profiling (RIPDPI)

## Project context

- 23 Rust crates at `native/rust/`, cross-compiled to Android NDK targets
- Custom Cargo profiles in `native/rust/Cargo.toml`:
  - `android-jni` -- release for APK: `opt-level="z"`, `panic="unwind"`, inherits release (thin LTO, codegen-units=1, strip=symbols)
  - `android-jni-dev` -- dev for on-device debugging: `opt-level=1`, `debug="line-tables-only"`, `panic="unwind"`
  - `bench` -- host benchmarks: `debug=false`, `lto="thin"`
- Benchmark crate: `native/rust/crates/ripdpi-bench/` with `config_parse` and `relay_throughput` benchmarks
- Criterion 0.8 (workspace dependency)

## 1. Android on-device profiling (primary workflow)

Host tools like `perf`, `heaptrack`, `DHAT` do not work for Android targets.
Use `simpleperf` or Perfetto instead.

### simpleperf (CPU profiling)

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

### Perfetto (system-wide tracing)

```bash
# Record CPU scheduling + callstacks
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

### Reading Android profiles

- Use `android-jni-dev` profile for symbol info (`debug="line-tables-only"`)
- `android-jni` strips symbols -- profiles will show raw addresses only
- For release builds, keep an unstripped copy: check `target/aarch64-linux-android/android-jni/libripdpi.so` before strip

## 2. Binary size analysis (cargo-bloat)

Always target `--profile android-jni` to match what ships in the APK.

```bash
cd native/rust

# Per-crate breakdown (what matters for APK)
cargo bloat --profile android-jni --target aarch64-linux-android --crates

# Top 20 functions by size
cargo bloat --profile android-jni --target aarch64-linux-android -n 20

# Compare before/after
cargo bloat --profile android-jni --target aarch64-linux-android --crates > before.txt
# make changes
cargo bloat --profile android-jni --target aarch64-linux-android --crates > after.txt
diff before.txt after.txt
```

### .so stripping vs debug=0 trade-offs

The `android-jni` profile sets `strip = "symbols"` (inherited from release).

| Setting | .so size | Debuggable | Notes |
|---------|----------|------------|-------|
| `strip = "symbols"` (current) | Smallest | No | Default for APK; removes all symbols + debug info |
| `strip = "debuginfo"` | ~5-10% larger | Partial | Keeps symbol names for profiling, drops DWARF |
| `strip = "none"` + `debug = 0` | ~10-15% larger | No | No debug info generated, but ELF symbols remain |
| `strip = "none"` + `debug = "line-tables-only"` | ~30-50% larger | Yes | For on-device profiling; do not ship |

For APK size, `strip = "symbols"` with `opt-level = "z"` is optimal.
Keep unstripped builds only for profiling sessions.

## 3. Monomorphization bloat (cargo-llvm-lines)

```bash
cd native/rust

cargo llvm-lines --release -p ripdpi-ws-tunnel | head -30
cargo llvm-lines --release -p ripdpi-session | head -30
```

High `Copies` count = monomorphization expansion. Fix with the inner-function pattern:

```rust
// Before: monomorphized for every T
fn send<T: AsRef<[u8]>>(data: T) { send_inner(data.as_ref()) }

// After: thin generic wrapper + concrete inner (single copy)
fn send<T: AsRef<[u8]>>(data: T) { fn inner(data: &[u8]) { /* ... */ } inner(data.as_ref()) }
```

## 4. Criterion microbenchmarks (ripdpi-bench)

The project uses Criterion 0.8 with two benchmarks: `config_parse` and `relay_throughput`.

```bash
cd native/rust

# Run all benchmarks
cargo bench -p ripdpi-bench

# Run specific benchmark
cargo bench -p ripdpi-bench --bench relay_throughput

# Filter to specific function
cargo bench -p ripdpi-bench -- "throughput/4096"

# Save baseline and compare
cargo bench -p ripdpi-bench -- --save-baseline before
# make changes
cargo bench -p ripdpi-bench -- --baseline before

# View HTML report
open target/criterion/report/index.html
```

See [references/cargo-flamegraph-setup.md](references/cargo-flamegraph-setup.md) for writing new benchmarks with throughput reporting and async support.

## 5. Host flamegraphs (cargo-flamegraph)

Works for host-target binaries and benchmarks only (not Android targets).

```bash
# Profile a benchmark on host
cargo flamegraph --bench relay_throughput -p ripdpi-bench -- --bench

# macOS: requires DTrace + sudo
sudo cargo flamegraph --bench relay_throughput -p ripdpi-bench -- --bench

# Linux: requires perf_event_paranoid <= 1
cargo flamegraph --bin ripdpi-cli -- args
```

### Reading flamegraphs

| Axis | Meaning |
|------|---------|
| X (width) | CPU time proportion (wider = hotter) -- NOT time sequence |
| Y (height) | Call stack depth (bottom = entry point) |
| Color | Random (no significance) unless differential |

What to look for:

| Pattern | Meaning | Action |
|---------|---------|--------|
| Wide plateau at top | Leaf hotspot | Optimize that function |
| Wide frame, tall narrow towers | Hot dispatch | Reduce call overhead |
| Unexpected `alloc`/`drop` frames | Excessive allocation | Pool or reuse buffers |
| Many `<closure>` frames | Closure overhead in tight loops | Extract to named function |

Differential flamegraphs: red = regression, blue = improvement.

## 6. Host-only profiling tools (brief reference)

These require running on the host, not on Android:

- **`perf stat`/`perf record`** -- Linux only; use `RUSTFLAGS="-C force-frame-pointers=yes"` for better call graphs
- **`heaptrack`** -- Linux heap profiler; `heaptrack ./target/release/binary`
- **`DHAT`** -- Valgrind heap profiler; `valgrind --tool=dhat ./target/debug/binary`
- **DTrace** -- macOS; used automatically by `cargo flamegraph`

For host-target testing with `ripdpi-cli`, these work directly.
For Android profiling, use simpleperf/Perfetto (section 1).

## References

- [references/cargo-flamegraph-setup.md](references/cargo-flamegraph-setup.md) -- flamegraph setup and Criterion config details
- Android NDK simpleperf docs: `$ANDROID_NDK/simpleperf/doc/`
- Perfetto UI: https://ui.perfetto.dev

## Related skills

- `rust-build-times` -- build time optimization, LTO trade-offs
- `cargo-workflows` -- workspace management, feature flags, profiles
- `flamegraphs` (global) -- detailed flamegraph generation from any profiler input
