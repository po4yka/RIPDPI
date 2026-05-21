# Testing

This document describes the current test stack for RIPDPI after the migration to in-repository Rust native modules.

## Coverage Layers

Use the [feature test checklist](feature-test-checklist.md) as the canonical feature and combination inventory when selecting release, nightly, or manual QA coverage. Use the [manual evidence template](feature-test-manual-evidence-template.md) when recording rooted-device, physical-network, provider-relay, TalkBack, routed netem, or remote-workflow evidence. Final checklist sign-off must run `test-lab/scripts/check-feature-test-signoff.sh` with an evidence-backed, operator-reviewed readiness JSON whose required `ready` rows are tied to the filled manual template. The required readiness rows are `android_device`, `rooted_physical_device`, `manual_talkback`, `physical_network_handover`, `routed_netem_vm`, `production_relay_matrix`, and `remote_workflow_confirmation`; print the canonical list with `test-lab/scripts/check-feature-test-signoff.sh --list-required-readiness`.

### Kotlin/JVM tests

These run through Gradle on the host JVM and cover the Android-facing logic without starting an emulator.

- `core:engine` - native wrapper lifecycle - config contract snapshots - state-machine tests - fault-injection tests - relay config bridge coverage for Cloudflare Tunnel modes, credential references, TLS catalog versioning, and Finalmask payloads - advanced strategy JSON coverage for markers, fake payload profiles, activation windows, adaptive fake TTL, per-network `networkScopeKey`, and UI/native bridge parity - native telemetry golden contracts
- `core:data` - app settings and serializer coverage for network-strategy memory toggles - fingerprint hashing and privacy-preserving network summaries - relay profile persistence coverage for Cloudflare Tunnel mode, Finalmask config, and credential-reference round trips - encrypted DNS path candidate planning, ordering, and persistence-backed migration coverage
- `core:service` - service state store - lifecycle coordination - diagnostics runtime coordination - relay supervisor coverage for Cloudflare Tunnel publish mode, MASQUE URL validation, feature gating, helper orchestration, and NaiveProxy watchdog behavior - connection-policy resolution, remembered-policy replay, and active-policy signature tracking - direct-mode DNS and transport-policy enforcement, confirmation/revalidation, and transport-family replay - owned-stack browser / `SecureHttpClient` execution path, Android 17 ECH gating, H2-only retry, and native owned-TLS fallback trace handling - handover monitor debounce/classification and service restart behavior - merged service telemetry golden contracts
- `core:diagnostics` - diagnostics manager orchestration - automatic probing profile wiring, hidden handover-triggered `quick_v1` probes, `full_matrix_v1` audit cohort rotation/provenance, recommendation persistence, and recommendation invariant validation - authority-scoped DNS classification, honest direct-mode verdict persistence, and transport-specific remediation projection - resolver recommendation ranking, diversified encrypted-DNS path planning, and temporary encrypted-DNS override flow - candidate-aware strategy-probe progress, audit confidence/coverage assessment, and summary/export metadata projection - runtime-history persistence of resolver telemetry and remembered-network proof/suppression state - export/archive contents - persisted passive-monitor and native-event golden contracts
- `app` - settings and diagnostics ViewModel coverage for chain DSL, fake payload/fake TLS controls, adaptive split placement, activation windows, adaptive fake TTL, remembered-network presentation, automatic probing/audit presentation, exact remediation states, transport-specific remediation, owned-stack browser flow, and winners-first audit reports

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
python3 -m unittest scripts.tests.test_offline_analytics_pipeline
```

Offline analytics pipeline coverage:

- `scripts/tests/test_offline_analytics_pipeline.py` - extraction normalization from the checked-in sample corpus - clustering reproducibility - candidate artifact publishing and drift generation - blessing flow - reviewed asset presence

Run the checked-in sample corpus end to end with:

```bash
python3 -m scripts.analytics.pipeline run-all \
  --manifest scripts/analytics/sample-corpus.json \
  --output-dir /tmp/ripdpi-offline-analytics
