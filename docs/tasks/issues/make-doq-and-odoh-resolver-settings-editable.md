---
id: DNS-1790339241109329
title: Make DoQ and ODoH resolver settings editable
kind: bug
status: review
area: dns
priority: medium
owner: DNS editor lane
parent: null
blocked_by: []
spec_mode: required
openspec_change: dns-resolver-editor-protocols
created: 2026-09-25
updated: 2026-09-25
---

## Goal

DNS settings presents DoQ and ODoH fields without silently changing the active resolver. ODoH can be saved after validation. DoQ can resolve hostnames handled by the local proxy; it does not change device DNS, and VPN activation remains blocked until routed VPN DNS supports its UDP transport.

## Acceptance criteria

- A saved DoQ resolver opens a DoQ form. Proxy mode can save and restart it; VPN mode explains why Save is unavailable.
- Home VPN activation with saved DoQ is disabled with an explanation, while an active VPN retains its Stop action.
- VPN start and config restart paths reject unsupported DoQ before tunnel setup or stopping a running service.
- A DoQ DataStore Save and an accepted VPN start cannot overlap, including the period when service status is still Halted. Completion of either of two overlapping starts cannot release the other's reservation.
- Proxy mode copy distinguishes local proxy hostname resolution from device DNS interception.
- A saved ODoH resolver opens an ODoH form and saves all required fields as ODoH.
- Selecting a custom protocol before Save does not persist settings or restart a running service.
- Invalid or expired ODoH configs cannot be saved; app unit and Compose tests cover the boundary.

## Ownership

DNS editor lane owns app DNS screens/state/actions, home/config restart guards, service start dispatch/queue reservation and connection policy guard, focused tests and this task/OpenSpec change. This lane owns only DNS-specific resource text and their generated `config/i18n/translatable-keys.txt` manifest entries; integration of shared locale files, manifest, and generated board is serialized by the root agent.
