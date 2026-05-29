---
title: Per-session CloudflarePublishRuntime instances
type: task
status: done
area: relay
priority: medium
owner: unassigned
parent: epic-cloudflare-publish-hardening
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-29
---

- [x] #task Per-session CloudflarePublishRuntime instances #repo/RIPDPI #area/relay #status/done 🔼

## Summary

`DefaultCloudflarePublishRuntimeFactory` returns a singleton runtime — state leaks across sessions.

## Audit citation

- `core/service/.../CloudflarePublishRuntime.kt:442-464`

## Acceptance criteria

- [x] Factory creates a fresh `CloudflarePublishRuntime` per session.
- [x] No mutable state survives between sessions unless explicitly persisted and audited (install cache is the one documented exception — see [[Install Cloudflare binaries once per ABI and version]]). The `@Singleton CloudflarePublishManager` is the audited exception: it is the process-wide concurrency gate and resets its own session state on stop().
- [x] Old singleton path removed.

## Links

- [[Epic - Cloudflare publish hardening]]
- [[Install Cloudflare binaries once per ABI and version]]
