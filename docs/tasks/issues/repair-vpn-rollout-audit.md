---
id: VPN-1792657107995294
title: Repair VPN rollout audit defects
kind: bug
status: doing
area: vpn
priority: high
owner: rollout-integrator
parent: null
blocked_by: []
spec_mode: required
openspec_change: repair-vpn-rollout-audit
created: 2026-10-22
updated: 2026-10-22
---

## Goal

Repair the five confirmed rollout defects and prove the affected behavior with regression tests and deployment configuration checks.

## Acceptance criteria

- SHARE exports contain no subscription URL or token; FULL exports preserve them.
- Native relay hostname resolution uses the protected Android underlay and fails closed.
- Unexpected runtime exit stops all live siblings and preserves pending cleanup ownership.
- New long-lived subscriptions refresh automatically; explicit existing opt-outs and bootstrap semantics remain intact.
- Nested server selectors cannot silently remove supported relay profiles; unsupported-only payloads fail explicitly.
- Targeted regressions, affected static checks, combined-tree gates and cross-repository contract checks pass, with device and deployment limits stated.

## Ownership

- repair_native_dns: native/rust/crates/ripdpi-relay-android callback and focused tests.
- repair_runtime_cleanup: core/service runtime stack, exit handlers, supervisors and related tests.
- repair_subscription_privacy: core/data backup redaction and tests; app subscription import, worker scheduling and tests; two separate commits.
- rollout-integrator: selector parser, subscription refresh fallback, compatibility tests, docs/server-integration.md, this portfolio, OpenSpec artifacts and final integration.
- Serialized files: no Cargo.lock, version catalog, proto, locale, baseline or golden changes. Only rollout-integrator writes tasking/specification files.
