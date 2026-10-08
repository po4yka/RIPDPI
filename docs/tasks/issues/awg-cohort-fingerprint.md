---
id: DAT-1791479983861691
title: Validate AWG cohort fingerprints during import
kind: bug
status: done
area: data
priority: medium
owner: Fingerprint import worker
parent: null
blocked_by: []
spec_mode: required
openspec_change: validate-awg-cohort-fingerprint
created: 2026-10-08
updated: 2026-10-08
closed_at: "2026-10-08T18:07:13Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Independent review approved; 35 targeted parser tests and lint passed. Combined 7a4a87a9 passed 269 tests, staticAnalysis, architecture health, and locked Cargo metadata.
---

## Goal

Reject inconsistent AWG cohort metadata before it can become an imported profile.

## Acceptance criteria

- Accept absent or valid fingerprints and reject every present invalid value.
- Keep valid sibling profiles and emit a fixed rejection reason without parameter values.
- Pass parser and cross-repository contract JVM tests and affected lint checks.

## Ownership

Fingerprint worker owns fingerprint validation hunks in SingBoxSubscriptionParser.kt, fingerprint tests, and this task/change. The DNS worker owns separate presence-flag constructor hunks in the parser. No native, schema, golden fixture, mapper, repository, or app changes. The integration owner serializes board regeneration after merge.

## Final evidence ownership

The subscription evidence worker owns verification records and task closure receipts on `docs/subscription-p2-final`. The integration owner provides the final gate results and serializes main integration. Source, tests, golden images, and generated artifacts remain with their assigned owners.
