---
id: DAT-1792081130689287
title: Validate deployed AWG dual-stack addresses and MTU
kind: feature
status: doing
area: data
priority: high
owner: AWG dual-stack consumer
parent: null
blocked_by: []
spec_mode: required
openspec_change: awg-dualstack-mtu
created: 2026-10-15
updated: 2026-10-15
---

## Goal

Preserve deployed AWG IPv4/IPv6 host addresses and the shared MTU through import and runtime activation, rejecting malformed transport input before persistence or network startup.

Deployment consumer of `po4yka/ripdpi-vpn-deploy#ANS-1791618048083304`.

## Acceptance criteria

- Deployment bundles admit exactly an IPv4 /32 then IPv6 /128 and an integer MTU from 1280 through 1420.
- Invalid addresses, missing/invalid MTU, and mismatched families produce explicit skipped-entry evidence without fallback; standalone INI address selection remains unchanged.
- Parser, activation, service resolver, contract mirrors, and independent native peer gates pass.
- The Android acceptance gate requires only arm64 emulator native-core interop: real bidirectional IPv4/IPv6 TCP, UDP and DNS at MTUs 1280 and 1420, with three reconnect cycles per MTU. Evidence records emulator/native_core scope. Physical devices and Android VpnService TUN/no-leak checks are not required for this task; Linux forwarding/no-leak evidence belongs to the producer task.

## Ownership

The AWG consumer writer owns this dedicated worktree's bundle parser/validation, activation MTU guard, related Kotlin tests and imported test resources, the serialized bundle schema/example/negative mirrors, native interop test and peer adapter, Android AWG instrumentation, and the shared native virtual TCP initialization/close path required for reconnects against a persistent peer. No shared dependency versions, Cargo.lock, protobuf, locale, golden images, or JNI wire shape changes are owned. Independent review is read-only.
