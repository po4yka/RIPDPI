---
id: DGN-1786264762917684
title: Add connection-concurrency as an independent evidence axis
kind: feature
status: done
area: diagnostics
priority: high
owner: Codex
parent: null
blocked_by: []
spec_mode: required
openspec_change: dgn-1786264762917684-add-connection-concurrency-evidence-axis
created: 2026-07-10
updated: 2026-08-26
closed_at: "2026-08-26T08:22:58Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "Verification chain complete: implementation 0fa837bff integrated via rebuilt history root d4034d2cc; focused suites BUILD SUCCESSFUL in 23m31s at f28e90966 covering the concurrency contract/workflow/wire tests; green full-CI run 32933047982 on exact main SHA c465912fe"
---

## Goal

Model TLS fingerprint and same-SNI connection concurrency as independent evidence axes so diagnostics can identify their conjunction without adding another failure-symptom signal.

## Scope

- Add schema-versioned Rust and Kotlin observation, matrix, assessment, and catalog contracts.
- Run the quick and audit concurrency matrices after ordinary strategy candidates across the six canonical TLS profiles.
- Persist only confirmed quick-scan policy under the hashed network fingerprint and apply the selected profile plus per-profile cap on the next service start.
- Enforce the learned same-SNI/profile cap alongside the existing exit-IP ceiling and surface typed export/UI diagnosis with the proxy-mode caveat.

## Shared-file ownership

This task owns the serialized lane for `native/rust/Cargo.lock`, diagnostics wire/schema manifests, API snapshots, contract and archive goldens, the diagnostics Room schema, and all locale string sets. No parallel writer may change those files until this task reaches review.

## Ship definition

- Classifier tests prove fingerprint-only and concurrency-only evidence cannot produce a conjunction verdict.
- Synthetic network tests cover launch barriers, observed peak, post-check freeze handling, rotation, cancellation, and partial results.
- Runtime tests cover per-network/profile persistence, next-start-only application, independent same-SNI counters, exit-IP coexistence, RAII release, pass-through fallback, and raw-SNI privacy.
- Requested Rust, Kotlin, static-analysis, architecture, boundary, metadata, API-snapshot, and locale gates pass, with unrelated baseline failures documented separately.

## Work log

- Added the schema-versioned axis-M evidence, catalog eligibility metadata, six-profile quick/audit matrix runner, conjunction classifier, report/export projection, remembered-network policy, next-start runtime context, and same-SNI/profile limiter.
- Added classifier, real TLS fixture, freeze/cancellation/partial recovery, Kotlin wire/catalog/persistence, and runtime limiter regression coverage.
- Feature-specific tests, diagnostics boundary verification, architecture health, Cargo metadata, and locale-key parity pass. Full-tree diagnostics/static-analysis gates still expose unrelated `origin/main` baseline drift in the confirm-good taxonomy fixture, existing engine/service detekt findings, and the host-dependent runtime-platform API snapshot.
- The classifier confirms replicated evidence across two eligible targets in one scan. Cross-scan target-rotation history is not persisted, so the alternative two-scan confirmation path remains follow-up work; a single clean target remains non-actionable `CONJUNCTION_SUSPECTED`.
