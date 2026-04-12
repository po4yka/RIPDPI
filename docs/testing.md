# Testing

This document describes the current test stack for RIPDPI after the migration to in-repository Rust native modules.

## Coverage Layers

### Kotlin/JVM tests

These run through Gradle on the host JVM and cover the Android-facing logic without starting an emulator.

- `core:engine`
  - native wrapper lifecycle
  - config contract snapshots
  - state-machine tests
  - fault-injection tests
  - relay config bridge coverage for Cloudflare Tunnel modes, credential references, TLS catalog versioning, and Finalmask payloads
  - advanced strategy JSON coverage for markers, fake payload profiles, activation windows, adaptive fake TTL, per-network `networkScopeKey`, and UI/native bridge parity
  - native telemetry golden contracts
- `core:data`
  - app settings and serializer coverage for network-strategy memory toggles
  - fingerprint hashing and privacy-preserving network summaries
  - relay profile persistence coverage for Cloudflare Tunnel mode, Finalmask config, and credential-reference round trips
  - encrypted DNS path candidate planning, ordering, and persistence-backed migration coverage
- `core:service`
  - service state store
  - lifecycle coordination
  - diagnostics runtime coordination
  - relay supervisor coverage for Cloudflare Tunnel publish mode, MASQUE URL validation, feature gating, helper orchestration, and NaiveProxy watchdog behavior
  - connection-policy resolution, remembered-policy replay, and active-policy signature tracking
  - handover monitor debounce/classification and service restart behavior
  - merged service telemetry golden contracts
- `core:diagnostics`
  - diagnostics manager orchestration
  - automatic probing profile wiring, hidden handover-triggered `quick_v1` probes, `full_matrix_v1` audit cohort rotation/provenance, recommendation persistence, and recommendation invariant validation
  - resolver recommendation ranking, diversified encrypted-DNS path planning, and temporary encrypted-DNS override flow
  - candidate-aware strategy-probe progress, audit confidence/coverage assessment, and summary/export metadata projection
  - runtime-history persistence of resolver telemetry and remembered-network proof/suppression state
  - export/archive contents
  - persisted passive-monitor and native-event golden contracts
- `app`
  - settings and diagnostics ViewModel coverage for chain DSL, fake payload/fake TLS controls, adaptive split placement, activation windows, adaptive fake TTL, remembered-network presentation, automatic probing/audit presentation, exact remediation states, and winners-first audit reports

Main command:

```bash
./gradlew testDebugUnitTest
```

Focused command set:

```bash
./gradlew \
  :core:data:testDebugUnitTest \
  :core:engine:testDebugUnitTest \
  :core:service:testDebugUnitTest \
  :core:diagnostics:testDebugUnitTest
```

## Rust native tests

The Rust workspace contains several test styles:

- unit tests for JNI adapters and helpers
- property-based and fuzz-style parsing coverage with `proptest`
- config and planner coverage for semantic markers, adaptive `auto(...)` markers, activation filters, fake payload profile selection, QUIC fake Initial profiles, and HTTP parser evasions
- relay transport coverage for MASQUE path and auth handling, xHTTP Finalmask mutation, Cloudflare publish-origin helper behavior, and NaiveProxy helper contracts
- runtime policy coverage for host autolearn scoping, route advancement, adaptive fake TTL learning, retry-stealth pacing, and candidate diversification
- diagnostics monitor coverage for automatic probing/audit candidate catalogs, candidate-aware progress, probe pacing, target-order shuffling, rotating target cohorts, recommendation assembly, and audit-assessment propagation
- state-machine coverage for proxy and tunnel session registries
- deterministic fault-injection tests
- telemetry/logging golden tests
- repo-owned local-network E2E for the proxy runtime

Main CI-parity command:

```bash
bash scripts/ci/run-rust-native-checks.sh
```

That script runs:

- `cargo fmt --check`
- `cargo clippy --workspace --all-targets -D warnings`
- workspace Rust tests through `cargo nextest`


Focused native commands for the current policy/runtime surface:

```bash
cargo test -p ripdpi-runtime --lib
cargo test -p ripdpi-monitor --lib
cargo test -p ripdpi-android --lib
cargo test -p ripdpi-masque -p ripdpi-relay-core -p ripdpi-xhttp -p ripdpi-naiveproxy -p ripdpi-cloudflare-origin
./gradlew :core:engine:testDebugUnitTest \
  --tests com.poyka.ripdpi.core.NativeTelemetryGoldenTest \
  -x :core:engine:buildRustNativeLibs
./gradlew :core:service:testDebugUnitTest \
  --tests com.poyka.ripdpi.services.UpstreamRelaySupervisorTest \
  -x :core:engine:buildRustCloudflareOrigin \
  -x :core:engine:buildRustNativeLibs \
  -x :core:engine:buildRustNaiveProxy \
  -x :core:engine:buildRustRootHelper
```

## Local network E2E

RIPDPI includes a repo-owned local fixture binary that exposes:

