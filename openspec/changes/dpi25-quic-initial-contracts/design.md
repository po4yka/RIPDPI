## Context

See proposal.md and portfolio ENG-1794638006879351. The strategy is a synthetic prelude, not a QUIC endpoint.

## Goals / Non-Goals

- Goal: close the five reproduced defects with owning regression tests and independent local packet checks.
- Non-goal: browser-version parity, external DPI effectiveness, root transport, physical devices, or full QUIC handshake recovery.

## Decisions

- Reuse the existing protocol-correct probe ClientHello helper through a used explicit-CID seed API. Keep frozen legacy builders and hash tests unchanged.
- Use independent connections for alternating profiles. A single stream cannot carry different ClientHello messages at the same CRYPTO offset.
- Keep a count limit of 32 for each step and for the entire DPI-25 chain. Fail before allocation for invalid internal models.
- Use the conservative 1200-byte QUIC datagram size without remote PMTU evidence. Bound the final encoded packet, not only its requested minimum.
- Propagate fixed/adaptive TTL and use the existing resolver. Do not train adaptive TTL from relay outcomes where no prelude ran.

## Contracts and ownership

- Kotlin :core:data:model and Rust ripdpi-config/ripdpi-proxy-config own matching count validation.
- ripdpi-packets owns the new seed and its SCID transport parameter; ripdpi-desync owns independent IDs and final packet budget.
- The UDP runtime adapter owns resolved TTL and socket execution. The real datagram and SOCKS fallback stay intact.
- Portfolio ownership lists the isolated worktrees and serialized desync file lane. The integrator alone regenerates API snapshots. Cargo.lock, baselines and golden fixtures are excluded.

## Risks / Trade-offs

- Secure random failure omits a decoy; original delivery remains available.
- A 1200-byte budget removes unverified MTU expansion. It does not claim exact browser packet sizes.
- Counts above 32 now fail validation; the error names the count bound. No silent saved-config clamping.

## Migration Plan

No field migration is needed. Validate saved config against the new count bound. Revert the corresponding source commits for rollback. Run owning Rust/Kotlin tests, static analysis, independent local oracle, API checks, ci-gates and a read-only independent review before fast-forward integration. Hosted CI and device evidence are recorded separately.
