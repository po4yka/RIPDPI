---
id: DGN-1790430642039687
title: Correct QUIC and STUN reachability probes
kind: bug
status: doing
area: diagnostics
priority: high
owner: Diagnostics network probe writer
parent: null
blocked_by: []
spec_mode: required
openspec_change: dgn-1790430642039687-correct-quic-stun-reachability-probes
created: 2026-09-26
updated: 2026-09-26
---

## Goal

Make the Android DPI suite report QUIC reachability from valid network probes, and make the Snowflake STUN check accept only its own valid binding response.

## Acceptance criteria

- [ ] The DPI suite sends native QUIC Initial packets for the bundled `cloudflare.com` target instead of synthetic fixture bytes.
- [ ] A native QUIC packet build failure cannot silently produce a QUIC block verdict from a synthetic packet.
- [ ] A STUN response with a wrong type, magic cookie, transaction ID, source, or truncated header is rejected; a matching binding success is accepted.
- [ ] Focused regression tests and `:core:diagnostics:testDebugUnitTest` pass; app compilation is checked if available.

## Ownership

- Diagnostics network probe writer owns `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpi/`, `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpich/PluggableTransportReachabilityProbe.kt`, their focused tests, and `app/src/main/kotlin/com/poyka/ripdpi/activities/DiagnosticsDpiSuiteExecutionSupport.kt`.
- Other writers own diagnostics finalization, export, and RKN paths. This change does not edit those paths.
- This writer owns this task, its OpenSpec change, and generated task board updates until completion.
