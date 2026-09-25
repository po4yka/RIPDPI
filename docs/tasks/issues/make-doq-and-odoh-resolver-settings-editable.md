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

DNS settings presents DoQ and ODoH fields without silently changing the active resolver. ODoH can be saved after validation; DoQ can be saved for Proxy mode while VPN activation remains blocked until routed VPN DNS supports its UDP transport.

## Acceptance criteria

- A saved DoQ resolver opens a DoQ form. Proxy mode can save and restart it; VPN mode explains why Save is unavailable.
- Home VPN activation with saved DoQ is disabled with an explanation, while an active VPN retains its Stop action.
- A saved ODoH resolver opens an ODoH form and saves all required fields as ODoH.
- Selecting a custom protocol before Save does not persist settings or restart a running service.
- Invalid or expired ODoH configs cannot be saved; app unit and Compose tests cover the boundary.

## Ownership

DNS editor lane owns `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/dns/`, `app/src/main/kotlin/com/poyka/ripdpi/activities/{DnsUiState,SettingsDnsActions,SettingsViewModel,HomeModeCardUiState,MainConnectionActions,MainViewModel}.kt`, `app/src/main/kotlin/com/poyka/ripdpi/settings/state/SettingsNetworkUiStateMappers.kt`, relevant app tests and this task/OpenSpec change. This lane owns only DNS-specific resource keys and their generated `config/i18n/translatable-keys.txt` manifest entries; integration of shared locale files, manifest, and generated board is serialized by the root agent.
