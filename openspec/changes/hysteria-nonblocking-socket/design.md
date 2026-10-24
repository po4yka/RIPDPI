## Context

The shared UDP builder binds and protects a socket. Salamander passes that socket directly to Tokio. Tokio requires nonblocking operation and rejects a blocking socket. Initial Hysteria endpoints and local endpoint rebind both reach this constructor.

## Goals / Non-Goals

- Goal: safely admit the bound socket for asynchronous Salamander traffic.
- Non-goal: new hopping behavior, socket protection changes, native dependencies, Android state changes, or peer deployment.

## Decisions

- Call `set_nonblocking(true)` on the supplied socket immediately before Tokio registration and propagate errors with `?`.
- Keep this precondition in the constructor so every caller receives the same rule. Do not duplicate it only in initial endpoint setup.
- Retain socket ownership, descriptor protection, and bound address. No replacement socket is created.
- Use real loopback UDP exchange for blocking and nonblocking inputs, plus initial endpoint and rebind coverage. Bound async tests with timeouts.

## Contracts and ownership

- Runtime worker owns `ripdpi-hysteria2` socket admission and regression tests plus this task/spec.
- Integration owner serializes the generated task board with other delivery changes and owns Android rebuild and device acceptance.
- No public Kotlin, JNI, wire, storage, or configuration contract changes. The pinned Rust lockfile is unchanged.

## Risks / Trade-offs

- Cloned descriptors share nonblocking mode. This is required for their use by the async endpoint; the clone remains the same bound socket used for migration state.
- Local regression success does not establish physical payload transfer. Device acceptance stays open until measured.

## Migration Plan

No persistent migration is needed. Run the complete Hysteria crate tests and Clippy with the lockfile under `build-gate`, then formatting, task validation, and independent review. Integrate the source, rebuild the Android artifact, and repeat physical Hysteria payload and UDP acceptance. Reverting this isolated source change restores the previous implementation but also restores the known panic.
