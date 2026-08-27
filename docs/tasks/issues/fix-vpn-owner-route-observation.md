---
id: DGN-1786867116840500
title: Fix VPN owner-process route observation and evidence
kind: bug
status: doing
area: diagnostics
priority: high
owner: Codex VPN route coordinator
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-vpn-route-observation-and-evidence
created: 2026-08-16
updated: 2026-08-16
---

## Goal

Stop treating the VPN owner's expected non-VPN process default as proof that
the Android VPN route is absent. Observe the owner-created VPN network and its
installed route shape independently, project an honest Route status, and
export enough privacy-safe provenance to distinguish observer false negatives,
route-policy mismatches, and native data-plane degradation.

## Acceptance criteria

- A self-excluded RIPDPI process may observe Wi-Fi or cellular as its calling-
  UID default while an owner-created, validated VPN network exists; this state
  does not produce a Route warning.
- VPN presence and installed IPv4/IPv6 default-route families come from a
  callback observation correlated to the current RIPDPI service lifecycle, not
  from `ConnectivityManager.activeNetwork`.
- `Unavailable`, `Degraded`, `Checking`, and healthy Route outcomes distinguish
  authoritative absence, route-plan mismatch, and incomplete callback evidence;
  Android validation and generation-bound forwarding health remain separate
  Network and Tunnel axes.
- Exported diagnostics contain categorical observer provenance, intended and
  observed route families, app-routing shape, lifecycle/receipt generation,
  freshness, and forwarding-correlation outcome without package names,
  addresses, interface names, or stable identifiers.
- Regression tests cover owner self-exclusion, VPN callback appearance/loss,
  startup and handover races, route-family mismatch, a genuinely absent VPN,
  and UI/archive projections.
- Targeted JVM tests, `./gradlew staticAnalysis`, architecture health,
  task-board validation, and a physical-device API 36 scenario are reported as
  separate evidence. Hosted CI is not inferred from local checks.

## Current execution ownership

- Reopened service step SVC-1786867116840502 after finding that separate
  capabilities/routes callbacks erase each other. The previous atomic-snapshot
  tests did not exercise this path; synchronous framework getters could mask
  the defect or manufacture stale evidence. The callback repair now passes all
  1,855 service unit tests, including rejected-owner loss handling; the service
  step is complete again. Combined and device acceptance remain separate.
- The coordinator owns the callback authority and receipt reducer under
  `core/service`, task/spec updates, combined validation, and integration.
- The isolated regression-test writer owns only
  `VpnRouteObservationAuthorityTest.kt`; production changes remain with the
  coordinator. Review agents are read-only.
- Existing diagnostics/archive behavior is retained. No schema, fixture,
  dependency, baseline, locale, JNI, or protobuf changes are assigned.
- Physical API 36 acceptance remains outstanding; the connected API 37
  device does not satisfy that criterion.
