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
updated: 2026-04-20
---

- [ ] #task Per-session CloudflarePublishRuntime instances #repo/RIPDPI #area/relay #status/backlog 🔼

## Summary

`DefaultCloudflarePublishRuntimeFactory` returns a singleton runtime — state
leaks across sessions.

## Audit citation

- `core/service/.../CloudflarePublishRuntime.kt:442-464`

## Acceptance criteria

- [ ] Factory creates a fresh `CloudflarePublishRuntime` per session.
- [ ] No mutable state survives between sessions unless explicitly persisted
    and audited (install cache is the one documented exception — see
    [[Install Cloudflare binaries once per ABI and version]]).
- [ ] Old singleton path removed.

## Links

- [[Epic - Cloudflare publish hardening]]
- [[Install Cloudflare binaries once per ABI and version]]
- [[ripdpi-android-audit-2026-04-20]]
