# Diagnostics & Monitor Architecture

How RIPDPI scans a connection target, probes strategies, and produces a typed
verdict — and how the diagnostics and monitor crates are layered, registered,
and bound to the Android service lifecycle.

Companion docs: [`ARCHITECTURE.md`](ARCHITECTURE.md),
[`NATIVE_RUST.md`](NATIVE_RUST.md) (crate taxonomy),
[`RUNTIME_MODES.md`](RUNTIME_MODES.md) §3 (raw-path vs in-path scans),
[`JNI_CONTRACT.md`](JNI_CONTRACT.md) (the Kotlin/Rust boundary),
[`FEATURE_EXTENSION_GUIDE.md`](FEATURE_EXTENSION_GUIDE.md) §3 (adding a probe).

> **Crate-name note.** The old monolithic `ripdpi-monitor` crate no longer
> exists. It was decomposed into the `ripdpi-monitor-*` engine/adapter family
> and the `ripdpi-diagnostics-*` probe family described below. Treat any doc
> still saying `ripdpi-monitor` as historical.

---

## What diagnostics does

A scan probes each connection target independently and produces a typed
**verdict**, stored per network fingerprint and replayed when the network is
seen again ([`GLOSSARY.md`](GLOSSARY.md)):

- `TRANSPARENT_WORKS` — raw path works, no intervention needed.
- `OWNED_STACK_ONLY` — works only via the app's owned TLS stack.
- `NO_DIRECT_SOLUTION` — on-device mutation cannot recover it; relay required.
- `IP_BLOCK_SUSPECT` — IP-level block detected.

Scans run in two path modes (see [`RUNTIME_MODES.md`](RUNTIME_MODES.md) §3):
**raw-path** (VPN stopped, direct connection) and **in-path** (through the
active proxy/VPN). Scan kinds include `quick_v1` (fast recommendation) and
`full_matrix_v1` (audit with rotating target cohorts).

## Scan pipeline

```
ScanRequest
  → candidate planning      ripdpi-diagnostics-candidates   (TCP + QUIC lanes)
  → probe execution         ripdpi-diagnostics-probes, per protocol
  → result classification   ripdpi-diagnostics-classification (observations → verdict)
  → winner selection        ripdpi-diagnostics-runner        (confidence + coverage)
  → ScanReport              ripdpi-diagnostics-contracts wire types
```

The `ripdpi-monitor-engine` crate hosts the scan **session** and execution
loop; `ripdpi-android-diagnostics-adapter` bridges it to
`NetworkDiagnostics.kt` over JNI ([`JNI_CONTRACT.md`](JNI_CONTRACT.md)).

---

## Probe & candidate registration flow

Diagnostics has **no central registry and no `linkme` distributed slice**.
Registration is decentralized across four independent, hand-maintained
mechanisms. Adding a probe touches the relevant one(s) directly; nothing is
discovered at link time.

### 1. Scan stage runners — the execution loop

The production scan loop is owned by `ripdpi-monitor-engine`. A scan is a
sequence of **stages**; each stage is one `ExecutionStageRunner`
(`engine/runtime/stage.rs` — `id() -> ExecutionStageId`, `phase()`,
`total_steps()`, `run()` / `run_collecting()`).

Stage runners are registered through a **descriptor/factory platform** —
`PROBE_STAGE_REGISTRATIONS` in
`ripdpi-monitor-engine/src/engine/runners/registry.rs`. Each connectivity
stage is one `ProbeStageRegistration` row carrying the descriptor metadata
inline (`probe_type`, `probe_id`, `runner_name`, `label`), the engine-side
scheduling metadata (`ExecutionStageId`, optional `ProbeTaskFamily`
selector), and a runner factory:

```rust
ProbeStageRegistration {
    probe_type: "dns_integrity",
    probe_id: "dns_integrity_probe",
    stage_id: ExecutionStageId::Dns,
    task_family_selector: Some(ProbeTaskFamily::Dns),
    runner_name: "DnsRunner",
    label: "DNS integrity",
    make_runner: || Box::new(DnsRunner),
},
```

