---
id: TRN-1794198826397434
title: Repair STD-05 profile and rotation contracts
kind: bug
status: doing
area: transport
priority: high
owner: tls-profile-integrator
parent: null
blocked_by: []
spec_mode: required
openspec_change: repair-std05-profile-contracts
created: 2026-11-09
updated: 2026-11-09
---

## Goal

Repair all six confirmed STD-05 findings with one repair commit per finding. Preserve the network registry and audit report.

## Acceptance criteria

- Static metadata does not claim an unmeasured ClientHello length.
- Validation preserves the rotation seed and counter and accepts native_default.
- Successful TLS 1.2 and TLS 1.3 probes retain the first-flight plan.
- Tests and documentation distinguish preset snapshots from browser parity and state the pinned backend limit.
- Regressions, independent review, affected tests, Clippy and combined ci-gates pass before authorized main integration and push.

## Ownership and serialized lanes

- Integrator: F1 profile metadata/invariant code and its regression; planning, task files, generated API snapshots and Cargo.lock.
- rotation_alias: F2 and F4 xHTTP validation and its owning tests, one commit each.
- diagnostic_flight: F3 diagnostics-tls capture and tests; its test-only manifest edge after integrator approval. Integrator alone updates Cargo.lock.
- parity_claims: F5 docs, preset snapshot test descriptions and the related rotation.rs/pq_kem.rs doc comments; F6 backend-limit comments; one commit each. Its tests.rs edits start at the acceptance-consistency test; integrator owns earlier F1 assertions. Profile.rs comment edits are serialized after F1.
- Each writer has a separate worktree. No worker may merge or push. Review agents are read-only.
- The integrator owns all shared manifests, task board generation and fixture decisions. No golden or baseline change is planned. Public/physical probes, root and real credentials are excluded.