```

Pipeline operation details live in [docs/offline-analytics-pipeline.md](offline-analytics-pipeline.md).

## Rust native tests

The Rust workspace contains several test styles:

- unit tests for JNI adapters and helpers
- property-based and fuzz-style parsing coverage with `proptest`
- config and planner coverage for semantic markers, adaptive `auto(...)` markers, activation filters, fake payload profile selection, QUIC fake Initial profiles, and HTTP parser variants
- relay transport coverage for MASQUE path and auth handling, xHTTP Finalmask mutation, Cloudflare publish-origin helper behavior, and NaiveProxy helper contracts
- relay interoperability CI matrix now also exercises `ripdpi-xhttp` and `ripdpi-cloudflare-origin` alongside MASQUE, NaiveProxy, and the other relay crates through `scripts/ci/run-rust-relay-interoperability.sh`
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
cargo test -p ripdpi-proxy-runtime --lib
cargo test -p ripdpi-monitor-engine --lib
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

## Native performance and size guardrails

Before changing runtime threading, lock granularity, JNI wrappers, or packet-parsing hot paths, capture a local baseline with the same guardrails CI uses.

Criterion benchmarks:

```bash
cargo bench -p ripdpi-bench --bench config_parse
cargo bench -p ripdpi-bench --bench relay_throughput
cargo bench -p ripdpi-bench --bench runtime_control_snapshot
cargo bench -p ripdpi-bench --bench relay_connect_setup
cargo bench -p ripdpi-bench --bench runtime_lock_contention
python3 scripts/ci/check-criterion-regressions.py
```

Tracked benchmark baselines live in `scripts/ci/rust-bench-baseline.json`.

Native fuzz smoke:

```bash
bash scripts/ci/run-rust-fuzz-smoke.sh
```

This is the same lightweight `cargo-fuzz` smoke lane used in CI. It runs one seeded `packets_parse` iteration and then builds the other checked-in fuzz targets to catch broken scaffolding early.

Targeted Miri smoke for pure unsafe helpers:

```bash
bash scripts/ci/run-rust-miri.sh
```

This currently validates the host-side ancillary-fd decoding helper in `ripdpi-runtime::platform` under strict provenance. Do not expand this lane to JNI or syscall-heavy paths unless they gain explicit `#[cfg(miri)]` stubs.

Packaged native size checks:

```bash
python3 scripts/ci/verify_native_bloat.py
```

`verify_native_sizes.py` is a CI-packaging check. It must run only after the CI-style debug APK has been assembled from the full stripped ABI set staged via `ripdpi.prebuiltJniLibsDir`; a default local debug build may contain only the local ABI and unstripped native outputs, so it is not valid input for that verifier. Use the `native-size` CI artifact, or refresh a full baseline through `scripts/ci/run-phase0-baseline.sh`.

Tracked native-size baselines live in:

- `scripts/ci/native-size-baseline.json`
- `scripts/ci/native-bloat-baseline.json`

`verify_native_bloat.py` uses the Android SDK/NDK configuration from `local.properties` and `gradle.properties`, so do not hardcode toolchain paths when refreshing those baselines.

Baseline snapshot:

```bash
bash scripts/ci/run-phase0-baseline.sh /tmp/ripdpi-phase0-baseline
```

This captures one repo-level artifact set for:

- criterion runtime hot-path and TCP relay benchmarks
- per-connection memory/thread growth from the native load lane
- engine wrapper startup, shutdown, and `pollTelemetry()` latency on fake bindings
- packaged debug and release `.so` size reports
- representative native bloat attribution

The aggregated snapshot is written as:

- `/tmp/ripdpi-phase0-baseline/phase0-baseline.json`
- `/tmp/ripdpi-phase0-baseline/phase0-baseline.md`

Use `docs/native/unsafe-audit.md` as the required unsafe-code checklist when changing JNI wrappers, fd ownership, or platform syscalls during follow-up performance work.

Architecture verification guardrails:

- `python3 scripts/ci/verify_diagnostics_boundary.py` ensures `core:diagnostics` does not regain a direct `:core:service` dependency or service-package imports in production sources.
- `NativeWrapperLifecycleRaceTest` exercises stale-handle transitions around JNI wrapper `poll`, `stop`, `destroy`, and network-snapshot update paths.
- `bash scripts/ci/run-rust-miri.sh` remains the targeted host-side validation lane for pure unsafe helper logic.

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

