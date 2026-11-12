## Context

The runtime sends initiation before it starts the peer deadline and checks Stop again. The live carrier is already protected and connected.

## Goals / Non-Goals

- Goal: cover the first write and authenticated response with one deadline and Stop observation.
- Non-goal: change the wire format, DNS routing, or recovery policy.

## Decisions

- Move initial initiation into the existing peer startup method. Select Stop against the complete startup operation, bounded by the existing peer timeout.
- Cancellation retires the current tunnel. The caller closes the listener and aborts and joins tasks. It never retries a buffered send.
- Use a controlled local carrier and assert completion and readiness.

## Contracts and ownership

- Native lifecycle agent owns `native/rust/crates/ripdpi-warp-core/src/amneziawg_runtime.rs` and test-only carrier construction.
- No Kotlin, JNI, storage, dependency, lockfile, locale, or baseline changes.
- Other agents own readiness/URL validation and service recovery in isolated worktrees.

## Risks / Trade-offs

- Boringtun commits initiation before I/O. Stop or deadline must retire the tunnel to avoid replaying a partial send.

## Migration Plan

No migration. Revert the fix for rollback. Run crate tests, async review and repository source gates before integration.
