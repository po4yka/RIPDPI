---
title: Decompose SOCKS5 core server module
type: task
status: done
area: proxy
priority: medium
owner: unassigned
parent: epic-clear-post-srp-architecture-regressions
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [x] #task Decompose SOCKS5 core server module #repo/RIPDPI #area/proxy #status/done 🔼

## Summary

`ripdpi-socks5-core/src/server.rs` is the largest non-test native protocol
module found in the full Rust scan. It combines server configuration,
authentication traits and implementations, typestate protocol transitions, TCP
proxy execution, UDP associate execution, transfer loops, DNS helpers, reply
encoding, and async stream wrappers in one file.

## Audit citation

- `native/rust/crates/ripdpi-socks5-core/src/server.rs` lines 24-1230.
- Full native LOC scan: about `921` non-comment production lines in this file.

## Scope

- In scope: split server config/errors, authentication, typestate handshake,
  TCP command execution, UDP associate/relay, reply encoding, and transfer
  helpers.
- Out of scope: changing the SOCKS5 wire protocol, public behavior, or client
  API semantics.

## Acceptance criteria

- [x] SOCKS5 server responsibilities are split into focused modules behind the
    existing public API.
- [x] TCP and UDP paths can be reviewed independently.
- [x] Existing SOCKS5 server tests pass, with new tests for any moved helper that
    loses direct coverage.
- [x] Native hotspot/architecture checks either cover the split modules or stay
    clean without baseline increases.

## Links

- [[Epic - Clear post-SRP architecture regressions]]
