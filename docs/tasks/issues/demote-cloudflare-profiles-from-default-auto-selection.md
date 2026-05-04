---
title: Demote Cloudflare profiles from default auto selection
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

- [ ] #task Demote Cloudflare profiles from default auto selection #repo/RIPDPI #area/relay #status/backlog ⏫

## Summary

Make Cloudflare-backed XHTTP/HTTPS profiles low-priority or manual-only in the default selector when Russian-path degradation is detected or likely.

## Motivation

Cloudflare can pass TCP/TLS and still fail payload transfer. It should not compete equally with direct REALITY or non-Cloudflare HTTPS fallback in auto mode for Russian users.

## Scope

- In scope: profile capability flag, health-state based demotion, selector ordering, manual override, and UI labels.
- Out of scope: removing Cloudflare support entirely.

## Acceptance criteria

- [ ] Default auto candidates prefer direct REALITY and non-Cloudflare HTTPS fallback.
- [ ] Cloudflare-backed profiles are excluded from auto when marked degraded.
- [ ] Manual selection still allows Cloudflare profile use where it works.
- [ ] Selector UI labels Cloudflare paths as optional/edge fallback.
- [ ] Tests cover transition from healthy to degraded and back after payload health recovers.

## Design notes

This task complements, but does not replace, the broader failover state machine.

## Risks / open questions

- Some Russian ISPs may still pass Cloudflare; demotion should be health-based, not a global hard block.

## Links

- [[Epic - Remove Cloudflare from critical path]]
- [[Epic - Xray VPN client mode]]
- [[Add Cloudflare large-payload healthcheck]]
