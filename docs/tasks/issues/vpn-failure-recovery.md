---
id: VPN-1795489847968987
title: Keep VPN blocking and recover after network return
kind: bug
status: review
area: vpn
priority: critical
owner: VPN service
parent: null
blocked_by: []
spec_mode: required
openspec_change: vpn-failure-recovery
created: 2026-11-24
updated: 2026-11-24
---

## Goal

Keep the VPN TUN installed when the connection engine fails with blocking
enabled. Restore forwarding after handover exhaustion when a usable network
returns.

## Acceptance criteria

- Xray and unexpected proxy exit code zero honor the blocking setting.
- A failed or superseded recovery keeps the installed TUN. Only the current
  network event and runtime command can publish Connected.
- A later eligible recovery resumes the same VPN session and its telemetry.
- Pass the affected runtime-state and service JVM tests and source gates.
  Record physical Android evidence separately.
