---
id: RTE-1792840535644306
title: Route active AWG profile DNS through its tunnel
kind: bug
status: review
area: routing
priority: high
owner: protocol-runtime
parent: null
blocked_by: []
spec_mode: required
openspec_change: awg-profile-dns-routing
created: 2026-10-24
updated: 2026-10-24
status_detail: Reviewed source and exact 47a5ee708a497086eb29739ca17f35e164119846 genuine artifacts passed all four owned physical USB scenarios and cleanup. Final published hosted gates remain required before closure.
---

## Goal

Route TCP and UDP traffic to exact resolver addresses from the active standalone AWG profile on ports 53 and 853 through its tunnel. Preserve effective Block and unrelated destination routing.

## Acceptance criteria

- Capture and validate resolver authority with the same AWG request that starts the runtime; clear stale authority when AWG is inactive.
- Preserve the original ordered destination policy and its digest. Effective Block wins; unrelated addresses and ports keep their original route.
- Pass complete affected Kotlin and native tests, static checks, independent review, and exact-commit hosted gates.
- Prove IPv4 and IPv6 DNS payload through the independent AWG peer on a rebuilt Android artifact, with seeded LAN rules and fixture addresses unchanged.
- Keep this task open until required local, hosted, device, and artifact evidence is recorded.

## Ownership

Protocol runtime worker owns the engine API/codec/projection, shared service composition, typed native configuration, native shared egress decision, and adjacent regressions listed in the design. The integration owner serializes task-board changes, merges the reviewed commit, and owns physical Android acceptance. No provider or server changes are included.
