---
id: TRN-1792759596946903
title: Select profiles using bounded transfer quality and scoped MTU diagnostics
kind: feature
status: done
area: transport
priority: high
owner: transport-audit
parent: null
blocked_by: []
spec_mode: required
openspec_change: profile-quality-mtu
created: 2026-10-23
updated: 2026-10-24
linked_task: "po4yka/ripdpi-vpn-deploy#OPS-1792759217301702"
closed_at: "2026-10-24T11:19:21Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Merged quality source and production storage fix are included in e22c8d66d8fa0c9245eb5bf8ef802e24028d0f48; main CI 40085973754 passed. Verified exact-main artifacts contain ten native quality passes across five Android APIs and the API35 REALITY quality pass, with no required-test skips. Local, UI, PMTU and migration regressions passed; withdrawn PR658 was not used as closure evidence.
---

## Goal

Improve the existing encrypted-profile selector using bounded verified transfer
quality and explain scoped path-MTU evidence without introducing another selector.

## Acceptance criteria

- Opt-in schema-2 policy imports atomically with the producer contract; malformed,
  unsupported or over-budget inputs cannot silently fall back or activate a profile.
- Actual authenticated candidate transfers report verified-body goodput and
  baseline/loaded transaction latency only when measurement conditions are met.
- Existing selection, candidate ownership, network fences and native reload paths
  apply confirmed quality improvements with cooldown and bounded application-byte
  budgets; failed, cancelled and stale measurements cannot refill the budget.
- Real relay and Reality candidates complete positive payload measurements;
  AWG stays outside the automatic relay pool and no direct fallback is introduced.
- Existing PMTU diagnostics distinguish acknowledged UDP/IP lower bounds from
  ceilings, failures and unverified scope; no automatic tunnel-MTU mutation occurs.
- Portable, real socket, native Android and applicable UI/locale checks pass,
  followed by independent review, protected PR and exact-main CI. Mock-only or
  refusal-only behavior cannot close the feature; unobserved acceptance remains open.

## Ownership

- Transport writer: this worktree's task/OpenSpec planning, runtime-state quality
  policy/results/store/parser, service candidate measurement/adapters, subscription
  selector/state, their tests and native quality instrumentation. Copy shared
  contract mirrors only after the server owner freezes them.
- Diagnostic/UI writer: a separate worktree owns core/diagnostics PMTU derivation,
  app activities/screens and existing profile presentation, all locale resources,
  architecture documentation and shared CI integration. Integrate reviewed commits.
- Journal migration writer: a separate worktree owns only pending refresh journal
  recovery and dedicated upgrade/restart tests, using the transport-owned local
  health-policy migration helper. It does not modify wire import or native probes.
- Storage migration writer: a separate worktree owns the production catalogue
  schema/read boundary, local health-policy upgrade and their regression tests in
  `core/data/runtime-state/`. This lane does not modify journal replay or closure.
- Server writer: the linked deployment task owns the canonical bundle schema,
  policy validation, producer, controlled payload fixture and shared vectors.
- Do not modify Cargo.lock, protobuf/JNI shapes or golden baselines without a
  separately assigned need. All heavy checks use the machine-wide build gate.
