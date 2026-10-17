# Performance comparison protocol

## Builds and provenance

Compare three production source states with the same benchmark-only harness:

1. Original UI and stable dependencies, before `0c2d37932`.
2. Dependency-only UI at `0c2d37932`, with the same harness applied.
3. The committed Styles implementation, with the same harness.

The original production source is `829f5a90b04aabcd4d8a4d0820190c9f48f3de98`. Planning commits do not change that UI. Record the actual build source SHA and APK SHA-256 for each state. Save each target APK and the matching test APK before device runs. Do not rebuild between series. Confirm that the benchmark harness files are identical across the states.

Use `-Pripdpi.nativeAbis=arm64-v8a` with ABI splits enabled for all three benchmark builds. This emits only the ARM64 and universal target APKs, instead of five target APKs. Install the ARM64 APK so that its split version code matches existing pool installations. Keep the native ABI override unchanged. These supported build settings reduce packaging disk use and do not skip native compilation.

Use the existing release-derived `GithubFullBenchmark` app variant and matching `:baselineprofile` benchmark variant. The target must have `debuggable=false` and `profileable=true`. Debug builds and a suppressed debuggable check are not acceptable substitutes.

Compile and assemble through the host build gate. Use the supported verified libXray artifact directory, the same native ABI, and the same build properties for all states. Do not use `skipNativeBuild` as evidence of a complete target APK.

The source build for PT assets starts nested Cargo commands without `--jobs`. Set `[build] jobs = 2` in a disposable Cargo home as well as the Gradle native budget. Cargo then gives build scripts `NUM_JOBS=2`; the BoringSSL build uses this value for CMake. Keep the same config for all three states. Do not copy credentials or change the user Cargo config. See the [Cargo jobs setting](https://doc.rust-lang.org/cargo/reference/config.html#buildjobs).

```sh
CARGO_HOME="$PWD/.gradle-home/cargo-home" CMAKE_BUILD_PARALLEL_LEVEL=2 \
  build-gate -- env -u CARGO_BUILD_JOBS ./gradlew --max-workers=4 \
  -Pripdpi.nativeCpuBudget=2 -Pripdpi.nativeAbisOverride=arm64-v8a \
  -Pripdpi.nativeAbis=arm64-v8a -Pripdpi.enableAbiSplits=true \
  -Pripdpi.pluggableTransportAssetsMode=source \
  :app:assembleGithubFullBenchmark :baselineprofile:assembleBenchmark
```

## Workloads

`ComposeStylesBenchmark` runs ten iterations with `CompilationMode.Full()` for each method:

| Method | Workload | Acceptance metric |
|---|---|---|
| `startupCold` | Actual product cold startup with the production Application | Median `timeToInitialDisplayMs` |
| `settingsScroll` | Real Settings screen with fixed local state and repeated down/up swipes | P95 `frameDurationCpuMs` |
| `logsScroll` | Real Logs screen with 500 fixed entries, auto-scroll off, repeated down/up swipes | P95 `frameDurationCpuMs` |
| `controlInteractions` | Ten button, chip, and switch actions, with actual state updates checked | P95 `frameDurationCpuMs` |

Frame workloads use a benchmark-only activity. They do not test product navigation, persistence, VPN, or probe behavior. The production Application is unchanged and can schedule background work. Keep network state and app data preparation consistent across all states. Do not add debug automation to the benchmark or release source sets.

## Device runs

Hold one emulator-pool lease through all installs, launches, data preparation, and result pulls. Use only its exported `ANDROID_SERIAL`. Keep OS, density, font scale, animation settings, refresh rate, compilation mode, and workload inputs unchanged. Restore settings changed by the comparison before releasing the lease.

```sh
python3 scripts/ci/android-avd-pool.py run \
  --api 37 --arch arm64-v8a --target google_apis \
  --profile pixel_10_pro_xl -- <comparison-command>
```

Install each saved target APK and its matching test APK. Discover the instrumentation component from the installed test package. Run the full `com.poyka.ripdpi.performance.ComposeStylesBenchmark` class. On the emulator, suppress only `EMULATOR`. Retain the standard benchmark validity checks and the explicit target profileable check.

For a saved-APK comparison, start instrumentation directly. A connected Gradle task can reinstall its current target and replace the intended source state. Save instrumentation output, benchmark JSON, and Perfetto traces in separate state/series directories. Run a second complete series on the same leased device. Report emulator evidence as such; do not describe it as a physical-device measurement.

## Report and decision

The comparison script consumes a manifest with six run records: all three states in series 1 and 2. Each record supplies `state`, `series`, `result_files`, `device_serial`, `source_sha`, `apk_path`, and `apk_sha256`. Paths are relative to the manifest. The script verifies local APK hashes, completeness, recorded conditions, and metric values. SHA and serial declarations must also agree with the saved build/install logs.

Published AndroidX 1.5.0 sources define unknown metadata as CPU frequency `-1`, memory `-1`, and an unavailable CPU list with zero cores. Keep these values explicit and compare them exactly between runs. Report unknown metadata; it cannot prove clock conditions. JSON `context.compilationMode` describes the instrumentation target compiler filter. The stored harness and successful instrumentation logs must prove `CompilationMode.Full()` for the measured app. See the published [benchmark-common sources](https://dl.google.com/dl/android/maven2/androidx/benchmark/benchmark-common/1.5.0/benchmark-common-1.5.0-sources.jar).

```sh
python3 scripts/ci/check-compose-styles-performance.py \
  build/reports/styles-performance/manifest.json --format markdown
```

Use the raw iteration values for startup median and raw sampled CPU frame durations for P95. Show original-to-dependencies, dependencies-to-styles, and original-to-styles deltas. A final-to-original regression above 5 percent for the same metric in both series fails acceptance. A breach in only one series is inconclusive and needs investigation. Missing, invalid, or mixed-device evidence cannot pass.

Do not bless or replace a stored benchmark baseline. Compiler reports support diagnosis but do not replace startup or frame measurements. See `verification.md` for observed results; this protocol alone is not performance evidence.

## Observed status on 2026-10-18

The harness, strict comparison parser and source-state provenance checks are implemented. No three-state performance series has run. There is no evidence to accept or reject the five-percent performance criterion.

The original-state benchmark worktree remains at `fcf68112b8a9018f10dff002f55965086e26b44d`. Its warm Rust, pluggable-transport and Go inputs are preserved. User-authorized cleanup removed four closed generated-output directories, with 49,917 protected native-input, harness, report and artifact hashes unchanged. The report is `build/research/styles-performance-orchestration/authorized-cache-cleanup-1792277399146346000/result.json` in that worktree.

The reviewed original-build resume requires at least 12 GiB free space at admission and stops its own process group at the 3-GiB cutoff. The final UI static-analysis gate passed, but the host then had about 3.5 GiB free. The benchmark resume was not started below its floor. Earlier disk failures are not accepted build or measurement results.

Remaining acceptance requires an actual saved profileable APK for each source state, matching harness hashes, two complete series on the same leased device, and successful strict comparison of the six raw result sets. Current UI unit, screenshot and lint results cannot replace those measurements. Do not advance the benchmark source state until the original APK is accepted and saved.
