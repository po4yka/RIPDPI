---
id: SVC-1791566464861507
title: Keep physical handovers separate from transient lease state
kind: bug
status: done
area: service
priority: high
owner: Android acceptance agent
parent: null
blocked_by: []
spec_mode: required
openspec_change: svc-1791566464861507-acceptance-physical-handover
created: 2026-10-09
updated: 2026-10-10
status_detail: Clean1e513 full42, Xray repeats/VM-routed, combined gates, independent review, cleanup and all5 published code workflows PASS; commit review before terminal closure
closed_at: "2026-10-09T21:01:39Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "Clean1e51343f1: full42 plus Xray repeat1/repeat2/VM-routed PASS; combined7631 executed and staticAnalysis PASS; all5 exact published code workflows PASS; independent source/JNI/runtime review CLEAR; own AVD/Lima stopped, artifacts/evidence preserved; committed review precedes terminal record."
---

## Goal

Keep a running VPN provider stable when transient lease observation changes without a physical path change. Preserve physical network handovers. This defect was found during the local acceptance task TST-1791553917096956.

## Acceptance criteria

- Retain the clean c25730eca Xray baseline failure and identify the exact handover input that caused the second tunnel establishment before peer loss.
- Reproduce false handover behavior with a regression test before the fix.
- Preserve physical network, validation, captive portal, DNS, and underlay lease changes that require recovery.
- Keep fingerprint hashing, stored identities, public interfaces, and routing security unchanged.
- Run service tests, lint, static analysis, real Android Xray and Network scenarios, a full Xray repeat, VM-routed Xray, and report verification on clean combined sources.
- Observe successful remote CI on the published main commit.

## Ownership

Android writer owns the service handover observation and its regression tests. Coordinator owns this plan, task state, combined gates, and publication. Independent reviewer audits the cause, compatibility, and runtime evidence. Each writer uses a separate worktree. No schema, locale, baseline, or lockfile changes are planned.
