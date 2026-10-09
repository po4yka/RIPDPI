---
id: SVC-1791566464861507
title: Keep physical handovers separate from transient lease state
kind: bug
status: doing
area: service
priority: high
owner: Android acceptance agent
parent: null
blocked_by: []
spec_mode: required
openspec_change: svc-1791566464861507-acceptance-physical-handover
created: 2026-10-09
updated: 2026-10-09
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
