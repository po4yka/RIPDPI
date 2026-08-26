---
id: RLY-1786707070050078
title: Stop cascading relay and VPN startup failures
kind: bug
status: review
area: relay
priority: high
owner: codex
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-relay-vpn-startup-cascade
created: 2026-08-14
updated: 2026-08-26
status_detail: All execution steps complete incl. device-matrix gates; awaiting acceptance review
---

## Goal

Prevent target-specific synthetic probe failures from cascading through relay cooldown, repeated VPN restarts, and final startup failure while keeping startup fail-closed until egress is proven.

## Acceptance criteria

- [ ] Startup and steady-state failover use one profile-derived typed evidence contract; no runtime-only public probe target remains.
- [ ] Recent current-generation data-plane success suppresses target-only failures, clears pending negative state, and cannot quarantine or switch the working relay.
- [ ] Only permanent or twice-observed relay-stage failures confirm a broken tuple; probe concurrency, cadence, candidate attempts, and cooldown are bounded.
- [ ] Session-local TCP-only fallback disables UDP ASSOCIATE, and every failed or losing session is fully stopped before a successor starts.
- [ ] Local runtime readiness, VPN checking, validated egress, inconclusive verification, and candidate exhaustion remain distinct in service/UI state.
- [ ] Exact VLESS/REALITY attempt stages and relay health decisions persist and export with complete privacy-safe provenance and no fabricated correlation.
- [ ] Kotlin/Rust/static-analysis/contract/CI gates pass on the rebased exact SHA.
- [ ] The exact signed simple artifact passes the approved Pixel 7 `dad-phone` matrix, restores the original VPN state within the disruption budget, and remains stable for the recovery observation.

## Ownership

- Worktree: `/private/tmp/ripdpi-fix-relay-vpn-startup-cascade-20260814` on `codex/fix-relay-vpn-startup-cascade-20260814`.
- Owned feature lanes: `:core:service` relay health/lifecycle additions, simple-flavor failover, required native relay/VLESS telemetry, and diagnostics persistence/export.
- Serialized lanes: native telemetry schema/API snapshot, diagnostics Room/archive schema, Kotlin/Rust manifests, locale sets, and affected goldens.
- Externally owned: current uncommitted home/actuator UI work and every unrelated dirty path/worktree; integrate semantically after rebase without overwrite.
