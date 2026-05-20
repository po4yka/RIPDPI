# AGENTS.md -- RIPDPI

## Project

RIPDPI is an Android VPN/proxy application for DPI (Deep Packet Inspection) bypass. It runs a local SOCKS5 proxy and VPN tunnel through in-repository Rust native modules.

## Setup

1. Requirements: JDK 17, Android SDK, Android NDK 29.0.14206865, stable Rust toolchain with Android targets, **Android CLI 1.0+** (`android`) from `d.android.com/tools/agents` (agents and CI depend on it; `android docs search` / `android docs fetch` must be available). `android sdk install` takes slash-notation package IDs (`ndk/29.0.14206865`, `platforms/android-36`, `system-images/android-34/...`) -- not the legacy sdkmanager semicolon notation
2. Native build properties are defined in `gradle.properties` -- do not hardcode NDK version, ABI filters, or SDK levels elsewhere
3. The Android build invokes the `ripdpi.android.rust-native` convention plugin from `:core:engine`, which builds the native workspace under `native/rust/`
4. Agent runtime permissions for `android` CLI calls: - **Claude Code**: copy `.claude/settings.example.json` to your local (gitignored) `.claude/settings.json` -- it carries `permissions.allow: ["Bash(android:*)"]`, a wildcard covering every subcommand (`android sdk`, `android docs search`, `android studio`, `android emulator`). - **Codex**: approve the project once via `trust_level = "trusted"` in `~/.codex/config.toml` (Codex prompts on first run). Codex has no per-command allowlist; project trust covers all shell invocations including `android docs` / `android sdk` / `android emulator`.
5. **Android CLI 1.0 agent features** (optional, local dev): run `android init` once to install the `android-cli` skill (or `android skills add --all` for the full Android skill library) -- these write to agent skill directories under `$HOME` (including `~/.claude/skills/`), never the repo tree. Natural-language UI journeys live under `journeys/`; there is no `android journeys` command -- an agent executes them by driving `android screen capture` / `screen resolve` / `layout` (see `scripts/ci/run-android-journeys-emulator.sh` and the `android-test-runner` agent). The `android studio ...` commands (`studio check`, `studio version-lookup ndk agp gradle`, `studio open-file`) bridge to a running Android Studio (Quail 2 Canary 1+ with Gemini) -- preview, local-only, not wired into CI.

## Build & Test

```bash
./gradlew assembleDebug              # Debug build (includes native compilation)
./gradlew assembleRelease             # Release build (requires signing env vars)
./gradlew testDebugUnitTest           # Run all unit tests
./gradlew :core:data:testDebugUnitTest  # Run tests for a single module
./gradlew staticAnalysis              # Run detekt + ktlint + Android lint
```

## Project Rules

- **Never extend baselines** (detekt, LoC, lint). Fix the underlying violation -- baselines exist only for legacy debt; do not work around CI or hook enforcement.
- **Non-rooted Android baseline** -- the app must fully function on non-rooted devices. Root-only features (`ripdpi-root-helper`, `FakeRst`, `MultiDisorder`, `IpFrag2`) are opt-in behind the `root_mode_enabled` setting and must degrade gracefully when root is unavailable.
- **No backend server** -- all features work offline and locally. Do not design features that require an API endpoint or remote service. External data uses static files on GitHub or bundled assets; user data never leaves the device unless the user explicitly exports it.
- **Goal-driven execution** -- before implementing, convert each task into verifiable success criteria (test name, metric delta, UI render) and verify each before reporting completion. Ask for clarification when criteria are ambiguous rather than guessing.
- **Surface ambiguity early** -- an undocumented JNI contract, a missing schema migration, an unclear protobuf field number, a `DesyncMode` without documented activation: name it, do not guess.
- **Reproduce before fixing** -- a packet-smoke scenario, a `cargo nextest` test, or a Roborazzi baseline is the artifact you change; the source edit follows.
- Removing custom detekt rules, lint baselines, or other quality gates is out of scope unless explicitly requested.
- **Keep all locales in sync** -- the app ships 7 locales (en, ru, es, de, fr, fa, zh-CN). Any new key added to `app/src/main/res/values/strings.xml` must land in all six other locale files in the same commit; same rule for `core/service/src/main/res/values/strings.xml`. `lint.xml` sets `MissingTranslation` to severity `error`, so a missing key fails CI. Verify with `comm -23 <(grep -oE 'name="[^"]+"' app/src/main/res/values/strings.xml | sort -u) <(grep -oE 'name="[^"]+"' app/src/main/res/values-XX/strings.xml | sort -u) | wc -l` returning `0` for each `XX` in `{ru,es,de,fr,fa,zh-rCN}`. `language_name_*` keys carry NATIVE display names (Español, Deutsch, etc.) and stay byte-identical across every locale file. Android resource keys forbid hyphens, so BCP-47 `zh-CN` maps to resource key `language_name_zh_cn`. New locales must be registered in `app/src/main/res/xml/locales_config.xml` and added to `app/src/test/kotlin/com/poyka/ripdpi/platform/LocalesConfigTest.kt`. Any change to a README selector block must keep `scripts/check-readme-selectors.sh` green (42 link + 7 bold-tag assertions across all 7 README files).

