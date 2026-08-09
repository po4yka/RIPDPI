---
id: EPC-1786264762917455
title: Epic - Protocol conformance and regression tests
kind: epic
status: dropped
area: epic
priority: medium
owner: unassigned
parent: null
blocked_by: []
spec_mode: required
openspec_change: epc-1786264762917455-epic-protocol-conformance-tests
created: 2026-06-10
updated: 2026-08-09
source_wiki_pages: []
linked_task: null
closed_at: "2026-08-09T11:12:19Z"
closed_reason: epic merged into its sole active child
evidence_summary: Only the Salamander byte-vector conformance task remains, now tracked directly without a one-child epic wrapper.
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
- [[add-hysteria2-salamander-obfuscation-conformance-fixtures]] — **externally-gated** (2026-06-11): harness shipped + passing; the 8 conformance goldens need real `apernet/hysteria` byte vectors at a pinned tag, unavailable locally and not fabricable. `status: blocked`.
- QUIC PMTUD regression — **done** (2026-07-16), task closed (git history is the audit trail): Hysteria 2, TUIC, and MASQUE/H3 exercise the production clients against `MtuDropSocket`; MASQUE covers IPv4/IPv6 CONNECT-UDP payload integrity, typed datagram sizing, black-hole detection, recovery telemetry, and redacted drop evidence.
- Per-transport throughput benchmarks — **done** (2026-06-11), task closed (no issue file; git history is the audit trail): 7/7 benches wired; the `protocol-throughput/*` baseline was captured on the CI reference runner and committed to `scripts/ci/rust-bench-baseline.json`, arming the nightly enforced lane (see the Ship-definition item below).

## Ship definition

- [ ] Every transport has at least eight upstream-pinned golden fixtures where a conformance bar applies, each round-tripped.
- [x] The cross-stack chain test covers single-stream and VLESS Reality mux/multi-stream behavior.
- [x] Throughput baselines for all seven transports are captured in `scripts/ci/rust-bench-baseline.json` and enforced. **Done 2026-06-11** (reference-runner capture; nightly enforced lane armed).
- [ ] A deliberate framing/behavior regression in any covered layer fails a named test. *Partial:* VLESS mux, QUIC PMTUD, and throughput gates contribute; full coverage still awaits Salamander fixtures.

## Risks / open questions

- VLESS wire-mux is no longer a blocker.
- Sourcing upstream reference vectors (Hysteria2 Salamander) depends on external repos at pinned tags.

## References

- `golden-bless-discipline.md`, `contract-fixtures/README.md`, `diagnostics-system` skill.
- Completed orchestration-test epic: available in git history.
