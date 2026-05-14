---
title: Reject concurrent CloudflarePublishManager sessions
type: task
status: backlog
area: relay
priority: high
owner: unassigned
parent: epic-cloudflare-publish-hardening
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-04-20
---

- [ ] #task Reject concurrent CloudflarePublishManager sessions #repo/RIPDPI #area/relay #status/backlog ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `reject-concurrent-cloudflarepublishmanager-sessions`
- **Verify:** `just test-module core:service`
- **Scope (only modify these + this file + the ledger):** `core/service/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

`CloudflarePublishManager.start()` does not clearly reject an already-running
session — overlap / reentry is possible.

## Audit citation

- `core/service/.../CloudflarePublishRuntime.kt:175-181,183-247`

## Acceptance criteria

- [ ] `start()` returns a typed error (`AlreadyRunning`) when invoked on a
    running session.
- [ ] State transitions are covered by a state machine or explicit guard.
- [ ] Unit test exercises concurrent `start()` calls.

## Links

- [[Epic - Cloudflare publish hardening]]
- [[ripdpi-android-audit-2026-04-20]]


## composable-transport-layer-parity
