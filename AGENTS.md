# AGENTS.md -- RIPDPI

## Project

RIPDPI is an Android VPN/proxy application for DPI (Deep Packet Inspection) bypass. It runs a local SOCKS5 proxy and VPN tunnel through in-repository Rust native modules.

## Setup

1. Requirements: JDK 17, Android SDK, Android NDK 29.0.14206865, stable Rust toolchain with Android targets, Android CLI (`android`) from `d.android.com/tools/agents` (agents and CI depend on it; `android docs` must be available)
2. Native build properties are defined in `gradle.properties` -- do not hardcode NDK version, ABI filters, or SDK levels elsewhere
3. The Android build invokes the `ripdpi.android.rust-native` convention plugin from `:core:engine`, which builds the native workspace under `native/rust/`
4. Agent runtime permissions for `android` CLI calls:
   - **Claude Code**: `.claude/settings.local.json` allowlists `Bash(android:*)` -- committed, no per-developer action needed
   - **Codex**: approve the project once via `trust_level = "trusted"` in `~/.codex/config.toml` (Codex prompts on first run). Codex has no per-command allowlist; project trust covers all shell invocations including `android docs` / `android sdk` / `android emulator`.

## Build & Test

```bash
./gradlew assembleDebug              # Debug build (includes native compilation)
./gradlew assembleRelease             # Release build (requires signing env vars)
./gradlew testDebugUnitTest           # Run all unit tests
./gradlew :core:data:testDebugUnitTest  # Run tests for a single module
./gradlew staticAnalysis              # Run detekt + ktlint + Android lint
```

## Architecture

```
:app (UI/Compose) --> :core:service (VPN/proxy services)
                          |
                     :core:engine (Rust native + JNI)
                          |
                     :core:data (protobuf + DataStore)
                          |
              :core:diagnostics (active/passive diagnostics)
                          |
            :core:diagnostics-data (diagnostics contracts)
```

### Modules

- **`:app`** -- Jetpack Compose UI with Material 3, navigation, ViewModels
- **`:core:data`** -- App settings via Protobuf (schema: `core/data/src/main/proto/app_settings.proto`) and Jetpack DataStore
- **`:core:diagnostics`** -- Active network diagnostics, passive telemetry collection, diagnostics UI logic
- **`:core:diagnostics-data`** -- Protobuf schemas and data contracts for diagnostics
- **`:core:engine`** -- Native proxy and tunnel engine with JNI bridge, built from repo-owned Rust crates
- **`:core:service`** -- Android VPN and proxy foreground services
- **`:quality:detekt-rules`** -- Custom detekt rules (DI guardrails, Hilt ViewModel checks)
- **`:baselineprofile`** -- Baseline profile generation for runtime performance

### Current Diagnostics Surface

- `quick_v1` automatic probing is used for user-triggered recommendations and hidden first-seen-network handover re-checks
- `full_matrix_v1` Automatic Audit is a manual diagnostics workflow with rotating curated target cohorts, confidence/coverage assessment, and winners-first reporting
- Strategy-probe progress is structured: active TCP/QUIC lane, candidate index/total, candidate id, and candidate label are exposed through the native progress contract
- Strategy-probe reports now carry `auditAssessment` and `targetSelection`; export/share summaries include the selected audit cohort and coverage/confidence details
- Automatic probing/audit is unavailable when `Use command line settings` is enabled because those workflows require isolated UI-config strategy trials
- Remembered-network persistence is driven by validated recommendations; full-matrix audit results remain manual-apply only

### Home Composite Diagnostic Run

The home analysis runs 4 stages sequentially:

| Stage | Profile | Kind | Timeout |
|-------|---------|------|---------|
| automatic_audit | automatic-audit | STRATEGY_PROBE | 300s |
| default_connectivity | default | CONNECTIVITY | 120s |
| dpi_full | ru-dpi-full | CONNECTIVITY | 240s |
| dpi_strategy | ru-dpi-strategy | STRATEGY_PROBE | 300s |

