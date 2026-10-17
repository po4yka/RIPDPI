# UI responsiveness: logs, telemetry and profile catalog

Task: UIX-1792226888578993.

## Result

The complete API 37 comparison passed six methods with three measured iterations per method on both versions (36 Perfetto traces). Large catalogs use less frame time and stable log updates avoid repeated event projection. Telemetry no longer re-enters the navigation shell on each counter update. Some frame results remain mixed; the tables below retain those results without a general smoothness claim.

Logs now project entries only when native event lists or active session IDs change. Counter and scan progress updates reuse the entries. The projection runs on the existing injected Default dispatcher. Timestamp order, duplicate precedence, clear boundaries, filters, session flags and the 250-entry limit retain their previous behavior.

The catalog caches its projection by profiles, query and filter. Probe URL and check control updates reuse this projection. Search keeps case-insensitive AND matching across label, group and kind. Favorites, Recent, applied-first sorting, stable row keys and actions retain their previous behavior. Lazy rows also declare their content type.

The navigation shell observes a distinct permission summary from the existing MainUiState stream. The ready activity root observes settings readiness and connection state. The full runtime state stays current for connection actions. The VPN permission dialog observes full state locally while it is visible. No additional sharing timeout is introduced.

Production strings, visual layout, service lifecycles, wire formats and native sources are unchanged. The current RDS implementation and screenshot fixtures remain the visual reference.

## Reproduced redundant work

These are observed failed assertions on original production code, followed by passing regression checks on the optimized code.

| Check | Before | After |
|---|---:|---:|
| 100 counter updates with the same event list: unnecessary event reads | 100 | 0 |
| 100 progress updates with the same diagnostic event list: unnecessary event reads | 100 | 0 |
| Five control updates with 5,000 profiles: unnecessary profile reads | 25,000 | 0 |
| Five separately delivered Xray updates: extra ready-root compositions | 5 | 0 |

The original RED logs and XML are retained under `.artifacts/ui-stage-four/before/{red,progress-red,root-red}` in the job checkout. The first focused GREEN suite passed 113 tests. These counts establish work avoidance for the specified inputs; they are not frame or CPU measurements. Fresh equal lists still require content comparison.

## Device comparison

The immutable measurement sources are baseline `4b90c30b1c676309d5a1df6e59ed74b11ee202af` and optimized `0e511e843cdbcf69c3036ff6274e3e08a8fc2927`, both based on main `9f11a434572f52a2340a1e27ccac803db6f9e839`.

| APK | SHA-256 |
|---|---|
| Original app | `e170c2a03af23bbe0a8020699d53073eaf2e730b65c226d5d0e1787a5568d5f3` |
| Optimized app | `337c248248398da751f8e502aa8ebf96fe5d77ef8285a2255756bbe756d28760` |
| Shared test APK | `e56aa58d454e7091080e1bee5563c6190dbc4517e818e71e8ab3720952d0d3f6` |

Both APK builds passed and contain 17 verified ARM64 native ELF libraries. The original and optimized device suites passed in 137.577 s and 135.491 s. These suite times include test orchestration and are not performance metrics. Both phases used the same `RIPDPI_Pool_1` lease on `emulator-5554`: Android 17/API 37, ARM64, four emulator cores, 1080 x 2400, density 480, font scale 1, light theme and animation scales 1.

Both APKs contain the same benchmark driver and use real production screens. Logs and telemetry use their actual ViewModels and ServiceStateStore. The catalog receives deterministic UI state directly; its ViewModel, storage and network probes are outside this measurement. The release-derived build is minified, profileable and not debuggable. The benchmark Activity and Perfetto binary dependency exist only in the benchmark variant.

Six methods each run three measured iterations with CompilationMode.None and no ART warmup:

- Logs with the same list instance and with fresh equal snapshots.
- The actual NavHost and Settings screen during Xray updates.
- Catalogs with 100, 1,000 and 5,000 profiles during check control updates, search, clear and scroll.

Each burst publishes 100 updates at a 20 ms interval. Initial composition settles outside the measured block. The telemetry path waits for the Home stream stop timeout before the burst. All 100 input ticks must reach the store; telemetry also waits for the last value in MainViewModel. Log methods require terminal event delivery and the 250-entry cap. Catalog methods require the exact filtered row, the first row after clear, and different visible rows after scrolling.

The catalog uses accessibility text input. It does not measure per-character typing or keyboard latency. Catalog frame distributions combine the burst, search, clear and scroll, including shared driver controls and checkpoint overhead. The custom counterDispatch trace measures the elapsed fixture construction and publication span, not CPU time or downstream completion. Compose trace counts cover the named traced regions, not every composable call. The telemetry frame benchmark excludes the outer MainActivityContent root; a separate actual Compose regression checks root compositions, and the actual launcher smoke checks readiness.

