---
title: Make VPN protect cleanup exception-safe
type: task
status: doing
area: vpn
priority: high
owner: Codex service lane
parent: epic-close-remaining-android-audit-risks
blocks: []
blocked_by: []
created: 2026-07-13
updated: 2026-07-13
---

## Goal

Rollback partial native protect registration, attempt every unregister and UDS cleanup after failures, preserve suppressed errors, and allow retry when cleanup is incomplete.

## Acceptance criteria

- [ ] Fault injection at every register/unregister position proves rollback and best-effort cleanup.
- [ ] The idempotence state records completion only after all required owners are released.
