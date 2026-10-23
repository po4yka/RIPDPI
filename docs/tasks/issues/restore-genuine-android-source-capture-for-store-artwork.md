---
id: CIC-1792748686642962
title: Restore genuine Android source capture for store artwork
kind: bug
status: doing
area: ci
priority: high
owner: Screenshot CI repair
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-23
updated: 2026-10-23
spec_reason: tooling-only
---

## Goal

Restore verified, current-source Android frames and generated Play Store artwork without weakening source freshness or actual-state checks.

## Acceptance criteria

- Reproduce stale-source failure and retain rejection regression coverage.
- Provide opt-in disposable CI capture using actual app/native builds and normal Android VPN consent.
- Review all 42 source frames and 49 unique generated assets; preserve real measured outcomes.
- Pass exact-head PR checks, then merge normally and verify merge-triggered checks.

## Ownership

Screenshot CI repair owns the screenshot workflow, capture tools, generated artwork and this task. Existing owner PR #644 and all native dependency files remain untouched. Independent reviewer is read-only.

## Initial evidence

At main `5e778024b07be987f2bad73d7700a9a4ffdf5266`, `validate-source-captures.py` fails on stale Android inputs. Scheduled run 39932738003 confirms the same failure. The renderer never recaptures Android frames, and its old always-run summary falsely described unchanged retained assets as no drift after failed regeneration.
