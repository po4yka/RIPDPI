## Context

`ripdpi-packets` already derives Initial keys for v1 and v2 (`src/quic/crypto.rs` holds `QUIC_V2_SALT`), builds Initials for a given version (`build_quic_initial_from_tls`, `build_realistic_quic_initial` in `src/quic/build.rs` and `src/quic/fake_initial.rs`), and has v1 and v2 regression vectors in `tests/fixtures/quic-initial/`. DIA-07 (`DGN-1794734098746391`) owns the QUIC Initial probe, its response validator, and its UDP transport; its design lists QUIC v2, handshake, and HTTP/3 as non-goals.

## Goals / Non-Goals

Goal: tell a version-specific or flow-state-specific QUIC failure from a general one, at the Initial stage.

Non-goals: QUIC handshake completion, HTTP/3, endpoint authentication, background runs, upload, automatic relay or strategy changes. Handshake-level evidence needs a separate change.

## Decisions

Packets. Build v1 and v2 Initials with the existing `ripdpi-packets` builders and the same ClientHello except for the SNI. Add a known-answer test that checks the v2 Initial key derivation against the RFC 9369 Appendix A vectors, so the v2 test does not depend on the builder under test. Unknown-version decoys are never counted as v2.

Matrix. Four cells: {v1, v2} by {control SNI, test SNI}, two repetitions each, in a recorded order that alternates versions. Each cell uses a fresh UDP socket on a new local port. Local port separation does not prove separate NAT mappings; the result says so. The residual cell (a test-SNI Initial followed by a control-SNI Initial on the same socket) runs only when the user enables it and is reported apart from the matrix.

Response stages. Each cell records one of: valid Initial response (validated by the DIA-07 validator), Version Negotiation only, other response, or timeout. A valid Initial response is not endpoint authentication: Initial keys come from public inputs.

Inference. A version difference is reported only when both control cells get a valid Initial response. If any control cell fails, the result is inconclusive. If the endpoint answers v2 with Version Negotiation, v2 is `unsupported by endpoint`.

Bounds. One endpoint, fixed byte budget per cell, one total deadline, cancellation between and during cells. Sockets are protected and bound to the selected network.

## Migration Plan

The result is a new optional field in the diagnostics contract. Older saved reports stay readable. Validate the current schema version in Rust and Kotlin before changing the contract.
