---
title: Install Cloudflare binaries once per ABI and version
type: task
status: backlog
area: relay
priority: medium
owner: unassigned
parent: epic-cloudflare-publish-hardening
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-04-20
---

- [ ] #task Install Cloudflare binaries once per ABI and version #repo/RIPDPI #area/relay #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `install-cloudflare-binaries-once-per-abi-and-version`
- **Verify:** `just test-module core:service`
- **Scope (only modify these + this file + the ledger):** `core/service/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Binaries are copied from assets on every start — slow startup and extra
flash churn.

## Audit citation

- `core/service/.../CloudflarePublishRuntime.kt:529-545`

## Acceptance criteria

- [ ] Install happens once, keyed by `(ABI, binary version hash)`.
- [ ] Subsequent starts validate hash and skip copy.
- [ ] Asset version change invalidates the install cache.
- [ ] Startup latency measured before/after.

## Links

- [[Epic - Cloudflare publish hardening]]
- [[ripdpi-android-audit-2026-04-20]]
