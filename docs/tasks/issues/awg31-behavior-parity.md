---
id: TRN-1793271658511991
title: Complete AmneziaWG 3.1 behavior and verification
kind: feature
status: backlog
area: transport
priority: high
owner: protocol-runtime
parent: null
blocked_by: []
spec_mode: required
openspec_change: awg31-behavior-parity
created: 2026-10-29
updated: 2026-10-29
---

## Goal

Complete AmneziaWG 3.1 runtime behavior and profile handling against pinned upstream peers. Close findings F01-F16 in the linked OpenSpec design while preserving authentication, replay protection, bounded parsing, secret storage, and existing profiles. This record starts in backlog; the current delivery is planning only.

## Acceptance criteria

All modified and added requirements in `openspec/changes/awg31-behavior-parity/specs/awg3-native-engine/spec.md` have passing evidence mapped in `verification.md`. Timer fields and DisableCookies affect runtime behavior; every transport packet follows padding policy; ambiguous receive classification and authenticated malformed payloads are handled safely. Profile input, readiness, v3 editing, and preset replacement have regression coverage. Host, emulator, hosted CI, physical arm64, and exact-artifact checks are recorded separately. The shared deployment guard remains unchanged.

## Scope and sequence

Resolve the backend extension boundary first. Implement native state and packet fixes plus independent profile/UI fixes, then extend feature-specific peer validators and run acceptance. Preserve documented numeric/CPS/header limits. New dependency or public-contract decisions must update the design before dependent implementation.

## Ownership

Planning owner: protocol-runtime. Future native, profile/UI, and acceptance writers use separate worktrees with the paths assigned in the design. One integration owner serializes dependency manifests/lockfiles, any schema or JNI changes, locale sets, and evidence fixtures. The shared arm64 deployment guard is outside this change's write scope.

## Completion boundary

Do not close this task when planning validation passes. Implementation steps stay unchecked until their evidence exists. Historical emulator results and the research baseline are not current implementation acceptance. No server deployment or production DPI claim is part of this task.
