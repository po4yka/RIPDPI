---
title: Add desync trait-split parity tests for TcpDesyncPlatform sub-traits
type: task
status: doing
area: rust-native
priority: high
owner: Senior Network Protocol Engineer
parent: null
blocks: []
blocked_by: []
created: 2026-05-04
updated: 2026-05-04
---

- [ ] #task Add desync trait-split parity tests for TcpDesyncPlatform sub-traits #repo/RIPDPI #area/rust-native #status/doing ⏫

Owner: Senior Network Protocol Engineer (with QA Lead review).

Context
ripdpi-desync-runtime split TcpDesyncPlatform into five capability traits (TcpPlatformCapabilities, TcpSocketOptions, TcpFakeSender, TcpPayloadSender, TcpFragmentSender) plus a blanket impl. Without dedicated tests, future trait splits or impl drift could silently break runtime callers.

Acceptance criteria
- Compile-time guard `fn _assert_impl<T: TcpDesyncPlatform>() {}` covering existing call sites.
- Unit tests on TestTcpDesyncPlatform exercising each of the five sub-traits independently.
- `cargo nextest run -p ripdpi-desync-runtime` green.
- No live network; no payload capture.

Definition of done
PR merged with green tests; QA Lead acknowledges parity coverage in POY-4.
