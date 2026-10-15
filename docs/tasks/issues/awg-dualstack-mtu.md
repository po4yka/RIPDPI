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
- Android arm64 emulator native interop measures real bidirectional IPv4/IPv6 traffic and records its emulator/native-core scope. Physical-device, Android TUN/no-leak and deployment evidence remain separate.

## Ownership

The AWG consumer writer owns this dedicated worktree's bundle parser/validation, activation MTU guard, related Kotlin tests and imported test resources, the serialized bundle schema/example/negative mirrors, native interop test and peer adapter, and Android AWG instrumentation. No shared dependency versions, Cargo.lock, protobuf, locale, golden images, or production native runtime changes are owned. Independent review is read-only.
