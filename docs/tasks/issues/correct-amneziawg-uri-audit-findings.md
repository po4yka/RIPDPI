---
id: DAT-1794720880096281
title: Correct AmneziaWG URI import contracts and redaction
kind: bug
status: review
area: data
priority: high
owner: IMP-02 integrator
parent: null
blocked_by: []
spec_mode: required
openspec_change: imp02-uri-audit-corrections
created: 2026-11-15
updated: 2026-11-15
---

## Goal

Correct IMP-02 findings F01 through F05. URI imports must fail safely, preserve route policy, produce readable share links, and keep credentials out of QR errors.

## Acceptance criteria

- F01: unsafe conf values return a normal import failure without an exception.
- F02: explicit empty allowed_ips is rejected; omitted routes keep their defaults.
- F03: an empty I slot does not produce an unreadable share URI. Document semantic normalization.
- F04: invalid QR previews contain no credential bytes.
- F05: S3/S4 documentation agrees with the current validators.
- Keep one correction commit per finding. Run affected tests, staticAnalysis, ci-gates, and independent review before integration.

## Ownership

- Import worker: app proxyimport ScannerUriDispatcher.kt and related app unit tests; F01 and F04 in separate commits.
- Codec worker: runtime-state AmneziaWgUriCodec.kt and owning unit tests; F02 and F03 in separate commits.
- Documentation worker: docs/amneziawg-uri-scheme.md and runtime-state AwgProfileForm.kt KDoc; F05 and normalization text.
- Integrator: this portfolio record, OpenSpec artifacts, generated board, combined verification and integration.
- Every writer uses a separate worktree. Only the named writer changes each lane. No locale, golden, wire schema, dependency, or baseline edits are planned.