Both phases run under one pool lease with fixed display size, density, font, theme and animation scales. The runner verifies installed source identities and APK hashes, saves JSON and Perfetto traces, checks crashes and service state, and restores previous device settings. Three samples on a shared-host emulator do not prove physical-device latency or release startup performance.

### Frame timing

The numbers are pooled workload percentiles from three iterations, in milliseconds. `frameDurationCpuMs` is the benchmark metric name; it includes elapsed UI/RenderThread stages and must not be read as sampled CPU execution time. Each catalog journey includes the burst, search, clear, snapshot retries and scroll.

| Method | Frame P50 before | After | Frame P95 before | After | Overrun P95 before | After |
|---|---:|---:|---:|---:|---:|---:|
| catalog100 | 4.808 | 4.354 | 15.251 | 17.982 | 1.768 | 3.118 |
| catalog1000 | 7.203 | 3.901 | 33.006 | 12.656 | 18.727 | 1.721 |
| catalog5000 | 18.575 | 4.028 | 23.651 | 8.109 | 14.337 | -3.567 |
| logsCounterBurst | 3.249 | 2.984 | 19.392 | 4.532 | 3.592 | -8.158 |
| logsFreshSnapshotBurst | 2.896 | 17.375 | 3.943 | 20.088 | -9.247 | 4.824 |
| telemetryShellCounterBurst | 3.325 | 3.587 | 5.349 | 8.152 | 6.573 | 9.453 |

Negative overrun means that the frame completed before its deadline. Catalog 1,000 and 5,000 improve across all three per-run medians. Catalog 100 has a small P50 improvement and a worse P95. Telemetry P50/P95 and the fresh-log pooled frame percentiles do not show an improvement. No general frame-time claim is made for those workloads.

### Work avoided and publication spans

| Workload | Before | After |
|---|---:|---:|
| Stable log list: event reads during 100 updates | 25,000 | 0 |
| Fresh equal log lists: counted event reads | 25,000 | 50,000 |
| Catalog 100: reads during burst | 10,000 | 0 |
| Catalog 1,000: reads during burst | 99,000 | 0 |
| Catalog 5,000: reads during burst | 500,000 | 0 |
| Telemetry: RipDpiNavHost traced invocations during burst, each iteration | 100 | 0 |
| Stable logs: median dispatch elapsed sum, ms | 126.164 | 22.259 |
| Fresh logs: median dispatch elapsed sum, ms | 123.307 | 26.599 |

The fresh-list comparison traverses both 250-event lists. The counter includes an AtomicLong increment per element access; twice as many counted reads does not establish twice the production CPU cost. It remains a cost of structural deduplication, while mapping and formatting are skipped. Both log scenarios observed all 100 ticks, delivered the terminal event and retained 250 output rows. The catalog 1,000 baseline observed 99 projections because Compose can combine adjacent updates; it is not reported as 100 exact projections.

Fresh-log frame medians vary: before 2.880/3.007/2.814 ms; after 17.827/2.880/17.858 ms. The same slow regime appears in the original stable-log iteration 2 (17.878 ms), while optimized fresh iteration 1 remains fast with the same 50,000 counted reads. The comparison therefore does not prove that structural equality caused the frame outliers. A [narrow review of four traces](data/frame-diagnosis-summary.json) locates a graphics buffer-release wait component:

| Iteration | RenderThread waitForBufferRelease, count | Elapsed sum, ms |
|---|---:|---:|
| Optimized fresh logs 0 | 129 | 1,872.656 |
| Optimized fresh logs 1 | 2 | 0.101 |
| Optimized fresh logs 2 | 130 | 1,890.446 |
| Original stable logs 2 | 130 | 1,870.955 |

The slow fresh iterations have about 1,971-1,983 ms in eglSwapBuffers, which includes these nested waits. The fast fresh iteration has about 92 ms there. Main-thread Compose:recompose elapsed sums stay close across the three optimized fresh runs (19.3-20.5 ms). Do not add nested slice durations as CPU time. These traces establish the wait component, not a GPU or driver root cause. They also do not establish that equality traversal causes the waits.

Actual launcher checks reached MainActivity and onboarding on both APKs. Crash buffers were empty, service dumps contained no running app service, and exit records contained only test force stops. Prior device settings were restored exactly. Screenshots of Logs, Settings, the 5,000-profile catalog and the real launcher were rendered and inspected.

## Native input provenance

Local benchmark builds reuse genuine ARM64 outputs from the successful original-source build `1993443f95232c1f7fd89e81891d350e93b6344d`. The receipt verifies every copied file and checks that native sources, engine, build logic, Gradle properties and wrapper inputs match both measurement sources. The final APK preservation step checks all 16 original native ELF hashes against that producer APK. The pinned real Xray AAR is also verified. Kotlin compilation, R8 and APK packaging run for each current source.

