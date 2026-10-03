---
id: SVC-1791025113925875
title: Deliver actionable diagnostics and verified selector activation
kind: feature
status: doing
area: service
priority: high
owner: Codex integration owner
parent: null
blocked_by: []
spec_mode: required
openspec_change: svc-1791025113925875-actionable-diagnostics-verified-selector
created: 2026-10-03
updated: 2026-10-03
---

## Goal

Deliver user-directed diagnostic recommendation navigation and selector selection that reaches the running or next-started runtime, with candidate transport payload evidence instead of TCP-connect-only ranking. Remove obsolete representations and forwarding interfaces while retaining working privacy, recovery, and platform seams.

## Acceptance criteria

- Recommendation actions open the intended typed destination without changing settings or restarting runtime; unsupported automatic fixes remain absent.
- Persisted offline member selection is reconciled before runtime start; member changes use the service-session lifecycle with no self-cancelling service teardown.
- Candidate health is measured through its configured relay transport with bounded payload checks; stale evidence cannot override manual selection, and Cloudflare remains manual-only.
- Remove obsolete settings selectors, diagnostics compatibility interfaces, unused native metadata and pass-through modules; preserve real behavior and negative regression assertions.
- Relevant unit, static-analysis, architecture, locale and available device gates pass; report exact local, hosted-CI and device evidence separately.

## Ownership

- Root (`codex/selector-integration`): selector activation/reconciliation, candidate payload probes, diagnostics compatibility and coroutine-wrapper consolidation; portfolio/OpenSpec/board state and main integration.
- Native writer (`codex/native-module-prune`): native crate/module deletions, canonical dependencies and their native architecture documentation; sole Cargo.toml/Cargo.lock writer.
- Detection writer (`codex/actionable-detection`): core/detection recommendation model and app detection UI/navigation; sole locale resource writer.
- Serialized lanes: root owns task/spec state and integration; native owns Cargo manifests/lock; detection owns locales. No baseline, golden fixture, JNI/protobuf/wire schema or signing changes.
