---
name: native-profiling
description: Use when profiling native Rust code on Android -- CPU flamegraphs with simpleperf, memory debugging with HWASan, and desktop profiling with cargo-flamegraph
---

# Native Profiling

CPU and memory profiling for Rust `.so` libraries running on Android devices and emulators, plus desktop profiling for `ripdpi-cli`.

## Prerequisites

| Requirement | Status | Location |
|-------------|--------|----------|
| Frame pointers (`-Cforce-frame-pointers=yes`) | Enabled | `native/rust/.cargo/config.toml` |
| Debug symbols preserved in debug APK | Enabled | `ripdpi.android.native` convention plugin (`keepDebugSymbols`) |
| Panic hook with backtrace capture | Installed | `android-support::install_panic_hook()` in both `JNI_OnLoad` |
| Unstripped `.so` for symbolication | Available | `native/rust/target/<triple>/debug/` |

## CPU Profiling with simpleperf

### On-Device Recording

```bash
# Find the app PID
adb shell pidof com.poyka.ripdpi

# Record for 30 seconds with call-graph
adb shell simpleperf record \
  -p $(adb shell pidof com.poyka.ripdpi) \
  --call-graph dwarf \
  --duration 30 \
  -o /data/local/tmp/perf.data

# Pull to host
adb pull /data/local/tmp/perf.data
```

### Generate Flamegraph

Use Inferno (bundled with the NDK):

```bash
python3 $ANDROID_NDK_HOME/simpleperf/inferno.py \
  -sc --record_file perf.data
# Opens flamegraph.html in browser
```

Or with the standalone `inferno` Rust tool:

```bash
cargo install inferno
simpleperf report-sample --show-callchain perf.data | inferno-flamegraph > flame.svg
```

### Why Frame Pointers Matter

Without `-Cforce-frame-pointers=yes`, simpleperf cannot walk the stack reliably on ARM64. Flamegraphs show empty or truncated stacks. The flag is set for all four Android targets in `native/rust/.cargo/config.toml`.

The overhead is negligible -- one general-purpose register (x29 on ARM64) is reserved as the frame pointer.

## Desktop Profiling (ripdpi-cli)

Profile the CLI proxy on macOS or Linux without an Android device:

```bash
# Install cargo-flamegraph (uses dtrace on macOS, perf on Linux)
cargo install flamegraph

# Generate flamegraph while running the proxy
cd native/rust
cargo flamegraph -p ripdpi-cli -- -p 1080 -x 1
# Send traffic through the proxy, then Ctrl+C
# Opens flamegraph.svg
```

### Heap Profiling with DHAT

```bash
# Requires nightly
cd native/rust
cargo +nightly run -p ripdpi-cli \
  --features dhat-heap -- -p 1080 -x 1
# Produces dhat-heap.json on exit
# Open at https://nnethercote.github.io/dh_view/dh_view.html
```

## Memory Debugging with HWASan

HWASan (Hardware Address Sanitizer) is the replacement for ASan, which is unsupported since NDK r26. Requires ARM64 device running Android 10+.

### Build with HWASan

```bash
# Requires nightly Rust for -Zbuild-std
cd native/rust
RUSTFLAGS="-Zsanitizer=hwaddress" cargo +nightly build \
  -p ripdpi-android \
  --target aarch64-linux-android \
  -Zbuild-std \
  --profile android-jni-dev
```

### Run on Device (Android 14+)

Create a `wrap.sh` script in the APK's lib directory:

```bash
#!/system/bin/sh
LD_HWASAN=1 exec "$@"
```

### What HWASan Detects

- Heap buffer overflow / underflow
- Use-after-free
- Double-free
- Stack use-after-return
- Use of uninitialized memory (partial)

## Android Studio Integration

### Native Debugging with LLDB

1. Open the project in Android Studio
2. Edit Run/Debug Configuration -> set Debug type to **Dual (Java + Native)**
3. Set breakpoints in Rust source files (Android Studio resolves them via debug symbols)
4. Run in debug mode -- LLDB attaches to the native process automatically

`keepDebugSymbols` in the convention plugin ensures the debug APK contains unstripped `.so` files with line-table info.

### Memory Profiler

1. Open the **Profiler** tab in Android Studio
2. Select the app process
3. Click **Record native allocations**
4. The profiler shows native memory alongside Java heap

Stack traces in the native profiler require unstripped symbols. Debug builds have them; release builds need offline symbolication.

## Offline Symbolication

### ndk-stack (Recommended)

Symbolicates native crash logs from logcat:

```bash
adb logcat | $ANDROID_NDK_HOME/ndk-stack \
  -sym native/rust/target/aarch64-linux-android/debug/
```

### llvm-addr2line (Single Address)

```bash
$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/*/bin/llvm-addr2line \
  -e native/rust/target/aarch64-linux-android/debug/libripdpi_android.so \
  0x29ba4
```

Use the **unstripped** `.so` from `native/rust/target/<triple>/debug/`, not the stripped copy in `core/engine/build/generated/jniLibs/`.

### Panic Backtrace

The global panic hook (`android-support::install_panic_hook()`) captures `std::backtrace::Backtrace::force_capture()` and logs it to logcat via `log::error!`. Filter with:

```bash
adb logcat -s ripdpi-native:E ripdpi-tunnel-native:E | grep -A 50 "PANIC:"
```

Backtraces in `android-jni-dev` profile show file/line info (`debug = "line-tables-only"`). Release profile backtraces show only addresses -- symbolicate offline with `ndk-stack`.

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Flamegraph shows empty stacks | Verify `-Cforce-frame-pointers=yes` in `.cargo/config.toml` |
| Symbolication shows `<unknown>` | Use unstripped `.so` from `target/<triple>/debug/`, not `jniLibs/` |
| ASan build fails | ASan is unsupported since NDK r26; use HWASan instead |
| HWASan on x86_64 emulator | HWASan is ARM64-only; use a physical device or ARM64 emulator |
| Panic backtrace missing | Ensure `install_panic_hook()` is called after `init_android_logging()` |
| `simpleperf record` permission denied | Run `adb shell` as root, or target a debuggable app (`android:debuggable="true"`) |
| Profiling release build shows no symbols | Expected -- release profile strips symbols; profile with `android-jni-dev` build |

## Quick Reference

| Task | Command |
|------|---------|
| Record CPU profile | `adb shell simpleperf record -p $(adb shell pidof com.poyka.ripdpi) --call-graph dwarf --duration 30 -o /data/local/tmp/perf.data` |
| Generate flamegraph | `python3 $ANDROID_NDK_HOME/simpleperf/inferno.py -sc --record_file perf.data` |
| Desktop flamegraph | `cargo flamegraph -p ripdpi-cli -- -p 1080 -x 1` |
| Symbolicate crash log | `adb logcat \| ndk-stack -sym native/rust/target/aarch64-linux-android/debug/` |
| Filter panic backtraces | `adb logcat -s ripdpi-native:E \| grep -A 50 "PANIC:"` |

## See Also

- `android-device-debug` -- ADB commands, logcat filtering, crash/ANR debugging
- `native-jni-development` -- Build pipeline, JNI exports, lifecycle rules