Both APKs include the same additional ARM64 Perfetto ELF, SHA-256 `56f3ee5ac2acffb4da14a9656e2793fe38eed6d2a50c67954b09972572caa2b7`. The benchmark-only `tracing-perfetto-binary:1.0.1` dependency is the same version already resolved by the test module. It lets the tracing handshake load the installed APK library. The previous fallback copied it to writable code cache, which [Android 17 rejects for native dynamic loading](https://developer.android.com/about/versions/17/behavior-changes-17#safer-native-dcl). Target SDK, full tracing and platform security remain enabled. Hosted CI retains the native source build gate.

## Validation

The job was rebased onto main `3a902bddea2d7cab970fe9e3a0b6ff7f81a6703e`. Independent review compared combined implementation source `fa8f942899a8bd78abde6b115d8d670e0fe28b24` with the measured optimized source. Logs, catalog, benchmark, engine, native sources, build wiring and native build inputs have no difference. New main changes add reconnect presentation and transition feedback. Settings identity is created on settings emissions, not telemetry ticks. The applied runtime configuration starts as Unknown; this skips reconnect comparison in the measured Halted scenario. Home and History feedback are outside the measured screens. New stable fields still pass through the combined state. This review supports the scope of the existing idle measurement; it does not establish byte identity or measured latency for the rebased APK. Active reconnect performance remains unmeasured.

At the previous combined source on main `f67a777ed8b06452650eb3355771566abf7bbfd2`, 322 Full host tests and all 16 selected screenshot methods passed again. Root static analysis and all serial lint variants passed. The latest main requires a fresh combined run, which is queued. At the latest main, the architecture check reported 21 existing indicators, zero new or worsened indicators, and zero stale entries. Full locked Cargo metadata resolved 910 packages and 116 workspace members. Main integration and hosted CI for this change remain pending.

The machine-readable [measurement summary](data/ui-stage-four-measurement.json) retains all six workloads, per-run medians, counters, sources, APK hashes and device guards. The 36 traces, XML, logs, screenshots and installed APK identities remain under `.artifacts/ui-stage-four/{before-device-v6,after-device-v6,analysis-v6,pair-device-v6}` in the job checkout. The original APKs remain under `.artifacts/ui-stage-four/before/apks-4b90c30b1c67` in the measurement checkout. Optimized APKs remain under `.artifacts/ui-stage-four/after/apks-0e511e843cdb` in the job checkout. Those local artifacts are not committed binaries.

Checks observed before and during combined validation:

- Expanded Github Full host suite: 322 tests in 34 classes; no failures, errors or skips.
- Screenshot verification: 16 tests checked 17 existing PNGs; fixtures unchanged.
- Root staticAnalysis, Full and service lint passed. Simple lint passed. Benchmark lint, detekt and ktlint passed.
- Architecture health and locked Cargo metadata passed.
- Independent implementation and harness reviews found no remaining actionable defect.

The host gate is `:app:testGithubFullDebugUnitTest` with the affected logs, root, navigation, profiles, permissions and theme classes. The screenshot gate uses the same task with `-Pripdpi.includeRoborazziUnitTests=true -Proborazzi.test.verify=true`. Static gates are `staticAnalysis`, `:app:lintGithubFullDebug`, `:app:lintGithubSimpleDebug`, `:core:service:lintDebug` and `:app:lintGithubFullBenchmark`. The measured APK gates are `:app:assembleGithubFullBenchmark` and `:baselineprofile:assembleBenchmark`. Architecture and metadata gates are `python3 scripts/ci/check_architecture_health.py` and `cargo metadata --manifest-path native/rust/Cargo.toml --locked`.

Simple unit tests stopped before execution because the private local embedded relay bundle was absent. They are not counted as passing. No fake bundle or weakened gate was added. Lint variants run sequentially after KSP prewarm because concurrent variant generation caused a lint-engine UAST race.

## Excluded runs and resource handling

Earlier attempts are retained separately and excluded from the paired comparison. They stopped because of insufficient disk space, a checkpoint under SystemUI, writable Perfetto library loading, an unnecessary Back action after accessibility text input, or stale accessibility references after scrolling. The Back action finished the standalone Activity. The fixes remove that Back action and retry a complete row snapshot only on StaleObjectException, within the existing 15-second timeout. All row, clear and scroll assertions remain. A partial run passed logs and telemetry but failed the catalog methods; it is not a complete baseline result.

With user authorization, bounded cleanup removed inactive reproducible intermediates and compiler output caches. Each operation checked path identity, ownership, active use and artifact preservation. Source files, APKs, test reports, traces, downloaded dependencies and AVD data were preserved. Cleanup receipts remain in `.artifacts/ui-stage-four/cleanup`. No cleanup runs during measurement. The final device selector leases only an existing compatible initialized Pool_1 and fails if it cannot acquire it; it does not create or remove a device.
