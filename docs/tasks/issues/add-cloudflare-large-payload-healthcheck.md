---
title: Add Cloudflare large-payload healthcheck
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

- [ ] #task Add Cloudflare large-payload healthcheck #repo/RIPDPI #area/relay #status/backlog ⏫

## Summary

Add payload-level health checks that detect Cloudflare-like degradation where TCP/TLS succeeds but transfer stalls around the first tens of kilobytes.

## Motivation

Small `/generate_204`-style checks cannot detect the documented Russian Cloudflare disruption pattern. RIPDPI needs large-payload checks before treating Cloudflare-backed profiles as healthy.

## Scope

- In scope: 64 KB payload check, 256 KB hash check, protocol-level tunnel probe, degraded state, selector integration, and diagnostics.
- Out of scope: storing user identifiers in probe URLs or making Cloudflare a required probe target.

## Acceptance criteria

- [ ] Health checker records TCP connect, TLS handshake, small response, 64 KB body, 256 KB body hash, and protocol-level tunnel outcome separately.
- [ ] If TLS succeeds but large body stalls, profile is marked `DEGRADED_CLOUDFLARE_LIKE` or equivalent.
- [ ] Degraded Cloudflare-like profiles are disabled for auto selection and remain manual-only.
- [ ] Health checks are rate-limited and do not include subscription tokens.
- [ ] UI explains that handshake success is not sufficient evidence of payload availability.

## Design notes

Use neutral controlled health objects. Do not rely on public Cloudflare test URLs as the only evidence source.

## Risks / open questions

- Large probes consume bandwidth; cadence should be conservative and triggered by profile health transitions.

## Links

- [[Epic - Remove Cloudflare from critical path]]
- [[Add priority-based outbound failover state machine]]
- [[Add Android VPN leak-test instrumentation matrix]]
