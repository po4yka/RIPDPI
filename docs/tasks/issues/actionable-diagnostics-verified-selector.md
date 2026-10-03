---
id: SVC-1791025113925875
title: Deliver actionable diagnostics and verified selector activation
kind: feature
status: done
area: service
priority: high
owner: Codex integration owner
parent: null
blocked_by: []
spec_mode: required
openspec_change: svc-1791025113925875-actionable-diagnostics-verified-selector
created: 2026-10-03
updated: 2026-10-03
status_detail: All local, exact-source hosted CI and available device acceptance gates passed.
closed_at: "2026-10-03T14:41:34Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Source 335336b5eac0c29cd3185c46f05d8bbf8f885358; CI 37128129033 terminal SUCCESS, all five required Android APIs succeeded; local static/locale/architecture and Kotlin/Rust gates passed; API37 actual JNI3 plus Android lifecycle24 passed.
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

- Root (`codex/selector-integration`): selector activation/reconciliation, diagnostics compatibility and coroutine-wrapper consolidation; portfolio/OpenSpec/board state and main integration.
- Native writer (`codex/native-module-prune`): native crate/module deletions, canonical dependencies and their native architecture documentation; sole Cargo.toml/Cargo.lock writer.
- Detection writer (`codex/actionable-detection`): core/detection recommendation model and app detection UI/navigation; sole locale resource writer.
- Serialized lanes: root owns task/spec state and integration; native owns Cargo manifests/lock; detection owns locales. Root also owns the Android native-config serialization fix and actual-JNI regression fixture. Native strict wire schema and ABI remain unchanged; no baseline, golden fixture, protobuf or signing changes.

- Payload writer (`codex/selector-payload-probe`): app/subscription probe and stale-evidence guards, pure RelayProfileActivator mapping extraction, transient service candidate resolver/probe and their tests; no runtime coordinator, registry, locale, Rust manifest or task-state writes.