- If the audit stage fails/times out, remaining stages are skipped
- If the VPN service halts during a stage, it is marked FAILED and remaining stages are skipped
- The `dpi_strategy` stage runs `finalizeHomeAudit()` as a fallback when the audit was not actionable
- Native scan deadline is set to `stageTimeout - 30s` to ensure the native engine finalizes partial results before the Kotlin timeout fires
- Partial results are recovered via a 3s grace period poll after cancellation

### Strategy Probe Candidates

**24 TCP candidates** (ordered modern-first for censored networks):

| Category | Candidates | requires_fake_ttl |
|----------|-----------|-------------------|
| TLS record + split/hostfake | tlsrec_split_host, tlsrec_hostfake_split | false |
| TLS record + fake | tlsrec_fake_rich, tlsrec_fakedsplit, tlsrec_fakeddisorder | true |
| Split | split_host | false |
| Disorder | disorder_host, tlsrec_disorder | true |
| OOB (TCP urgent pointer) | oob_host, tlsrec_oob | false |
| Disorder + OOB | disoob_host, tlsrec_disoob | true |
| TLS random record | tlsrandrec_split, tlsrandrec_disorder | mixed |
| Hostfake | tlsrec_hostfake | true |
| Hostfake (random) | tlsrec_hostfake_random | false |
| Delayed split | split_delayed_50ms, split_delayed_150ms | false |
| Parser (legacy) | parser_only, parser_unixeol, parser_methodeol | false |
| ECH (specialized) | ech_split, ech_tlsrec | false |

**6 QUIC candidates**: quic_disabled, quic_compat_burst, quic_realistic_burst, quic_sni_split, quic_fake_version, quic_dummy_prepend

**Performance optimizations**:
- CONNECT_TIMEOUT: 2.5s (reduced from 4s)
- Within-candidate domain parallelism: 3 domains tested concurrently via `thread::scope`
- Tournament bracket: Round 1 qualifier tests each candidate against 1 domain, eliminating ~70% of failing candidates before the full-matrix round
- RST-pattern adaptive timeout: 1.5s when baseline detects TcpReset
- Candidate reordering: modern TLS-record techniques first, legacy parser tricks last

### DNS Resolver Resilience

- Default encrypted DNS: AdGuard (Russian company, least likely blocked)
- Provider order: AdGuard > DNS.SB > Mullvad > Google IP > Cloudflare IP > Google > Quad9 > Cloudflare
- Fallback resolver loop: when primary encrypted DNS fails, tries up to 3 alternative resolvers (AdGuard, DNS.SB, Google IP, Mullvad) in both strategy probes and connectivity probes
- Eager failover: catastrophic DNS errors (connection reset, refused) trigger immediate resolver switch on first query
- Service halt guard: VPN service doesn't halt while DNS failover candidates remain

### Autolearn Host Filtering

The autolearn system filters known telemetry/system hosts (Google, Huawei, Samsung, Apple, Microsoft, Xiaomi, Firebase) from promotion to prevent them from wasting autolearn capacity (max 512 hosts) and diluting preferred-group statistics for actually-blocked domains.

### Structured Logging

Key decision points are logged for diagnostics debugging:
- `DnsFailover`: network scope changes, failure counts, path switches, exhaustion
- `HomeAnalysis`: stage start/complete/timeout/skip with durations
- `ProtectSocket`: server start/stop, fd protection
- `strategy probe` (tracing): TTL capability, baseline classification, candidate start/skip/elimination
- `send_fake_tcp` (tracing): TTL set/fallback decisions
- DNS tampering detection: protocol-level anomaly signals (AA flag abuse, TTL anomalies, missing EDNS0/authority sections, small response size, malformed compression pointers), record-level comparison between UDP and encrypted resolvers (record type mismatch, TTL divergence, extra CNAMEs, authority mismatch, rcode mismatch), diagnosis codes `dns_response_anomaly`, `dns_cname_redirect`, `dns_record_divergence`
- Response parser framework: pluggable `ResponseParser` trait with `FieldObserver` emission for HTTP (status/headers/body/redirect), TLS (alert/version/ServerHello), and SSH (banner extraction)

### VPN Socket Protection

The native Rust proxy calls `VpnService.protect(fd)` on upstream sockets so they bypass the TUN device, enabling `setsockopt(IP_TTL)` for fake-packet DPI evasion strategies.

