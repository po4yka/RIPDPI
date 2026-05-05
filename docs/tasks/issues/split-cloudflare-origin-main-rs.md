---
title: Split ripdpi-cloudflare-origin main.rs into focused transport modules
type: task
status: backlog
area: rust-native
priority: medium
owner: unassigned
parent: epic-srp-and-architecture-refactoring
blocks: []
blocked_by: []
created: 2026-05-05
updated: 2026-05-05
---

- [ ] #task Split ripdpi-cloudflare-origin main.rs into focused transport modules #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Objective

Extract config/args, HTTP handlers, session registry, VLESS relay, channel body, and structured status/error reporting from `main.rs` into separate modules so the binary crate stops acting as a full transport runtime.

## Context

`ripdpi-cloudflare-origin` keeps CLI parsing, config, session registry, HTTP request handling, xHTTP channel bodies, VLESS header parsing, upstream relay, and structured error output all in `main.rs`. It is a binary crate, but this file is now a full transport runtime.

Source: `native/rust/crates/ripdpi-cloudflare-origin/src/main.rs:26-53`

## Acceptance criteria

- [ ] `config.rs` owns CLI argument parsing and config struct.
- [ ] `session.rs` owns session registry logic.
- [ ] `http_handlers.rs` owns HTTP request routing and response construction.
- [ ] `xhttp_channel.rs` owns xHTTP channel body handling.
- [ ] `vless_relay.rs` owns VLESS header parsing and upstream relay.
- [ ] `error.rs` owns structured status/error reporting types.
- [ ] `main.rs` is reduced to wiring: parse config, build registry, start server.
- [ ] Binary builds and behaves identically; existing smoke tests pass.

## Definition of done

`main.rs` is under ~50 lines of wiring code; `cargo build -p ripdpi-cloudflare-origin` succeeds; smoke tests green.
