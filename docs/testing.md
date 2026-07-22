# Testing

This document describes the current test stack for RIPDPI after the migration to in-repository Rust native modules.

## Coverage Layers

Use the [feature test checklist](feature-test-checklist.md) as the canonical feature and combination inventory when selecting release, nightly, or manual QA coverage. Use the [manual evidence template](feature-test-manual-evidence-template.md) when recording rooted-device, physical-network, provider-relay, TalkBack, routed netem, or remote-workflow evidence. Final checklist sign-off must run `test-lab/scripts/check-feature-test-signoff.sh` with an evidence-backed, operator-reviewed readiness JSON whose required `ready` rows are tied to the filled manual template. The required readiness rows are `android_device`, `rooted_physical_device`, `manual_talkback`, `physical_network_handover`, `routed_netem_vm`, `production_relay_matrix`, and `remote_workflow_confirmation`; print the canonical list with `test-lab/scripts/check-feature-test-signoff.sh --list-required-readiness`.

### Kotlin/JVM tests

These run through Gradle on the host JVM and cover the Android-facing logic without starting an emulator.

- `core:engine` - native wrapper lifecycle - config contract snapshots - state-machine tests - fault-injection tests - relay config bridge coverage for Cloudflare Tunnel modes, credential references, TLS catalog versioning, and Finalmask payloads - advanced strategy JSON coverage for markers, fake payload profiles, activation windows, adaptive fake TTL, per-network `networkScopeKey`, and UI/native bridge parity - native telemetry golden contracts
- `core:data` - app settings and serializer coverage for network-strategy memory toggles - support settings deep-link parsing, all-settings registry coverage, and all-or-nothing preview/apply coverage - fingerprint hashing and privacy-preserving network summaries - relay profile persistence coverage for Cloudflare Tunnel mode, Finalmask config, and credential-reference round trips - encrypted DNS path candidate planning, ordering, and persistence-backed migration coverage
- `core:service` - service state store - lifecycle coordination - diagnostics runtime coordination - relay supervisor coverage for Cloudflare Tunnel publish mode, MASQUE URL validation, feature gating, helper orchestration, and NaiveProxy watchdog behavior - connection-policy resolution, remembered-policy replay, and active-policy signature tracking - direct-mode DNS and transport-policy enforcement, confirmation/revalidation, and transport-family replay - owned-stack browser / `SecureHttpClient` execution path, Android 17 ECH gating, H2-only retry, and native owned-TLS fallback trace handling - handover monitor debounce/classification and service restart behavior - merged service telemetry golden contracts
- `core:diagnostics` - diagnostics manager orchestration - automatic probing profile wiring, hidden handover-triggered `quick_v1` probes, `full_matrix_v1` audit cohort rotation/provenance, recommendation persistence, and recommendation invariant validation - authority-scoped DNS classification, honest direct-mode verdict persistence, and transport-specific remediation projection - resolver recommendation ranking, diversified encrypted-DNS path planning, and temporary encrypted-DNS override flow - candidate-aware strategy-probe progress, audit confidence/coverage assessment, and summary/export metadata projection - runtime-history persistence of resolver telemetry and remembered-network proof/suppression state - export/archive contents - persisted passive-monitor and native-event golden contracts
- `app` - settings and diagnostics ViewModel coverage for chain DSL, fake payload/fake TLS controls, adaptive split placement, activation windows, adaptive fake TTL, remembered-network presentation, support settings deep-link routing/preview reachability, automatic probing/audit presentation, exact remediation states, transport-specific remediation, owned-stack browser flow, and winners-first audit reports

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
- relay transport coverage for MASQUE path and auth handling, xHTTP Finalmask mutation, Cloudflare publish-origin helper behavior, Trojan, AnyTLS, Shadowsocks, Tor bridge/PT config, NaiveProxy helper contracts, and external PT launch paths
- relay interoperability CI matrix exercises `local-network-fixture`, `ripdpi-xhttp`, `ripdpi-vless`, `ripdpi-hysteria2`, `ripdpi-tuic`, `ripdpi-shadowtls`, `ripdpi-anytls`, `ripdpi-masque`, `ripdpi-cloudflare-origin`, `ripdpi-naiveproxy`, `ripdpi-relay-core`, and the nested proxy runtime E2E through `scripts/ci/run-rust-relay-interoperability.sh`
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
(cd native/rust && cargo test -p ripdpi-proxy-runtime --lib)
(cd native/rust && cargo test -p ripdpi-monitor-engine --lib)
(cd native/rust && cargo test -p ripdpi-android --lib)
(cd native/rust && cargo test -p ripdpi-masque -p ripdpi-relay-core -p ripdpi-xhttp -p ripdpi-naiveproxy -p ripdpi-cloudflare-origin -p ripdpi-trojan -p ripdpi-anytls -p ripdpi-shadowsocks -p ripdpi-tor)
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
(cd native/rust && cargo bench -p ripdpi-bench --bench config_parse)
(cd native/rust && cargo bench -p ripdpi-bench --bench protocol_throughput)
(cd native/rust && cargo bench -p ripdpi-bench --bench relay_throughput)
(cd native/rust && cargo bench -p ripdpi-bench --bench runtime_control_snapshot)
(cd native/rust && cargo bench -p ripdpi-bench --bench relay_connect_setup)
(cd native/rust && cargo bench -p ripdpi-bench --bench runtime_lock_contention)
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