## Task Board

This repository uses Obsidian Tasks-compatible Markdown task lines as the canonical task system. Use the `repo-task-board` skill for all task-related operations.

Canonical files:

- `docs/tasks/issues/<slug>.md` — **source of truth** — one note per task/epic (YAML frontmatter + canonical `- [ ]` line + spec)
- `docs/tasks/active.md` — Obsidian Tasks query view (`#status/doing`, `#status/review`)
- `docs/tasks/backlog.md` — Obsidian Tasks query view (`#status/backlog`)
- `docs/tasks/blocked.md` — Obsidian Tasks query view (`#status/blocked`)
- `docs/tasks/epics.md` — Obsidian Tasks query view (`#area/epic`)
- `docs/tasks/dashboard.md` — Obsidian Tasks query hub + Bases view links
- `docs/tasks/board.md` — Kanban board (visual layer; source of truth is `issues/`)

Canonical task syntax (lives inside `docs/tasks/issues/<slug>.md`):

```md
- [ ] #task <imperative title> #repo/RIPDPI #area/<area> #status/<status> <priority>
```

Per-task note YAML frontmatter:

```yaml
---
title: Imperative task title
type: task            # task | epic
status: doing         # backlog | todo | doing | review | blocked | done | dropped
area: diagnostics     # engine | rust-native | diagnostics | transport | outbound | dns |
                      # routing | vpn | proxy | relay | android | ui | data | service |
                      # testing | ci | epic
priority: high        # critical | high | medium | low
owner: Role name
parent: epic-slug     # slug of parent epic, or null
blocks: []
blocked_by: []
created: YYYY-MM-DD
updated: YYYY-MM-DD
---
```

Lifecycle: create via Templater template → transitions update `status:` + `#status/*` tag → delete file on close (git history is the audit trail). Do NOT add task lines to `active.md`, `backlog.md`, `blocked.md`, or `epics.md` — those are query-only views.

Invoke the `repo-task-board` skill when the user mentions: roadmap, TODO, backlog, Kanban, task board, sprint, blocked work, or agent-ready work.

## Architecture

Deep-dive architecture references live under `docs/architecture/`: start with [`ARCHITECTURE.md`](docs/architecture/ARCHITECTURE.md) (the canonical map), then [`NATIVE_RUST.md`](docs/architecture/NATIVE_RUST.md), [`JNI_CONTRACT.md`](docs/architecture/JNI_CONTRACT.md), [`CONFIG_CONTRACTS.md`](docs/architecture/CONFIG_CONTRACTS.md), and [`FEATURE_EXTENSION_GUIDE.md`](docs/architecture/FEATURE_EXTENSION_GUIDE.md). The summary below is a quick reference.

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

JNI native libraries are built from repo-owned Android adapter crates in the native workspace; [`docs/architecture/NATIVE_RUST.md`](docs/architecture/NATIVE_RUST.md) carries the complete native artifact map and crate taxonomy. The table below is a quick reference:

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
- **`ripdpi-monitor-engine`** (active-scan engine) plus the `ripdpi-diagnostics-*` family -- DNS tampering detection (`ripdpi-diagnostics-dns`, `dns_analysis` with 8 anomaly signals + record-level comparison + compression pointer validation), response parsers (`ripdpi-diagnostics-parsers`, HTTP/TLS/SSH), PCAP diagnostic recording (`ripdpi-diagnostics-pcap`). See `docs/architecture/DIAGNOSTICS_ARCHITECTURE.md`.
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
- **`fleet-fixtures.yml`** -- PR-triggered (paths-filtered on the subscription parser, routing/AWG/relay models, and the fleet fixtures): runs the structural drift gate (`scripts/ci/check_fleet_fixtures.py` + its unittest) and the JVM `*FleetCompat*` golden-file suite that locks RIPDPI against the sibling `ripdpi-vpn-deploy` emitter output

