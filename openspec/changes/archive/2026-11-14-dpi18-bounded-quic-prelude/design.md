## Context

Task ENG-1794637894202698 addresses F1 and F2. Planner and socket operations are synchronous.

## Goals / Non-Goals

- Goal: bounded allocation and original delivery after optional failure.
- Non-goal: new packet strategies, stateful assembly, ECH Inner or endpoint packet-number ownership.

## Decisions

- Limit DPI-18 to 64 copies per step and per group in Kotlin, JSON and CLI. Cap active copies before building packets for direct models.
- Limit rebuilt packets to input length and a conservative UDP payload cap. Combined with the decoder's 65535-byte cap, copy storage remains below 4 MiB.
- On a recoverable local send error before a known original, restore TTL and attempt the original once. Record fallback; preserve required-send and restoration errors.
- Use bounded tests and loopback sockets. No external probes, root, OOM runs or golden blessing.

## Contracts and ownership

- Bounds worker: Kotlin model, native config, plan_udp.rs and owning tests.
- Datagram worker: packet_family/split.rs and UDP adapter with separate tests.
- Integration: task/OpenSpec, generated API snapshots, gates, review and commits.
- Separate worktrees. No lockfile, dependency, baseline, golden, protobuf or JNI schema changes.

## Risks / Trade-offs

- Excessive configurations fail validation.
- Oversized optional packets are skipped. No PMTU discovery is added.
- Fallback must not produce successful technique evidence.

## Migration Plan

No storage migration. Roll back by normal revert. Run owning Rust tests with --locked, Kotlin model tests, static analysis, just ci-gates, architecture health, locked Cargo metadata and independent review. Keep separate commits per finding and test the combined tree.

The count constant stays with the other UDP chain limits in the group model. A private CLI child module owns flag validation, so existing hotspot budgets do not grow.