This currently validates focused VLESS scoped-handle and Reality-hook helpers plus tier-3 privileged-op helpers under strict provenance. SCM_RIGHTS fd passing in `ripdpi-root-helper-protocol` depends on real Unix ancillary-data behavior, so it is covered by normal unit and integration tests instead of the Miri lane. Do not expand this lane to JNI or syscall-heavy paths unless they gain explicit `#[cfg(miri)]` stubs.

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

Generate the fail-closed local MASQUE/H3 PMTUD evidence from a clean exact
commit with an output directory outside the checkout:

```bash
bash scripts/ci/run-pmtud-local-evidence.sh \
  --output-dir /tmp/ripdpi-pmtud-evidence
python3 scripts/ci/pmtud_local_evidence.py validate \
  --manifest /tmp/ripdpi-pmtud-evidence/manifest.json \
  --artifact-dir /tmp/ripdpi-pmtud-evidence/artifacts \
  --source-sha "$(git rev-parse HEAD)"
```

The runner uses a `git archive` snapshot and `cargo test --locked --offline`.
Its canonical manifest requires all six named controls: clear-path discovery,
black-hole fault injection, context-zero DATAGRAM payload integrity, the exact
DATAGRAM size boundary, and MASQUE/H3 recovery over IPv4 and IPv6 underlays.
Missing, reordered, skipped, partial, stale, digest-tampered, or non-PASS
evidence is rejected. The environment is an allowlisted OS/architecture and
tool-version summary; usernames, hostnames, paths, addresses, packet payloads,
and inherited environment values are not written to artifacts.

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
- Real-provider rows are present in the fixture as `runnerRequired=real-provider` and `evidenceTier=real-provider`; default matrix emission excludes them so normal scheduled lab runs do not queue on carrier hardware, and even explicitly filtered real-provider rows require `workflow_dispatch` with `include_real_provider=true`.
- The L7 adversarial emulator row is present as `executionKind=l7_adversarial_emulator`, `networkCondition=l7_adversarial_emulator`, and `evidenceTier=synthetic-adversarial`; default matrix emission excludes it, but `workflow_dispatch` can select `matrix_filter=l7_adversarial_emulator_v1_1` without `include_real_provider` or carrier hardware.
- Release sign-off for real-provider confidence must use the exact workflow dispatch input `include_real_provider=true` on `.github/workflows/phase16-matrix.yml`, normally paired with `matrix_filter=<real_provider_* entry id>` for the namespace being validated. The required evidence artifact is the uploaded `phase16-<entry-id>` artifact containing both `phase16-run.json` and `phase16-pcap-summary.json`; do not claim real-provider confidence from labels, logs, or synthetic-lab artifacts alone.
- The same fixture also carries required non-baseline `networkCondition` rows for PMTUD blackholes, rooted IP fragmentation under MTU stress, IPv6 extension-header blackholes, and carrier-style NAT/reordering. Those rows require `RIPDPI_PHASE16_PREPARE_HOOK` so they fail closed instead of running against an unstressed baseline path.
- `.github/workflows/phase16-matrix.yml` fans that fixture out onto self-hosted `ripdpi-lab` runners instead of pretending GitHub-hosted runners can provide those environments.
- `scripts/ci/run-phase16-matrix-entry.sh` writes `phase16-run.json` plus `phase16-pcap-summary.json` for each entry so archive/export work can consume the same measurement evidence consistently; real-provider rows fail closed with `status=runner_unavailable` unless `RIPDPI_PHASE16_REAL_PROVIDER_CONFIG` is readable, declares the requested `carrierNamespace`, sets `pcapScrubPolicy=required`, and `RIPDPI_PHASE16_PREPARE_HOOK` is executable. For real-provider rows the hook receives the requested namespace through the positional `carrierNamespace` argument and `RIPDPI_PHASE16_REQUESTED_NAMESPACE`; hook stdout/stderr is suppressed and only non-secret hook status metadata is written to artifacts.
- For L7 emulator rows, `scripts/ci/run-phase16-matrix-entry.sh` runs `scripts/ci/run-l7-adversarial-dryrun.sh` under `build/phase16-matrix/<entry-id>/l7-adversarial/`, records `l7-adversarial/verdict-report.json`, and fails the lane when any adversary-pattern cell reports `blocked`; `degraded` or `inconclusive` cells are surfaced as a `partial` summary verdict for release-owner review.
- `scripts/ci/phase16_pcap_summary.py` understands both host `capture.pcap`/`capture.tshark.json` artifacts and Android `device-capture.pcap`/`device-capture.tshark.json` artifacts.
- `scripts/ci/phase16_pcap_summary.py` also links L7 emulator evidence through `linkedArtifacts.l7VerdictReport` and summarizes `l7Adversarial.gateVerdict`, `failedCells`, and `partialCells`; this is synthetic adversary-pattern evidence, not proof from a carrier SIM. Real-provider confidence still requires filtered rows with `evidenceTier=real-provider`, a namespace-specific private runner config, and SIM-backed captures whose identifiers are scrubbed before upload.

Private real-provider runner config stays outside the repository and uses only symbolic namespace keys:

```json
{
  "version": "phase16_real_provider_runner_v1",
  "namespaces": {
    "ns-mts": {
      "pcapScrubPolicy": "required"
    },
    "ns-megafon": {
      "pcapScrubPolicy": "required"
    },
    "ns-beeline": {
      "pcapScrubPolicy": "required"
    }
  }
}
```

