---
title: Clean up Cloudflare credential artifacts on stop
type: task
status: done
area: relay
priority: high
owner: unassigned
parent: epic-cloudflare-publish-hardening
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-29
---

- [x] #task Clean up Cloudflare credential artifacts on stop #repo/RIPDPI #area/relay #status/done ⏫

## Summary

Named-tunnel credentials and config are written to persistent `filesDir` state and survive the session. `allowBackup="false"` prevents backup leak, but the files still persist unnecessarily.

## Audit citation

- `core/service/.../CloudflarePublishRuntime.kt:673-680`

## Acceptance criteria

- [x] Ephemeral working directory used where possible — session state lives under `cacheDir/cloudflare-publish/cloudflare-publish-session-<id>`, never `filesDir`.
- [x] Credential files deleted on session stop (success or error) — `stop()` cleans the session dir in a `finally` block.
- [x] Stale credential files cleaned up at startup if a previous run crashed without cleanup — `evictStaleCredentialDirs()` runs in the manager `init`.

## Links

- [[Epic - Cloudflare publish hardening]]
