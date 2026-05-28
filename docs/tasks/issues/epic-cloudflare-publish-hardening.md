---
title: Epic - Cloudflare publish hardening
type: epic
status: backlog
area: relay
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-14
---

- [ ] #task Epic - Cloudflare publish hardening #repo/RIPDPI #area/relay #status/backlog ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `epic-cloudflare-publish-hardening`
- **Verify:** `all child rows in GOAL_LEDGER.md are DONE or BLOCKED`
- **Scope (only modify these + this file + the ledger):** _epic — coordination only; child tasks carry the file scope_
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Goal

Remove session-to-session state leakage, reentrancy risk, and unnecessary flash churn from the Cloudflare publish runtime. Binaries installed once per ABI/version; working state is ephemeral; credentials are cleaned on stop; concurrent starts refused cleanly.

## Why now

The audit found four stacked problems in one subsystem: `CloudflarePublishManager.start()` doesn't reject already-running sessions; `DefaultCloudflarePublishRuntimeFactory` returns a singleton; binaries copy from assets on every start; named-tunnel credentials live in `filesDir` beyond session lifetime. `allowBackup="false"` prevents backup leak, but the on-device persistence is still unnecessary and slow.

## Key decisions

- **Per-session runtime instance.** No shared mutable state between sessions. Each session owns its own runtime object, which is thrown away at stop.
- **Binary install once, keyed by `(ABI, version hash)`.** Hash-verified on every subsequent start; asset version change invalidates.
- **Ephemeral working dir** (`cacheDir` or a session-scoped subdir) for anything that isn't legitimately persistent operator configuration.
- **Credential cleanup on stop** (both happy-path and error); orphan cleanup on startup for crashed-prior-run cases.

## Scope

- **In scope:** `CloudflarePublishManager`, `CloudflarePublishRuntime`, `DefaultCloudflarePublishRuntimeFactory`, binary install path, credential persistence on `filesDir`.
- **Out of scope:** non-Cloudflare publish paths (separate stack).

## Ship definition

- [ ] Concurrent `start()` on a running session returns a typed `AlreadyRunning` error, not undefined behavior.
- [ ] `DefaultCloudflarePublishRuntimeFactory` no longer hands out a singleton — each session receives its own.
- [ ] Binary install measured to happen at most once per ABI+version hash per install; cold-start latency drops measurably.
- [ ] No credential files remain in `filesDir` after a clean stop; crashed-prior-run files are cleaned at startup.

## Child tasks

**Reentrancy**
- Reject concurrent CloudflarePublishManager sessions (closed task)

**State isolation**
- [[Per-session CloudflarePublishRuntime instances]]
- [[Clean up Cloudflare credential artifacts on stop]]

**Install path**
- [[Install Cloudflare binaries once per ABI and version]]

Child tasks roll up via the TaskNotes relationships view on this note.

## Risks / open questions

- Install-cache invalidation semantics on asset version bump — decide during the install task (delete everything, or keep N-1 for rollback?).
- Ensure ephemeral-dir cleanup doesn't race with next session's startup.

## Links

- [[ripdpi-android]]
- [[ripdpi-android-audit-2026-04-20]] §7
- Child issues: 4
