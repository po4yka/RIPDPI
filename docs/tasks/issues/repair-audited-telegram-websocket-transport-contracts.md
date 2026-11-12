---
id: TRN-1794494451740063
title: Repair audited Telegram WebSocket transport contracts
kind: bug
status: doing
area: transport
priority: high
owner: TUN-06 repair team
parent: null
blocked_by: []
spec_mode: required
openspec_change: tun06-transport-repairs
created: 2026-11-12
updated: 2026-11-12
---

## Goal

Repair the seven confirmed TUN-06 findings. Preserve existing supported transport behavior and provide one code commit per finding.

## Acceptance criteria

- F1/F3: Worker binary and canonical production/test routing tests pass.
- F2/F4/F7: exact-destination classification and bounded init tests pass.
- F5: runtime stop cancels every bootstrap entry path.
- F6: cancellation and durable publication tests preserve settings/credential consistency.
- Independent review and affected local gates pass before main integration.
- Main receives each fix commit and is pushed after combined checks.

## Ownership

- `tun06_worker`: `docs/native/cloudflare-workers/` and its operator guide; F1 and F3.
- `tun06_runtime`: affected WS/proxy Rust crates and tests; F2, F4, F5, and F7.
- `tun06_credentials`: Kotlin Worker credential provisioner/store and tests; F6.
- Coordinator: portfolio/OpenSpec records, combined validation, generated API snapshots if required, and serial main integration.
- Each writer uses a separate worktree. Cargo.lock, dependency catalogs, locale sets, golden files, and baselines are not assigned for edits. No writer may change another lane without coordination.
