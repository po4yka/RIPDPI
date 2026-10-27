---
id: TRN-1792949035079937
title: Run AmneziaWG 3.x configs in the native engine and the Kotlin contract
kind: feature
status: done
area: transport
priority: high
owner: Integration owner
parent: null
blocked_by: []
spec_mode: required
openspec_change: awg3-native-engine
created: 2026-10-25
updated: 2026-10-27
closed_at: "2026-10-27T09:23:20Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "Client acceptance met on Android arm64 emulator evidence (96/96 sessions at ba228c17c, recorded in core/data/src/test/resources/awg-arm64-emulator-observation.json); shared deployment guard amneziawg-arm64-version-floor.json keeps guard_required true by owner decision #687 (b058531e7); no physical arm64 revalidation done; hosted CI, CodeQL, fleet-fixtures and Secret Scan passed on 3959b79bdae646fcfe9286f6b28c27df72f9519d (runs 40391039518, 40391039493, 40391039559, 40391039870)."
---

## Goal

Configs from current AmneziaVPN (AWG 3.1) servers import into RIPDPI, and the native engine runs their header protection, padding, trailers, timer ranges and non-zero S3/S4.

## Acceptance criteria

- The requirements in `openspec/changes/awg3-native-engine/specs/awg3-native-engine/spec.md` have passing local evidence.
- Locked `ripdpi-warp-core` tests and Clippy, the core JVM suites, the app gate and architecture health pass on the integrated commit.
- The client S3/S4 floor changes only after the owner-approved Android arm64 emulator interop gate passes. The emulator matrix in `verification.md` passed, the client floor is removed, and `core/data/src/test/resources/awg-arm64-emulator-observation.json` records the evidence.
- The shared deployment guard in `core/data/src/test/resources/contract/amneziawg-arm64-version-floor.json` stays `guard_required: true`, unchanged by owner decision #687 (b058531e7), until a physical arm64 revalidation.
- No physical arm64 revalidation has been done.
