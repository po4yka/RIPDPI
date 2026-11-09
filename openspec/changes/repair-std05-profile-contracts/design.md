## Context

Task ID: `TRN-1794198826397434`. The audit reproduced four contract defects and two incorrect evidence claims. Existing TLS backends remain responsible for protocol and cryptographic behavior.

## Goals / Non-Goals

- Goal: repair F1 through F6 with one independent repair commit per finding.
- Non-goal: exact browser impersonation, new profiles, resumption, ECH changes, external probes or golden blessing.

## Decisions

- Use the existing Satisfied metadata status for static configuration checks. Preserve the legacy enum for source compatibility, but do not emit its unmeasured wire claim. Size hints remain template hints, not captured lengths.
- Validate the rotating marker by checking the default pool without selecting from it. Use the existing fixed-profile invariant gate for aliases and invalid names.
- In the diagnostic caller, bound the input to the first complete TLS record before invoking the strict planner. Keep its exact-length contract; do not build a general fragmented-handshake parser in this repair.
- Use a real loopback TLS peer for the diagnostic regression. Add the already pinned workspace rcgen only as a dev-dependency if required; serialize its lock edge through the integrator.
- Keep useful preset regression checks, but remove invented browser-version and JA4 claims. Document the existing BoringSSL C API without adding an unsafe adapter.

## Contracts and ownership

- Rust: ripdpi-tls-profiles, ripdpi-xhttp and ripdpi-diagnostics-tls. Their existing consumers inherit the repaired metadata and validation.
- No Kotlin API, JNI, protobuf, persisted field or TLS wire policy change. The metadata status changes from a false wire assertion to existing static success.
- The portfolio records separate writer worktrees and file ownership. The integrator alone owns Cargo.lock, generated API snapshots, task files and the combined gates.

## Risks / Trade-offs

- Consumers must not interpret static validation success as measured browser parity. Existing enum shape is retained to limit compatibility impact.
- First-record extraction preserves the strict planner boundary. Fragmented ClientHello and HRR need separate acceptance; this repair does not silently claim them.
- Host loopback tests prove the regressions, not Android or external compatibility.

## Migration Plan

No stored migration is required. Revert a finding commit to roll back its isolated repair. Before authorized fast-forward integration and push, run affected package tests, Clippy, formatting, independent review, just ci-gates, architecture health and locked Cargo metadata. Preserve each finding as a separate commit.
