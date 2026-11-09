---
id: TRN-1794198667426286
title: Correct standard TLS certificate verification policies
kind: bug
status: doing
area: transport
priority: high
owner: TLS policy maintainers
parent: null
blocked_by: []
spec_mode: required
openspec_change: std06-certificate-policies
created: 2026-11-09
updated: 2026-11-09
---

## Goal

Correct the three confirmed STD-06 audit defects: SAN-only server identity, explicit native trust roots, and one diagnostic verification policy.

## Acceptance criteria

- Standard BoringSSL clients reject CN-only certificates in TLS 1.2 and TLS 1.3. Valid DNS/IP SAN certificates still work.
- SSL_CERT_FILE and a hashed SSL_CERT_DIR cannot add implicit trust. Explicit caller roots still work.
- Diagnostic helpers use only the explicit verifier override. The unused verification flag and all callers are removed.
- WebTunnel and DoT pin-only policies retain their behavior.
- Focused regressions, affected Rust tests and lint checks, API contracts, and `just ci-gates` pass. Independent review has no unresolved findings.

## Ownership

- san_identity: `native/rust/crates/ripdpi-tls-profiles/src/cert_blocklist.rs`, SAN identity integration tests, and related identity helpers if needed; isolated worktree `RIPDPI-std06-san-identity`.
- explicit_roots: `native/rust/crates/ripdpi-tls-profiles/src/trust.rs` and trust-store integration tests; isolated worktree `RIPDPI-std06-explicit-roots`.
- diagnostics_policy: diagnostic helper/options API and all affected Rust callers and behavior tests; isolated worktree `RIPDPI-std06-diagnostics-policy`.
- Primary agent: OpenSpec and task files, combined integration, generated API snapshots, validation, review coordination, and commits. Serialized shared files: no Cargo.lock, manifests, baselines, locale or golden changes are planned. Primary agent owns any required generated API snapshot update.
- Each confirmed defect has a separate implementation commit. Workers do not commit before independent review.
