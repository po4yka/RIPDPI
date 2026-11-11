---
id: TRN-1794384012167321
title: Fix AWG profile input and compatibility regressions
kind: bug
status: done
area: transport
priority: high
owner: transport-maintainer
parent: null
blocked_by: []
spec_mode: required
openspec_change: tun03-input-contracts
created: 2026-11-11
updated: 2026-11-11
closed_at: "2026-11-11T10:34:38Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: 139 JVM tests, affected lint, full staticAnalysis, ci-gates, architecture health, locked Cargo metadata, and independent review passed at 1f90e28198ab0811289b026007ce3f5eaa31c293.
---

## Goal

Fix the five confirmed TUN-03 input and compatibility defects. Preserve the bounded AWG wire profile and all supported values.

## Acceptance criteria

- Preserve IPv6 hosts through URI, configuration import, and activation.
- Require canonical 32-byte Base64 header protection keys before activation.
- Reject malformed or duplicate URI query fields.
- Keep unknown nested wire settings incompatible without changing stored rows or secrets.
- Reject unknown carriers; keep UDP as the default only when the field is absent.
- Add regression coverage, pass affected local gates, and land each fix as a separate commit.

## Parallel ownership

- uri_fixes: URI codec and owning core/data and app import tests; F1 then F3 as separate slices.
- hp_key_fix: header protection key model validation and owning model tests; F2.
- compat_fixes: stored AWG compatibility validation and Room tests, activation carrier validation and service resolver tests; F4 then F5 as separate slices.
- transport-maintainer: planning, task lifecycle, review, combined validation, commit integration, and push.
- Serialized lanes: task records and OpenSpec artifacts belong only to transport-maintainer. No dependency, locale, baseline, golden, or Rust production changes are planned. Shared URI tests are serialized within uri_fixes; repository and carrier changes are serialized within compat_fixes.