The descriptor fields are inlined here rather than imported from
`ripdpi-diagnostics-probes` because monitor-engine deliberately does not
depend on a concrete diagnostics lane crate — the lane-adapter is the seam,
enforced by `scripts/ci/check_native_architecture_contracts.py`. Drift
between this table and the `PROBE_DESCRIPTORS` /
`SCHEDULED_PROBE_INVENTORY` tables in `ripdpi-diagnostics-probes` is covered
transitively: the frozen `(runner, phase, artifact_source)` table in
`engine/runners/parity.rs` pins each runner's `probe_type`
(`ARTIFACT_SOURCE`), and the probes crate's drift tests pin descriptors to
its inventory.

`execution_coordinator()` in `engine/runners/mod.rs` iterates the registry to
build the connectivity portion of the runners vector, then appends the 5
strategy runners (`StrategyDnsBaselineRunner`, `StrategyTcpRunner`,
`StrategyQuicRunner`, `StrategyRecommendationRunner`,
`StrategyConnectionConcurrencyRunner`) — those are
intentionally **not** registered because they are hand-composed strategy-stage
runners rather than `Probe`-trait connectivity registrations. Only the TCP and
QUIC runners consume `StrategyCandidateSpec` inputs and take a
`CandidateRuntimeLauncher`; the DNS baseline, recommendation, and connection-
concurrency runners are unit structs. `connectivity_stage_order()`
in `engine/plan.rs` derives `stage_order` from the same registry: it filters
for always-on entries first (today only `Environment`), then either iterates
the user-supplied `probe_tasks` (deduplicated) or the registration order for
the default scan. The engine-side drift tests in
`engine/runners/registry.rs` pin the registry to a frozen canonical
sequence. Adding a connectivity probe is now a one-line addition to
`PROBE_STAGE_REGISTRATIONS`; the central runner vector and the
`connectivity_stage_order` family `match` are gone.

`ExecutionCoordinator` (`engine/runtime/coordinator.rs`) keys the runners by
`ExecutionStageId` into a `BTreeMap` and walks `plan.stage_order`. The DNS /
TCP / QUIC connectivity stages run **concurrently** in a `std::thread::scope`
when the plan marks them parallel — that concurrency is part of the engine's
fixed scheduling and is **not** a per-probe knob. The connectivity runners
(`DnsRunner` … `ThroughputRunner`) are generated by the
`impl_connectivity_runner!` macro in `engine/runners/connectivity.rs`,
which binds a runner type to a `ConnectivityProbeFamily` and an
`ExecutionStageId`.

> The `ExecutionCoordinator` / `ProbeFamilyRunner` pair in
> `ripdpi-diagnostics-runner/src/domain.rs` is a **separate, narrower
> lane-registration contract** used inside the runner crate. The production
> scan loop is the monitor-engine `ExecutionStageRunner` coordinator above.

### 2. Lane adapters — the probe-crate wiring seam

`ripdpi-monitor-lane-adapter` is the seam between the `ripdpi-diagnostics-*`
probe crates and the engine. It exposes a **static descriptor table**,
`LANE_ADAPTERS: &[LaneAdapter]`, in
`ripdpi-monitor-lane-adapter/src/lanes.rs` — one `LaneAdapter { name,
module_path, source_crate }` row per adapter module (`candidates`, `http`,
`tls`, `telegram`, `transport`, `connectivity`, `strategy`,
`blockpage_fingerprints`, `cdn_ech`, `classification`, `observations`). The
`adapters` module re-exports each probe crate behind an engine-compatible
function surface. The table is metadata for inventory and audit; the engine
calls the adapter functions directly.

### 3. Concrete probes — the `Probe` trait

`ripdpi-diagnostics-probes` defines the narrow per-probe contract — the
`Probe` trait (`src/lib.rs`):

```rust
pub trait Probe {
    fn id(&self) -> &'static str;          // stable, golden/telemetry contract
    fn family(&self) -> ProbeTaskFamily;
    fn run(&self, ctx: &ProbeContext) -> ProbeOutcome;
}
```

