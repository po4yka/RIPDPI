---
id: RST-1793267609889013
title: Retire traffic-shape VoIP camouflage spec
kind: chore
status: done
area: rust-native
priority: low
owner: Rust native owner
parent: null
blocked_by: []
spec_mode: required
openspec_change: rst-1793267609889013-retire-traffic-shape-voip-camouflage-spec
created: 2026-10-29
updated: 2026-10-29
closed_at: "2026-10-29T09:55:45Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Spec retired via archive sync with retire_capabilities; no reference to ripdpi-traffic-shape or TrafficShapeConfig remains outside archive; deletion commits dd81a2831 and 6cba60ff6; cargo metadata lists no traffic-shape member
---

## Goal

Retire the normative spec `add-constant-rate-traffic-shaping-voip-camouflage`, whose required code (crate `ripdpi-traffic-shape`, Kotlin `TrafficShapeConfig`) was deleted.

## Acceptance criteria

- The OpenSpec archive sync removes `openspec/specs/add-constant-rate-traffic-shaping-voip-camouflage/`.
- No reference to the deleted crate or type remains outside archived changes.
