---
title: Add connection-concurrency as an independent evidence axis
type: task
status: doing
area: diagnostics
priority: high
owner: Codex
parent: null
blocks: []
blocked_by: []
created: 2026-07-10
updated: 2026-07-10
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
