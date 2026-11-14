## Context

Task ENG-1794637887235609 addresses three observed DPI-20 audit findings. The synchronous UDP association owns flow state and egress sockets.

## Goals / Non-Goals

- Goal: finite memory and work, faithful configuration, and correlated success evidence.
- Non-goal: change decoy keys or tags, implement version negotiation, add root behavior, or claim external DPI compatibility.

## Decisions

- Reuse the existing count limit of 64. Validate per-step and aggregate counts, and defend the planner independently. Bound packet size before cloning. This limits DPI-20 payload allocation to 4194240 bytes per plan without a cancellation redesign.
- Copy the selected fake version in the existing UDP group conversion owner.
- Retain a bounded real Initial in the session and reuse packet response validation. Keep unmatched bytes transparent but exclude them from inbound progress and success. Preserve generic UDP and target-reset ownership.

## Contracts and ownership

Ownership is recorded in the linked portfolio record. Workers have disjoint production files and separate worktrees. No dependency, lockfile, schema, locale, golden, public API or baseline changes are planned. No JNI or storage migration is needed.

## Risks / Trade-offs

- Existing excessive counts are rejected; count 0 and normal counts retain their behavior.
- Initial response correlation must cover retransmission, Retry, late VN and target reset without filtering generic UDP.
- The existing mixed wire test has a stale DummyPrepend assertion; fix its expected semantic boundary rather than add an unrelated method change.

## Migration Plan

No stored-data migration. Revert the individual fix commit for rollback. Run owning regressions, affected Rust crate tests and strict Clippy, Kotlin model tests, just ci-gates, architecture health and cargo metadata --locked on the combined tree. Observe a direct loopback wire test for v1/v2. Physical devices, external peers and releases are outside this local correction scope.
