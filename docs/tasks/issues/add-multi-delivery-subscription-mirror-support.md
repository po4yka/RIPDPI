---
title: Add multi-delivery subscription mirror support
type: task
status: backlog
area: relay
priority: high
owner: unassigned
parent: epic-remove-cloudflare-from-critical-path
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-01
---

- [ ] #task Add multi-delivery subscription mirror support #repo/RIPDPI #area/relay #status/backlog ⏫

## Summary

Allow a per-device subscription profile to carry multiple delivery URLs or bootstrap mirrors, with Cloudflare mirrors treated as optional rather than authoritative.

## Motivation

Users need a way to refresh profiles when one delivery plane is unreachable. A single bearer URL behind Cloudflare is a critical failure point.

## Scope

- In scope: mirror list model, ordered refresh attempts, mirror health state, token redaction, no-log diagnostics, and UI showing which mirror last succeeded.
- Out of scope: sharing one token across unrelated devices or bypassing per-device token scope.

## Acceptance criteria

- [ ] Subscription state can store multiple scoped delivery mirrors for one physical device.
- [ ] Refresh attempts prefer non-Cloudflare direct delivery when available.
- [ ] Cloudflare mirror failures do not block trying non-Cloudflare mirrors.
- [ ] Logs and diagnostics redact every mirror token and full URL.
- [ ] UI shows last refresh mirror and degraded mirror state without exposing secrets.

## Design notes

Mirror support must not weaken bearer-token scope. Each mirror can have its own token or a scoped token design, but shared all-user URLs are not allowed.

## Risks / open questions

- Multiple URLs increase leak surface; pair this with token expiry and redaction tests.

## Links

- [[Epic - Remove Cloudflare from critical path]]
- [[Epic - NekoBox subscription and profile import]]
- [[Add per-device subscription token UX and shared-link warnings]]
