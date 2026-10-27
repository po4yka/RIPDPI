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
- The client S3/S4 floor changes only after the owner-approved Android arm64 emulator interop gate passes. The emulator matrix in `verification.md` passed, the client floor is removed, and `core/data/src/test/resources/awg-arm64-emulator-observation.json` records the evidence.
- The shared deployment guard in `core/data/src/test/resources/contract/amneziawg-arm64-version-floor.json` stays `guard_required: true`, unchanged by owner decision #687 (b058531e7), until a physical arm64 revalidation.
- No physical arm64 revalidation has been done.
