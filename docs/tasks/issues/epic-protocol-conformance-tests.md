---
id: EPC-1786264762917455
title: Epic - Protocol conformance and regression tests
kind: epic
status: doing
area: epic
priority: medium
owner: unassigned
parent: null
blocked_by: []
spec_mode: required
openspec_change: epc-1786264762917455-epic-protocol-conformance-tests
created: 2026-06-10
updated: 2026-07-26
source_wiki_pages: []
linked_task: null
---

## Goal

Lock the on-wire correctness of RIPDPI's transport stack with golden-fixture conformance tests, cross-stack chain tests, throughput benchmarks, and protocol-behavior regression tests. These guard the *wire contract* layer; the completed orchestration-test epic remains available in git history.

## Why now

The conformance tasks share one infrastructure substrate (`contract-fixtures/`, the `local-network-fixture` loopback crates, and the `protocol-throughput` Criterion bench group). VLESS Reality mux and cross-stack coverage have landed; upstream-pinned Salamander vectors remain the external fixture gap.

## Key decisions

- **Separate from orchestration/lifecycle failure injection.** Wire conformance is a different test layer; the completed orchestration epic is retained in git history.
- **Golden fixtures are pinned to upstream tags.** Capture reference vectors from the upstream project at a named tag and commit under `contract-fixtures/<proto>/<tag>/`, governed by `golden-bless-discipline.md`.
- **Cross-stack chain tests use the real backends over loopback**, not mocks, so a per-crate change that breaks a stacked combination fails the chain test even when per-crate tests pass.

## Scope

- **In scope:** VLESS mux golden conformance + multi-stream interleave (now folded into the cross-stack task), Hysteria2 Salamander obfuscation vectors, QUIC path-MTU discovery regression, cross-stack VLESS-over-xHTTP-over-Reality chains, and per-transport throughput benchmarks with CI baseline capture.
- **Out of scope:** fuzz targets (already landed for the xHTTP FinalMask decoder), orchestration/lifecycle injection (its own epic), and live on-device network probes (operator/device-gated).

## Child tasks

- VLESS cross-stack and Reality mux coverage — **done**: the sing-mux/yamux carrier, multi-stream interleave, and relay-core Reality mux fixture are covered by current tests; the former audit task is closed in git history.
- [[add-hysteria2-salamander-obfuscation-conformance-fixtures]] — **done** (2026-10-31): nine upstream app/v2.13.0 vectors passed encode, decode, and mutation checks.
- QUIC PMTUD regression — **done** (2026-07-16), task closed (git history is the audit trail): Hysteria 2, TUIC, and MASQUE/H3 exercise the production clients against `MtuDropSocket`; MASQUE covers IPv4/IPv6 CONNECT-UDP payload integrity, typed datagram sizing, black-hole detection, recovery telemetry, and redacted drop evidence.
- Per-transport throughput benchmarks — **done** (2026-06-11), task closed (no issue file; git history is the audit trail): 7/7 benches wired; the `protocol-throughput/*` baseline was captured on the CI reference runner and committed to `scripts/ci/rust-bench-baseline.json`, arming the nightly enforced lane (see the Ship-definition item below).

## Ship definition

- [ ] Every transport has at least eight upstream-pinned golden fixtures where a conformance bar applies, each round-tripped.
- [x] The cross-stack chain test covers single-stream and VLESS Reality mux/multi-stream behavior.
- [x] Throughput baselines for all seven transports are captured in `scripts/ci/rust-bench-baseline.json` and enforced. **Done 2026-06-11** (reference-runner capture; nightly enforced lane armed).
- [ ] A deliberate framing/behavior regression in any covered layer fails a named test. Salamander, VLESS mux, and QUIC PMTUD checks now run. See the fixture and WS throughput gaps below.

## Risks / open questions

- VLESS wire-mux is no longer a blocker.
- Sourcing upstream reference vectors (Hysteria2 Salamander) depends on external repos at pinned tags.

## References

- `golden-bless-discipline.md`, `contract-fixtures/README.md`, `diagnostics-system` skill.
- Completed orchestration-test epic: available in git history.

## Acceptance gaps on 2026-11-24

The upstream fixture pin check passes. The Salamander child landed nine
upstream Hysteria 2 app/v2.13.0 vectors with encode/decode and a mutation
check. VLESS has eight pinned sing-mux encoder comparisons. A round-trip
through a compatible decoder for those eight records has yet to be shown.
The committed throughput baseline covers seven benchmarks. The scheduled
hard gate excludes `ws_tunnel_1MiB` because variance on the shared runner
exceeds its threshold. Closure requires repeatable WS measurements with
an enforced result and evidence for the outstanding fixture checks.
