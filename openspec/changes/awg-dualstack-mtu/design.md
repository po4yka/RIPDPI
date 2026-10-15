## Context

Bundle import currently filters address arrays and defaults missing MTU; the native runtime raises low MTU to 1280. The deployment contract now supplies ordered dual-stack host addresses and a shared 1280..1420 MTU.

## Goals / Non-Goals

- Goal: exact transport admission and real independent-peer consumer verification.
- Non-goal: change standalone INI address ordering, native wire shape, production runtime, VPN TUN policy, or deploy infrastructure.

## Decisions

- Validate bundle transport before constructing a subscription profile; use numeric-only syntax plus URI IPv6 validation without DNS. Reuse the existing INVALID_NETWORK_POLICY skipped-entry reason and name the offending field.
- Reject out-of-range activation MTU so Android and native values cannot diverge through native clamping.
- Keep schema version 1 and synchronize the canonical deployment schema/example/negative artifacts byte-identically.
- Use actual native libraries for emulator acceptance. JVM fakes and host tests do not count as Android evidence. The independent peer uses pinned upstream versions and loopback-only host transport.

## Contracts and ownership

The portfolio ownership section assigns parser, activation, affected tests, contract mirrors, independent peer and instrumentation to this single writer. No production dependencies, Cargo.lock, protobuf or JNI shape changes. Source contract producer is `po4yka/ripdpi-vpn-deploy#ANS-1791618048083304`.

## Risks / Trade-offs

Strict admission breaks malformed or old single-family deployment bundles; producers and fixtures migrate together. Standalone INI address support remains independent. Emulator NAT/native-core evidence cannot establish Android TUN/no-leak, physical-device, or live server acceptance.

## Migration Plan

Update contract fixtures and tests together, run focused data/runtime-state/service gates and independent peer interop, then obtain independent review. Run scoped emulator acceptance when installed toolchains and real native artifacts are available. Record absent evidence explicitly and keep the task open.
