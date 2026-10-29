---
id: RST-1793267609889013
title: Retire traffic-shape VoIP camouflage spec
kind: chore
status: review
area: rust-native
priority: low
owner: Rust native owner
parent: null
blocked_by: []
spec_mode: required
openspec_change: rst-1793267609889013-retire-traffic-shape-voip-camouflage-spec
created: 2026-10-29
updated: 2026-10-29
---

## Goal

Retire the normative spec `add-constant-rate-traffic-shaping-voip-camouflage`, whose required code (crate `ripdpi-traffic-shape`, Kotlin `TrafficShapeConfig`) was deleted.

## Acceptance criteria

- The OpenSpec archive sync removes `openspec/specs/add-constant-rate-traffic-shaping-voip-camouflage/`.
- No reference to the deleted crate or type remains outside archived changes.
