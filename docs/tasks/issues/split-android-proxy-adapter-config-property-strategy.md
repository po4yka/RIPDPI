---
title: Split Android proxy adapter config property strategy
type: task
status: done
area: android
priority: medium
owner: unassigned
parent: epic-clear-post-srp-architecture-regressions
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [x] #task Split Android proxy adapter config property strategy #repo/RIPDPI #area/android #status/done 🔼

## Summary

`ripdpi-android-proxy-adapter/src/config.rs` is production-small, but its
property-test strategy builder is a 212-line function that creates listen,
protocol, TCP, fake-packet, parser-evasion, host, QUIC, autolearn, and UDP
config variants in one body. This makes Android proxy config contract testing
hard to review when one config family changes.

## Audit citation

- `native/rust/crates/ripdpi-android-proxy-adapter/src/config.rs` lines 135-249.
- Architecture-health indicator: `long-function-or-composable`,
  `functionLines=212`, limit `180`.

## Scope

- In scope: test strategy builders, per-family generators, and property-test
  readability.
- Out of scope: changing JNI config parsing behavior or proxy config schema.

## Acceptance criteria

- [x] `proxy_ui_config_strategy` is split into per-family strategy builders.
- [x] Config property tests preserve current coverage and shrink behavior.
- [x] The long-function architecture indicator is removed.
- [x] `cargo test -p ripdpi-android-proxy-adapter` passes.

## Links

- [[Epic - Clear post-SRP architecture regressions]]
