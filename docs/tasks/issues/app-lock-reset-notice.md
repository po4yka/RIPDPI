---
id: DAT-1793534707148297
title: Persist and show the app lock reset notice
kind: bug
status: backlog
area: data
priority: medium
owner: UI overhaul
parent: null
blocked_by: []
spec_mode: required
openspec_change: app-lock-reset-notice
created: 2026-11-01
updated: 2026-11-01
---

## Problem

When the PIN key is lost, `SettingsViewModelBootstrapper` wipes the PIN and turns app lock off. It now posts a notice on the Settings list through `SettingsNoticeState`. That notice lives in memory only. After process death, nothing tells the user why app lock is off.

## Goal

Persist an "app lock was reset" flag. Show the notice on the Settings list until the user acknowledges it or sets a new PIN. Clear the flag at that point.

## Ownership

`core/data/model` (proto), the `AppSettings` serializer, the settings repository, the backup path, and the Settings notice code in `:app`. `*.proto` and locale resources are serialized lanes with one writer each. Read `docs/architecture/CONFIG_CONTRACTS.md` and the `protobuf-schema-evolution` skill before the proto change. The task adds no new string key unless the existing notice text needs a change; any new key lands in every locale and in `config/i18n/translatable-keys.txt`.

## Acceptance criteria

- `AppSettings` has a new boolean field that records the reset. The field number is the next free number above every declared and reserved number, checked against the live schema. `AppSettingsSerializer` sets its default, and the default is inert.
- The bootstrapper sets the flag in the same settings update that wipes the PIN and turns app lock off.
- After process death, the Settings list shows the notice while the flag is set.
- The flag clears when the user acknowledges the notice or sets a new PIN.
- The design records whether the flag is part of settings backup and restore, and the backup path follows that decision.
- `docs/architecture/CONFIG_CONTRACTS.md` documents the field.
- A migration test shows that bytes written without the field decode with the flag off.
