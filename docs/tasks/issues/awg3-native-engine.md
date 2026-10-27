---
id: TRN-1792949035079937
title: Run AmneziaWG 3.x configs in the native engine and the Kotlin contract
kind: feature
status: review
area: transport
priority: high
owner: Integration owner
parent: null
blocked_by: []
spec_mode: required
openspec_change: awg3-native-engine
created: 2026-10-25
updated: 2026-10-27
---

## Goal

Configs from current AmneziaVPN (AWG 3.1) servers import into RIPDPI, and the native engine runs their header protection, padding, trailers, timer ranges and non-zero S3/S4.

## Acceptance criteria

- The requirements in `openspec/changes/awg3-native-engine/specs/awg3-native-engine/spec.md` have passing local evidence.
- Locked `ripdpi-warp-core` tests and Clippy, the core JVM suites, the app gate and architecture health pass on the integrated commit.
- The S3/S4 floor changes only after the owner-approved Android arm64 emulator interop gate passes. The arm64 emulator matrix in `verification.md` passed and lifted it.
- Physical arm64 device revalidation was not performed; the policy fixture records `physical_device_revalidated: false`.
