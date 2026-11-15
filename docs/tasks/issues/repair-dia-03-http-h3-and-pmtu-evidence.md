---
id: DGN-1794732416004558
title: Repair DIA-03 HTTP, H3, and PMTU evidence
kind: bug
status: review
area: diagnostics
priority: high
owner: DIA-03 repair team
parent: null
blocked_by: []
spec_mode: required
openspec_change: dia03-probe-integrity
created: 2026-11-15
updated: 2026-11-15
---

## Goal

Repair the four DIA-03 findings and verify the H3 buffer and pseudo-header risks. Keep one fix commit per finding, integrate into main, and push origin/main as requested.

## Acceptance criteria

- Regression tests fail before each repair and pass after it.
- H3 rejects malformed responses and bounds received frames without false protocol success.
- HTTP content coding cannot hide a known blockpage, and ordinary quoted text cannot confirm blocking.
- PMTU reports local timeout, scan deadline, and cancellation separately.
- Targeted tests, affected-package checks, independent review, and just ci-gates pass.
- No protocol scope expansion, schema drift, baseline inflation, or external active probes.

## Parallel ownership

- Root H3 lane: native/rust/vendor/h3 and diagnostics-http/src/http3* for F1/H2 and the later H1 delta. The resource worker handed back its unchanged baseline before source or test writes; root owns frame.rs, proto/frame.rs, error/internal_error.rs, the patch manifest, HTTP3 resource fixtures, and the PMTU test-server helper/resource module. F1 is committed before the H1 delta. The F4 production function and deadline-test body remain frozen.
- HTTP coding worker: diagnostics-http/src/http/probe_execution.rs, response_parser.rs and a dedicated coding test module; F2. No classifier or shared tests.rs edits.
- Fingerprint worker: ripdpi-failure-classifier matcher/catalog/tests and diagnostics-http/src/http/classifier.rs plus dedicated regression tests; F3. No probe_execution or parser edits.
- Root: PMTU files/tests for F4, all task/OpenSpec files, board, integration, review, and combined validation.
- Each writer uses a separate worktree. All writers preserve other work.
- Root alone owns Cargo.lock, Cargo.toml changes, API snapshots, schemas, locales, baselines, and goldens. No dependency, schema, locale, baseline, or golden change is planned.
