## Context

The audit at 80d2a664cdb1a0e1061cfc3a35f3e226a0deba4c has four local reproductions. The job starts from the current origin/main; the affected sources have no incoming changes.

## Goals / Non-Goals

- Goal: fix each confirmed finding in its own commit and test the H3 buffer and pseudo-field risks.
- Non-goal: add a browser, proxy H3/PMTU support, live injection attribution, external probes, or a new backend.

## Decisions

- Enforce malformed H3 fields in the shared decoder so order and duplicate data are still available. Add real trusted loopback coverage in the diagnostic caller.
- Prove and bound non-DATA frame buffering while keeping DATA streaming and extension frames compatible.
- Request identity HTTP content and explicitly reject unsupported coding. Do not add a decompressor or dependency for this repair.
- Fix fingerprint specificity in its owning shared matcher, with ordinary-content and real blockpage controls. Keep existing result schemas.
- Preserve both local and scan deadlines in PMTU, as the H3 probe already does.
- Use one writer per area and separate worktrees. Root owns all task files and serialized lanes. Ownership is recorded in the linked portfolio task.

## Contracts and ownership

- Affected Rust crates: ripdpi-diagnostics-http, ripdpi-failure-classifier, and the local h3 source patch. Check the monitor runner and Kotlin evidence consumers after integration.
- No JNI, protobuf, storage, configuration, or evidence schema change is planned. RAW_PATH/Direct admission, TLS trust, content privacy, and lower-bound MTU semantics remain required.
- No Cargo.lock, production dependency, locale, baseline, or golden update is planned. Root alone may resolve a required generated API snapshot change after review.
- Root runs task state, review, integration, validation, and push. Writers do not commit before independent review.

## Risks / Trade-offs

- A shared h3 fix can affect MASQUE and other callers. Run h3 tests, diagnostic HTTP tests, and affected dependent checks; preserve valid headers, trailers, unknown frames, and streamed DATA.
- A stricter text matcher can miss a short blockpage. Pair ordinary text controls with strong ISP and explicit blocking pages.
- An origin can ignore identity negotiation. Keep an explicit error in that case instead of clean success.
- Host tests do not establish Android runtime or external interoperability. Keep those acceptance gates separate and unclaimed.

## Migration Plan

No data migration or activation step is required. Each repair can be reverted by its own normal revert commit. Integration requires targeted regression results, affected-package fmt/clippy, independent pr-reviewer and async review where applicable, cargo metadata --locked, architecture health, and just ci-gates. No hosted CI, device, artifact, or deployment proof is inferred from local checks.
