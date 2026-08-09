---
id: TRN-1786299802611226
title: Consume versioned AmneziaWG revisions and stage interoperability
kind: feature
status: backlog
area: transport
priority: high
risk: high
owner: AWG compatibility
parent: null
blocked_by:
  - TRN-1786264762917677
  - po4yka/ripdpi-vpn-deploy#SCR-1786299499104067
spec_mode: required
openspec_change: trn-1786299802611226-consume-versioned-amneziawg-revisions
created: 2026-08-09
updated: 2026-08-09
related_tasks:
  - po4yka/ripdpi-vpn-deploy#TST-1786299293097217
---

## Goal

Consume the deploy-owned AWG wire-revision contract, reject unsupported or
inconsistent profiles before activation, preserve current-revision behavior,
and prove a later revision only in an explicit staging and physical-device lane.

## Ownership

- Primary surfaces: vendored bundle contract, subscription/parser and profile
  models, revision-aware fingerprints, native AWG runtime selection, typed UI
  refusal, cross-stack fixtures, and physical arm64 evidence.
- Serialized lanes: vendored schema/goldens, profile persistence, JNI/config
  contracts, and generated strings have one writer at a time.

## Acceptance criteria

- The client parses the canonical revision/provenance fields and rejects missing,
  unknown, substituted, or unsupported combinations before runtime activation.
- Existing current-revision profiles retain identical parsing, persistence, and
  wire behavior after explicit migration.
- Runtime codec selection is revision explicit; there is no heuristic fallback
  from a later revision to current semantics.
- A later revision is feature-gated to staging and covered by upstream-pinned
  fixtures, cross-stack server interop, and physical Android arm64 evidence.
- User-visible diagnostics distinguish unsupported revision, stale profile,
  implementation mismatch, and ordinary transport failure without leaking parameters.

## Verification

- Cross-repository contract drift and negative-fixture tests
- Focused parser, persistence, Rust runtime/JNI, UI, and current-revision regression tests
- Isolated staging interop and physical arm64 evidence before any production eligibility
