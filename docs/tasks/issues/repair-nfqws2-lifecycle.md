---
id: VPN-1794158885036621
title: Repair VPN-10 root backend lifecycle and ownership
kind: bug
status: doing
area: vpn
priority: high
owner: vpn10-integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: repair-nfqws2-lifecycle
created: 2026-11-08
updated: 2026-11-08
---

## Goal

Repair the five confirmed VPN-10 audit findings without changing the non-root packet path or the intentional NFQUEUE bypass policy.

## Acceptance criteria

- A dead helper at monitor registration reports failure to its current session.
- Failed stale-rule discovery or deletion remains pending and retries before activation.
- Predecessor shutdown targets only a verified helper of this private app directory and allows cleanup before escalation.
- Firewall chain parsing cannot select another app UID, including legacy names.
- Lua file validation checks the actual plain or compressed file selected by upstream.
- Regression tests, independent review, local CI source gates, and affected Kotlin/Rust checks pass.
- Each finding has a separate Conventional Commit; integrate into main and push as requested.
- Root/device and external-network acceptance remain unrun unless separately authorized.

## Parallel ownership

- vpn10-integration owns this task, OpenSpec artifacts, combined validation, review fixes, and main integration.
- kotlin-repairs owns RootHelperManager lifecycle, scoped predecessor termination, and their Kotlin tests. Findings F1 and F3 are separate commits.
- rust-repairs owns ripdpi-root-helper nfqws2 lifecycle, chain ownership, file validation, and their Rust tests. Findings F2, F4, and F5 are separate commits.
- Each writer uses a separate worktree. Native files shared by F2/F4/F5 have one writer. No changes to Cargo.lock, dependencies, locales, baselines, upstream assets, JNI/protobuf schemas, or harness files are planned.
