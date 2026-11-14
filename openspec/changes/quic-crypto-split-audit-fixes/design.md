## Context

DPI-19 is a low-TTL decoy prelude followed by the original datagram. Its packetizer is stateless and its executor is synchronous. See proposal.md.

## Goals / Non-Goals

- Goal: resolve F1 through F5 with one fix commit per finding and observed regression evidence.
- Non-goal: endpoint PN/ACK rewriting, cross-datagram reassembly, new versions, or external/device acceptance.

## Decisions

- Reject split packets larger than the input datagram in the shared input packetizer helper. Retain runtime protection when optional plain prelude sends fail with local size or buffer errors; skip remaining decoys, restore the known default TTL and send the pending original with explicit fallback evidence. Network/ICMP flow errors remain terminal.
- Copy fixed TTL policy when the UDP group is built. Do not add adaptive TTL behavior in this change.
- Compare covered CRYPTO bytes before copy; reject conflicts instead of selecting last-wins semantics.
- Limit each and combined CRYPTO split count to 64. Apply a remaining quota before packet cloning for typed planner callers. This bounds CRYPTO split output bytes to about 4 MiB at the accepted datagram limit.
- Reject input shorter than 1200 in the planner normalizer, not in the coalesced packet header parser.

## Contracts and ownership

Follow the Parallel ownership section in the portfolio task. Kotlin count validation and Rust conversion must agree. A new Rust-only fallback reason may map to the existing wire fallback category; no diagnostics schema change is required. Parent owns plan_udp/quic.rs and existing tests; the count worker owns plan_udp.rs and its separate tests. Count worker alone owns API snapshot edits. No dependencies, locks, locales, goldens, or JNI/storage schema changes are planned.

## Risks / Trade-offs

- Rejecting an oversized prelude reduces shaping coverage but preserves original data. The receipt must distinguish fallback from Applied.
- Malformed overlaps previously selected the last byte; they now decline shaping.
- Saved counts above the new limit fail validation. Ordinary counts remain stable.
- Local wire decoding does not establish Android or external DPI behavior.

## Migration Plan

No persisted schema migration is required. Revert the individual finding commit if needed; do not roll back by removing quality gates. Run owning Rust and Kotlin tests, independent loopback byte decoding, a read-only independent review, just ci-gates, architecture health and locked Cargo metadata before fast-forward integration. No device or external endpoint run is required for these local regression fixes. Record hosted CI separately after the authorized push.
