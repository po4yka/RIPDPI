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
  - advanced strategy JSON coverage for markers, fake payload profiles, activation windows, adaptive fake TTL, per-network `networkScopeKey`, and UI/native bridge parity
  - native telemetry golden contracts
- `core:data`
  - app settings and serializer coverage for network-strategy memory toggles
  - fingerprint hashing and privacy-preserving network summaries
  - encrypted DNS path candidate planning, ordering, and persistence-backed migration coverage
- `core:service`
  - service state store
  - lifecycle coordination
  - diagnostics runtime coordination
  - connection-policy resolution, remembered-policy replay, and active-policy signature tracking
  - handover monitor debounce/classification and service restart behavior
  - merged service telemetry golden contracts
- `core:diagnostics`
  - diagnostics manager orchestration
  - automatic probing profile wiring, hidden handover-triggered `quick_v1` probes, and recommendation persistence
  - resolver recommendation ranking, diversified encrypted-DNS path planning, and temporary encrypted-DNS override flow
  - runtime-history persistence of resolver telemetry and remembered-network proof/suppression state
  - export/archive contents
  - persisted passive-monitor and native-event golden contracts
- `app`
  - settings and diagnostics ViewModel coverage for chain DSL, fake payload/fake TLS controls, adaptive split placement, activation windows, adaptive fake TTL, remembered-network presentation, and automatic probing presentation

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
- runtime policy coverage for host autolearn scoping, route advancement, adaptive fake TTL learning, retry-stealth pacing, and candidate diversification
- diagnostics monitor coverage for automatic probing candidate catalogs, probe pacing, target-order shuffling, and recommendation assembly
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
./gradlew :core:engine:testDebugUnitTest \
  --tests com.poyka.ripdpi.core.NativeTelemetryGoldenTest \
  -x :core:engine:buildRustNativeLibs
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

Structured telemetry, diagnostics events, and selected exported files are treated as compatibility contracts.

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

- `build`
- `static-analysis`
- `rust-network-e2e`
- `android-network-e2e`

Nightly/manual lanes add:

- `rust-native-soak`
- `linux-tun-e2e`

The CI jobs upload test reports, golden diffs, logcat, fixture logs, and soak artifacts when available.
