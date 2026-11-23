---
id: AND-1795456111842469
title: Improve root mode in extended Android builds
kind: feature
status: doing
area: android
priority: high
owner: Android and native integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: improve-root-runtime
created: 2026-11-23
updated: 2026-11-23
---

## Goal

Show the state of root access and recover the connection when privileged operation is unavailable in extended Android builds.

## Acceptance criteria

- Root activation is explicit. The UI reports the actual helper state and offers retry.
- A compatible connection continues without privileged operations if the helper fails.
- The privileged backend only runs verified shipped files.
- Verify the Play package boundary, the independent PCAP contract, and the root-manager flow on a compatible device.

## Ownership

- Integration owns the Android app, service, locale files, and edition tests.
- Native hardening owns the nfqws2 asset build, native supervisor, and its tests.
