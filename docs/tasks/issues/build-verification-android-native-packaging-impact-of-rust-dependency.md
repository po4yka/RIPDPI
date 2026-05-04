---
title: Build verification: Android native packaging impact of Rust dependency-surface changes
type: task
status: doing
area: android
priority: medium
owner: Senior Build Gradle CI Engineer
parent: null
blocks: []
blocked_by: []
created: 2026-05-04
updated: 2026-05-04
---

- [ ] #task Build verification: Android native packaging impact of Rust dependency-surface changes #repo/RIPDPI #area/android #status/doing 🔼

## Objective
Verify build and Android native packaging risk from current Rust dependency-surface changes.

## Context
Parent POY-3 found Cargo.lock changes and removed direct dependencies from ripdpi-android and ripdpi-monitor-engine, while ripdpi-android remains the Android cdylib/JNI facade. Native Android artifacts are built through Gradle :core:engine Rust native tasks.

Priority:
Medium.

Parent issue or goal linkage:

## Acceptance criteria
- Confirm removed direct dependencies do not affect Android cdylib linking, generated jniLibs, or root-helper/tunnel artifacts.
- Identify the smallest Android/Gradle verification needed for this diff and whether local ABI narrowing is acceptable for initial validation.
- Confirm no Gradle convention, ABI, SDK, NDK, signing, or release behavior was changed by this diff.
- Escalate if Android native packaging needs broader CI/release verification.

Expected artifact:
Paperclip comment with required build checks, any failures observed, and merge-readiness recommendation.

Constraints:
Do not change signing configuration. Do not publish artifacts. Avoid broad builds unless necessary; prefer the smallest relevant verification.

## Risks
Cargo dependency pruning can compile on host but fail Android cdylib packaging or ABI-specific native builds.

## Required verification
At minimum recommend/perform the smallest relevant Gradle native build check, such as :core:engine:buildRustNativeLibs with local ABI narrowing if appropriate, plus note whether full ABI CI coverage remains required.

## Definition of done
Build/packaging verification requirements and result are posted, with any required CI follow-up made explicit.
