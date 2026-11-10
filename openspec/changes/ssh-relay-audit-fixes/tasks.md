# RLY-1794300186609700

## Objective

Correct SSH trust, import, credential sharing, discovery fallback, and cancellation with a separate commit for each finding.

## Ownership

Native writer: ripdpi-ssh and the serialized Cargo/dependency patch lane. Import writer: shared admission and generic import/editor trust integration. Integration writer: share mapper/tests and service observer/tests. Planning writer: task and OpenSpec artifacts. See the portfolio task for exact shared-file boundaries.

## Execution

- [x] RLY-1794300262750090 Enforce host-key pin during rekey #bug !high @item:RLY-1794300186609700
- [x] RLY-1794300278412406 Validate SSH imports and require explicit trust before activation #bug !high @item:RLY-1794300186609700
- [x] RLY-1794300282442646 Preserve nonempty key passphrases through sharing #bug !high @item:RLY-1794300186609700
- [x] RLY-1794300285482245 Reserve discovery time for later resolved addresses #bug !high @item:RLY-1794300186609700
- [x] RLY-1794300288104643 Cancel SSH workers with retained unread channels #bug !high @item:RLY-1794300186609700
- [x] RLY-1794300290056513 Review all corrections and run the required combined gates #bug !high @item:RLY-1794300186609700

## Verification

Run affected Kotlin suites and cargo test -p ripdpi-ssh --locked with the narrow regression tests. Verify rekey and retained-channel close against an independent peer. Before integration run just ci-gates, architecture health, and locked cargo metadata. Record exact-SHA local evidence, review, hosted CI, device, artifact, and deployment states in verification.md.
