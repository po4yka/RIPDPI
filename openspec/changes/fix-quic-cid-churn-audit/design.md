## Context

Task ENG-1794637897912969 fixes the three confirmed defects in proposal.md. The base revision is 74a434befe11cff7cf17226918201d251c89510c.

## Goals / Non-Goals

- Goal: bounded CID churn, distinct IDs within one plan, exact Initial padding.
- Non-goal: live QUIC migration, global random IDs, new dependencies, external network acceptance.

## Decisions

- Reuse the established count limit of 64. Validate each CID step and their total in Kotlin and native configuration. Clamp internally constructed plans before generation. Normalized inputs are at most 65535 bytes, so 64 packets also bound retained payload memory.
- Keep a per-plan CID ordinal across chain steps. Mutate the full synthetic DCID from that ordinal; derive keys after mutation. Do not retain state between flows or rounds.
- Select the final QUIC Length width before padding. A legal non-minimal varint can represent transition-gap targets without oscillation or extra bytes.
- Fix packet sizing at its owner. Do not change generic UDP error handling to hide malformed decoys.

## Contracts and ownership

- cid_budget owns core/data/model strategy validation/tests, ripdpi-proxy-config UDP validation/tests, and ripdpi-desync plan_udp.rs plus private budget tests.
- cid_uniqueness owns ripdpi-desync packet_family.rs and churn_gap.rs plus private CID tests.
- initial_length owns ripdpi-packets quic/build.rs and focused packet tests; a separate runtime regression test may be added.
- The integrator owns task/OpenSpec files, combined validation, review, commits, main integration, and push.
- No serialized shared dependency, protobuf/JNI/storage schema, locale, golden, or quality baseline changes are planned. Exported API shapes remain unchanged.

## Risks / Trade-offs

- Previously accepted excessive counts become validation errors. This is intentional input hardening.
- Exact sizing changes packets above a Length varint transition. Independent decryption and IPv4 loopback tests must verify integrity.
- Unit and host wire checks do not prove Android, external server, or DPI compatibility.

## Migration Plan

No data migration is required. Create one fix commit for each defect. Validate targeted Kotlin/Rust suites, independent host wire results, pr-reviewer, just ci-gates, architecture health, and locked Cargo metadata before main integration. No new dependencies or quality baseline exceptions are allowed. Revert the affected fix commit if rollback is required. Remote CI runs after the authorized push; do not infer its result from local checks.