The prepare hook owns the private modem/SIM mapping for those namespace keys. It must not print IMSI, subscriber IDs, APN secrets, carrier IPs, or modem identifiers; the repo runner discards real-provider hook output as a second boundary and emits only `phase16-prepare-hook.json` with entry id, symbolic namespace, status, and exit code.

## Redacted dual-vantage release evidence

DNS and the TUN-establish-to-native-ready direct window require redacted
dual-vantage packet evidence for Android releases. Fleet-profile rollout keeps
all 19 applicable gates on its ordinary-results contract. IPv6 and the remaining kill-switch gates use the
ordinary gate-results document. A release decision is complete only when the
checker receives both inputs and their disjoint gate maps cover every applicable
policy gate. The capture can run from a clean local checkout or the physical
self-hosted workflow in `.github/workflows/dns-ipv6-killswitch-evidence.yml`.
It requires one connected Android device and a private mode-0600 config outside
the checkout:

```json
{
  "version": "ripdpi_network_evidence_runner_v1",
  "clientHook": "/opt/ripdpi/bin/evidence-client",
  "observerHook": "/opt/ripdpi/bin/evidence-observer",
  "workloadHook": "/opt/ripdpi/bin/evidence-workload",
  "clientVantageId": "<64 lowercase hex characters>",
  "observerVantageId": "<different 64 lowercase hex characters>",
  "clientNetworkId": "<different 64 lowercase hex characters>",
  "observerNetworkId": "<different 64 lowercase hex characters>"
}
```

Generate all four private identifiers independently with `openssl rand -hex 32`
and keep the network-to-random-ID mapping only in runner-owned configuration.
All four values must be distinct. Labels, SSIDs, BSSIDs, IP addresses, hostnames,
device identifiers, and unsalted hashes derived from those low-entropy values are
forbidden. Vantage and network identifiers use separate domain prefixes before
SHA-256 hashing; their raw values and hook paths never leave the runner. The
runner also records executable digests for itself, the validator, the policy,
the source-pinned producer policy, both collectors, and the workload,
rejects a shared collector path, and requires a non-emulated ADB device. Before
capture, the workflow builds both the `GithubFullDebug` client and its androidTest
APK from the checked-out SHA for the physical device ABI, installs them, pulls
both installed APKs back, verifies byte equality, and records both SHA-256 values
in provenance. The observer hook owns the
authenticated remote capture transport; its credentials and endpoint stay in the
runner-owned installation.

The runtime accepts manifest, observation, and scenario-plan v3 only. The v1 and
v2 schemas remain archived for interpreting historical artifacts; they are not
accepted release-evidence inputs. The runner first extracts its own script, the
validator, gate policy, and producer policy directly from the selected Git
commit, re-executes that immutable snapshot, and records the four blob digests.
Before capture, the mode-0600 config is opened once with no-follow semantics,
validated through the same descriptor, and copied into private scratch. Hooks
and both APKs are likewise copied
through no-follow file descriptors into the mode-0700 scratch directory and the
immutable copies are hashed and used. Collector, workload, and APK digests must
also appear in the source-pinned
`quality/release-gates/network-evidence-producers.json` allowlist. That committed
allowlist is intentionally empty until real private hook binaries and an
exact-source signed release-candidate and androidTest APKs have been locally materialized,
reviewed, captured, and pinned. The current `GithubFullDebug` evidence workflow
does not attest a signed release candidate and therefore remains fail-closed.
A real capture therefore
currently fails closed at producer and client-artifact approval rather than
trusting caller-controlled config. ADB operations and the workload are bounded
by runner-owned timeouts. The runner requires exactly one physical
ADB device, requires exactly one package path for `com.poyka.ripdpi` and
`com.poyka.ripdpi.test`, and compares both installed APKs byte-for-byte with the
scratch artifacts before and after capture.

`test-lab/scripts/network-evidence-ssh-capture.py` is a checked-in private
capture utility for acquiring a PCAP from an owner-controlled SSH/tcpdump
vantage. It requires the test-flow peer IP exactly as visible at that vantage
and one to eight explicit endpoint IP-and-port pairs, builds the bidirectional
BPF itself, uses a 192-byte snap length and a fixed size/packet bound, requires
passwordless `sudo`, and verifies remote process cleanup on every exit path. It requires a
`RIPDPI-EVIDENCE-V2:<sha256>` packet marker; a missing marker fails the capture
and removes the PCAP, metadata, and readiness marker. This preflight is
mandatory before treating a host as the Pixel's client-underlay vantage. Being
reachable from the Mac, sharing an Internet exit, or observing unrelated
traffic is not proof that a router can see the Pixel's packets.

The backend deliberately emits private-capture metadata rather than a release
observation. `test-lab/scripts/network-evidence-pcap-oracle.py` now provides the
source-owned raw-artifact boundary for later hooks: it validates both canonical
capture-metadata documents and a strict private action ledger, parses bounded
classic PCAP for Ethernet, raw IP, Linux SLL, and Linux SLL2 (including a bounded
VLAN stack), aligns each vantage by its action/outcome packet markers rather than
mixing local and remote clocks, and derives canonical mode-0600 unstamped
observations without publishing addresses or payloads. Caller-supplied counters
and verdicts are rejected; snap-length truncation becomes a capture error.

