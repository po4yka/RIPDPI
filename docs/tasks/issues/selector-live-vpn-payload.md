---
id: VPN-1795255851577459
title: Verify selector health on the applied VPN path
kind: bug
status: review
area: vpn
priority: high
owner: VPN runtime engineering
parent: null
blocked_by: []
spec_mode: required
openspec_change: selector-live-vpn-payload
created: 2026-11-21
updated: 2026-11-21
---

## Goal

Recover an active Android VPN when its selected member completes the HTTP handshake but stalls while sending the response body.

## Acceptance criteria

- Check the complete body and its SHA-256 through the authenticated SOCKS route used by the current VPN.
- Check the reserve independently on the same network before changing the automatic selection. Drop measurements when the network or applied VPN route changes.
- On Android, reproduce a stalled response on the active route while a fresh instance of the primary succeeds. Verify a complete transfer from a separate app UID after the VPN applies the reserve.
