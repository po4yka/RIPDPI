---
id: DAT-1795705030545238
title: Study remembered network policies as input to shared priors
kind: research
status: done
area: data
priority: low
owner: Offline Learner research lane
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-26
updated: 2026-11-27
spec_reason: research-only
status_detail: "ADR 0017 written: conditional go"
closed_at: "2026-11-27T10:57:20Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "ADR 0018 (docs/adr/0018-remembered-policies-as-shared-priors.md) merged to main via PR #811: names allowed and forbidden aggregate fields, fit with the opt-in field report and shared priors manifest v1, conditional-go recommendation"
---

## Goal

A written assessment says whether per-network validated and suppressed policies can feed the Offline Learner shared priors as aggregated, opt-in data, and under which privacy limits.

## Context

Remembered network policies record which route or strategy a user validated or suppressed for a network fingerprint. In aggregate and opt-in form they could improve the shared priors of the Offline Learner.

This is a research item. It ties to the existing roadmap follow-up for shared priors and does not add a roadmap item. The release infrastructure of the shared priors is not complete, so this task cannot become an implementation task yet.

The assessment must respect the field report rules: opt-in, no user identification, no addresses of user networks, buckets below the report minimum suppressed.

## Acceptance criteria

- The assessment names the fields that an aggregate would carry and the fields that it must never carry.
- The assessment states how the aggregate fits the opt-in field report and the shared priors manifest, or why it does not.
- The assessment ends with a go, conditional-go or no-go recommendation.

## Ownership

Not assigned yet. Research only. No source changes.