## Code Quality

```bash
./gradlew staticAnalysis   # Runs all checks (detekt, ktlint, Android lint)
```

- detekt config: `config/detekt/detekt.yml`
- Max line length: 120 characters
- SDK targets: compileSdk 36, minSdk 27, targetSdk 35
- Baseline policy lives in CLAUDE.md and is hook-enforced; do not extend baselines.

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

Project-specific skills are split across three directories:

- `.github/skills/` -- Android, Kotlin, Gradle, CI, and testing skills (shared across Claude Code and Codex)
- `.claude/skills/` -- Rust native, systems, Compose, and diagnostics skills
- `.codex/skills/` -- relative symlinks to `.claude/skills/` entries (same skills, available to Codex agents)

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

Additional skills in `.claude/skills/` (also accessible to Codex via `.codex/skills/` symlinks):

| Skill | Use when |
|-------|----------|
| `cargo-workflows` | Managing the Rust workspace, feature flags, build scripts, Gradle-Cargo integration, or cross-compilation |
| `compose` | Compose expert guidance (state, recomposition, modifiers, navigation, theming) or scored Compose codebase audit (Performance/State/Side Effects/API Quality), generating `COMPOSE-AUDIT-REPORT.md` |
| `desync-engine` | Working with DPI desync evasion pipeline, DesyncMode, DesyncGroup, TcpChainStep, UdpChainStep, OffsetExpr, or ActivationFilter |
| `diagnostics-system` | Working with diagnostics scan pipeline, ScanRequest, ScanReport, ProbeTask, the `ripdpi-monitor-*` / `ripdpi-diagnostics-*` crates, strategy probes, or diagnostics catalog |
| `legal-check` | Reviewing public docs, store listings, or UI copy for Russian VPN/circumvention advertising risk |
| `material-3` | Material Design 3 token usage, component selection, dynamic color, layout, or accessibility guidance |
| `memory-model` | Understanding memory ordering, writing lock-free code, using Rust atomics, or diagnosing data races on ARM64 Android |
| `play-store-screenshots` | Creating Play Store listing assets, marketing screenshots, or feature graphics |
| `repo-task-board` | Creating, updating, triaging, or completing repository tasks in `docs/tasks/` |
| `rust-android-build` | Modifying `.cargo/config.toml` Android targets, the `[profile.android-jni]` block, ELF symbol allowlist, 16 KiB page-size verification, or per-ABI `.so` size budgets |
| `rust-android-jni` | Authoring or reviewing JNI exports under `ripdpi-*-android` crates — panic containment, AttachCurrentThread discipline, local-ref frames, JNIEnv-across-await rules, JByteArray vs DirectByteBuffer, VpnService.protect callback wiring |
| `rust-android-telemetry` | Authoring telemetry emission, bounded event ring, control-plane vs data-plane logging channel selection, pull-model 1Hz polling, deterministic JSON for goldens, ANR-precursor heartbeat |
| `rust-async-internals` | Diagnosing select!/join! pitfalls, blocking-in-async issues, JNI-to-async bridging, or tokio runtime configuration for Android NDK |
| `rust-debugging` | Debugging Rust native libraries on Android (JNI panics, logcat tracing, tombstones, addr2line), using GDB/LLDB with Rust |
| `rust-discipline` | Authoring or reviewing Rust API signatures (borrowed args, lifetime infection, HRTB, Drop rules) and catching anti-patterns (panic policy, error propagation, RAII, hot-path allocation, concurrency primitives, atomic ordering, unsafe encapsulation, lints) |
| `rust-lints` | Reviewing or modifying workspace `[workspace.lints]` and `clippy.toml`, adding a new crate, or auditing why an LLM-class bug went undetected by clippy |
| `rust-performance` | Profiling Android .so binaries with simpleperf/perfetto or cargo-flamegraph; measuring monomorphization bloat with cargo-llvm-lines; micro-benchmarking with Criterion; or optimizing build times with cargo-timings, sccache, and NDK cross-compilation |
| `rust-sanitizers-miri` | Running AddressSanitizer or ThreadSanitizer on Rust code, using Miri to detect undefined behaviour in unsafe Rust, or enabling MTE on Android 14+ |
| `rust-security` | Auditing dependencies with cargo-audit, enforcing policies with cargo-deny, or reviewing RUSTSEC advisories |
| `rust-test-tools` | Authoring tests for unsafe code, custom atomics/locks, or packet parsers — cargo-careful, loom, proptest, cargo-fuzz, cargo-mutants beyond the standard `cargo test` |
| `rust-unsafe` | Writing or reviewing unsafe Rust, auditing unsafe blocks, understanding raw pointers, or implementing safe abstractions over FFI |
| `ws-tunnel-telegram` | Working with MTProto WebSocket tunnel for Telegram traffic, ripdpi-ws-tunnel crate, DC IP database, or obfuscated2 classification |

