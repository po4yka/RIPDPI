---
title: "Epic - Protocol conformance and regression tests"
type: epic
status: doing
area: epic
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-06-10
updated: 2026-07-16
source_wiki_pages: []
linked_task: null
---

## Goal

Lock the on-wire correctness of RIPDPI's transport stack with golden-fixture conformance tests, cross-stack chain tests, throughput benchmarks, and protocol-behavior regression tests. These guard the *wire contract* layer — distinct from `epic-orchestration-test-posture`, which owns service/lifecycle failure injection and explicitly excludes parser/conformance coverage.

## Why now

The conformance tasks were drifting as parentless orphans on the board even though they share one infrastructure substrate (`contract-fixtures/`, the `local-network-fixture` loopback crates, the `protocol-throughput` Criterion bench group). Grouping them makes the gap visible: most have exactly one golden fixture where the acceptance bar is eight, and the VLESS mux datapath blocks two of them. A single epic surfaces that shared blocker and the shared fixture-capture work.

## Key decisions

- **Separate from `epic-orchestration-test-posture`.** That epic is scoped to orchestration/lifecycle failure injection (`OrchestrationFailureHarness`, fake clock, corrupt-file fixtures). Wire conformance is a different test layer; conflating them would muddy both.
- **Golden fixtures are pinned to upstream tags.** Capture reference vectors from the upstream project at a named tag and commit under `contract-fixtures/<proto>/<tag>/`, governed by `golden-bless-discipline.md`.
- **Cross-stack chain tests use the real backends over loopback**, not mocks, so a per-crate change that breaks a stacked combination fails the chain test even when per-crate tests pass.

## Scope

- **In scope:** VLESS mux golden conformance + multi-stream interleave (now folded into the cross-stack task), Hysteria2 Salamander obfuscation vectors, QUIC path-MTU discovery regression, cross-stack VLESS-over-xHTTP-over-Reality chains, and per-transport throughput benchmarks with CI baseline capture.
- **Out of scope:** fuzz targets (already landed for the xHTTP FinalMask decoder), orchestration/lifecycle injection (its own epic), and live on-device network probes (operator/device-gated).

## Child tasks

- [[add-protocol-cross-stack-chain-tests-vless-over-xhttp-over-reality]] — single-stream done; mux criterion (absorbed from the former vless-mux task) **still blocked on the unimplemented VLESS wire-mux datapath** (feature work, not test work). This is the blocker keeping the epic open.
- [[add-hysteria2-salamander-obfuscation-conformance-fixtures]] — **externally-gated** (2026-06-11): harness shipped + passing; the 8 conformance goldens need real `apernet/hysteria` byte vectors at a pinned tag, unavailable locally and not fabricable. `status: blocked`.
- QUIC PMTUD regression — **done** (2026-07-16), task closed (git history is the audit trail): Hysteria 2, TUIC, and MASQUE/H3 exercise the production clients against `MtuDropSocket`; MASQUE covers IPv4/IPv6 CONNECT-UDP payload integrity, typed datagram sizing, black-hole detection, recovery telemetry, and redacted drop evidence.
- Per-transport throughput benchmarks — **done** (2026-06-11), task closed (no issue file; git history is the audit trail): 7/7 benches wired; the `protocol-throughput/*` baseline was captured on the CI reference runner and committed to `scripts/ci/rust-bench-baseline.json`, arming the nightly enforced lane (see the Ship-definition item below).

## Ship definition

- [ ] Every transport has at least eight upstream-pinned golden fixtures where a conformance bar applies, each round-tripped.
- [ ] The cross-stack chain test covers both single-stream and (once VLESS wire-mux lands) two-stream mux.
- [x] Throughput baselines for all seven transports are captured in `scripts/ci/rust-bench-baseline.json` and enforced. **Done 2026-06-11** (reference-runner capture; nightly enforced lane armed).
- [ ] A deliberate framing/behavior regression in any covered layer fails a named test. *Partial:* the QUIC PMTUD lane (Hysteria 2 + TUIC + MASQUE/H3) and the per-transport throughput gate now contribute; full coverage still pending the mux + Salamander criteria.

## Risks / open questions

- The VLESS wire-mux datapath is the shared blocker for the mux conformance and the two-stream cross-stack criterion; it is feature work, not test work.
- Sourcing upstream reference vectors (Hysteria2 Salamander) depends on external repos at pinned tags.

## References

- `golden-bless-discipline.md`, `contract-fixtures/README.md`, `diagnostics-system` skill.
- Sibling: `epic-orchestration-test-posture` (lifecycle/orchestration injection).
