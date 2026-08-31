---
id: RST-1786264762917569
title: Introduce a WsTransport port to fix L6/L4 -> L7 dependencies on ripdpi-ws-tunnel
kind: feature
status: doing
area: rust-native
priority: medium
owner: codex
parent: EPC-1786264762917503
blocked_by: []
spec_mode: required
openspec_change: rst-1786264762917569-introduce-ws-transport-port-to-fix-layer-violations
created: 2026-06-10
updated: 2026-08-30
source_wiki_pages: []
linked_task: null
---

## Motivation

The 2026-06-10 architecture audit found two **new** actionable layering violations (both upward dependencies into the relay-transport layer L7):

- **R-1 (MEDIUM)** — `ripdpi-diagnostics-telegram` (L6 diagnostics) depends directly on `ripdpi-ws-tunnel` (L7 transport impl). A diagnostics probe reaching into a concrete transport breaks if the WS tunnel is split or reimplemented.
- **R-2 (MEDIUM)** — `ripdpi-ws-bootstrap` (L4 runtime orchestrator) imports `ripdpi-ws-tunnel` (L7) directly. Bootstrap orchestration and the concrete transport should be decoupled by a port trait.

Both dependency graphs are otherwise clean DAGs with no cycles; these two edges make `ripdpi-ws-tunnel` a hidden coupling point between runtime orchestration, diagnostics, and relay transport.

## Work ownership (2026-08-30)

- `codex` owns the port trait design, architecture documentation,
  OpenSpec/task lifecycle, integration, commits, and push.
- `implement_ws_transport_port` owns the production Rust sources and Cargo
  manifests/lockfile needed to apply that fixed design. It must not edit task,
  OpenSpec, architecture, or legal-facing Markdown.
- `map_ws_transport_port` and `audit_ws_layers` are read-only; they own current
  API/dependency mapping and independent layer verification respectively.
- A TDD test subagent may own one new focused test file after the port contract
  is fixed by the main agent. It must not edit production code, manifests,
  serialized shared files, or task/OpenSpec artifacts.

## Proposed change

1. Add a small port crate (e.g. `ripdpi-ws-transport-port`) at L2/L4 defining a `WsTransport` trait covering what `ws-bootstrap` and `diagnostics-telegram` actually consume.
2. Implement the trait in `ripdpi-ws-tunnel`.
3. Repoint `ripdpi-ws-bootstrap` and `ripdpi-diagnostics-telegram` at the port crate, not the implementation.
4. This is a ~1–2 day mechanical refactor with no behavior change.

## Acceptance criteria

- [x] Baseline `cargo metadata` confirmed both direct implementation edges.
- [x] New port crate defines the trait; `ripdpi-ws-tunnel` implements it.
- [x] Neither `ripdpi-ws-bootstrap` nor `ripdpi-diagnostics-telegram` lists `ripdpi-ws-tunnel` as a direct dep afterward.
- [x] `arch-layer-auditor` re-run reports R-1 and R-2 resolved, no new cycle.
- [x] `cargo nextest run --locked` is green for affected crates; `cargo deny --locked check` is clean.

## Implementation evidence (2026-08-31)

- `ripdpi-ws-transport-port` is a dependency-free L2 contract crate containing
  the object-safe synchronous port and shared Telegram/Worker DTOs.
- Concrete `TelegramWsTransport` construction occurs only in outer composition
  roots; runtime and monitoring own `Arc<dyn WsTransport>`.
- The architecture contract rejects direct bootstrap/diagnostics dependencies
  on `ripdpi-ws-tunnel` and rejects any workspace dependency from the port.
- Local affected-crate nextest result: 435 passed, 8 skipped. Architecture
  contract result: 0 violations. Independent layer/API audits found no blocking
  issue introduced by this change.

## Risks / open questions

- Keep the trait minimal — only what the two consumers need; do not abstract speculatively.
- New crate adds a small build-time cost; justified by removing the cross-layer coupling.

## References

- Audit memory `project_native_audit_findings.md` (2026-06-10, item 11 / R-1, R-2).
- `docs/architecture/NATIVE_RUST.md` (layer taxonomy).
- `ws-tunnel-telegram` skill.
