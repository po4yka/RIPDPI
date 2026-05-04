---
title: Decouple JNI handle-lifetime and telemetry locking
type: task
status: blocked
area: service
priority: medium
owner: Principal Android Rust Architect
parent: epic-runtime-lifecycle-and-supervisors
blocks: [select-resolver-mapping-from-dns-classification, adopt-handlereservation-primitive-in-ripdpiwarp, adopt-handlereservation-primitive-in-tun2sockstunnel, adopt-handlereservation-primitive-in-networkdiagnostics-cancelscan-latency]
blocked_by: [surface-typed-cache-degradation-reasons]
created: 2026-04-20
updated: 2026-04-20
---

- [ ] #task Decouple JNI handle-lifetime and telemetry locking #repo/RIPDPI #area/service #status/blocked 🔼

## Summary

`RipDpiProxy` and `RipDpiRelay` serialize all handle-sensitive JNI work
behind a single mutex. Telemetry polls head-of-line-block lifecycle calls
and vice versa.

## Audit citation

- `core/engine/.../RipDpiProxy.kt:132-142,220-254,267-277`
- `core/engine/.../RipDpiRelay.kt:192-318`

## Acceptance criteria

- [ ] Separate locks: one for handle create/destroy (lifetime), one for
    ordinary telemetry/config updates against a live handle.
- [ ] Telemetry calls no longer block lifecycle operations (measured).
- [ ] Lifetime transitions remain serialized against all other handle use.
- [ ] No new correctness regressions in existing tests.

## Links

- [[Epic - Runtime lifecycle and supervisors]]
- [[Add native readiness events to RipDpi wrappers]]
- [[ripdpi-android-audit-2026-04-20]]
