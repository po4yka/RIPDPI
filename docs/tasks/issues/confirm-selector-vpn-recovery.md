---
id: VPN-1795264616970883
title: Confirm selector reload and recovery on the applied VPN
kind: bug
status: review
area: vpn
priority: high
owner: VPN runtime engineering
parent: null
blocked_by: []
spec_mode: required
openspec_change: confirm-selector-vpn-recovery
created: 2026-11-21
updated: 2026-11-21
---

## Goal

Confirm that the running VPN applied the selected reserve and that the reserve carries traffic.

## Acceptance criteria

- Distinguish a reload rejection, a timeout, and a newer applied revision for the exact selected member.
- Reconcile a rejected reload after handover against the confirmed active revision. Preserve newer selections and the VPN barrier.
- Start the anti-flapping cooldown from an applied switch. Confirm recovery and publish the promotion only after a complete payload on the applied VPN route.
