---
title: Clean up Cloudflare credential artifacts on stop
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

- [ ] #task Clean up Cloudflare credential artifacts on stop #repo/RIPDPI #area/relay #status/backlog ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `clean-up-cloudflare-credential-artifacts-on-stop`
- **Verify:** `just test-module core:service`
- **Scope (only modify these + this file + the ledger):** `core/service/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Named-tunnel credentials and config are written to persistent `filesDir`
state and survive the session. `allowBackup="false"` prevents backup leak,
but the files still persist unnecessarily.

## Audit citation

- `core/service/.../CloudflarePublishRuntime.kt:673-680`

## Acceptance criteria

- [ ] Ephemeral working directory used where possible (e.g. `cacheDir` or
    a session-scoped subdir).
- [ ] Credential files deleted on session stop (success or error).
- [ ] Stale credential files cleaned up at startup if a previous run
    crashed without cleanup.

## Links

- [[Epic - Cloudflare publish hardening]]
- [[ripdpi-android-audit-2026-04-20]]
