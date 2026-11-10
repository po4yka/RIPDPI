## Context

The current schema is 10. The audit has four confirmed boundary defects and separate hypotheses about telemetry scope, coherent reads, and DTO drift.

## Goals / Non-Goals

- Goal: repair the four confirmed findings and add checks at their owning boundaries.
- Goal: resolve each audit hypothesis and fix only a confirmed in-scope defect.
- Non-goal: add transport modes, providers, storage fields, dependencies, or external services.

## Decisions

- Native explicit activation uses the existing provider after-image in the same transaction. Store-only updates retain their behavior.
- Shared native validation rejects invalid imported endpoints before mapping or persistence.
- JNI session dispatch requires the current schema before it chooses Standard or Apps Script. Keep standalone Apps Script aliases where callers require them.
- Reality backend dispatch uses an explicit transport allowlist. Unknown strings never imply TCP.
- Add contract checks using real serializers and consumers. Do not copy protocol algorithms into expected values.
- Investigate telemetry and coherent reads before changing them. Record intentional aggregate scope or unsupported DTO modes instead of adding speculative runtime features.

## Contracts and ownership

- Kotlin worker owns app relay activation, native profile validation, and owning regression tests, in a private worktree.
- Rust worker owns relay-android config dispatch, relay-core schema export if needed, Reality backend validation, and their tests, in a private worktree.
- Coordinator owns planning, architecture documentation, shared contract checks, final validation, review and integration. Only the coordinator commits the combined tree and pushes main.
- No locale, protobuf, lockfile, version catalog, schema version, descriptor registry, baseline, or golden change is planned. Assign any extra shared file before writing it.

## Risks / Trade-offs

- Rejecting an invalid imported node must not replace a working subscription member. Use production mutation tests where feasible.
- A shared native schema check must not reject supported Apps Script fields or expose credentials in errors.
- Additional contract tests must not require a network connection, a device, or unavailable artifacts for ordinary unit checks.
- Run narrow Kotlin and Rust tests first, then staticAnalysis, just ci-gates, architecture health, locked Cargo metadata, affected-package Clippy, and independent review on the combined tree. Compiler-backed work uses build-gate with at most four workers.

## Migration Plan

No persisted schema migration is required. Existing valid profiles retain their values. Revert the atomic fix commit to roll back a regression; do not weaken validation or overwrite unrelated state. Integrate separate finding commits onto main only after relevant checks pass, then push the authorized main branch.
