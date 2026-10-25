---
id: TRN-1792949035079937
title: Run AmneziaWG 3.x configs in the native engine and the Kotlin contract
kind: feature
status: doing
area: transport
priority: high
owner: Integration owner
parent: null
blocked_by: []
spec_mode: required
openspec_change: awg3-native-engine
created: 2026-10-25
updated: 2026-10-25
---

## Goal

Configs from current AmneziaVPN (AWG 3.1) servers import into RIPDPI, and the native engine runs their header protection, padding, trailers and timer ranges, while the arm64 S3/S4 floor stays in force.

## Acceptance criteria

- The requirements in `openspec/changes/awg3-native-engine/specs/awg3-native-engine/spec.md` have passing local evidence.
- Locked `ripdpi-warp-core` tests and Clippy, the core JVM suites, the app gate and architecture health pass on the integrated commit.
- The S3/S4 floor changes only after a physical device revalidation.