The CLI packet-smoke registry lives at `scripts/ci/packet-smoke-scenarios.json`. Each scenario runs in its own process/capture session and emits a fixture manifest, fixture events, CLI stderr, `pcap`, and decoded `tshark` JSON artifacts.

Phase 16 measurement automation builds on top of the same packet-smoke surface:

```bash
python3 scripts/ci/phase16_matrix.py validate
python3 scripts/ci/phase16_matrix.py list
bash scripts/ci/run-phase16-matrix-entry.sh
python3 scripts/ci/phase16_pcap_summary.py --artifact-root build/phase16-matrix/<entry-id>
```

- `contract-fixtures/phase16_lab_matrix.json` is the source of truth for the repeated Wi-Fi/cellular x IPv4/IPv6 x rooted/non-rooted x proxy/VPN matrix.
- `.github/workflows/phase16-matrix.yml` fans that fixture out onto self-hosted `ripdpi-lab` runners instead of pretending GitHub-hosted runners can provide those environments.
- `scripts/ci/run-phase16-matrix-entry.sh` writes `phase16-run.json` plus `phase16-pcap-summary.json` for each entry so archive/export work can consume the same measurement evidence consistently.
- `scripts/ci/phase16_pcap_summary.py` understands both host `capture.pcap`/`capture.tshark.json` artifacts and Android `device-capture.pcap`/`device-capture.tshark.json` artifacts.

## Docker Local Network Test Lab

The Docker-backed lab in [`test-lab/`](../test-lab/README.md) provides a MacBook-hosted network target set for debug Android builds:

- CoreDNS with emulator and physical-device profiles
- httpbin and deterministic WireMock HTTP targets
- Caddy HTTPS with a generated local debug certificate
- TCP and UDP echo endpoints
- QUIC/HTTP3 server for host validation and future Android HTTP/3 probes
- Toxiproxy and optional netem scripts for fault scenarios
- debug-only ADB probe output as app-private JSON

Start the lab for emulator work:

```bash
./test-lab/scripts/start-lab.sh --profile emulator
./test-lab/scripts/adb-install-debug.sh
./test-lab/scripts/adb-run-probe-emulator.sh --mode diagnostics
./test-lab/scripts/stop-lab.sh
```

Start the lab for a physical device on the same Wi-Fi network:

```bash
./test-lab/scripts/start-lab.sh --profile device
./test-lab/scripts/adb-install-debug.sh
./test-lab/scripts/adb-run-probe-device.sh --mode diagnostics
./test-lab/scripts/stop-lab.sh
```

Use `--mode diagnostics` for a transport reachability smoke that does not require RIPDPI's foreground service to be active. Use `--mode proxy` only after the app has started proxy mode; the probe will then require the fixed loopback proxy listener to accept connections. Use `--mode vpn` only after the app has started VPN mode; the probe will then require Android to report an active VPN transport and the lab traffic checks to pass. VPN mode does not require `127.0.0.1:1080` because the service may use an ephemeral authenticated internal SOCKS hop between the tunnel and proxy.

The probe JSON is pulled into `test-lab/artifacts/probe-<profile>-<mode>.json`. `verdict=Fail` exits non-zero; typed recoverable failures such as UDP timeout or Android QUIC probe unsupported return `Degraded` so they remain observable without masking DNS, HTTP, HTTPS, or TCP success.

For a rooted emulator with Magisk `su`, run the repeatable on-device netem diagnostics lane with:

```bash
ANDROID_SERIAL=emulator-5554 ./test-lab/scripts/run-rooted-emulator-netem.sh
```

The runner verifies root access, captures qdisc state, runs baseline diagnostics, applies `tc qdisc replace dev wlan0 root netem delay 200ms 40ms loss 1%`, reruns diagnostics, asserts DNS, HTTP, HTTPS, TCP, UDP, and relay readiness with no probe errors, and clears the qdisc before exit. This proves the rooted-emulator controlled-network lane; it does not replace the release row that requires a physical Pixel traffic path through a Linux routed netem VM/router.

## Offline Analytics CI

The offline analytics pipeline has a dedicated workflow:

- `.github/workflows/offline-analytics.yml`

It runs the Python unit suite plus the full checked-in sample corpus and uploads:

- `offline-records.json`
- candidate device-fingerprint and winner-mapping catalogs
- `drift-report.json`
- `report.md`

The `build` job in `.github/workflows/ci.yml` also runs the offline analytics unit tests for fast PR feedback.

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

For physical devices, expose the host fixture over `adb reverse` and point the fixture manifest at loopback before running the E2E package:

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

When the E2E package starts VPN-mode tests on an Android 15/16 physical device, the shared UiAutomator helper now auto-confirms the real system VPN consent dialog. This applies only to the real E2E/device flows; the service integration suite uses a fake `VpnTunnelSessionProvider` and does not exercise platform consent UX.

The packet-smoke instrumentation matrix can be run one scenario at a time with:

```bash
ANDROID_SERIAL=<device-serial> \
  bash scripts/ci/run-android-packet-smoke.sh
```

Optional runner inputs:

- `RIPDPI_PACKET_SMOKE_CAPTURE_MODE=auto|raw|indirect`
- `RIPDPI_PACKET_SMOKE_SCENARIO_FILTER=<scenario id or instrumentation selector>`
- `RIPDPI_PACKET_SMOKE_ARTIFACT_DIR=/abs/path/to/output`

The Android runner reuses the shared fixture manifest, resets fixture faults/events between scenarios, collects `logcat`, `dumpsys connectivity`, `ip addr`, `ip route`, and grabs a failure screenshot on test failures. On rooted emulators or rooted devices with `tcpdump` installed, `capture_mode=raw` adds an on-device `pcap`; otherwise `auto` falls back to the ADB-observable lane.

Physical-device note: `adb reverse` only covers TCP, so the runner skips DoQ scenarios when the fixture host is loopback on an unrooted physical device. Emulators and direct host-reachable devices can exercise the full DoQ path.

Optional runner args for physical-device VPN consent handling:

- `-Pandroid.testInstrumentationRunnerArguments.ripdpi.vpnConsentTimeoutMs=25000`
- `-Pandroid.testInstrumentationRunnerArguments.ripdpi.vpnConsentPackageHints=com.vendor.vpndialogs,com.oem.permissioncontroller`

If the system dialog shape changes and consent is not confirmed, rerun the failing E2E class and collect the paths emitted by the assertion message for:

- the dumped UI hierarchy XML
- the captured screenshot PNG
- the active package / visible package list / selector matches embedded in the failure text

CI and release still build the full ABI set from `ripdpi.nativeAbis`.

## External UI automation

Debug builds expose a launch contract for deterministic Appium and raw `adb` sessions. Maestro uses the same resource-id selector policy while navigating through visible UI controls. The contract, selector policy, and Appium checklist live under `docs/automation/`.

Run the committed Maestro smoke pack locally with:

```bash
bash scripts/ci/run-maestro-smoke.sh
```

The smoke flows avoid `pm clear` and assert stable resource IDs. Use Appium or raw `adb shell am start` for launch extras such as:

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

### Fleet-compat golden fixtures

A separate golden set locks RIPDPI's importers against the sibling `ripdpi-vpn-deploy` repo's emitter output. Hand-authored, fully-synthetic fixtures (RFC-5737 doc IPs, `-fixture` credentials) live under `core/data/src/test/resources/fleet-fixtures/<scenario>/`; the `FleetCompatGoldenFileTest` JVM suite parses each `bundle.json` through the production subscription parser and asserts the imported profiles, selector groups, and per-app routing rules.

The sibling emitter (`emit-singbox.sh`) needs Terraform + SOPS + real infra, so it cannot run in CI. Two tools bridge that gap:

- `scripts/refresh-fleet-fixtures.sh` -- local regenerator. Pins the deployer git SHA on a single line, shims `terraform` + `sops` against a checked-in frozen synthetic secret-set, and runs the real emitter. `--check` (default) diffs vs the committed fixtures; `--write` overwrites them.
- `scripts/ci/check_fleet_fixtures.py` -- deployer-independent structural drift gate (required files, JSON shape, `meta.json` SHA vs the script pin, no production-token leaks). Run on every relevant PR by `fleet-fixtures.yml`.

## Load/stress tests

Load tests exercise high-concurrency ramp-up profiles, burst spikes, and saturation behavior. They complement the soak suite which covers endurance over time.

Scenarios:

- `proxy_ramp_load` -- gradually increases concurrent connections from 8 to max\_clients, measuring acceptance rate, latency percentiles, and thread pool scaling at each step
- `proxy_burst_load` -- coordinates 128 simultaneous connection attempts against a 64-slot proxy, verifying capacity enforcement and post-burst recovery
- `proxy_saturation_load` -- holds the proxy at full capacity with long-lived connections, attempts overflow, and verifies existing connection quality is maintained

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

## Known gaps & coverage roadmap

This section is the human-curated companion to the feature-test checklist. It records the small set of gaps where the test pyramid is intentionally thin or where work is still in flight. Sized so it can be re-verified in one pass.

### Verified closed (2026-05-16)

Findings from earlier audits that were still open at the start of 2026-Q2 and have since landed. Re-verify before re-investigating.

- **B-1** -- "Unbounded retry loop in `connect_target_with_route`." Bounded in `native/rust/crates/ripdpi-proxy-runtime/src/runtime/routing/retry.rs` by `state.max_route_retries()`, default 8. Covered by `max_route_retries_default_is_eight` and `max_route_retries_is_customizable` unit tests in the same file.
- **C-1** -- "Dual adaptive systems (`adaptive_tuning` + `strategy_evolver`) uncoordinated." Resolved via a documented priority chain in `ripdpi-runtime-strategy/src/strategy_evolver.rs` and `ripdpi-runtime-adaptive/src/adaptive_tuning.rs`: evolver hints override per-flow adaptive hints when the evolver is enabled, otherwise per-flow cycling drives the dimensions.
- **E-1** -- "`adaptive_tuning.rs` has no dedicated unit tests." `#[cfg(test)] mod tests;` is declared and the module covers candidate cycling and dimension-order shuffling.
- **VPN/DNS-leak instrumentation matrix** -- landed under `core:service`'s androidTest sources; the original task issue is marked `status: done`.
- **HTTP-injection error-page probe** -- landed as `ripdpi-diagnostics-http::http_injection_probe`; task `status: done`.

### Open but tracked

These have task issues under `docs/tasks/issues/` and are sized for routine roadmap work, not new design.

- **D-1 residual** -- evolver combo space spans 5 adaptive dimensions plus fake-TTL (6 of 7). Timing-jitter and OOB-byte placement are not in the combo space and so are not explored by the bandit.
- **A-1 spot-check** -- no `1.1.1.1`/`cloudflare-dns` literal remains in `ripdpi-monitor-engine`, but the new `ripdpi-diagnostics-probes::ProbeContext` contract should be threaded through as new probes migrate in so the fix becomes structurally enforced instead of vigilance-based.
- ECH for TLS outbounds (`add-ech-encrypted-client-hello-for-tls-outbounds.md`).
- Owned-stack JA3/JA4 fingerprint snapshot in release CI (`snapshot-owned-stack-ja4-fingerprint-in-release-ci.md`). Without this, transitive TLS dependency bumps can silently shift the ClientHello and fail evasion in production without any test signal.
- Telegram MTProto DC reachability diagnostic (`add-telegram-mtproto-diagnostic-with-dc-reachability-and-throughput.md`).
- Android lockdown / kill-switch onboarding health checks (`add-android-lockdown-onboarding-and-kill-switch-health-checks.md`).
- Split-DNS interceptor leak coverage (`add-dns-interceptor-and-split-dns-leak-tests.md`, currently `blocked`).

### Open and not yet tracked

Heavier infrastructure work tracked as a single design spike under [`spike-adversarial-network-harness-and-realprovider-matrix.md`](tasks/issues/spike-adversarial-network-harness-and-realprovider-matrix.md); the spike unblocks three separate implementation issues. Listed here so they are not silently forgotten.

**TSPU adversarial emulator v1 landed.** Dry-run path is verifiable on any host:

```bash
cd test-lab/chaos/tspu
python3 -m runner.cli dry-run \
  --matrix matrix.json --fixtures fixtures --out-dir /tmp/tspu-dryrun-v1
python3 -m unittest discover -s tests
```

