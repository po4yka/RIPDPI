## Context

The audit concerns the library API, not a connected session driver. See proposal.md.

## Goals / Non-Goals

- Goal: correct flush, path, cancellation, and carrier-result behavior.
- Non-goal: add a user mode, HTTP/2 driver, TLS policy, or TunMulti.

## Decisions

- Replace the encoder task with a bounded pending frame drained by AsyncWrite polling.
- Replace the decoder task with owned incremental state polled by AsyncRead.
- Keep four independent fixes in separate commits.
- Escape UTF-8 service bytes with the pinned Go PathEscape segment rules. Keep existing trim and outer-slash aliases.
- Add explicit response completion with an io::Result channel. Clean DATA EOF without completion is an error. Driver owns HTTP/gRPC metadata validation and reset handling.

## Contracts and ownership

- Primary writer owns native/rust/crates/ripdpi-xhttp and planning records in this worktree.
- Subagents inspect and review read-only; there are no parallel shared-file writers.
- No Kotlin, Cargo manifests, lockfiles, schemas, locales, baselines, or golden fixtures change.

## Risks / Trade-offs

- Flush can block on backpressure: tests must run the wire reader concurrently.
- Response completion is a library behavior change: update all owning tests and document caller obligations.
- Polling must retain partial frames across cancellation and yield on empty-message floods.

## Migration Plan

Update library tests to publish carrier completion. There are no production callers to migrate. Revert the isolated commits for rollback. Run crate tests and clippy, independent review, task validation, architecture and offline CI gates before main integration.
