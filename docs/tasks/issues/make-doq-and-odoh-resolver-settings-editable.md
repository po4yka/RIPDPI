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

DNS settings presents the stored DoQ and ODoH protocols and permits editing their required endpoint settings without silently converting either protocol to DoH.

## Acceptance criteria

- A saved DoQ resolver opens a DoQ form and saves as DoQ.
- A saved ODoH resolver opens an ODoH form and saves all required fields as ODoH.
- Invalid endpoints cannot be saved; app unit and Compose tests cover both paths.

## Ownership

DNS editor lane owns `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/dns/`, `app/src/main/kotlin/com/poyka/ripdpi/activities/{DnsUiState,SettingsDnsActions,SettingsViewModel}.kt`, `app/src/main/kotlin/com/poyka/ripdpi/settings/state/SettingsNetworkUiStateMappers.kt`, relevant app tests and this task/OpenSpec change. This lane owns only DNS-specific new resource keys and their generated `config/i18n/translatable-keys.txt` manifest entries; integration of shared locale files, manifest, and generated board is serialized by the root agent.
