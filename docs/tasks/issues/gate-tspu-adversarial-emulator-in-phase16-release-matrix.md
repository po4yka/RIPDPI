---
title: Gate TSPU adversarial emulator in the Phase-16 release matrix
type: task
status: backlog
area: testing
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-25
updated: 2026-05-25
---

- [ ] #task Gate TSPU adversarial emulator in the Phase-16 release matrix #repo/RIPDPI #area/testing #status/backlog 🔼

## Summary

Promote the existing TSPU adversarial emulator from standalone dry-run/live smoke coverage into a Phase-16 release-gate lane that reports adversary-pattern pass, fail, or partial verdicts next to packet-smoke evidence.

## Motivation

The emulator now reproduces deterministic TSPU-like failure modes, but release confidence still depends on operators reading separate artifacts. Phase-16 should make adversarial evidence first-class so a green release cannot hide that only synthetic packet shapes were checked.

## Scope

- In scope: matrix row contract, artifact naming, verdict summary ingestion, and release-gate documentation for `test-lab/chaos/tspu`.
- Out of scope: adding new adversary patterns beyond the v1 emulator surface.

## Acceptance criteria

- [ ] Phase-16 can select a TSPU adversarial lane without requiring real-provider hardware.
- [ ] The lane emits a machine-readable verdict report and links it from the Phase-16 artifact summary.
- [ ] Release documentation explains how TSPU emulator evidence differs from real-provider SIM evidence.
- [ ] Tests prove a failed adversary-pattern cell fails the release lane.

## Links

- [Design spike: TSPU adversarial emulator](../../architecture/spike-tspu-adversarial-emulator.md)
- [Parent spike](spike-adversarial-network-harness-and-realprovider-matrix.md)
