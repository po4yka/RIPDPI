## Context

TRN-03 found strict option and session checks before status dispatch, and domain encoding with only a length check. The reference is Xray-core 0afec66085a85191e9dd2a4a6646c7c9a94688d1.

## Goals / Non-Goals

- Goal: preserve valid control and datagram traffic; reject incompatible addresses locally.
- Non-goal: reconnect, general multiplexing, padding extensions, or larger payload budgets.

## Decisions

- Dispatch control status before enforcing the Single XUDP data session ID.
- Keep one reader task and one writer task; no task ownership or queue changes.
- Validate domains at the XUDP boundary, not in the shared SOCKS parser.
- Use existing unit tests, protocol-derived byte vectors, and the pinned loopback peer.

## Contracts and ownership

The root agent owns native/rust/crates/ripdpi-vless/src/xudp.rs, its test modules, and these planning records. Subagents inspect contracts and review without writing. The target-validation worker owns F2 in a separate worktree. Root owns F1 here and integrates the F2 patch after committing F1. No writers share a physical file; no shared schema/dependency/baseline edits are planned.

## Risks / Trade-offs

- Unknown options must still fail closed.
- Rejected target input must not advance NEW state or publish source authority.
- The current 7526-byte payload and 512-byte metadata limits remain unchanged.

## Migration Plan

No migration or activation is needed. Revert each repair commit separately to roll back. Run ripdpi-vless tests and clippy, pinned Xray interop, just ci-gates, architecture health, and locked Cargo metadata before integration. Independent review and async cancellation audit are required. Device, release artifact, and deployment validation are not applicable to this host codec repair; hosted CI follows the push and is not local completion evidence.
