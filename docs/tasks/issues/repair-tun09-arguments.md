---
id: TRN-1794489808420764
title: Preserve WebTunnel transport argument boundaries
kind: bug
status: doing
area: transport
priority: medium
owner: WebTunnel repair coordinator
parent: null
blocked_by: []
spec_mode: required
openspec_change: tun09-argument-repair
created: 2026-11-12
updated: 2026-11-12
---

## Goal

Close TUN-09 F4 without changing the supported Tor transport scope. Revalidate F1-F3, which current main already fixes in 0552aaae27 and f5afd3060f.

## Acceptance criteria

- Preserve PT argument key/value boundaries and match pinned goptlib first-value selection.
- Reject malformed entries without changing unrelated valid bridge options.
- Keep the existing WebTunnel TLS, managed startup, and Tor-only activation tests passing.
- Run independent review, owning gates and offline source gates before main integration.

## Ownership

- Argument worker: native/rust/crates/ripdpi-webtunnel/src/bridge_line.rs, src/pt.rs, tests/bridge_line.rs and tests/pt_managed_client.rs, in a separate worktree.
- Coordinator: this portfolio record, openspec/changes/tun09-argument-repair, validation evidence and integration.
- Review agents: read-only findings and review. No code writes.
- No writer owns Cargo.lock, manifests, locales, schemas, registry baselines or golden files; keep them unchanged.

## Scope

F1-F3 are already fixed on main. Do not create empty or duplicate repairs. Detached library tasks are not a confirmed defect in the supported helper subprocess; no embedding contract is added. Android start/stop now has TUN-10 repairs. External Tor/device acceptance remains separate from local validation.
