---
title: Consolidate native Rust manual implementations with vendored deps and stdlib replacements
type: epic
status: backlog
area: epic
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [ ] #task Consolidate native Rust manual implementations with vendored deps and stdlib replacements #repo/RIPDPI #area/epic #status/backlog ⏫

## Goal

Eliminate hand-rolled code where industry-standard crates provide equivalent or superior implementations. Reduce production `unsafe` block count, eliminate duplicated SOCKS5 wire-protocol logic across 5 crates, and replace fragile bespoke utilities (CLI parsers, daemonize, ring buffer, waker vtable) with well-tested alternatives.

## Why now

A full audit of the 81-crate native workspace identified ~75 production `unsafe` blocks and 20+ hand-rolled patterns. The top 10 findings represent high effort-to-value ratios with low regression risk. Consolidating now prevents each pattern from being copied further as the codebase grows.

## Key decisions

- `fast-socks5` is **vendored** (source copied to `crates/ripdpi-socks5-core/`) rather than taken as an external dependency. This keeps the build hermetic, allows project-specific patches, and removes the upstream version-pin risk.
- `tokio-util`, `nix`, `flume` are already transitive workspace deps — adding them explicitly to new crate `[dependencies]` is zero additional supply-chain risk.
- `waker-fn`, `pollster`, `pico-args`, `daemonize`, `enum-map`, `ringbuf` are new workspace deps; each is evaluated and approved per-task.
- Domain-specific hand-rolled code (DNSCrypt, MTProto obfuscation, TLS choreography, QUIC varint, HTTPS SVCB TLV) is explicitly **kept** — no upstream crate covers those.

## Scope

| # | Child task | Unsafe Δ | Lines removed |
|---|---|---|---|
| 1 | Vendor fast-socks5 and consolidate 5 SOCKS5 impls | 0 | ~500 |
| 2 | Replace SCM_RIGHTS unsafe with nix recvmsg | −15 | ~80 |
| 3 | Replace io-uring RawWaker + block_on with waker-fn + pollster | −5 | ~60 |
| 4 | Replace 3 hand-written CLI parsers with pico-args | 0 | ~120 |
| 5 | Replace libc::daemon + fcntl with daemonize crate | −3 | ~30 |
| 6 | Replace EnumMap with enum-map crate | 0 | ~60 |
| 7 | Replace ring buffer with ringbuf crate | 0 | ~160 |
| 8 | Replace android-support event queues with flume::bounded | 0 | ~80 |
| 9 | Replace DNS TCP framing with LengthDelimitedCodec | 0 | ~40 |
| 10 | Replace DNS name label parser with hickory-proto BinDecoder | 0 | ~40 |

**Total:** −23 unsafe blocks, ~1 170 lines removed.

## Ship definition

- All 10 child tasks at `#status/done`.
- `cargo nextest run --workspace` passes.
- `cargo clippy --workspace` passes with no new warnings.
- Android `./gradlew assembleRelease` produces a valid APK.
- Unsafe block count in `native/rust/` does not exceed the pre-epic baseline minus 23.