The oracle keeps the generic marker-pair rule only for non-policy self-tests and
explicitly rejects it for every Android dual-vantage release gate. The
`tun-establish-native-ready-v1` rule is the first gate-specific implementation:
it validates the private Android action receipt and canonical fixture identity,
reassembles bounded TCP marker streams independently at both vantages, treats
the conservative interval from before VPN start through confirmed native
readiness as the direct-window boundary, and permits only the receipt-bound
post-ready fixture DNS exchange observed identically in both captures without
publishing its endpoint or question. Any other non-control packet within that
interval is emitted as unexpected evidence; truncated packets are capture
errors. The oracle remains outside
`network-evidence-producers.json` until an authorized physical capture and the
remaining producer-provenance review are complete.

The first source-owned Android action contract is registered in
`quality/release-gates/android-network-evidence-actions.json` for
`killswitch-tun-establish-native-ready`. Its fixed host runner,
`test-lab/scripts/run-android-network-evidence-action.sh`, invokes exactly
`VpnStartupWindowE2ETest#vpnStartupWindowHoldsDnsPacketUntilNativeReady` and
accepts only a private test-produced receipt bound to the clean source SHA,
client/androidTest artifact digests, fixture identity, a private DNS-query
digest, and source-derived wire
markers. Skipped, zero-test, ambiguous, stale, non-private, or semantically
incomplete receipts fail closed. The action is explicitly `productionReady:
false`: its analyzer is implemented, but an authorized physical capture and
producer-provenance approval are still missing, so it cannot open a producer
allowlist or establish a release PASS. The runner starts and stops RIPDPI VPN;
it must not be executed without operator authorization for the current device
and network state.

On success, the utility leaves the raw PCAP at the explicitly requested private
path for the future gate-specific analyzer. The operator must delete it after
analysis; unlike the integrated runner scratch directory, this standalone
utility does not apply an automatic successful-run retention policy.

Network-switch scenarios require a separately authorized operator action and a
source-owned action receipt. Capture or workload tooling must not change Wi-Fi,
cellular, routes, DNS, Private DNS, VPN state, or airplane mode on its own; an
absent receipt remains release-blocking.

The fixed hooks receive only a correlation digest, source SHA, marker paths,
artifact paths and digests, and output paths. The workload emits a strict v3
scenario plan with action and outcome marker digests derived from a
domain-separated hash of correlation ID, gate ID, gate kind, and purpose. All
markers must be unique across both purposes. The plan, both observations, and
manifest provenance bind both the client and androidTest artifact digests. Both
observations must also attest those markers and the canonical plan digest with
positive action/outcome counts. Validation immediately before publication uses
the all-PASS mode, so a valid but FAIL or INCONCLUSIVE bundle is never uploaded
as release evidence. `run-dual-vantage-network-evidence.sh` starts both collectors
in isolated process groups before the workload, stops their complete process
trees on every exit path, and runs the workload in its own process group. A
workload timeout sends TERM and then KILL to the whole group and waits for it
before failing publication. The runner publishes exactly
`manifest.json`, `client-observation.json`, and `observer-observation.json`.
Raw PCAP and private logs remain in the mode-0700 scratch directory and are
deleted; only their SHA-256 digests and allowlisted packet counters are kept.

The manifest validator rejects missing/duplicate vantages, unknown fields,
wrong source or execution provenance, stale or non-overlapping windows, missing
positive controls, digest tampering, path traversal, and a declared result that
does not match the two observations. Capture errors derive `INCONCLUSIVE`, while
observed forbidden packets derive `FAIL`; both are release-blocking. Observations
must be canonical JSON and cannot be repackaged after their freshness window.
For a real local capture, start from a clean checkout and pass the APK itself;
the runner derives and verifies `HEAD` and hashes the artifact locally:

```bash
execution_id="local-$(date +%s)"
test-lab/scripts/run-dual-vantage-network-evidence.sh \
  --config /absolute/private/network-evidence-runner.json \
  --output-dir /tmp/ripdpi-network-evidence \
  --source-root "$PWD" \
  --client-artifact /absolute/path/to/installed-and-verified.apk \
  --test-artifact /absolute/path/to/installed-and-verified-androidTest.apk \
  --execution-kind local \
  --execution-id "$execution_id" \
  --execution-attempt 1

python3 scripts/ci/approve_local_android_release_evidence.py \
  --results /absolute/path/to/ordinary-gate-results.json \
  --evidence-manifest /tmp/ripdpi-network-evidence/manifest.json \
  --execution-id "$execution_id" \
  --execution-attempt 1
```

The ordinary results file must omit the DNS and startup direct-window gates;
those results are derived exclusively from the dual-vantage observations. The
local command is not evidence unless the configured hooks perform real captures
from distinct networks and the APK path identifies the exact installed client.
The checked-in ordinary producer intentionally emits 11 structured no-ship
failures until source-owned raw-artifact verifiers exist; it cannot self-attest
a PASS. The local acceptance entrypoint derives the exact clean `HEAD`, requires
local execution provenance, snapshots the ordinary results and dual-vantage
manifest into a private
directory, and runs the checker extracted from that exact commit. GitHub Actions
is not an acceptance prerequisite. The optional
compatibility release workflow still requires
`RIPDPI_DNS_IPV6_KILLSWITCH_RESULTS` to identify a real exact-SHA artifact and
fails closed when it is absent; it never synthesizes PASS results.
Run the local contract checks with:

