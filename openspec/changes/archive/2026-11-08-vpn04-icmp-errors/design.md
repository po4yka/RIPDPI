## Context

Task VPN-1794142179721255 fixes report findings F2, F3 and F1 in that order. Standard SOCKS5 UDP has no remote ICMP feedback frame. Treating its association REP as remote port evidence is incorrect.

## Goals / Non-Goals

- Goal: exact raw quotes and reachable, bounded local error feedback for IPv4/IPv6 without root.
- Non-goal: a new proxy feedback protocol, remote ICMP/echo relay or PMTU support.

## Decisions

- Use etherparse 0.21.0 for outer packet serialization and existing smoltcp 0.14.0 plus independent fixed vectors for checksum verification.
- Capture only a validated bounded original prefix. Preserve options, extension bytes and UDP checksum. Suppress IPv6 routing-header inputs until final-destination checksum semantics are supported.
- Retain at most one prebuilt local error packet on an association after its first successful queue operation. Local failures use configured tunnel IPv4/IPv6 addresses; types/codes are 3/1 and 1/3.
- Report only deterministic setup failures. Timeout, congestion, cancellation and uncorrelated receive failures remain silent. Standard SOCKS remote closed-port evidence is unavailable; do not invent it.
- The single TUN owner validates event generation, applies one token bucket, queues the error and removes the association. No new lock or per-packet JNI call.
- Reassembled datagrams keep forwarding, but cannot supply an original wire quote and do not originate errors. Preserve this explicit boundary.

## Contracts and ownership

- One Rust crate: ripdpi-tunnel-core. JNI/protobuf/config/storage/SOCKS wire contracts unchanged.
- Wire worker owns packet/icmp.rs and packet tests; primary owns runtime and all planning files. Separate worktrees; primary integrates wire commits before runtime validation.
- No serialized high-risk files, golden fixtures, production dependencies or baselines are changed.

## Risks / Trade-offs

- Wrong packet attribution: retain only the first admitted and successfully queued context; reject stale generations.
- Error storms: one owner, token bucket burst 10 and 10/s; queue bounds stay in force.
- Payload privacy: bounded RAM only, no payload logging or persistence.
- Android delivery remains an acceptance boundary: host socketpair exercises the real packet loop but cannot prove app-visible Android socket errors.

## Migration Plan

No migration or activation flag. Separate conventional commits fix F2, F3 and F1; rollback is a revert. Before integration run affected crate tests and Clippy, independent reviews, just ci-gates, architecture-health check and cargo metadata --locked. Device/external network acceptance is recorded separately and is not replaced by host evidence.