**Dual mechanism** (JNI preferred, Unix socket fallback):
- **JNI callback** (`ripdpi-android/src/vpn_protect.rs`): `jniRegisterVpnProtect()` stores `JavaVM` + `VpnService` global ref; worker threads call `vm.attach_current_thread()` → `VpnService.protect(fd)` directly
- **Unix socket fallback** (`VpnProtectSocketServer.kt`): `LocalServerSocket` + `SCM_RIGHTS` fd passing; used when JNI callback is unavailable
- **Selection logic** (`ripdpi-runtime/src/platform/mod.rs`): `has_protect_callback()` → JNI; else → Unix socket

**Note**: Diagnostics RAW_PATH scans stop the VPN service before probing (`runRawPathScan()`), which unregisters both mechanisms. RAW_PATH probes connect directly (no TUN), so `setsockopt(IP_TTL)` works without protection.

**Key files**:
- `native/rust/.../ripdpi-android/src/vpn_protect.rs` -- JNI protect callback
- `native/rust/.../platform/protect.rs` -- `ProtectCallback` trait + global registry
- `native/rust/.../platform/mod.rs` -- fallback selection logic
- `core/service/.../VpnProtectSocketServer.kt` -- Unix socket fallback
- `core/engine/.../RipDpiProxy.kt` -- `jniRegisterVpnProtect()` / `jniUnregisterVpnProtect()`

### Root Helper IPC

**Opt-in on rooted devices** (Magisk, KernelSU, APatch). When `root_mode_enabled` is set in AppSettings:

- `RootHelperManager.kt` extracts `ripdpi-root-helper` from APK assets, starts via `su`, and polls the Unix socket for readiness
- `root_helper_client.rs` connects per-operation, sends JSON command + fd via SCM_RIGHTS, receives response + optional replacement fd
- `root_helper.rs` global `RwLock` registry (same pattern as `protect.rs`); registered from config at startup
- `platform/mod.rs` dispatch: each privileged function checks `with_root_helper()` first, falls back to local linux calls
- Replacement fds from TCP_REPAIR operations are swapped via `dup2()` in `swap_replacement_fd()`

**Key files**:
- `native/rust/crates/ripdpi-root-helper/` -- standalone binary crate (protocol, handlers, main)
- `native/rust/.../platform/root_helper_client.rs` -- IPC client
- `native/rust/.../platform/root_helper.rs` -- global registry
- `core/service/.../RootHelperManager.kt` -- Kotlin lifecycle (extract, start, stop)
- `core/service/.../RootDetector.kt` -- `su -c id` root access test

## Native Code

Two native libraries are built from repo-owned Android adapter crates in the native workspace:

| Library | Build system | Source | Output |
|---------|-------------|--------|--------|
| `libripdpi.so` | Cargo + Android NDK linker via `:core:engine:buildRustNativeLibs` | `native/rust/crates/ripdpi-android/` | `core/engine/build/generated/jniLibs/` |
| `libripdpi-tunnel.so` | Cargo + Android NDK linker via `:core:engine:buildRustNativeLibs` | `native/rust/crates/ripdpi-tunnel-android/` | `core/engine/build/generated/jniLibs/` |
| `ripdpi-root-helper` | Cargo + Android NDK linker via `:core:engine:buildRustRootHelper` | `native/rust/crates/ripdpi-root-helper/` | `core/engine/build/generated/rootHelperAssets/bin/` |

- Kotlin bridge for `libripdpi.so`: `core/engine/src/main/kotlin/com/poyka/ripdpi/core/RipDpiProxy.kt`
- Kotlin bridge for `libripdpi-tunnel.so`: `core/engine/src/main/kotlin/com/poyka/ripdpi/core/Tun2SocksTunnel.kt`
- Kotlin lifecycle for `ripdpi-root-helper`: `core/service/src/main/kotlin/com/poyka/ripdpi/services/RootHelperManager.kt`
- Supported ABIs: armeabi-v7a, arm64-v8a, x86, x86_64
- Never edit `.so` files -- they are compiled from source
- Local non-release builds default to `ripdpi.localNativeAbisDefault=arm64-v8a`.
- Use `ripdpi.localNativeAbis=x86_64` for emulator-heavy local iteration. CI and release always build the full ABI set.

