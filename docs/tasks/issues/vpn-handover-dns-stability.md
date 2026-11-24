---
id: VPN-1795491626935120
title: Reduce VPN handover disruption and DNS recovery delay
kind: bug
status: review
area: vpn
priority: high
owner: VPN service
parent: null
blocked_by: []
spec_mode: required
openspec_change: vpn-handover-dns-stability
created: 2026-11-24
updated: 2026-11-24
---

## Goal

Keep unchanged VPN components active during metadata refresh and recover
encrypted DNS promptly after failures.

## Acceptance criteria

- Same-route metadata changes preserve the native proxy when its inputs match.
- VPN handover readiness is separate from current-route forwarding evidence.
- DNS failure bursts and exhaustion trigger bounded recovery, including privacy
  and stale-generation guards.
- Record JVM, repository and physical Android evidence separately.