The live `nfqueue` mode is documented in [`test-lab/chaos/tspu/README.md`](../test-lab/chaos/tspu/README.md) and follows in a separate PR. The generator-driven packet-smoke and real-provider Phase-16 spikes are still open; design docs live under `docs/architecture/spike-generator-packet-smoke.md` and `docs/architecture/spike-phase16-real-provider.md`.

- **Adversarial TSPU emulator in `test-lab/chaos/`.** Today `chaos/` ships Toxiproxy + netem for loss/latency/jitter; it does not reproduce the RU-TSPU behaviour set (RST-injection on SNI, blackhole after N bytes, selective QUIC-Initial drop, MTU-clamp). Without this, packet-smoke scenarios verify the *byte shape on the wire* but not *survival against a known adversary pattern*. Next step: container with nftables + `nfqueue`/scapy rules per documented TSPU pattern, matrix run against every desync mode.
- **Generator-driven packet-smoke.** `scripts/ci/packet-smoke-scenarios.json` hand-lists ~10-15 scenarios; the desync parameter space is 7-dimensional. Move to a generator that samples the space and replays a named TSPU pattern set per PR. The Probe trait landed in `ripdpi-diagnostics-probes` does not solve this on its own; it is the oracle, not the input source.
- **Phase-16 lab matrix on real-provider SIMs.** `contract-fixtures/phase16_lab_matrix.json` is synthetic. Release-time confidence requires self-hosted runners on actual carrier SIMs; GitHub-hosted runners cannot supply this signal.

### How to use this section

- Before claiming an "audit finding is still open," verify against the cited file path and re-run the named tests. The 2026-Q1 audits have aged out of most of their concrete claims.
- Before adding a probe, route it through `ripdpi-diagnostics-probes::Probe` with a populated `ProbeContext`. Do not add probes that talk to hard-coded endpoints regardless of user policy.
- When you close one of the "open but tracked" items, move it to "verified closed" with the same shape (claim, file path, test name).

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
- `fleet-fixtures` -- structural drift gate + `*FleetCompat*` golden-file suite, on PRs touching the subscription/routing/AWG/relay models or the fleet fixtures
- `tspu-dryrun` -- TSPU adversarial emulator matrix-runner dry-run + unittest suite, on PRs touching `test-lab/chaos/tspu/` or its CI script. Uploads `verdict-report.json` and per-cell `.pcap` artifacts for triage.
- `tspu-live` -- TSPU adversarial emulator live-mode smoke. Installs `nftables` and `python3-netfilterqueue` on an ubuntu-latest runner, loads the CI nft ruleset that funnels TCP:8443 into nfqueue 0, runs the live handler with a watchdog `--timeout-seconds`, sends a synthetic TLS ClientHello with a blocklisted SNI, and asserts that the resulting `verdict-report.json` records at least one `blocked` cell. Uploads `verdict-report.json` and `handler.log` as artifacts.

Nightly/manual lanes add:

- `rust-native-soak` -- endurance tests (restart, sustained traffic, fault recovery)
- `rust-native-load` -- high-concurrency ramp-up, burst, and saturation tests
- `linux-tun-e2e` -- privileged TUN data-plane tests
- `linux-tun-soak` -- privileged TUN endurance tests
- `nightly-rust-coverage` -- coverage including ignored tests

The CI jobs upload test reports, golden diffs, logcat, fixture logs, soak/load artifacts, and coverage reports when available.

For a remote sign-off pass, follow the repository ruleset first: push local commits to a review branch, let the pull request checks run, and merge to `main` only after required reviews/checks pass. For final sign-off on the merged commit, use the default workflow-dispatch inputs unless a release owner explicitly asks for the heavier emulator, soak, load, coverage, benchmark, or private-corpus lanes:

```bash
gh workflow run ci.yml --ref main
gh workflow run local-network-lab.yml --ref main -f run_vpn_emulator_lane=false
gh workflow run offline-analytics.yml --ref main -f private_corpus_path=''
gh workflow run mutation-testing.yml --ref main -f packages='' -f in_diff=false
gh workflow run fuzz-nightly.yml --ref main -f fuzz_seconds=1800
```

`CodeQL` does not expose `workflow_dispatch`; it runs from the push to `main`. Record the run IDs and conclusions in `docs/feature-test-manual-evidence-template.md` under `Remote Workflows`, then record the final sign-off guard command/result in the template's final verdict section.