```bash
python3 -m unittest \
  scripts.tests.test_dns_ipv6_killswitch_gates \
  scripts.tests.test_network_evidence_manifest \
  scripts.tests.test_network_evidence_pcap_oracle \
  scripts.tests.test_local_android_release_acceptance
bash test-lab/scripts/test-dual-vantage-network-evidence.sh
```

## Docker Local Network Test Lab

The Docker-backed lab in [`test-lab/`](../test-lab/README.md) provides a MacBook-hosted network target set for debug Android builds:

- CoreDNS with emulator and physical-device profiles
- httpbin and deterministic WireMock HTTP targets
- Caddy HTTPS with a generated local debug certificate
- TCP and UDP echo endpoints
- QUIC/HTTP3 server for host validation and future Android HTTP/3 probes
- In-process `MasqueH3ClassicConnectFixture` for H3-only RFC 9114 classic-CONNECT request-shape tests; the proxy port has no TCP/H2 listener, so fallback cannot mask a malformed H3 request
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

For the repeatable physical TCP-family netem forwarder slice, run:

```bash
ANDROID_SERIAL=31130DLH2000EG ./test-lab/scripts/run-netem-forwarder.sh --profile device
```

The runner starts a temporary Docker container on the lab network, publishes fresh host TCP ports, forwards HTTP, HTTPS, TCP echo, and mock relay traffic through that container, applies `tc netem` delay and loss to the container interface, and asserts the Android diagnostics probe still reports DNS, HTTP, HTTPS, TCP, UDP, and relay readiness. DNS and UDP intentionally remain on host helpers because Docker Desktop UDP publication is not reliable for same-LAN physical devices. This is a repeatable partial routed-netem proof for TCP-family and relay paths; it does not close the full physical routed-netem requirement until a Linux VM/router carries DNS, UDP, TCP, HTTPS, and relay traffic in the Pixel path.

For repeatable physical or emulator DNS/UDP fault combinations using the host-side fallback helpers, run:

```bash
ANDROID_SERIAL=31130DLH2000EG ./test-lab/scripts/run-host-fault-matrix.sh --profile device
```

The runner starts per-scenario host DNS and UDP echo helpers on fresh ports, then probes baseline, DNS UDP drop, UDP echo drop, and combined DNS UDP plus UDP echo drop cases. It asserts DNS UDP loss recovers through DNS-over-TCP fallback, UDP echo loss surfaces as a typed recoverable timeout, and HTTP, HTTPS, TCP, and relay readiness remain healthy. This closes a fast local diagnostics-regression slice for same-LAN physical devices; it does not replace cellular, provider relay, or physical routed-netem evidence.

For the controlled local relay-failure slice, run:

```bash
ANDROID_SERIAL=emulator-5554 ./test-lab/scripts/run-mock-relay-matrix.sh --profile emulator
```

The runner validates the relay matrix manifest, restarts the local lab unless `--skip-start` is set, installs the current debug APK unless `--skip-install` is set, then runs ready, invalid-credential, malformed-response, server-reset, and timeout scenarios through the Android debug diagnostics probe. It asserts DNS, HTTP, HTTPS, TCP, and UDP stay healthy while relay failures surface as typed `RELAY_NOT_READY` diagnostics. This is a fast local regression harness for relay readiness and fault handling; it does not replace provider-backed relay evidence.

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

CI runs `:app` integration tests and the macrobenchmark suite on **Gradle Managed Devices**
(the `android-instrumented-tests` and `android-macrobenchmark` jobs) — Pixel 6 / API 34,
`aosp-atd` + `google_apis`. To reproduce on a managed emulator instead of a connected device,
run `just test-instrumented` (or `./gradlew :app:pixel6Api34AtdGithubFullDebugAndroidTest`). The
shared device registry lives in `build-logic/convention/src/main/kotlin/RipDpiManagedDevices.kt`.

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

The Maestro pack covers cold launch, top-tab navigation, settings and advanced-settings mutation, config and mode-editor reachability, DNS mode/protocol/custom-field controls, secondary settings screens, diagnostics dashboard/scan/tools/history paths, route-preset history/logs coverage, permission-banner surfaces, connected proxy/VPN seeded state, and seeded diagnostics strategy reports. `RUN_MAESTRO_ROUTE_FLOWS=0` skips the route-preset launches when debugging only visible navigation; `RUN_MAESTRO_COMPLEX_FLOWS=1` adds complex stateful journeys for onboarding, config edit/connect, DNS persistence, diagnostics report drill-down, permission repair, activation-window editing, logs/history filters, biometric PIN fallback, and background guidance dismissal; `RUN_MAESTRO_LAB_FLOWS=1` adds the live network lab flows in `test-lab/maestro/` after the lab/device environment is prepared.

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

A separate golden set locks RIPDPI's importers against the sibling `ripdpi-vpn-deploy` repo's emitter output. Fully synthetic fixtures (RFC-5737 doc IPs, `-fixture` credentials) live under `core/data/src/test/resources/fleet-fixtures/<scenario>/`; each `meta.json` identifies whether the scenario is emitted or an explicit parser reference. The `FleetCompatGoldenFileTest` JVM suite parses each `bundle.json` through the production subscription parser and asserts the imported profiles, selector groups, and per-app routing rules.

