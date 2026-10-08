---
id: DGN-1791481962888112
title: Require positive HTTP blockpage evidence
kind: bug
status: review
area: diagnostics
priority: medium
owner: HTTP classifier agent
parent: null
blocked_by: []
spec_mode: required
openspec_change: require-http-blockpage-evidence
created: 2026-10-08
updated: 2026-10-08
---

## Goal

Require positive evidence before labeling HTTP 403 as a blockpage.

## Acceptance criteria

- Bare HTTP 403 is an ordinary refusal.
- Existing positive blockpage evidence remains recognized.
- Focused crate tests and independent review pass.

## Ownership

Classifier agent owns HTTP classifier and tests in its isolated worktree. Root owns combined board generation and integration. No shared schema, lockfile, golden, or locale changes.
