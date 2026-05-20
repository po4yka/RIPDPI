# Diagnostics & Monitor Architecture

How RIPDPI scans a connection target, probes strategies, and produces a typed
verdict — and how the diagnostics and monitor crates are layered.

Companion docs: [`ARCHITECTURE.md`](ARCHITECTURE.md),
[`NATIVE_RUST.md`](NATIVE_RUST.md) (crate taxonomy),
[`RUNTIME_MODES.md`](RUNTIME_MODES.md) §3 (raw-path vs in-path scans),
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
  → probe execution         ripdpi-diagnostics-probes / -protocols, per protocol
  → result classification   ripdpi-diagnostics-classification (observations → verdict)
  → winner selection        ripdpi-diagnostics-runner        (confidence + coverage)
  → ScanReport              ripdpi-diagnostics-contracts wire types
```

The `ripdpi-monitor-engine` crate hosts the scan **session** and execution
loop; `ripdpi-android-diagnostics-adapter` bridges it to
`NetworkDiagnostics.kt` over JNI ([`JNI_CONTRACT.md`](JNI_CONTRACT.md)).

## Crate layering

| Role | Crates |
|------|--------|
| **Contract** (L2) | `ripdpi-diagnostics-contracts` — `ScanRequest`/`ScanReport`/progress wire types, `DIAGNOSTICS_ENGINE_SCHEMA_VERSION` |
| **Probe primitives** | `ripdpi-diagnostics-transport` — TCP-connect / TTL / WS-TLS |
| **Per-protocol probes** | `ripdpi-diagnostics-{tls, http, dns, fat-header, telegram}` |
| **Protocol-probe aggregation** | `ripdpi-diagnostics-protocols` (current), `ripdpi-diagnostics-net` (compat facade — no current consumer) |
| **Candidate planning** | `ripdpi-diagnostics-candidates` — strategy-probe candidate enumeration |
| **Classification** | `ripdpi-diagnostics-classification` — probe observations → verdict |
| **Probe-task execution** | `ripdpi-diagnostics-probes` — concrete probe tasks |
| **Support** | `ripdpi-diagnostics-parsers` (response parsers — no current consumer), `ripdpi-diagnostics-pcap` (PCAP recording) |
| **Scan runner** | `ripdpi-diagnostics-runner` — connectivity / strategy / domain scans |
| **Monitor engine** | `ripdpi-monitor-engine` — the active-scan engine (sessions, execution) |
| **Monitor adapters** | `ripdpi-monitor-adapter` (↔ contracts), `ripdpi-monitor-lane-adapter` (TCP/QUIC probe lanes), `ripdpi-monitor-proxy-runtime` (↔ passive proxy-runtime telemetry) |

All of the above are JNI-free; `ripdpi-android-diagnostics-adapter` (L8) is the
only JNI surface. Dependencies point inward — probe crates depend on contracts
and transport, never on the runner or the monitor engine.

## Wire contract & golden discipline

`DIAGNOSTICS_ENGINE_SCHEMA_VERSION` (`ripdpi-diagnostics-contracts/src/wire.rs`,
currently `1`) versions the `ScanRequest`/`ScanReport`/progress payloads. The
JVM `DiagnosticsContractGovernanceTest` and `ripdpi-monitor-engine`'s
`tests/contract_fixtures.rs` are golden contracts — a payload-shape change
requires a schema bump and a supervised re-bless. See
[`CONFIG_CONTRACTS.md`](CONFIG_CONTRACTS.md) §8 and
[`.claude/rules/golden-bless-discipline.md`](../../.claude/rules/golden-bless-discipline.md).

## Adding a probe

See [`FEATURE_EXTENSION_GUIDE.md`](FEATURE_EXTENSION_GUIDE.md) §3 — add the
probe to the matching `ripdpi-diagnostics-*` crate, register it in the runner /
lane adapter, bump the schema version if the wire shape changes, and update the
contract goldens under human supervision. The `diagnostics-system` skill owns
the deeper `ScanRequest`/`ProbeTask` detail.

---

## Cross-references

| Topic | Source |
|-------|--------|
| Whole-app architecture | [`ARCHITECTURE.md`](ARCHITECTURE.md) |
| Crate taxonomy & dependency direction | [`NATIVE_RUST.md`](NATIVE_RUST.md) |
| Raw-path vs in-path scans, home composite run | [`RUNTIME_MODES.md`](RUNTIME_MODES.md) §3 |
| Adding a diagnostics probe | [`FEATURE_EXTENSION_GUIDE.md`](FEATURE_EXTENSION_GUIDE.md) §3 |
| Diagnostics surface, candidates, home audit | [`AGENTS.md`](../../AGENTS.md) |