- TCP echo
- UDP echo
- TLS echo
- DNS responders
- RFC8484 DNS-over-HTTPS endpoints
- SOCKS5 relay
- deterministic fault injection control endpoints

The fixture is used by both host Rust E2E and Android instrumentation E2E.

Run the host-side network E2E suite with:

```bash
bash scripts/ci/run-rust-network-e2e.sh
```

Run the raw host packet-smoke lane with:

```bash
RIPDPI_RUN_PACKET_SMOKE=1 \
  bash scripts/ci/run-cli-packet-smoke.sh
```

Optional runner inputs:

- `RIPDPI_PACKET_SMOKE_CAPTURE_MODE=auto|raw`
- `RIPDPI_PACKET_SMOKE_SCENARIO_FILTER=<scenario id or exact test selector>`
- `RIPDPI_PACKET_SMOKE_ARTIFACT_DIR=/abs/path/to/output`
- `RIPDPI_PACKET_SMOKE_IFACE=lo` (or `lo0` on macOS if auto-detection is wrong)

The CLI packet-smoke registry lives at `scripts/ci/packet-smoke-scenarios.json`. Each scenario runs in
its own process/capture session and emits a fixture manifest, fixture events, CLI stderr, `pcap`, and
decoded `tshark` JSON artifacts.

## Android instrumentation

Android instrumentation is split into two practical layers:

- integration tests for JNI wrappers, services, and Hilt-backed lifecycle flows
- network-path E2E tests against the local fixture and the real packaged `.so` libraries

Common commands:

```bash
./gradlew :app:assembleDebugAndroidTest -Pripdpi.localNativeAbis=x86_64
./gradlew :app:connectedDebugAndroidTest -Pripdpi.localNativeAbis=arm64-v8a
```

Useful runner filters:

- `-Pandroid.testInstrumentationRunnerArguments.package=com.poyka.ripdpi.integration`
- `-Pandroid.testInstrumentationRunnerArguments.package=com.poyka.ripdpi.e2e`
- `-Pandroid.testInstrumentationRunnerArguments.class=com.poyka.ripdpi.e2e.NativeTelemetryGoldenSmokeTest`

For local debug builds you can narrow native compilation to one ABI:

```bash
./gradlew :app:connectedDebugAndroidTest -Pripdpi.localNativeAbis=arm64-v8a
```

For physical devices, expose the host fixture over `adb reverse` and point the fixture manifest at
loopback before running the E2E package:

```bash
export ANDROID_SERIAL=<device-serial>
export RIPDPI_FIXTURE_ANDROID_HOST=127.0.0.1
bash scripts/ci/start-local-network-fixture.sh
adb reverse tcp:46090 tcp:46090
adb reverse tcp:46001 tcp:46001
adb reverse tcp:46003 tcp:46003
adb reverse tcp:46053 tcp:46053
adb reverse tcp:46054 tcp:46054
./gradlew :app:connectedDebugAndroidTest \
  -Pripdpi.localNativeAbis=arm64-v8a \
  -Pandroid.testInstrumentationRunnerArguments.package=com.poyka.ripdpi.e2e
```

When the E2E package starts VPN-mode tests on an Android 15/16 physical device, the shared
UiAutomator helper now auto-confirms the real system VPN consent dialog. This applies only to the
real E2E/device flows; the service integration suite uses a fake `VpnTunnelSessionProvider` and does
not exercise platform consent UX.

The packet-smoke instrumentation matrix can be run one scenario at a time with:

```bash
ANDROID_SERIAL=<device-serial> \
  bash scripts/ci/run-android-packet-smoke.sh
```

Optional runner inputs:

- `RIPDPI_PACKET_SMOKE_CAPTURE_MODE=auto|raw|indirect`
- `RIPDPI_PACKET_SMOKE_SCENARIO_FILTER=<scenario id or instrumentation selector>`
- `RIPDPI_PACKET_SMOKE_ARTIFACT_DIR=/abs/path/to/output`

The Android runner reuses the shared fixture manifest, resets fixture faults/events between scenarios,
collects `logcat`, `dumpsys connectivity`, `ip addr`, `ip route`, and grabs a failure screenshot on
test failures. On rooted emulators or rooted devices with `tcpdump` installed, `capture_mode=raw`
adds an on-device `pcap`; otherwise `auto` falls back to the ADB-observable lane.

Physical-device note: `adb reverse` only covers TCP, so the runner skips DoQ scenarios when the
fixture host is loopback on an unrooted physical device. Emulators and direct host-reachable devices
can exercise the full DoQ path.

Optional runner args for physical-device VPN consent handling:

- `-Pandroid.testInstrumentationRunnerArguments.ripdpi.vpnConsentTimeoutMs=25000`
- `-Pandroid.testInstrumentationRunnerArguments.ripdpi.vpnConsentPackageHints=com.vendor.vpndialogs,com.oem.permissioncontroller`

If the system dialog shape changes and consent is not confirmed, rerun the failing E2E class and
collect the paths emitted by the assertion message for:

- the dumped UI hierarchy XML
- the captured screenshot PNG
- the active package / visible package list / selector matches embedded in the failure text