The workflow reads the exact public deploy commit from `scripts/fleet-fixtures/deployer-git-sha.txt`, checks out that commit, and runs one closed cross-repo chain:

- `scripts/refresh-fleet-fixtures.sh` requires an exact clean deploy checkout, shims `terraform`, `sops`, and `wg` against checked-in synthetic inputs, runs its real `emit-bundle.sh`, then runs the deploy repository's `validate-bundle.py`. Seven scenarios are real emissions; only the port-hopping negative case remains a `parser-reference` with an explicit reason. `--check` diffs emitted scenarios against the committed bytes; `--write` refreshes them.
- `scripts/ci/check_fleet_fixtures.py` rejects placeholder pins, provenance mismatches, malformed artifacts, metadata drift, and production-token shapes.
- `fleet-fixtures.yml` materializes the real emitted bundles with `--write`, rejects any resulting Git diff, and then passes those materialized bytes to the JVM `*FleetCompat*` production parsers. This closes `emit -> validate -> app parse` in one job without substituting committed examples for emitter output.

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

Privileged TUN E2E:

```bash
RIPDPI_RUN_TUN_E2E=1 bash scripts/ci/run-linux-tun-e2e.sh
```

Privileged TUN soak wrapper:

```bash
RIPDPI_RUN_TUN_E2E=1 RIPDPI_SOAK_PROFILE=smoke \
  bash scripts/ci/run-linux-tun-soak.sh
```

The native workspace registers separate `linux_tun_e2e` and `linux_tun_soak` entrypoints. Both wrappers fail closed when the target, Linux host, or `/dev/net/tun` is unavailable. The E2E TCP case requires an exact echo roundtrip plus non-zero TUN TX/RX counters and fixture relay events. The soak wrapper records the resolved profile and iteration count in `effective-profile.txt` beside its log.

The scheduled/manual `so-bindtodevice-e2e` job is a separate physical-kernel
adversarial gate. It creates an isolated IPv4/IPv6 veth peer, a real interface
named `tun0`, and two subprocesses with distinct non-root UIDs. Unbound direct
TCP/UDP controls must avoid TUN and SOCKS, the allowed UID must round-trip exact
payloads through TUN and SOCKS, denied TCP must observe an actual outbound RST,
and denied UDP must produce no TUN reply, SOCKS event, or peer delivery. Run it
on a disposable privileged Linux host with:

```bash
test_binary="$(bash scripts/ci/build-so-bindtodevice-e2e.sh)"
evidence_dir=/tmp/so-bind
source_sha="$(git rev-parse HEAD)"
mkdir -p "$evidence_dir"
sudo env \
  RIPDPI_RUN_SO_BINDTODEVICE_E2E=1 \
  RIPDPI_SO_BIND_TEST_BINARY="$test_binary" \
  RIPDPI_SO_BIND_EVIDENCE_PATH="$evidence_dir/manifest.json" \
  RIPDPI_EVIDENCE_SOURCE_SHA="$source_sha" \
  RIPDPI_EVIDENCE_RUN_ID=1 \
  RIPDPI_EVIDENCE_RUN_ATTEMPT=1 \
  bash scripts/ci/run-so-bindtodevice-e2e.sh
sudo chown -R "$(id -u):$(id -g)" "$evidence_dir"
python3 scripts/ci/check_so_bindtodevice_evidence.py \
  --manifest "$evidence_dir/manifest.json" \
  --expected-source-sha "$source_sha" \
  --expected-run-id 1 \
  --expected-run-attempt 1
```

The build helper compiles and resolves the exact test executable as the calling
user. Only the runtime wrapper runs under root/CAP_NET_ADMIN, so Cargo caches and
the target directory stay runner-owned; evidence ownership is restored before
validation/upload. The lane treats missing `/dev/net/tun`, `setpriv`, IPv6,
unprivileged `SO_BINDTODEVICE`, the redacted manifest, or deterministic
namespace/interface cleanup as failures. Client helpers run with empty
supplementary groups, all five Linux capability masks cleared, and
`NoNewPrivs=1`. The manifest validator pins all 12 phase IDs and rejects a
timeout-only TCP denial in place of a physical RST.

Host-side native soak:

```bash
RIPDPI_SOAK_PROFILE=smoke bash scripts/ci/run-rust-native-soak.sh
```

Profiles:

- `smoke`: 50 start/stop/handover iterations for local/manual runs
- `full`: 500 iterations for scheduled CI

`RIPDPI_SOAK_ITERS=<positive integer>` overrides either profile explicitly.

## Known gaps & coverage roadmap

This section is the human-curated companion to the feature-test checklist. It records the small set of gaps where the test pyramid is intentionally thin or where work is still in flight. Sized so it can be re-verified in one pass.

### Verified closed (updated 2026-05-25)

Findings from earlier audits that were still open at the start of 2026-Q2 and have since landed. Re-verify before re-investigating.

