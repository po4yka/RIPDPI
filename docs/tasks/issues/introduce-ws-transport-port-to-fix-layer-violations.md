---
id: RST-1786264762917569
title: Introduce a WsTransport port to fix L6/L4 -> L7 dependencies on ripdpi-ws-tunnel
kind: feature
status: dropped
area: rust-native
priority: medium
owner: unassigned
parent: EPC-1786264762917503
blocked_by: []
spec_mode: required
openspec_change: rst-1786264762917569-introduce-ws-transport-port-to-fix-layer-violations
created: 2026-06-10
updated: 2026-08-09
source_wiki_pages: []
linked_task: null
closed_at: "2026-08-09T11:12:16Z"
closed_reason: obsolete architecture premise
evidence_summary: The compared WebSocket transports are same-peer protocol tiers, and the architecture validator reports zero current violations for this premise.
---

## Motivation

The 2026-06-10 architecture audit found two **new** actionable layering violations (both upward dependencies into the relay-transport layer L7):

- **R-1 (MEDIUM)** — `ripdpi-diagnostics-telegram` (L6 diagnostics) depends directly on `ripdpi-ws-tunnel` (L7 transport impl). A diagnostics probe reaching into a concrete transport breaks if the WS tunnel is split or reimplemented.
- **R-2 (MEDIUM)** — `ripdpi-ws-bootstrap` (L4 runtime orchestrator) imports `ripdpi-ws-tunnel` (L7) directly. Bootstrap orchestration and the concrete transport should be decoupled by a port trait.

Both dependency graphs are otherwise clean DAGs with no cycles; these two edges make `ripdpi-ws-tunnel` a hidden coupling point between runtime orchestration, diagnostics, and relay transport.

## Proposed change

1. Add a small port crate (e.g. `ripdpi-ws-transport-port`) at L2/L4 defining a `WsTransport` trait covering what `ws-bootstrap` and `diagnostics-telegram` actually consume.
2. Implement the trait in `ripdpi-ws-tunnel`.
3. Repoint `ripdpi-ws-bootstrap` and `ripdpi-diagnostics-telegram` at the port crate, not the implementation.
4. This is a ~1–2 day mechanical refactor with no behavior change.

## Acceptance criteria

- [ ] PR confirms the two edges still exist in `cargo metadata`.
- [ ] New port crate defines the trait; `ripdpi-ws-tunnel` implements it.
- [ ] Neither `ripdpi-ws-bootstrap` nor `ripdpi-diagnostics-telegram` lists `ripdpi-ws-tunnel` as a direct dep afterward.
- [ ] `arch-layer-auditor` re-run reports R-1 and R-2 resolved, no new cycle.
- [ ] `cargo nextest run --locked` green for affected crates; `cargo deny check` clean.

## Risks / open questions

- Keep the trait minimal — only what the two consumers need; do not abstract speculatively.
- New crate adds a small build-time cost; justified by removing the cross-layer coupling.

## References

- Audit memory `project_native_audit_findings.md` (2026-06-10, item 11 / R-1, R-2).
- `docs/architecture/NATIVE_RUST.md` (layer taxonomy).
- `ws-tunnel-telegram` skill.
