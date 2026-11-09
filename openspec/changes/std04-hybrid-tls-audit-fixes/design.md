## Context

Task TRN-1794199027304873 addresses four confirmed findings from the STD-04 audit at 7b475fb9e1b433c80daee34db6cd2ec6b77f23a3. The current backend rejects invalid hybrid shares but overwrites their selected alert. Other findings concern metadata and source documentation.

## Goals / Non-Goals

- Goal: correct the alert and the three inaccurate contracts, with one fix commit per finding.
- Non-goal: change KEM preferences, implement PQ authentication, add Android KEM controls, change packet bytes, or expand relay protocol support.

## Decisions

- Set an internal-error default before Decap and retain its selected error. Test emitted bytes with an independent, hand-built loopback peer, including length, zero-component, wrong-group, and vector-framing boundaries.
- Use the existing Satisfied profile status for configuration checks. Keep the public enum shape and document size hints as reference metadata. Capture actual ClientHello records in the owning tests without modifying packet fixtures.
- Correct REALITY rustdoc and fake-profile provenance only; keep their runtime behavior and captures.
- Run focused crate tests first, then the combined CI source gates, architecture-health check, locked Cargo metadata, and independent read-only review before integration.

## Contracts and ownership

- Vendor BoringSSL: extensions.cc and vendor notes, owned by F1 worker. The wire error changes from internal_error to illegal_parameter for invalid peer inputs.
- ripdpi-tls-profiles metadata and existing tests, owned by F2 worker. The reported status changes to the existing satisfied value. No enum member, JNI/protobuf field, or storage schema changes.
- VLESS KEM rustdoc and fake-profile provenance, owned by the documentation worker as separate F3/F4 slices.
- Portfolio/OpenSpec/board files and main integration are owned only by the parent. All writers use separate worktrees.
- No serialized dependency, version, schema, locale, golden, or architecture-baseline file is changed.

## Risks / Trade-offs

- A common BoringSSL caller serves several groups. Preserve backend-selected alerts and test wrong-group/framing controls as well as valid fallback.
- A metadata consumer may have displayed the previous status. Keep the public type intact and check the current call sites and sample tests.
- Replacing a metadata claim does not implement an anti-517 network policy. The reference hint and measured bytes must remain distinct.
- Tests on the host do not establish Android ABI or external-server acceptance. Record those evidence categories as outside this local fix.

## Migration Plan

No persisted-data migration or deployment is needed. Apply each reviewed fix commit in the integration worktree, run the combined gates, rebase on fetched origin/main, and fast-forward main. Push main only after local validation. Rollback is a normal revert of the affected fix; no history rewrite is required.
