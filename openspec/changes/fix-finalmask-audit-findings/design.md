## Context

The audit report identifies F1–F6 and peer evidence gaps. The existing wire subset stays in scope.

## Goals / Non-Goals

- Goal: make the existing native masks bounded, consistent and observable under failure.
- Non-goal: new masks, UDP finalmask, custom Sudoku layouts, external Cloudflare or physical device probes.

## Decisions

- Keep fragment count within the existing UI limit 16 and size allocations by useful payload parts.
- Preserve nonblank password bytes. Parse random ranges only for header/noise and validate active parameters before readiness.
- Own bridge shutdown and errors; preserve valid half-close. Test pending sibling and owner drop.
- Preserve unknown normalized strings until validation rejects them, or reject them at import. Never silently use off.
- Keep one mask layer below TLS/REALITY. Document fixed-size prefix peers, empty response sequences and the absence of an upstream TCP noise type. Do not claim packet segment guarantees.

## Contracts and ownership

See the linked portfolio Ownership section for exact writer lanes. Rust and Kotlin config validation changes; no protobuf/JNI/storage shape change, lockfile, locale or golden change. Each finding has a separate Conventional Commit, integrated by the owner after review.

## Risks / Trade-offs

- Invalid existing profiles fail earlier; provide precise errors and tests.
- Fatal error cleanup can break half-close; test both paths and cancellation in current-thread and multi-thread runtimes where relevant.
- Reference code tests do not prove Cloudflare interoperability; keep evidence separate.

## Migration Plan

No data migration. Valid existing profiles retain their wire behavior, except corrected passwords with edge spaces. Revert the affected atomic commit for rollback. Run targeted Rust and Kotlin tests, affected lint/build checks, staticAnalysis, just ci-gates, cargo metadata --locked and architecture health before main integration. External CI/device/deployment are not acceptance requirements for these local fixes; no release is requested.