- **B-1** -- "Unbounded retry loop in `connect_target_with_route`." Bounded in `native/rust/crates/ripdpi-proxy-runtime/src/runtime/routing/retry.rs` by `state.max_route_retries()`, default 8. Covered by `max_route_retries_default_is_eight` and `max_route_retries_is_customizable` unit tests in the same file.
- **C-1** -- "Dual adaptive systems (`adaptive_tuning` + `strategy_evolver`) uncoordinated." Resolved via a documented priority chain in `ripdpi-runtime-strategy/src/strategy_evolver.rs` and `ripdpi-runtime-adaptive/src/adaptive_tuning.rs`: evolver hints override per-flow adaptive hints when the evolver is enabled, otherwise per-flow cycling drives the dimensions.
- **E-1** -- "`adaptive_tuning.rs` has no dedicated unit tests." `#[cfg(test)] mod tests;` is declared and the module covers candidate cycling and dimension-order shuffling.
- **VPN/DNS-leak instrumentation matrix** -- landed as JVM coverage under `core/service/src/test/kotlin/com/poyka/ripdpi/services/`: `DnsLeakDetectorTest`, `DnsLeakReportTest`, `DnsPolicyNetworkSwitchTest`, and the `services/leak/*` matrix tests cover DNS, IPv6, lifecycle, revoked-credential, per-app, and transition leak cases.
- **HTTP-injection error-page probe** -- landed as `ripdpi-diagnostics-http::http_injection_probe` with classifier unit tests and the `ripdpi-diagnostics-probes::http_injection` adapter.
- **Owned-stack JA3/JA4 fingerprint snapshot** -- release CI runs `scripts/ci/check-owned-stack-tls-fingerprint.sh`, which captures the native owned-TLS fallback ClientHello against a loopback fixture and compares it with `contract-fixtures/owned_stack_tls_fingerprint_snapshot.json`. To intentionally accept a reviewed upstream TLS profile rotation, run `CARGO_TARGET_DIR=/tmp/ripdpi-owned-stack-fingerprint-target RIPDPI_REGENERATE_OWNED_STACK_TLS_FINGERPRINT_FIXTURE=1 cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-android-fetch-adapter owned_stack_tls_fingerprint_snapshot_matches_fixture -- --nocapture`, inspect the JSON diff for `ja4RipdpiV1`, `keyShareGroupsNoGrease`, and `containsX25519Mlkem768KeyShare`, then rerun `bash scripts/ci/check-owned-stack-tls-fingerprint.sh`.
- **D-1 adaptive strategy residual** -- `ripdpi-runtime-strategy` now includes adaptive timing jitter and OOB byte placement in the evolver combo identity and shared-prior pool; `ripdpi-runtime-adaptive` threads those hints into the morph policy.
- **A-1 ProbeExecutionContext enforcement** -- `ripdpi-diagnostics-runner::ProbeExecutionContext` now owns approved resolver policy and active transport config, and monitor connectivity/strategy DNS stages receive that context instead of rebuilding ad hoc direct resolver paths.
- **ECH for TLS outbounds** -- `ripdpi-tls-profiles::OutboundEchConfig` is wired through xHTTP and MASQUE. Boring-backed xHTTP/MASQUE H2 applies `SSL_set1_ech_config_list`; MASQUE H3 uses rustls ECH; ECH retry configs are surfaced as retry-required errors rather than silent cleartext-SNI fallback.
- **Telegram MTProto diagnostic** -- `ripdpi-diagnostics-telegram` reports download/upload status, WS tunnel status, and per-DC direct MTProto reachability using the shared `ripdpi-ws-tunnel` DC classifier with 443/80 port evidence and median RTT. Default transfer windows are 10s per direction with 3s stall detection.

### Open but tracked

These have task issues under `docs/tasks/issues/` and are sized for routine roadmap work, not new design.

- TUN UID validation against `SO_BINDTODEVICE` bypass (`add-tun2socks-uid-validation-against-so-bindtodevice-bypass.md`).

### Phase-16 real-world confidence status

The original infrastructure spike is closed and its task note was removed per task-board lifecycle rules. The L7 adversarial emulator and generator-driven packet-smoke follow-ups are complete: the synthetic-adversarial release lane and deterministic generated CLI packet-smoke samples are now repo-side release evidence. [`operate-phase16-real-provider-sim-runner.md`](tasks/issues/operate-phase16-real-provider-sim-runner.md) remains open for the operator-owned private SIM runner; the repo-side fail-closed runner contract exists, but real-provider confidence still requires a self-hosted runner with the `real-provider` and namespace labels to upload the required artifacts.

**L7 adversarial emulator v1 landed.** Dry-run behavior is verifiable on any host:

```bash
bash scripts/ci/run-l7-adversarial-dryrun.sh
```

The live `nfqueue` mode is documented with the emulator harness. Phase-16 now distinguishes `synthetic-lab`, `synthetic-adversarial`, and opt-in `real-provider` evidence in the matrix fixture, runner manifest, and pcap summary; release owners must keep those tiers separate when interpreting confidence.

