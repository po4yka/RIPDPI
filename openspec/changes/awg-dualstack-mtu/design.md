## Context

Bundle import currently filters address arrays and defaults missing MTU; the native runtime raises low MTU to 1280. The deployment contract now supplies ordered dual-stack host addresses and a shared 1280..1420 MTU.

## Goals / Non-Goals

- Goal: exact transport admission and real independent-peer consumer verification.
- Non-goal: change standalone INI address ordering, native wire shape, production runtime, VPN TUN policy, or deploy infrastructure.

## Decisions

- Validate bundle transport before constructing a subscription profile; use numeric-only syntax plus URI IPv6 validation without DNS. Reuse the existing INVALID_NETWORK_POLICY skipped-entry reason and name the offending field.
- Reject out-of-range activation MTU so Android and native values cannot diverge through native clamping.
- Keep schema version 1 and synchronize the canonical deployment schema/example/negative artifacts byte-identically.
- The Android gate is emulator/native_core only and uses the actual production AWG JNI library. JVM fakes and host tests do not count as Android evidence. The independent peer is AWG v0.2.12 at commit 2e3f7d122ca8ef61e403fddc48a9db8fccd95dbf, built with Go 1.27.1 and loopback-only host transport.
- The isolated host obtains Android VPN authorization and registers a real `VpnService.protect(fd)` callback before native startup. It records the actual platform result and verifies that no Android VPN is active; it never establishes a TUN or substitutes a successful protection callback. Native exit before authenticated readiness fails immediately.
- Boot completion does not establish emulator network readiness. Before native startup, require an actual active underlay with an IPv4 route to the owned host peer; do not retry a failed tunnel. For each MTU retain one independent peer through cycle 0 and three subsequent native session restarts, verifying all traffic in each of the eight measured sessions.
- Physical-device and Android VpnService TUN/no-leak checks are not gates for this change. Linux forwarding/no-leak evidence remains owned by the producer task.

## Contracts and ownership

The portfolio ownership section assigns parser, activation, affected tests, contract mirrors, independent peer and instrumentation to this single writer. No production dependencies, Cargo.lock, protobuf or JNI shape changes. Source contract producer is `po4yka/ripdpi-vpn-deploy#ANS-1791618048083304`.

## Cross-repository task resolution

The producer consumes `./taskctl export --json` and on-demand `export --resolve-task ID --json`. This adapter validates the existing native portfolio and receipts, qualifies native relations, and reports unassessed risk as null. It does not retag issue records or rewrite terminal receipts. Export requires committed tasking, portfolio, OpenSpec and project-contract inputs, then binds the envelope to HEAD and the exact shared contract digest.

Purged tasks resolve only through native closure provenance checks, including the committed review-to-terminal transition, receipt hashes and archived execution. The exported historical node comes from the receipt-bound terminal snapshot and records terminal/deletion revisions. Missing or invalid provenance fails visibly. The consumer writer owns the task CLI, its tests and the new project/contract metadata for this adapter; dependency/tool pins and native issue schema remain unchanged.

## Risks / Trade-offs

Strict admission breaks malformed or old single-family deployment bundles; producers and fixtures migrate together. Standalone INI address support remains independent. Emulator NAT/native-core evidence cannot establish Android TUN/no-leak, physical-device, or live server acceptance.

## Migration Plan

Update contract fixtures and tests together, run focused data/runtime-state/service gates and independent peer interop, then obtain independent review. Complete scoped emulator acceptance with real native artifacts at both endpoint MTUs and three reconnect cycles per MTU. Record unavailable emulator prerequisites explicitly and keep its step open until measured native-core evidence passes.
