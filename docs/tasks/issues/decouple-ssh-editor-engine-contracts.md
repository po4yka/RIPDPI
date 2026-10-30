---
id: SVC-1793330036179924
title: Decouple SSH editor from engine probe contracts
kind: bug
status: doing
area: service
priority: high
owner: SSH service boundary repair
parent: null
blocked_by: []
spec_mode: required
openspec_change: decouple-ssh-editor-engine-contracts
created: 2026-10-30
updated: 2026-10-30
---

## Goal

Restore the strict app/service/engine boundary for the SSH observer and verify full staticAnalysis and hosted CI/CodeQL. The user explicitly authorizes direct main commits and pushes.

## Acceptance criteria

- Service-owned observation types replace engine probe types in app production and unit tests.
- Regression tests preserve native and service failure meanings, observation data, and trust confirmation behavior.
- The unchanged boundary gate and full staticAnalysis pass locally.
- Check exact-revision hosted CI and CodeQL after commit and push.

## Ownership

- Primary agent alone writes and commits on main.
- Review agent is read-only.
- No quality rule, baseline, allowlist, dependency, JNI, wire, storage, locale, or golden edits.