- **Adversarial L7 emulator release gating.** `matrix_filter=l7_adversarial_emulator_v1_1` selects the synthetic-adversarial row on GitHub-hosted Linux without carrier hardware. The row writes `l7-adversarial/verdict-report.json`, links it from `phase16-pcap-summary.json`, and fails closed when any adversary-pattern cell reports `blocked`.
- **Generator-driven packet-smoke.** `scripts/ci/run-cli-packet-smoke.sh` runs all named CLI packet-smoke scenarios first, then generated samples from `scripts/ci/packet-smoke-generator.py`; PR/default runs use a bounded budget of 8 cells and scheduled runs use 64 cells unless overridden. Each generated fixture records `generator_seed`, `generator_axis_values`, and `generator_origin` in `fixture-manifest.json`.
- **Phase-16 lab matrix on real-provider SIMs.** The repo-side contract is implemented: filtered real-provider rows require `include_real_provider=true`, a readable `RIPDPI_PHASE16_REAL_PROVIDER_CONFIG` with `pcapScrubPolicy=required`, and an executable `RIPDPI_PHASE16_PREPARE_HOOK`; missing or invalid runner state writes `runner_unavailable` metadata. Actual real-provider confidence still requires the private self-hosted SIM runner to execute the filtered rows and upload the `phase16-<entry-id>` evidence artifact.

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
- `android-instrumented-tests` -- `:app` integration tests on Gradle Managed Devices (Pixel 6 / API 34, `aosp-atd` + `google_apis`), one runner per device
- `coverage` -- JaCoCo + Rust LLVM coverage
- `rust-turmoil` -- deterministic fault-injection network tests
- `rust-loom` -- exhaustive concurrency verification (20 min timeout)
- `cli-packet-smoke` -- CLI proxy behavioral verification with pcap capture
- `fleet-fixtures` -- exact deploy checkout + real bundle emission + deploy validation + `*FleetCompat*` production-parser suite, on PRs touching the subscription/routing/AWG/relay models or the fleet fixtures
- `l7-dryrun` -- L7 adversarial emulator matrix-runner dry-run + unittest suite, on PRs touching the harness or its CI script. Uploads `verdict-report.json` and per-cell `.pcap` artifacts for triage.
- `l7-live` -- L7 adversarial emulator live-mode smoke. Installs `nftables` and `python3-netfilterqueue` on an ubuntu-latest runner, loads the CI nft ruleset that funnels TCP:8443 into nfqueue 0, runs the live handler with a watchdog `--timeout-seconds`, sends a synthetic TLS ClientHello with a fixture-denylisted SNI, and asserts that the resulting `verdict-report.json` records at least one `blocked` cell. Uploads `verdict-report.json` and `handler.log` as artifacts.

Nightly/manual lanes add:

- `rust-native-soak` -- endurance tests (restart, sustained traffic, fault recovery)
- `rust-native-load` -- high-concurrency ramp-up, burst, and saturation tests
- `linux-tun-e2e` -- privileged TUN data-plane roundtrip tests in `ripdpi-tunnel-core --test linux_tun_e2e`
- `so-bindtodevice-e2e` -- privileged real-`tun0` UID-admission adversarial evidence with non-root clients
- `linux-tun-soak` -- privileged TUN endurance tests in `ripdpi-tunnel-core --test linux_tun_soak`
- `nightly-rust-coverage` -- coverage including ignored tests

The CI jobs upload test reports, golden diffs, logcat, fixture logs, soak/load artifacts, and coverage reports when available.

### Rust coverage lane

Run the Rust coverage lane locally with:

```bash
bash scripts/ci/run-rust-coverage.sh
```

or:

```bash
just coverage-rust
```

The script uses `cargo llvm-cov` against the full native workspace for execution, but the generated report is intentionally scoped to the native packages that carry the user-facing proxy, tunnel, diagnostics, WebSocket tunnel, and Android JNI facade surfaces: `ripdpi-ws-tunnel`, `ripdpi-proxy-runtime`, `ripdpi-tunnel-core`, `ripdpi-monitor-engine`, `ripdpi-diagnostics-classification`, and `ripdpi-android`. Reports are written under `native/rust/target/coverage/` as HTML, LCOV, text summary, JSON summary, and `metrics.env`.

Coverage enforcement is enabled in CI with `RIPDPI_ENFORCE_COVERAGE_THRESHOLDS=1`. The default minimum line threshold is `78%` (`RIPDPI_RUST_COVERAGE_MIN_LINE`), and `scripts/ci/rust-coverage-critical-files.txt` lists critical native files that must not fall to `0%` line coverage. The normal coverage lane skips privileged CAP_NET_ADMIN tests plus one host UDP readiness test that is unstable under `cargo llvm-cov` instrumentation; that UDP test remains covered by the non-instrumented workspace test lane.

Nightly/manual coverage sets `RIPDPI_RUST_COVERAGE_INCLUDE_IGNORED=1`, which adds ignored low-cost tests while still skipping real TUN E2E tests that are covered by the dedicated privileged Linux TUN lanes.

For a remote sign-off pass, follow the repository ruleset first: push local commits to a review branch, let the pull request checks run, and merge to `main` only after required reviews/checks pass. For final sign-off on the merged commit, use the default workflow-dispatch inputs unless a release owner explicitly asks for the heavier emulator, soak, load, coverage, benchmark, or private-corpus lanes:

```bash
gh workflow run ci.yml --ref main
gh workflow run local-network-lab.yml --ref main -f run_vpn_emulator_lane=false
gh workflow run offline-analytics.yml --ref main -f private_corpus_path=''
gh workflow run mutation-testing.yml --ref main -f packages='' -f in_diff=false
gh workflow run fuzz-nightly.yml --ref main -f fuzz_seconds=1800
```

`CodeQL` does not expose `workflow_dispatch`; it runs from the push to `main`. Record the run IDs and conclusions in `docs/feature-test-manual-evidence-template.md` under `Remote Workflows`, then record the final sign-off guard command/result in the template's final verdict section.
