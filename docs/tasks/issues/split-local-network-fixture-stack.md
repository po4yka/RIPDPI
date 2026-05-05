---
title: Split local-network-fixture FixtureStack into per-protocol service builders
type: task
status: backlog
area: testing
priority: medium
owner: unassigned
parent: epic-srp-and-architecture-refactoring
blocks: []
blocked_by: []
created: 2026-05-05
updated: 2026-05-05
---

- [ ] #task Split local-network-fixture FixtureStack into per-protocol service builders #repo/RIPDPI #area/testing #status/backlog 🔼

## Objective

Split `FixtureStack::start` into per-protocol service builders and a manifest assembler so resolver or SOCKS fixture changes do not require reviewing every fixture lane.

## Context

`FixtureStack::start` constructs TLS material and starts TCP echo, UDP echo, TLS echo, UDP DNS, DoH, DoT, DNSCrypt, DoQ, SOCKS5, and control services in one startup path. This test infrastructure is a multi-protocol runtime of its own.

Source: `native/rust/crates/local-network-fixture/src/lib.rs:33-125`

## Acceptance criteria

- [ ] Each protocol lane (TCP echo, UDP echo, TLS echo, UDP DNS, DoH, DoT, DNSCrypt, DoQ, SOCKS5, control) has its own builder function or struct.
- [ ] `FixtureStack::start` becomes a manifest assembler that calls each builder and collects handles.
- [ ] Adding or removing a fixture lane requires touching only its own builder, not the assembler loop.
- [ ] All existing fixture-dependent tests compile and pass.
- [ ] TLS material construction is shared via a helper, not duplicated across builders.

## Definition of done

`lib.rs:33-125` is replaced by builder calls; `cargo nextest run -p local-network-fixture` green.
