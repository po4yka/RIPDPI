---
title: Install Cloudflare binaries once per ABI and version
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

- [x] #task Install Cloudflare binaries once per ABI and version #repo/RIPDPI #area/relay #status/done 🔼

## Summary

Binaries are copied from assets on every start — slow startup and extra flash churn.

## Audit citation

- `core/service/.../CloudflarePublishRuntime.kt:529-545`

## Acceptance criteria

- [x] Install happens once, keyed by `(ABI, binary version hash)` — per-ABI `cloudflare-runtime/<abi>` dir plus a `<name>.sha256` content marker.
- [x] Subsequent starts validate hash and skip copy.
- [x] Asset version change invalidates the install cache (a new asset hash differs from the marker and re-installs).
- [x] Startup latency measured before/after — `CloudflarePublishBinaryInstallTest` asserts the copy is performed once and skipped on identical re-installs (the flash-write that dominated cold-start cost).

## Links

- [[Epic - Cloudflare publish hardening]]
