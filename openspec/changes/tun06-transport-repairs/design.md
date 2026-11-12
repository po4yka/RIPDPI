## Context

Task ID: `TRN-1794494451740063`. Repair F1-F7 from the TUN-06 audit on current main. The user authorizes implementation, one fix commit per finding, main integration, and push. Planning and implementation run in this authorized task.

## Goals / Non-Goals

- Goal: repair each confirmed defect with a regression test and preserve supported paths.
- Non-goal: add MTProxy secrets, a new transport, IPv6 bootstrap, or external acceptance without permission.

## Decisions

- Set both Worker sockets to arraybuffer before acceptance. Keep binary ordering and the existing size cap. Accept only canonical kws1-5 hosts and production/test paths.
- Propagate an empty close through `close()` without arguments. Map abnormal observation code 1006 to valid error code 1011. Preserve ordinary wire status and reason. A real local workerd empty-close test confirmed F8: the old relay throws for reserved code 1005 and changes the downstream result to 1011.
- Classify structurally valid direct init against the exact destination before any carrier opens. Outside the exact table, retain existing explicit-DC behavior. Do not trust a subnet guess over client context.
- Share init completion across fresh, preserved, and prepared paths with a total read budget. Keep collected bytes on EOF/error and preserve remainder.
- Reuse the existing cancellable open and prepared relay interfaces. Apply one DNS/open budget and runtime shutdown callback to legacy and changed-DC paths. Do not add a second transport abstraction.
- Reject the forbidden zero second word before decrypt/classify.
- Use copy-on-write credential references when replacing an existing reference. Save the new secret, publish the settings, then remove the old reference. Clear settings before removing the old secret. Make the bounded durable publication sequence non-cancellable. A failure after publication must not delete the newly active secret. A process kill may leave an encrypted orphan, but cannot change the active pair to a partial pair.

## Contracts and ownership

- Runtime writer: WS tunnel/transport/bootstrap and proxy runtime/adapters, plus owning tests; F2/F4/F5/F7.
- Worker writer: reference Worker, local tests, guide, and dedicated Worker CI workflow; F1/F3/F8.
- Credential writer: core:data:runtime-state provisioner/store/callers and tests; F6.
- Coordinator: task and OpenSpec files, combined generated snapshots, validation, and serial integration. Ownership is also recorded in the portfolio task before writes.
- No protobuf/JNI/wire schema, dependency lock, locale, golden, or baseline edits are planned. Report any required contract expansion before changing it.

## Risks / Trade-offs

- Exact DC authority applies only to known direct destinations; no claim of complete Telegram address coverage.
- Credential copy-on-write can leave inactive encrypted records after a process kill. Active settings remain coherent; no startup sweep or storage migration is needed.
- Local tests do not establish external Telegram or device compatibility. Use real local Worker runtime where available; record a blocked runtime separately from Node tests.
- Gates: targeted Rust tests with --locked; Worker Node and local runtime checks; runtime-state unit tests and affected Kotlin lint; independent review; just ci-gates; architecture-health check and cargo metadata --locked before integration.

## Migration Plan

No schema migration. Existing references remain readable; replacement can return a new opaque reference. No external deployment. Integrate only reviewed code and observed gates. Rollback uses an ordinary revert after confirming affected durable state remains readable; do not rewrite shared history.
