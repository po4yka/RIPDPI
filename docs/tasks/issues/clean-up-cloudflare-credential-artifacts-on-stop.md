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