### Native Infrastructure

Supporting crates providing shared traits, data structures, and classification:

- **`ripdpi-packets`** -- protocol classification (`ProtocolClassifier` trait + `ClassifierRegistry` with `EnumMap` O(1) dispatch), protocol field extraction (`ProtocolField` + `FieldObserver` + `FieldCache`), TLS/HTTP/QUIC detection and mutation
- **`ripdpi-failure-classifier`** -- failure classification from pre-extracted fields (`classify_from_fields()` via `FieldCache`), blockpage CSV fingerprints, TLS alert/HTTP blockpage/redirect detection
- **`ripdpi-monitor`** -- DNS tampering detection (`dns_analysis.rs` with 8 anomaly signals + record-level comparison + compression pointer validation), response parser framework (`response_parser/` with HTTP/TLS/SSH parsers and `FieldObserver` emission), PCAP diagnostic recording
- **`ripdpi-root-helper`** -- standalone privileged binary for rooted devices; Unix socket IPC with SCM_RIGHTS fd passing for raw socket operations (`send_fake_rst`, `send_seqovl_tcp`, `send_multi_disorder_tcp`, `send_ip_fragmented_tcp/udp`, `probe_capabilities`); IPC client in `ripdpi-runtime/src/platform/root_helper_client.rs`
- **`android-support`** -- generic data structures: `BoundedHeap<T>` (fixed-capacity min-heap for session eviction), `EnumMap<K,V>` (O(1) enum-keyed dispatch for registries)

## Build Logic

Convention plugins live in `build-logic/convention/` and provide shared configuration:
- `ripdpi.android.application`, `ripdpi.android.library`, `ripdpi.android.compose`
- `ripdpi.android.hilt`, `ripdpi.android.serialization`
- `ripdpi.android.native`, `ripdpi.android.rust-native`, `ripdpi.android.protobuf`
- `ripdpi.android.quality`, `ripdpi.android.coverage`, `ripdpi.android.jacoco`
- `ripdpi.android.detekt`, `ripdpi.android.ktlint`, `ripdpi.android.lint`
- `ripdpi.android.roborazzi`
- `ripdpi.diagnostics.catalog`

All dependency versions are in `gradle/libs.versions.toml`.

## CI/CD

- **`ci.yml`** -- PR/push: `build`, `release-verification`, `native-bloat`, `cargo-deny`, `rust-lint`, `rust-cross-check`, `rust-workspace-tests`, `gradle-static-analysis`, `rust-network-e2e`, `cli-packet-smoke`, `rust-turmoil`, `coverage`, `rust-loom`; Nightly/manual: `rust-criterion-bench`, `android-macrobenchmark`, `rust-native-soak`, `rust-native-load`, `nightly-rust-coverage`, `android-network-e2e`, `linux-tun-e2e`, `linux-tun-soak`
- **`codeql.yml`** -- Runs on push/PR to main plus weekly schedule: GitHub Actions CodeQL analysis; Kotlin analysis is currently disabled pending upstream support
- **`release.yml`** -- Runs on `v*` tags: builds signed release APK, creates GitHub Release
- **`mutation-testing.yml`** -- Weekly Rust mutation testing via cargo-mutants
- **`offline-analytics.yml`** -- Weekly/manual offline diagnostics clustering pipeline; runs the checked-in sample corpus, emits analyst reports and candidate device-fingerprint catalogs, and optionally processes a runner-local private corpus

## Code Quality

```bash
./gradlew staticAnalysis   # Runs all checks (detekt, ktlint, Android lint)
```

- detekt config: `config/detekt/detekt.yml`
- Max line length: 120 characters
- SDK targets: compileSdk 36, minSdk 27, targetSdk 35
- **Never extend baselines** (detekt baselines, LoC baselines, lint baselines) to suppress new violations. Always fix the underlying issue -- refactor long files, resolve detekt findings, etc. Baselines exist only for legacy debt; new code must not add to them.

### Kotlin Anti-Patterns

#### Coroutines, state, Compose

