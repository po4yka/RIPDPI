## Context

See proposal.md. The Rust TUN already implements command 0x80 and the RUF1 nonce envelope in ripdpi-socks5-core. The pinned Go managed inbound currently accepts raw UDP through an IP-only filter. Diagnostic UDP probes still use standard associations.

## Goals / Non-Goals

- Goal: use the existing local authenticated datagram contract for all managed Xray callers.
- Goal: retain domain plus numeric pin handling, bounded authorities and linked cleanup failure ownership.
- Non-goal: add remote protocols, change VLESS flow policy, update dependency pins or publish a release.

## Decisions

- Reuse command 0x80 and RUF1; do not create another envelope or dependency.
- Each authenticated private association owns a loopback UDP socket, a fresh 128-bit random nonce and its dispatcher lifetime. Check source, nonce, frame bounds and request ordering before decoding payload.
- Reject standard UDP association on the managed inbound. Shared UDP ingress must not grant an alternate path.
- Upgrade diagnostic authenticated loopback associations to this existing contract. Allow standard fallback only after explicit unsupported-command reply on a new authenticated control connection.
- Make cleanup of the never-started stub succeed. Keep linked cleanup failure semantics unchanged.

## Contracts and ownership

- Portfolio RLY-1794335291032951 records exact writer paths and isolated worktrees.
- Go worker owns the patch manifest and source patch. Rust worker owns the diagnostic client. Stub worker owns stub source and tests. Integrator owns combined docs and validation.
- No Cargo.lock, Gradle dependency or golden fixture changes are planned.

## Risks / Trade-offs

- Standard clients cannot send UDP through the private managed inbound; update in-app callers and verify no alternate standard path remains.
- Control shutdown and concurrent datagrams must close dispatchers and reject stale frames; test these with independent loopback peers.
- A rebuilt AAR is required to package the corrected Go source. Source tests do not prove historical shipped artifact provenance.

## Migration Plan

Rebuild the pinned patched AAR with its new patch digest. Existing Rust TUN already speaks RUF1. Update diagnostic callers in the same atomic UDP fix. Rollback must roll back the paired source and patch digest, without claiming mixed-version support. Run owning Kotlin/Rust/Go tests, independent review and offline source gates before integration; record artifact and device evidence separately.
