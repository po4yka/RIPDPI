---
id: AND-1795456111842469
title: Improve root mode in extended Android builds
kind: feature
status: done
area: android
priority: high
owner: Android and native integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: improve-root-runtime
created: 2026-11-23
updated: 2026-11-24
closed_at: "2026-11-24T17:07:28Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: App and service tests verified explicit root activation, helper state, and fallback. Helper, package boundary, and no-su emulator checks passed. Both hosted Linux Rust shards and Android static analysis passed. Physical root acceptance is outside the approved app scope.
---

## Goal

Show the state of root access and recover the connection when privileged operation is unavailable in extended Android builds.

## Acceptance criteria

- Root activation is explicit. The UI reports the actual helper state and offers retry.
- A compatible connection continues without privileged operations if the helper fails.
- The privileged backend only runs verified shipped files.
- Verify root access and helper state with app and service tests, including helper loss and fallback.
- Verify the Play package boundary and the independent PCAP contract with local builds and tests.

## Ownership

- Integration owns the Android app, service, locale files, and edition tests.
- Native hardening owns the nfqws2 asset build, native supervisor, and its tests.
