## Context

Task ENG-1794637887235609 addresses three observed DPI-20 audit findings. The synchronous UDP association owns flow state and egress sockets.

## Goals / Non-Goals

- Goal: finite memory and work, faithful configuration, and correlated success evidence.
- Non-goal: change decoy keys or tags, implement version negotiation, add root behavior, or claim external DPI compatibility.

## Decisions

- Reuse the existing count limit of 64. Validate per-step and aggregate counts, and defend the planner independently. Bound packet size before cloning. This limits DPI-20 payload allocation to 4194240 bytes per plan without a cancellation redesign.
- Copy the selected fake version in the existing UDP group conversion owner.
- Reuse the upstream compact Initial response contexts and commit-aware registration. They retain at most 64 connection contexts and one pending Initial of at most 65535 bytes. They cover partial ClientHello, compatible versions, Retry, retransmission, and multiplexing. Reject late VN after a real response without retaining packet bytes after acceptance. Reset this guard on target reselection. Keep forwarding transparent and preserve generic UDP behavior.

## Contracts and ownership

Ownership is recorded in the linked portfolio record. Workers have disjoint production files and separate worktrees. No dependency, lockfile, schema, locale, golden or baseline changes are planned. The current runtime adapter owns QUIC response classification; no new helper or public API is needed. No JNI or storage migration is needed.

## Risks / Trade-offs

- Existing excessive counts are rejected; count 0 and normal counts retain their behavior.
- Initial response correlation must cover retransmission, Retry, late VN and target reset without filtering generic UDP.
- Current upstream fixes supersede the old CID-only response matcher and stale DummyPrepend wire assertion. Preserve those fixes during integration.

## Migration Plan

No stored-data migration. Revert the individual fix commit for rollback. Run owning regressions, affected Rust crate tests and strict Clippy, Kotlin model tests, just ci-gates, architecture health and cargo metadata --locked on the combined tree. Observe a direct loopback wire test for v1/v2. Physical devices, external peers and releases are outside this local correction scope.
