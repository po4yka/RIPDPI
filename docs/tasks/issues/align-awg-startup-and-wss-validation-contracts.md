---
id: TRN-1794477195384429
title: Align AWG startup and WSS validation contracts
kind: bug
status: done
area: transport
priority: high
owner: Readiness and validation worker
parent: null
blocked_by: []
spec_mode: required
openspec_change: tun05-startup-validation
created: 2026-11-12
updated: 2026-11-12
closed_at: "2026-11-12T11:09:26Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "Source 38b8f4bac6 matches the verified application tree: 2288 Kotlin and 234 Rust tests, staticAnalysis, ci-gates and independent reviews passed. Device and external acceptance are not claimed."
---

## Goal

Fix TUN-05 F1 and F3.

## Ownership
This worker owns engine-api helper, supervisor awaitReady call only, runtime-state validator, editor and tests. Recovery worker owns other supervisor edits. No schema, locale or lockfile changes.

## Acceptance criteria

WS readiness allows staged 10s+6s setup. UDP stays15s. Invalid URLs fail before activation; valid domain and IPv6 URLs pass. Targeted tests and combined gates pass.
