---
id: TST-1792309202117414
title: Sync canonical bundle fixture keys
kind: chore
status: review
area: testing
priority: high
owner: bundle-contract-fixtures
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-18
updated: 2026-10-18
spec_reason: test-only
---

## Goal

Keep the two vendored bundle examples byte-identical to the deployment source with canonical synthetic 32-byte public and preshared keys.

## Acceptance criteria

- Both vendored JSON examples equal the canonical deployment files byte for byte.
- Schema, cohort fingerprints, parser source and native artifacts do not change.
- The bundle parser contract test and task validation pass, or a bounded environment gap is recorded.

## Scope and ownership

The bundle-contract-fixtures worker owns only the two JSON examples under
`core/data/src/test/resources/contract/` and this task's issue, execution and
generated board. Other worktrees and production source are outside this change.
This is a test-only synchronization under the existing wire contract; no new
OpenSpec capability or schema version is required.

## Source

Canonical source: `po4yka/ripdpi-vpn-deploy` commit
`cc6e171e9edfe8555bbc7800aa584e8a571a865c`, security delivery repair PR #332.
The key values are deterministic synthetic fixture bytes. No private identity
or live deployment is part of this change.

## Completion evidence

PR #620 merged as `fdc42a19f8701216b727f76ab5dc7fd5b73abcde`. Its contract
resources match tested source commit
`34fc33d082b94573ac2dcd278c030ed0b02e1f11` byte for byte. CI run `39108896877`
passed. Its `test-results` artifact `12130007070` reports 7 parser contract
tests with 0 failures and 0 skipped tests. The execution record contains the
scoped checks and the local build queue limit.

The completion change owns only this issue, its execution record, close
receipt and generated board. It does not change source or resource files.
