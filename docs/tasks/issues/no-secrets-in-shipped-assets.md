---
id: DAT-1795085279367518
title: Keep secrets out of shipped app assets
kind: feature
status: doing
area: data
priority: critical
owner: Android data engineer
parent: EPC-1795085162356362
blocked_by: []
spec_mode: required
openspec_change: dat-1795085279367518-no-secrets-in-shipped-assets
created: 2026-11-19
updated: 2026-11-19
linked_task: AND-1795015211660121
---

## Goal

No secret material ships in app assets, and embedded relay credentials arrive through bootstrap provisioning into Keystore-backed storage. Planning only; the untracked app/src/simple/ file is not touched. Related: AND-1795015211660121 (removes the simple flavor and adds preset.json; review).

## Acceptance criteria

- Build guard fails on private keys, preshared keys, and static passwords in any asset directory.
- Bootstrap provisioning stores credentials in Keystore-backed storage.
- A rotation note lists shipped key kinds and versions without values.
- Deploy counterpart: D4.
