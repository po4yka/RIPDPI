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
updated: 2026-10-23
linked_task: "po4yka/ripdpi-vpn-deploy#OPS-1792759217301702"
closed_at: "2026-10-23T17:51:15Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "Implemented and accepted on main 261f301910fafe5b40c724b35f65a87e669f3876: CI 39990623691 and ci-required passed; ten required native Shadowsocks quality executions and the API 35 REALITY quality case passed without skips. Full static, unit, Rust, ABI, Debug/Release, UI and locale gates passed. Independent local review completed; physical-device and production deployment acceptance are outside this client task."
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
- Server writer: the linked deployment task owns the canonical bundle schema,
  policy validation, producer, controlled payload fixture and shared vectors.
- Do not modify Cargo.lock, protobuf/JNI shapes or golden baselines without a
  separately assigned need. All heavy checks use the machine-wide build gate.
