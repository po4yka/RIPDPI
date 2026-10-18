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

## Observed result on 2026-10-18

All three normal source builds passed and their actual APKs were saved. The table records the build commits, not the production reference commits. The Styles runtime and build tree matches the validated primary source `9793d98` across 8,049 scoped entries. Four harness source hashes are identical in all states.

| State | Actual build commit | ARM64 app APK SHA-256 |
|---|---|---|
| original | `fcf68112b8a9018f10dff002f55965086e26b44d` | `321c5777fca625d67c3076668d49cd949f5df9b286bc5cc69624170503a59bab` |
| dependencies | `abbbb0cb08c4768df47277186d39a7e6cba60daf` | `168145bef2ff089692ff420bad06809e597bb5580aa8f11c0f4561efe98f1375` |
| styles | `3675441ea60de7aa0ffd2d2805c6f25dbda79b3b` | `326b853ef087b5d4e5ca1e4e6ee08f56abcc8b57adf9d2ce544bdc064a738cfb` |

Acceptance records, metadata, APKs and source contracts are in [/Users/npochaev/Documents/Codex/2026-10-18/ripdpi-styles-validation/performance/accepted](/Users/npochaev/Documents/Codex/2026-10-18/ripdpi-styles-validation/performance/accepted). The original-to-dependencies native difference is exactly the published `graphics-path` 1.0.1 to 1.1.0 library; the other 15 entries match. All 16 Styles native entries match the accepted dependency-only APK. Published AAR hashes and extracted bytes are checked in [native dependency proof](/Users/npochaev/Documents/Codex/2026-10-18/ripdpi-styles-validation/performance/maven-proof/published-native-proof.json).

The final comparison completed all six runs under one standard pool lease on API 37 ARM64 `emulator-5558`. Each run passed all four benchmark methods with ten iterations each. All 240 Perfetto traces and six actual reported JSON files were pulled and hashed. The installed target hashes and the single shared test APK were checked for every run. Raw JSON execution contexts match exactly. Measured app compilation is `Full()` in the saved harness; JSON context `compilationMode=verify` is the instrumentation target compiler filter. Font scale and animation settings were fixed and restored. Cleanup had no failures and the build gate was released.

| Series | Metric | Original ms | Dependencies ms | Styles ms |
|---:|---|---:|---:|---:|
| 1 | startupCold/timeToInitialDisplayMs:median | 301.221 | 284.063 | 286.168 |
| 1 | settingsScroll/frameDurationCpuMs:p95 | 4.209 | 4.029 | 4.102 |
| 1 | logsScroll/frameDurationCpuMs:p95 | 20.521 | 31.950 | 30.845 |
| 1 | controlInteractions/frameDurationCpuMs:p95 | 15.843 | 16.929 | 19.613 |
| 2 | startupCold/timeToInitialDisplayMs:median | 255.555 | 278.446 | 297.959 |
| 2 | settingsScroll/frameDurationCpuMs:p95 | 4.075 | 3.733 | 5.180 |
| 2 | logsScroll/frameDurationCpuMs:p95 | 33.534 | 21.265 | 31.684 |
| 2 | controlInteractions/frameDurationCpuMs:p95 | 15.650 | 3.684 | 3.927 |

The strict parser returned **INCONCLUSIVE**, exit **3**. Original-to-Styles changes cross 5 percent for each metric in only one series. There is no repeated final-to-original breach and there is no PASS. Controls show a repeated dependency-only-to-Styles increase of **15.85 percent** and **6.61 percent**; this needs separate diagnosis. Large differences between series also prevent a general performance claim. No sample was filtered and no baseline or threshold was changed.

These are emulator measurements with four reported vCPUs and `cpuLocked=false`. Equal JSON contexts do not establish equal physical clock or host load. They do not establish physical-device performance or the cause of the variation. The next acceptance check is the same frozen APK comparison on a physical ARM64 device with controlled conditions, plus diagnosis of Controls. Migration acceptance remains open.

- [Full strict comparison](/Users/npochaev/Documents/Codex/2026-10-18/ripdpi-styles-validation/performance/measurements/three-state-20261018-listener-fix-1/comparison.md) and [machine-readable result](/Users/npochaev/Documents/Codex/2026-10-18/ripdpi-styles-validation/performance/measurements/three-state-20261018-listener-fix-1/comparison.json).
- [Six-run manifest](/Users/npochaev/Documents/Codex/2026-10-18/ripdpi-styles-validation/performance/measurements/three-state-20261018-listener-fix-1/manifest.json), [run and restoration summary](/Users/npochaev/Documents/Codex/2026-10-18/ripdpi-styles-validation/performance/measurements/three-state-20261018-listener-fix-1/summary.json), and per-run invocation, install, raw JSON and trace records in the same directory.
- Comparison JSON SHA-256: `3e6255abdd805efd69e8cf24f130734925abec30a80f2f44be72b09129d0eec7`.
- Manifest SHA-256: `0f17408bcbd7faf3edb0ec12b7825c3e2217f7dce74b3610a3fe8f1ecc84c832`.

Failed preflight and missing-run-end-JSON attempts are retained in separate directories. Their incomplete data are not in the final manifest. The final controller starts each preflight scene separately and uses the existing `androidx.benchmark.macro.junit4.InstrumentationResultsRunListener` from the pinned 1.5.0 test APK to report the final JSON. APKs and measured workloads were unchanged. Synthetic controller tests do not replace the six actual runs.

## Earlier capacity blocker

The following is the historical status before the accepted source rebuilds. Its former build reports later became unavailable; it does not describe the current artifact or measurement result.

The harness, strict comparison parser and source-state provenance checks are implemented. No three-state performance series has run. There is no evidence to accept or reject the five-percent performance criterion.

The original-state benchmark worktree remains at `fcf68112b8a9018f10dff002f55965086e26b44d`. Its warm Rust, pluggable-transport and Go inputs are preserved. User-authorized cleanup removed four closed generated-output directories, with 49,917 protected native-input, harness, report and artifact hashes unchanged. The report is `build/research/styles-performance-orchestration/authorized-cache-cleanup-1792277399146346000/result.json` in that worktree.

The reviewed original-build resume requires at least 12 GiB free space at admission and stops its own process group at the 3-GiB cutoff. The final UI static-analysis gate passed, but the host then had about 3.5 GiB free. The benchmark resume was not started below its floor. Earlier disk failures are not accepted build or measurement results.

Remaining acceptance requires an actual saved profileable APK for each source state, matching harness hashes, two complete series on the same leased device, and successful strict comparison of the six raw result sets. Current UI unit, screenshot and lint results cannot replace those measurements. Do not advance the benchmark source state until the original APK is accepted and saved.