Treat the tables above as an index only. The source of truth for each skill is its own `SKILL.md`.

### Project Rules (cross-tool)

Long-form rules that apply to both Claude Code and Codex CLI live in `.claude/rules/` (with relative symlinks in `.codex/rules/` for Codex parity). They are not auto-loaded into project memory; agents should `Read` them when their topic comes up in a diff or review.

| Rule | When to consult |
|------|-----------------|
| `llm-rust-prompts.md` | Delegating Rust work to a sub-agent (`executor`, `codex:rescue`, etc.); reviewing any AI-generated Rust diff. Diff-acceptance gate items and Android-specific sentinel patterns. |
| `rust-toolchain-pin.md` | Cargo invocations in agentic flows (`--locked` discipline); bumping MSRV; modifying `native/rust/rust-toolchain.toml`. |
| `vpnservice-protect-invariant.md` | Any code path constructing an outbound `TcpStream`/`UdpSocket`/`mio::net::*` in Rust — protect callback must precede `connect`/`bind` for non-loopback targets. |
| `golden-bless-discipline.md` | Anything that would invoke `RIPDPI_BLESS_GOLDENS=1` or touch files under `tests/golden/` / `src/test/resources/golden/`. |
| `android-vpn-lifecycle.md` | State persistence under LMK, tokio shutdown from JNI, Foreground Service contract, thread naming, signal masking, Doze/Standby. |
| `network-fingerprint-privacy.md` | Per-network policy cache, scope-key construction, anything that might log device identifiers (BSSID, IMEI, IP) — privacy + Play Data Safety implications. |
| `compose-preview.md` | Rendering `@Preview` composables to PNG via `ee.schimke.composeai.preview` — Gradle tasks, output paths under `build/compose-previews/`, and the hard rule that this output is NEVER copied into Roborazzi golden paths. |

## Design Sources

For UI work, use these sources in order:

1. `DESIGN.md` at the repository root for the portable design-system summary that agents can carry across tools
2. `docs/design-system.md` for RIPDPI-specific engineering constraints not captured by the current `DESIGN.md` format
3. `app/src/main/kotlin/com/poyka/ripdpi/ui/theme/` as the implementation source of truth for Compose tokens
4. Roborazzi baselines under `app/src/test/screenshots/` for visual regression verification

`DESIGN.md` is descriptive and portable. The Compose theme code and screenshot baselines remain canonical when there is any conflict.

## Repo-local Codex subagents

Project-local Codex subagents live in `.codex/agents/` and should be delegated explicitly.

### Model selection policy

- **Codex agents** inherit the global default `gpt-5.5` from `~/.codex/config.toml` unless the agent file explicitly pins a model. Pin only when the work warrants it; agents that pin keep `model_reasoning_effort = "high"` only for security-critical or packet-level work (`dpi-desync-specialist`, `dns-resilience-specialist`, `security-auditor`).
- **Claude agents** in `.claude/agents/` use the short aliases `opus`, `sonnet`, or `haiku` — never versioned IDs like `claude-opus-4-7`. Complex multi-file synthesis (PR review, unsafe audit, architecture, JNI, API surface, Kotlin design) maps to `opus`; pattern-matching test runners and profilers map to `sonnet`; parse-and-report tasks (coverage, golden, native size, regression) map to `haiku`.

| Agent | Prefer when |
|-------|-------------|
| `dpi-desync-specialist` | Packet evasion semantics, candidate behavior, desync config-to-plan-to-execution flow, TTL/fake/OOB/IP fragmentation logic, or strategy-probe desync regressions are the core problem. Prefer this over `rust-engineer` when the issue is specifically about path optimization behavior rather than general Rust implementation work. |
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