CI and release still build the full ABI set from `ripdpi.nativeAbis`.

## External UI automation

Debug builds now expose a launch contract for deterministic Maestro and Appium sessions. The contract,
selector policy, and Appium checklist live under `docs/automation/`.

Run the committed Maestro smoke pack locally with:

```bash
bash scripts/ci/run-maestro-smoke.sh
```

The smoke flows avoid `pm clear` and instead rely on launch extras such as:

- `com.poyka.ripdpi.automation.ENABLED`
- `com.poyka.ripdpi.automation.RESET_STATE`
- `com.poyka.ripdpi.automation.START_ROUTE`
- `com.poyka.ripdpi.automation.PERMISSION_PRESET`
- `com.poyka.ripdpi.automation.SERVICE_PRESET`
- `com.poyka.ripdpi.automation.DATA_PRESET`

## Golden contracts

Structured telemetry, diagnostics events, strategy-probe progress/report payloads, and selected exported files are treated as compatibility contracts.

- Rust fixtures live under crate-local `tests/golden/`
- JVM fixtures live under module-local `src/test/resources/golden/`
- Android instrumentation smoke fixtures live under `app/src/androidTest/assets/golden/`

Default mode is read-only. Tests fail on unexpected diffs.

To intentionally refresh all telemetry/logging fixtures:

```bash
bash scripts/tests/bless-telemetry-goldens.sh
```

Equivalent manual mode:

```bash
RIPDPI_BLESS_GOLDENS=1 ./gradlew ...
RIPDPI_BLESS_GOLDENS=1 cargo test ...
```

Scrubbed volatile fields:

- timestamps
- generated session ids
- loopback ports
- archive-time dynamic file names
- absolute temp paths

Semantic fields remain strict:

- state and health
- counters
- event order
- level and message text
- route group and target metadata
- retry pacing/diversification counters and reasons
- strategy signature and recommendation metadata
- per-lane TCP/QUIC/DNS winning-family metadata
- resolver metadata, fallback state, and handover classification

## Load/stress tests

Load tests exercise high-concurrency ramp-up profiles, burst spikes, and saturation behavior.
They complement the soak suite which covers endurance over time.

Scenarios:

- `proxy_ramp_load` -- gradually increases concurrent connections from 8 to max\_clients,
  measuring acceptance rate, latency percentiles, and thread pool scaling at each step
- `proxy_burst_load` -- coordinates 128 simultaneous connection attempts against a 64-slot
  proxy, verifying capacity enforcement and post-burst recovery
- `proxy_saturation_load` -- holds the proxy at full capacity with long-lived connections,
  attempts overflow, and verifies existing connection quality is maintained

Run locally:

```bash
RIPDPI_RUN_LOAD=1 RIPDPI_SOAK_PROFILE=smoke \
  bash scripts/ci/run-rust-native-load.sh
```

Or via just:

```bash
just test-rust-load
```

Env vars:

- `RIPDPI_RUN_LOAD=1` -- gate for load tests (required)
- `RIPDPI_SOAK_PROFILE=smoke|full` -- intensity (smoke is shorter/smaller)
- `RIPDPI_SOAK_ARTIFACT_DIR` -- override artifact output directory

Artifacts are written to `target/soak-artifacts/` (JSONL samples + JSON result summaries).

## Linux TUN E2E and soak

The real TUN data-plane tests are Linux-only and require privileged setup.

Privileged TUN soak:

```bash
RIPDPI_RUN_TUN_E2E=1 RIPDPI_SOAK_PROFILE=smoke \
  bash scripts/ci/run-linux-tun-soak.sh
```

Host-side native soak:

```bash
RIPDPI_SOAK_PROFILE=smoke bash scripts/ci/run-rust-native-soak.sh
```

Profiles:

- `smoke`: shorter local/manual runs
- `full`: nightly profile used by scheduled CI

## CI overview

PR CI runs:

- `build` -- Kotlin unit tests via `./gradlew testDebugUnitTest`
- `static-analysis` -- detekt + ktlint + Android lint + Rust fmt/clippy
- `rust-network-e2e` -- host-side proxy E2E against local fixture
- `android-network-e2e` -- instrumentation E2E on emulator
- `coverage` -- JaCoCo + Rust LLVM coverage
- `rust-turmoil` -- deterministic fault-injection network tests
- `rust-loom` -- exhaustive concurrency verification (20 min timeout)
- `cli-packet-smoke` -- CLI proxy behavioral verification with pcap capture

Nightly/manual lanes add:

- `rust-native-soak` -- endurance tests (restart, sustained traffic, fault recovery)
- `rust-native-load` -- high-concurrency ramp-up, burst, and saturation tests
- `linux-tun-e2e` -- privileged TUN data-plane tests
- `linux-tun-soak` -- privileged TUN endurance tests
- `nightly-rust-coverage` -- coverage including ignored tests

The CI jobs upload test reports, golden diffs, logcat, fixture logs, soak/load artifacts, and coverage reports when available.
