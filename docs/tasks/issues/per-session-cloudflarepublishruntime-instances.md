---
title: Per-session CloudflarePublishRuntime instances
type: task
status: backlog
area: relay
priority: medium
owner: unassigned
parent: epic-cloudflare-publish-hardening
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-28
---

- [ ] #task Per-session CloudflarePublishRuntime instances #repo/RIPDPI #area/relay #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `per-session-cloudflarepublishruntime-instances`
- **Verify:** `just test-module core:service`
- **Scope (only modify these + this file + the ledger):** `core/service/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

`DefaultCloudflarePublishRuntimeFactory` returns a singleton runtime — state leaks across sessions.

## Audit citation

- `core/service/.../CloudflarePublishRuntime.kt:442-464`

## Acceptance criteria

- [ ] Factory creates a fresh `CloudflarePublishRuntime` per session.
- [ ] No mutable state survives between sessions unless explicitly persisted and audited (install cache is the one documented exception — see [[Install Cloudflare binaries once per ABI and version]]).
- [ ] Old singleton path removed.

## Links

- [[Epic - Cloudflare publish hardening]]
- [[Install Cloudflare binaries once per ABI and version]]