A probe is stateless: it captures its parameters at construction and the
runner invokes `run()` once per tick with a `ProbeContext` (the *active*
network scope / resolver / relay / strategy hints — so a probe validates the
user's real path, not a hard-coded baseline). The 17 concrete probe structs
are plain modules re-exported from `src/probes.rs`; each owns a
`pub const <NAME>_PROBE_ID: &str`. The ECH implementation also exports a
driver (`HickoryRustlsEchHandshakeDriver`) alongside the probe structs. The
`compat-facade` feature is still an empty default-on compatibility marker;
root exports are unconditional today.

### 4. Strategy candidates — `StrategyCandidateSpec`

The strategy probe tests an ordered matrix of strategy configurations.
`ripdpi-diagnostics-candidates` plans them: `StrategyCandidateSpec`
(`src/candidates/types.rs`) is a static metadata struct per configuration,
and `build_strategy_probe_suite(suite_id, base)` (`src/candidates/suite.rs`)
returns a `StrategyProbeSuite` (TCP + QUIC candidate `Vec`s) for a named
suite — `quick_v1` (current TCP and QUIC pools, short-circuit on a host-fake
win) or `full_matrix_v1` (the quick pools plus lab/audit-only variants).
Candidate counts are builder- and capability-dependent: TCP Fast Open and IP
fragmentation candidates are added only when the platform probes allow them.
Candidates are a **plan**, not an executor: the spec describes the required
packet-level behaviour and the runtime capabilities it needs; the engine builds
the emitter from it.

---

## The probe descriptor seam

`ripdpi-diagnostics-probes` now owns the scheduled connectivity descriptor
table. The descriptor-shaped types that exist, by layer:

| Layer | Descriptor-shaped type | Shape |
|-------|------------------------|-------|
| Strategy candidate | `StrategyCandidateSpec` (`ripdpi-diagnostics-candidates`) | A full static descriptor — id, label, family, emitter tier, `requires_fake_ttl` / `requires_tcp_fast_open` / `requires_capabilities`, eligibility, warmup, config. **This is the canonical descriptor pattern in diagnostics.** |
| Lane adapter | `LaneAdapter` + `LANE_ADAPTERS` (`ripdpi-monitor-lane-adapter`) | A static `&[LaneAdapter]` inventory table — name, module path, source crate. Read-only metadata. |
| Concrete connectivity probe | the `Probe` trait + per-probe `*_PROBE_ID` const | Backing contract for each scheduled connectivity stage. |
| Scheduled connectivity stage | `ProbeDescriptor` + `PROBE_DESCRIPTORS` (`ripdpi-diagnostics-probes`) | One static row per scheduled connectivity stage: probe id, family, scheduled `probe_type`, runner name, path-mode requirement, and label. Drift tests pin the table to `SCHEDULED_PROBE_INVENTORY`. |
| Monitor-engine stage runner | `ProbeStageRegistration` (`ripdpi-monitor-engine`) | The runtime scheduler registry. It mirrors descriptor fields without importing the probes crate; parity tests and the probes crate drift tests pin the seam. |

`PROBE_DESCRIPTORS` intentionally covers only the 10 scheduled connectivity
stages. The 5 hand-composed strategy runners remain out of scope because they
do not implement the `Probe`-trait connectivity registration contract; only
the TCP and QUIC runners consume `StrategyCandidateSpec`. For new connectivity
probes, add the backing `Probe`, the scheduled-inventory row, and
the matching `ProbeDescriptor` row. For new strategy candidates, extend
`StrategyCandidateSpec` instead. See [`FEATURE_EXTENSION_GUIDE.md`](FEATURE_EXTENSION_GUIDE.md)
§3, "The probe registration seam".

---

## Raw-path vs in-path requirements

`ScanPathMode` (`ripdpi-diagnostics-contracts/src/types/scan.rs`, serialized
`SCREAMING_SNAKE_CASE`) is carried on both `ScanRequest` and `ScanReport`:

```rust
pub enum ScanPathMode { RawPath, InPath }
```

**Raw-path** (`RAW_PATH`). The diagnostics path **stops the VPN service
before probing** and connects **directly** — there is no TUN device.
Consequences a probe author must honour:

- `setsockopt(IP_TTL)` and fake-packet techniques work **without** a
  `protect()` callback, because stopping the service unregisters both protect
  mechanisms (see [`AGENTS.md`](../../AGENTS.md) § VPN Socket Protection and
  [`.claude/rules/vpnservice-protect-invariant.md`](../../.claude/rules/vpnservice-protect-invariant.md)).
- A scan can be cancelled mid-flight; partial results are recovered by a
  short grace-period poll after cancellation. Probes must finalize cleanly.
- Strategy candidates gate on runtime capability, not on path mode alone:
  `StrategyCandidateSpec::requires_fake_ttl`, `requires_tcp_fast_open`, and
  `requires_capabilities: &[RuntimeCapability]` declare what the platform
  must support. `enumerate_capable_candidates()` filters a candidate pool
  against a live capability lookup before a winner is promoted — a candidate
  whose capability is unavailable is skipped, never failed.

**In-path** (`IN_PATH`). Probes run **through the active proxy or VPN path**,
measuring targets exactly as the user's traffic experiences them; the running
service is left intact. Outbound sockets a probe opens are therefore subject
to the normal `protect()` invariant and the active desync/relay policy.

In both modes a probe **must classify cleanly on non-rooted devices** — the
non-root baseline applies: degrade to an approximate or inconclusive verdict,
never crash. `ProbeVerdict::Inconclusive` exists for exactly this and must
not drive an automatic strategy change. Classification itself consults the
path mode: `classify_probe_outcome(probe_type, path_mode, outcome)`
(`ripdpi-diagnostics-contracts/src/util/outcome_policy.rs`) can map the same
raw outcome to a different diagnosis on `RAW_PATH` vs `IN_PATH`.

---

## Diagnostics, the service lifecycle, and policy memory

### Service lifecycle

A scan is a JNI-handle-scoped session. `NetworkDiagnostics.kt` drives the
boundary — `jniCreate` / `jniStartScan` / `jniPollProgress` / `jniTakeReport`
/ `jniPollPassiveEvents` / `jniCancelScan` / `jniDestroy` — backed by a
`MonitorSession` registered in `ripdpi-android-diagnostics-adapter`'s handle
registry. These seven method names and signatures are the **Kotlin-visible
diagnostics contract** and are immutable without a coordinated JNI change.

The session reaches the host VPN service only through the
`MonitorPlatformBridge` trait (`ripdpi-monitor-engine/src/platform.rs`):

- `drain_passive_events()` — surfaces **passive** telemetry from the live
  proxy/VPN session into the scan report, so a running service contributes
  evidence without a dedicated active probe.
- `clear_passive_events()` — resets that buffer at scan start.
- `scoped_log_level()` — raises log verbosity for the scan's scope only.

The default `NoopMonitorPlatformBridge` does nothing; the Android
implementation binds these to the live service. Path mode and the lifecycle
interact directly: a **raw-path** scan stops the `VpnService` before probing,
and a DNS-corrected re-probe waits for the service to resume before its
second pass; an **in-path** scan leaves the service running. A VPN halt
mid-stage marks that stage `FAILED` (the home composite run sequences nine
stages and a failed audit stage skips the rest — see
[`RUNTIME_MODES.md`](RUNTIME_MODES.md) §3).

Process death is always possible (LMK `SIGKILL`, no Drop) — diagnostics state
that must survive a kill is persisted by Kotlin, not held only in the native
session. See [`.claude/rules/android-vpn-lifecycle.md`](../../.claude/rules/android-vpn-lifecycle.md).

### Policy memory

A scan verdict feeds the **per-network policy cache**. Results are persisted
by `:core:diagnostics-data` and keyed by a **SHA-256 network fingerprint
hash** — never a raw `BSSID` / `SSID` / carrier name (see
[`.claude/rules/network-fingerprint-privacy.md`](../../.claude/rules/network-fingerprint-privacy.md)).
The `RememberedNetworkPolicyStore` runs a small state machine over each
remembered policy — `OBSERVED → VALIDATED → APPLIED → SUCCESS / FAILURE` —
so a strategy proven on a network is replayed when that fingerprint is seen
again, and a policy that regresses is suppressed. Diagnostics is the
*producer* of these entries; the runtime is the *consumer*. The producer side
must keep its persisted artifacts inside the privacy bounds above — that
declaration is load-bearing for the Play Store Data Safety surface.

---

## Crate layering

| Role | Crates |
|------|--------|
| **Contract** (L2) | `ripdpi-diagnostics-contracts` — `ScanRequest`/`ScanReport`/progress wire types, `ScanPathMode`, `DIAGNOSTICS_ENGINE_SCHEMA_VERSION` |
| **Probe primitives** | `ripdpi-diagnostics-transport` — TCP-connect / TTL / WS-TLS |
| **Per-protocol probes** | `ripdpi-diagnostics-{tls, http, dns, fat-header, telegram}` |
| **Candidate planning** | `ripdpi-diagnostics-candidates` — `StrategyCandidateSpec` enumeration |
| **Classification** | `ripdpi-diagnostics-classification` — probe observations → verdict |
| **Probe-task execution** | `ripdpi-diagnostics-probes` — the `Probe` trait + concrete probe tasks |
| **Scan runner** | `ripdpi-diagnostics-runner` — connectivity / strategy / domain scans, budget, winner selection |
| **Monitor engine** | `ripdpi-monitor-engine` — the active-scan engine (sessions, the `ExecutionStageRunner` loop) |
| **Monitor adapters** | `ripdpi-monitor-adapter` (↔ contracts), `ripdpi-monitor-lane-adapter` (`LANE_ADAPTERS` probe wiring), `ripdpi-monitor-proxy-runtime` (↔ passive proxy-runtime telemetry) |

All of the above are JNI-free; `ripdpi-android-diagnostics-adapter` (L8) is the
only JNI surface. Dependencies point inward — probe crates depend on contracts
and transport, never on the runner or the monitor engine.

## Wire contract & golden discipline

`DIAGNOSTICS_ENGINE_SCHEMA_VERSION` (`ripdpi-diagnostics-contracts/src/wire.rs`,
currently `5`) versions the `ScanRequest`/`ScanReport`/progress payloads. The
JVM `DiagnosticsContractGovernanceTest` and `ripdpi-monitor-engine`'s
`tests/contract_fixtures.rs` are golden contracts — a payload-shape change
requires a schema bump and a supervised re-bless. See
[`CONFIG_CONTRACTS.md`](CONFIG_CONTRACTS.md) §9 and
[`.claude/rules/golden-bless-discipline.md`](../../.claude/rules/golden-bless-discipline.md).

## Adding a probe

See [`FEATURE_EXTENSION_GUIDE.md`](FEATURE_EXTENSION_GUIDE.md) §3 — add the
probe to the matching `ripdpi-diagnostics-*` crate, register it in the
appropriate seam from the *registration flow* above (a stage runner, a lane
adapter, a `Probe` impl, or a `StrategyCandidateSpec`), bump the schema
version if the wire shape changes, and update the contract goldens under
human supervision. The `diagnostics-system` skill owns the deeper
`ScanRequest`/`ProbeTask` detail.

---

## Cross-references

| Topic | Source |
|-------|--------|
| Whole-app architecture | [`ARCHITECTURE.md`](ARCHITECTURE.md) |
| Crate taxonomy & dependency direction | [`NATIVE_RUST.md`](NATIVE_RUST.md) |
| Raw-path vs in-path scans, home composite run | [`RUNTIME_MODES.md`](RUNTIME_MODES.md) §3 |
| Kotlin/Rust boundary, handle lifecycle | [`JNI_CONTRACT.md`](JNI_CONTRACT.md) |
| Adding a diagnostics probe | [`FEATURE_EXTENSION_GUIDE.md`](FEATURE_EXTENSION_GUIDE.md) §3 |
| Network fingerprint privacy bounds | [`.claude/rules/network-fingerprint-privacy.md`](../../.claude/rules/network-fingerprint-privacy.md) |
| VPN service lifecycle invariants | [`.claude/rules/android-vpn-lifecycle.md`](../../.claude/rules/android-vpn-lifecycle.md) |
| Diagnostics surface, candidates, home audit | [`AGENTS.md`](../../AGENTS.md) |
