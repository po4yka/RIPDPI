---
id: TRN-1794198500598113
title: Repair audited ECH support contracts
kind: bug
status: review
area: transport
priority: high
owner: ECH audit team
parent: null
blocked_by: []
spec_mode: required
openspec_change: repair-ech-support-contracts
created: 2026-11-09
updated: 2026-11-09
---

## Goal

Repair all six confirmed STD-03 ECH findings with one repair commit per finding. Preserve REALITY Off policy and existing backend trust.

## Acceptance criteria

- Real ECH cannot emit the inner SNI when its config is unusable.
- Ordinary TLS errors return no authenticated ECH retry evidence and never abort.
- Incompatible mandatory SVCB records are excluded.
- Kotlin follows bounded HTTPS aliases.
- Authenticated ECH rejection gets at most one fresh transport retry.
- Cancellation stops DoH, system DNS, and native ECH I/O.
- Targeted tests, independent review, combined source gates pass.

## Ownership and serialized lanes

- tls_safety_fixes: tls-profiles ECH helpers, vendored Boring ECH setter, MASQUE H2/xHTTP F1/F2 call sites/tests.
- dns_mandatory_fix: dns-resolver HTTPS/SVCB selection, core/diagnostics HttpsDnsWireParser.kt compatibility filtering and owning tests (F3).
- ech_diagnostics_fixes: Kotlin resolver/bridge, native ECH cancellation lifecycle (F4/F6).
- Integration owner: planning, F5 retry consumers and helpers, final combined validation and integration.
- Serialize tls-profiles/ech.rs and MASQUE/xHTTP edits: F1/F2 complete before F5.
- Serialize native_ech.rs: F6 lifecycle complete before F5 native retry.
- Integration owner serializes the pinned Quinn source patch and Cargo.lock dev-dependency edges; no dependency versions, locales, quality baselines or goldens change.
- Each writer uses a separate worktree; reviewer agents remain read-only.
