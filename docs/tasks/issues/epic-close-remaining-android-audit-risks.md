---
title: Close remaining Android audit risks
type: epic
status: doing
area: epic
priority: high
owner: Codex coordinator
parent: null
blocks: []
blocked_by: []
created: 2026-07-13
updated: 2026-07-13
---

## Goal

Close all fifteen non-critical findings from the post-fix Android audit with a regression check and one atomic fix commit per finding.

## Why now

The nine high-severity findings are already fixed on `main`; the remaining confirmed lifecycle, persistence, security, privacy, and Compose issues are the final audit scope.

## Key decisions

- Work in one isolated integration worktree and serialize commits.
- Use read-only specialist lanes for investigation; the coordinator owns all writes.
- Keep unrelated vendored BoringSSL deletions out of every commit.

## Scope

The fifteen child tasks linked by `parent: epic-close-remaining-android-audit-risks` are the complete scope.

## Ship definition

- [ ] Fifteen child fixes each have a red-capable regression check and an atomic Conventional Commit.
- [ ] Combined affected unit suites and `staticAnalysis` pass.
- [ ] Architecture, LoC, diff, and locked Cargo metadata gates pass.
- [ ] The linear commit series is fast-forwarded directly to local `main` as explicitly authorized.