- **Blocking coroutines on Main** -- never use `runBlocking` on the main thread.
- **GlobalScope usage** -- use structured concurrency with `viewModelScope`/`lifecycleScope`.
- **Collecting flows in `init`** -- use `repeatOnLifecycle` or `collectAsStateWithLifecycle`.
- **Mutable state exposure** -- expose `StateFlow`, not `MutableStateFlow`.
- **Not handling exceptions in flows** -- always use the `catch` operator.
- **`lateinit` for nullable** -- use `lazy` or nullable with `?`.
- **Hardcoded dispatchers** -- inject dispatchers for testability.
- **Not using sealed classes** -- prefer sealed for finite state sets.
- **Side effects in Composables** -- use `LaunchedEffect`/`SideEffect`.
- **Unstable Compose parameters** -- use stable/immutable types or `@Stable`.

#### Memory & resource leaks

- No `Activity`/`Context` references in singletons or companion objects; use `@ApplicationContext` through Hilt.
- Always unregister `BroadcastReceiver`, `ContentObserver`, and lifecycle observers symmetrically with where they were registered.
- Close `Cursor`, `InputStream`, `ParcelFileDescriptor`, and other `Closeable` instances via `use {}`.
- Call `TypedArray.recycle()` after reading styled attributes.

#### Coroutine cancellation correctness

- Never swallow `CancellationException`; rethrow it in any generic `catch (e: Throwable)`.
- Use `withContext(NonCancellable)` only for short cleanup inside `finally` blocks.
- Cleanup work that must survive cancellation (closing sockets, releasing VPN fds) belongs inside `NonCancellable` + `finally`, not outside it.
- See also: `kotlin-test-patterns`.

#### Flow hot-path discipline

- `shareIn(SharingStarted.Eagerly)` leaks across config changes -- prefer `WhileSubscribed(5_000)`.
- Keep `stateIn`/`shareIn` inside `viewModelScope`; never pin them to a global or application-scoped job.
- `conflate()` drops items, `buffer()` preserves them -- pick deliberately based on whether missed emissions are acceptable.
- See also: `android-compose-patterns`, `compose-performance`.

#### Foreground service discipline

- Call `startForeground()` within 5s of `onStartCommand`; missing this throws `ForegroundServiceDidNotStartInTimeException`.
- Create the notification channel before `startForeground`, not inside the foreground lifecycle.
- Handle `ForegroundServiceStartNotAllowedException` on API 31+ (apps in the background cannot start a foreground service without a qualifying reason).
- See also: `service-lifecycle`.

#### Security & logging

- No secrets, tokens, resolver IPs, user-visible URLs, or tunnelled traffic in `Log.*`.
- No `Log.d`/`Log.v` in release builds; use `Timber` with a release `Tree` that strips or gates by severity.
- Avoid `WebView.setAllowFileAccess(true)` and `setAllowUniversalAccessFromFileURLs(true)`.
- Never persist to `MODE_WORLD_READABLE`/`MODE_WORLD_WRITEABLE` storage; app-private or EncryptedFile only.

#### Serialization stability

- `@SerialName` values are a wire contract -- never rename them as part of a refactor.
- All cross-boundary `@Serializable` fields must have defaults, be nullable, or be `@Transient` with a default.
- Keep Kotlin/Rust wire structs field-order-aligned; mismatched ordering breaks golden contract tests.
- See also: `protobuf-schema-evolution`, `protobuf-datastore`.

Existing custom detekt rules live in `quality/detekt-rules/` (`InjectConstructorDefaultParameter`, `HiltViewModelApplicationContext`, `DisallowNewSuppression`); consult `detekt-custom-rules` skill before inventing new ones.

## Agent Skills

Project-specific skills are split across two directories:

- `.github/skills/` -- Android, Kotlin, Gradle, CI, and testing skills (shared across Claude Code and Codex)
- `.claude/skills/` -- Rust native, low-level systems, and diagnostics skills (Claude Code only)

