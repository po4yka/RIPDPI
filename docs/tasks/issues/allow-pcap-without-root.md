---
id: DGN-1795412913098712
title: Allow PCAP capture and viewing without root
kind: bug
status: review
area: diagnostics
priority: medium
owner: PCAP integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: allow-pcap-without-root
created: 2026-11-23
updated: 2026-11-23
---

## Goal

Allow users to record and view VPN packet captures with root mode off.

## Acceptance criteria

- Enable capture with separate consent and an active VPN.
- Keep the capture list and viewer accessible after VPN disconnects.
- Verify capture consent and tunnel checks, UI entry points, and locale parity.
