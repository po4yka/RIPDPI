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
updated: 2026-05-28
---

- [ ] #task Install Cloudflare binaries once per ABI and version #repo/RIPDPI #area/relay #status/backlog 🔼

## Summary

Binaries are copied from assets on every start — slow startup and extra flash churn.

## Audit citation

- `core/service/.../CloudflarePublishRuntime.kt:529-545`

## Acceptance criteria

- [ ] Install happens once, keyed by `(ABI, binary version hash)`.
- [ ] Subsequent starts validate hash and skip copy.
- [ ] Asset version change invalidates the install cache.
- [ ] Startup latency measured before/after.

## Links

- [[Epic - Cloudflare publish hardening]]
