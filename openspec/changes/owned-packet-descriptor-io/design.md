## Context

Task ID: `VPN-1793035703372515`. The platform supplies a raw IP packet descriptor. The packet loop uses only readiness and packet reads/writes. tun-rs 2.8.13 and 2.8.14 validate Linux TUN metadata, so neither can wrap the socketpair used by existing packet-loop tests.

## Goals / Non-Goals

- Goal: support the existing owned raw-packet descriptor contract on all current Unix targets.
- Non-goal: create interfaces, change routes, or change Android/JNI/configuration behavior.

## Decisions

- Add PacketFd in ripdpi-tunnel-core. Store a standard File in Tokio AsyncFd; use standard File read/write operations. Set O_NONBLOCK with libc fcntl while preserving existing flags.
- Use AsyncFd readiness and try_io so WouldBlock clears the matching readiness state. Keep one read/write syscall per packet.
- Keep tun-rs 2.8.14 in ripdpi-tun-driver for actual interface management. Remove the tunnel-core dependency on tun-rs and promote its existing libc test dependency to a regular dependency. No new package is introduced.
- Reject version rollback: 2.8.13 has the same ioctl check. Reject fallback constructors and test-specific production paths: they would hide the mismatched descriptor contract.

## Contracts and ownership

- run_tunnel, run_tunnel_with_ready, OwnedFd ownership, Android JNI, wire formats, settings, and storage stay the same.
- BREAKING: low-level Rust io_loop_task uses PacketFd in place of AsyncDevice. Update every workspace call site. Do not add a compatibility path.
- The author owns Cargo.lock, the tunnel-core manifest, source type changes, and this task record. Review agents are read-only.
- File retains ownership before all fallible setup. AsyncFd retains it after registration. Error, unwind, and normal drop close it once. Readiness futures do not consume packets and are cancel-safe.

## Risks / Trade-offs

- Readiness can be stale: use try_io and return WouldBlock without retries in the adapter.
- Wrong packet framing corrupts packets: test byte-exact IPv4/IPv6-like frames and run existing TCP E2E tests.
- Platform code uses fcntl: audit each unsafe call and preserve flags; run debug tests and Clippy. Hosted Linux tests complement local macOS tests.

## Migration Plan

Update packet-loop types and all call sites in one commit. No data migration is needed. Rollback is a source revert. Gates: cargo test -p ripdpi-tunnel-core --locked; cargo clippy -p ripdpi-tunnel-core --locked --all-targets -- -D warnings; architecture and task validation; full hosted CI including native ABI builds and Rust coverage. Hardware acceptance is a separate scope.
