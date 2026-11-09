## Context

The proposal links the confirmed STD-06 findings to TRN-1794198667426286. BoringSSL validates CA/name before its per-certificate callback. Its pinned hostname implementation uses CN only when SAN is absent. The standard connector loads filesystem trust before the application adds Mozilla roots. Diagnostic helpers carry an unused boolean through their public APIs.

## Goals / Non-Goals

- Goal: reject CN-only identities, remove implicit trust sources, and make the diagnostic verification override explicit.
- Non-goal: change pin-only modes, add online revocation, extend the blocklist, change trust-anchor block policy, or audit external engines.

## Decisions

- Reject a depth-zero leaf without a parsed SAN in the existing standard BoringSSL callback. Pinned BoringSSL already rejects a SAN that does not match the reference identity. This removes CN fallback without resetting caller hostflags. Do not use a context-level NEVER_CHECK_SUBJECT flag: connector setup can replace it.
- Build and populate a new mutable X509 store before assigning it to the connector. Use the builder setter that retains later cert_store_mut access. Propagate root insertion errors.
- Remove verify_certificates from diagnostic helpers and ProbeStreamOptions. Keep the optional verifier as the only policy override. Do not add an automatic trust-all branch.
- Keep WebTunnel custom chain pins, DoT pin callbacks, REALITY, and observational custom verifiers as explicit separate policies.

## Contracts and ownership

- san_identity owns the TLS blocklist callback and SAN integration tests in its isolated worktree.
- explicit_roots owns trust.rs and trust-store tests in its isolated worktree.
- diagnostics_policy owns the diagnostic API and every affected Rust caller/test in its isolated worktree.
- The primary agent owns task/OpenSpec files, generated API snapshots if required, combined validation, and integration. Cargo.lock, dependency manifests, locale resources, goldens, and baselines are outside the planned write set.
- No JNI, protobuf, wire, storage, or configuration schema changes. The diagnostic Rust signatures change across workspace crates; all call sites must compile together.

## Risks / Trade-offs

- A legacy private CA leaf with only CN stops working. This is the intended RFC 9525 identity policy; keep valid DNS/IP SAN controls.
- Resetting trust can remove a root that an operator provided through environment variables. That source was not part of the declared native trust contract; explicit roots remain available.
- Callback edits could affect pin-only modes. Run existing WebTunnel and DoT pin handshakes, including mismatch cases, on the combined tree.
- Subprocess tests must not change global environment in a threaded test process. Use child-scoped variables and prove each environment trust fixture works against plain BoringSSL.

## Migration Plan

Update all diagnostic callers in one atomic commit. Use one separate implementation commit for each confirmed finding. Run focused red/green regressions, affected crate tests and clippy, cargo metadata --locked, architecture health and just ci-gates. Obtain an independent read-only review before implementation commits. Rebase on origin/main, rerun gates if new inputs arrive, fast-forward main, then push as authorized. Rollback is a normal revert of the corresponding defect commit; no data migration is required.
