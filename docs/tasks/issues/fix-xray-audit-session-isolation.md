---
id: RLY-1794335291032951
title: Fix Xray UDP session isolation and unavailable runtime cleanup
kind: bug
status: doing
area: relay
priority: high
owner: Xray audit fixes
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-xray-audit-session-isolation
created: 2026-11-10
updated: 2026-11-10
---

## Goal

Close the two confirmed RLY-18 audit defects: isolate managed Xray UDP by authenticated session and release unavailable stub runtime ownership.

## Acceptance criteria

- Managed Xray UDP requires the existing authenticated RUF1 session envelope and closes with TCP control.
- Unauthenticated, wrong-session, replayed and stale UDP packets cannot reach the peer; valid TCP/UDP and domain pins still work.
- Actual stub startup failure releases ownership; failed linked cleanup still retains ownership.
- Relevant local tests, independent review and offline CI gates pass before integration.

## Ownership

- UDP Go worker: native/xray/patches/xray-core-protect-errors.patch, native/xray/patches/manifest.json, scripts/tests/libxray/* UDP tests, docs/native/libxray-packaging.md. Sole writer for patch manifest.
- UDP Rust worker: native/rust/crates/ripdpi-diagnostics-transport/src/transport/socks5* and relevant owning crate tests; do not change Cargo.lock or dependencies.
- Stub worker: core/engine/src/xrayStub and stub-specific unit tests/build source-set wiring only.
- Integrator: task/OpenSpec artifacts, combined validation, architecture/config contract documentation and Git integration.
- All writers use separate worktrees. Shared manifests, contracts, generated snapshots and baselines remain serialized with the integrator; no golden blessing is authorized.