| Skill | Use when |
|-------|----------|
| `android-device-debug` | Debugging the app on a device or emulator, capturing logs, reproducing crashes, or investigating runtime issues with ADB |
| `native-jni-development` | Modifying Rust native crates, JNI exports, or native build integration |
| `native-profiling` | Profiling native Rust code on Android or desktop |
| `network-traffic-debug` | Capturing or inspecting SOCKS5, VPN, or tunnel traffic |
| `android-compose-patterns` | Building Compose UI, ViewModels, navigation |
| `jetpack-compose-api` | Compose API internals, correct API usage, recomposition, performance, accessibility |
| `kotlin-test-patterns` | Writing any new test, reviewing test code, or debugging test failures in app/src/test, app/src/androidTest, or core/*/src/test |
| `appium-automation-contract` | Choosing automation launch routes/presets and debugging test launch state |
| `appium-test-authoring` | Writing or updating Appium page objects and tests |
| `appium-test-debug` | Debugging flaky or failing Appium tests |
| `gradle-build-system` | Adding dependencies, modules, or convention plugins |
| `dependency-update` | Updating Gradle/Rust dependencies, Renovate config, or version catalogs |
| `ci-workflow-authoring` | Modifying GitHub Actions workflows or CI job wiring |
| `client-legal-safety` | Reviewing domains, diagnostics targets, or workflows for client-side legal/compliance risk, especially current Russian Federation law and enforcement; always verify with live official sources |
| `compose-performance` | Diagnosing unnecessary recompositions, analyzing Compose compiler stability reports, optimizing LazyColumn/LazyRow scroll performance, deciding between @Stable and @Immutable annotations, reviewing UI model class stability, interpreting compose-metrics and compose-reports output, debugging infinite transition animations on HomeScreen, reducing AdvancedSettingsScreen recomposition scope, or applying derivedStateOf to filter-heavy screens like LogsScreen, DiagnosticsScreen, and HistoryScreen |
| `convention-plugin-development` | Adding a new convention plugin, modifying an existing plugin, changing shared SDK/ABI/profile properties in gradle.properties, debugging Gradle configuration cache issues in build-logic, wiring new AGP variant APIs, or updating the diagnostics catalog pipeline |
| `detekt-custom-rules` | Adding or fixing custom detekt rules and DI guardrails |
| `encrypted-dns` | Adding or modifying encrypted DNS protocols, debugging resolver failures, tuning health scoring, working with bootstrap IPs, investigating DNS tampering diagnostics, or understanding why a DoH/DoT/DNSCrypt/DoQ exchange fails |
| `golden-test-management` | Working with snapshot/golden fixtures and blessing workflows |
| `tdd` | Following project-standard red/green/refactor workflow |
| `protobuf-datastore` | Modifying app settings schema or DataStore persistence |
| `protobuf-schema-evolution` | Adding, removing, or renaming proto fields in AppSettings; managing reserved field numbers; evolving the diagnostics wire contract between Kotlin and Rust; bumping DIAGNOSTICS_ENGINE_SCHEMA_VERSION; writing or updating golden contract tests; ensuring DataStore round-trip safety after schema changes; or reviewing any PR that touches .proto files, EngineContract.kt, or wire.rs |
| `release-changelog` | Preparing a release, bumping version code/name, generating a changelog from conventional commits, writing Play Store whatsnew text, creating a git tag, running the release workflow, reviewing what changed since last release, or drafting GitHub release notes |
| `release-signing` | Building signed release artifacts and release pipeline changes |
| `rust-android-ndk` | Building Rust for Android, cross-compilation targets, and Gradle jniLibs integration |
| `rust-code-style` | Rust code organization and style in `native/rust/` |
| `rust-crate-architecture` | Creating or restructuring native workspace crates and dependencies |
| `rust-jni-bridge` | Implementing JNI in Rust (jni crate vs UniFFI), type mapping |
| `rust-lint-config` | Updating Clippy, rustfmt, or cargo-deny configuration |
| `local-ci-act` | Running CI workflows locally with act, troubleshooting CI failures |
| `mutation-testing` | Running cargo-mutants on the native/rust workspace, interpreting mutation testing results, triaging survived mutants, improving test adequacy, configuring mutants.toml, reviewing mutants-output artifacts, or writing mutation-resistant tests |

Additional Rust/native skills in `.claude/skills/`:

| Skill | Use when |
|-------|----------|
| `cargo-workflows` | Managing the Rust workspace, feature flags, build scripts, Gradle-Cargo integration, or cross-compilation |
| `compose-audit` | Conducting a periodic Compose codebase audit, scoring Performance/State/Side Effects/API Quality across `:app`, generating `COMPOSE-AUDIT-REPORT.md` with evidence-based deductions, or establishing a scored Compose quality baseline before a release |
| `desync-engine` | Working with DPI desync evasion pipeline, DesyncMode, DesyncGroup, TcpChainStep, UdpChainStep, OffsetExpr, or ActivationFilter |
| `diagnostics-system` | Working with diagnostics scan pipeline, ScanRequest, ScanReport, ProbeTask, ripdpi-monitor, strategy probes, or diagnostics catalog |
| `memory-model` | Understanding memory ordering, writing lock-free code, using Rust atomics, or diagnosing data races on ARM64 Android |
| `play-store-screenshots` | Creating Play Store listing assets, marketing screenshots, or feature graphics |
| `rust-anti-patterns` | Reviewing or writing Rust that touches panic/error/Drop/allocation/atomic-ordering policy, or choosing concurrency primitives and `spawn_blocking` vs dedicated threads |
| `rust-async-internals` | Diagnosing select!/join! pitfalls, blocking-in-async issues, JNI-to-async bridging, or tokio runtime configuration for Android NDK |
| `rust-build-times` | Profiling builds with cargo-timings, configuring sccache, splitting workspaces, or optimizing Android NDK cross-compilation times |
| `rust-debugging` | Debugging Rust native libraries on Android (JNI panics, logcat tracing, tombstones, addr2line), using GDB/LLDB with Rust |
| `rust-profiling` | Profiling Android .so binaries with simpleperf/perfetto, measuring monomorphization bloat, or micro-benchmarking with Criterion |
| `rust-sanitizers-miri` | Running AddressSanitizer or ThreadSanitizer on Rust code, using Miri to detect undefined behaviour in unsafe Rust |
| `rust-security` | Auditing dependencies with cargo-audit, enforcing policies with cargo-deny, or reviewing RUSTSEC advisories |
| `rust-unsafe` | Writing or reviewing unsafe Rust, auditing unsafe blocks, understanding raw pointers, or implementing safe abstractions over FFI |
| `ws-tunnel-telegram` | Working with MTProto WebSocket tunnel for Telegram traffic, ripdpi-ws-tunnel crate, DC IP database, or obfuscated2 classification |

Treat the tables above as an index only. The source of truth for each skill is its own `SKILL.md`.

## Repo-local Codex subagents

Project-local Codex subagents live in `.codex/agents/` and should be delegated explicitly.

| Agent | Prefer when |
|-------|-------------|
| `dpi-desync-specialist` | Packet evasion semantics, candidate behavior, desync config-to-plan-to-execution flow, TTL/fake/OOB/IP fragmentation logic, or strategy-probe desync regressions are the core problem. Prefer this over `rust-engineer` when the issue is specifically about DPI bypass behavior rather than general Rust implementation work. |
| `dns-resilience-specialist` | Encrypted DNS, bootstrap/failover, DNS tampering classification, runtime resolver context, or Kotlin VPN DNS failover logic are the core problem. Prefer this over `network-engineer` when the issue is primarily resolver resilience and DNS-path behavior inside RIPDPI. |

Pair these agents with companion specialists instead of stretching one agent across every concern:
- `packet-smoke-debugger` for packet-capture or on-wire desync verification
- `rust-test-runner` for Rust behavior changes that need targeted test execution
- `jni-bridge-verifier` for Kotlin/Rust engine contract changes
- `network-engineer` for live-path or infrastructure-network reasoning
- `android-test-runner` for Kotlin service/runtime validation on Android paths

Explicit delegation examples:
- Delegate to `dpi-desync-specialist`: "Trace why `tlsrec_disorder` regressed on Android VPN path, update the smallest safe runtime/planner logic, and identify which packet-smoke scenario should confirm the fix."
- Delegate to `dns-resilience-specialist`: "Trace why strategy probes short-circuit into `dns_tampering`, fix the smallest safe resolver/failover path across Rust and service logic, and list the exact tests needed to validate bootstrap and failover behavior."
