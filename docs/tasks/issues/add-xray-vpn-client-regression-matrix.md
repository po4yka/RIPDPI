---
title: Add Xray VPN client regression matrix
type: task
status: backlog
area: outbound
priority: medium
owner: unassigned
parent: epic-xray-vpn-client-mode
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Add Xray VPN client regression matrix #repo/RIPDPI #area/outbound #status/backlog 🔼

## Summary

Add focused automated coverage for the first Xray VPN client integration.

## Context

The risky parts are lifecycle, config rendering, socket protection, DNS loops,
provider telemetry, and Android VPN handoff. Tests should lock those down before
Xray mode becomes a default or recommended fallback.

## Acceptance criteria

- [ ] Config golden tests cover VLESS/REALITY, XHTTP, invalid combinations, and
    redaction.
- [ ] Service tests cover Xray startup failure, readiness timeout, stop,
    restart, and handover behavior.
- [ ] Protect-fd tests prove Xray dialer/listener sockets use the Android VPN
    protection path.
- [ ] DNS-loop regression proves provider bootstrap DNS does not re-enter TUN.
- [ ] Device/emulator smoke test verifies active VPN traffic exits through the
    Xray outbound path.
- [ ] CI or documented manual lanes identify which Xray tests need network,
    emulator, or private fixture dependencies.

## Notes

Keep private endpoints out of fixtures. Use local synthetic fixtures or
operator-provided private test profiles outside the vault.

## Links

- [[Epic - Xray VPN client mode]]
- [[Bridge TUN traffic through Xray local inbound]]
- [[Surface Xray diagnostics and telemetry]]
- [[ripdpi-android-xray-vpn-client-plan-2026-04-24]]
