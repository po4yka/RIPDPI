---
title: Remove proxy runtime concrete classifier and WS bootstrap edges
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

- [x] #task Remove proxy runtime concrete classifier and WS bootstrap edges #repo/RIPDPI #area/proxy #status/done 🔼

## Summary

`ripdpi-proxy-runtime` no longer directly links adaptive/runtime-policy crates,
but the architecture gate still reports concrete discouraged edges to
`ripdpi-failure-classifier` and `ripdpi-ws-bootstrap`. Runtime modules import
classifier details across relay, routing, UDP, warmup, and tests, and import WS
bootstrap logic from handshake, routing, and warmup paths.

## Audit citation

- `native/rust/crates/ripdpi-proxy-runtime/Cargo.toml` lines 13-25.
- Architecture-health indicators: discouraged dependency edges to
  `ripdpi-failure-classifier` and `ripdpi-ws-bootstrap`.

## Scope

- In scope: narrow decision/adapter ports for failure classification and WS
  bootstrap decisions, runtime imports, and Cargo dependency cleanup.
- Out of scope: changing classification outcomes, Telegram WS behavior, or
  resolver policy.

## Acceptance criteria

- [x] Proxy runtime consumes classification and WS bootstrap results through
    narrow ports or adapter crates.
- [x] Direct Cargo edges to `ripdpi-failure-classifier` and `ripdpi-ws-bootstrap`
    are removed from `ripdpi-proxy-runtime`.
- [x] Relay/routing/UDP/warmup behavior remains covered by existing or new tests.
- [x] `python3 scripts/ci/check_architecture_health.py --check` has no proxy
    runtime discouraged-edge indicators.

## Links

- [[Epic - Clear post-SRP architecture regressions]]
