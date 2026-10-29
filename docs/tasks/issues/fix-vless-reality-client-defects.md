---
id: TRN-1793295071036802
title: Fix audited VLESS REALITY client defects
kind: bug
status: review
area: transport
priority: high
owner: VLESS client remediation
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-vless-reality-client-defects
created: 2026-10-29
updated: 2026-10-29
---

## Goal

Correct the six native VLESS REALITY defects found by source comparison with Xray v26.10.29. The user explicitly authorizes commits on main and pushes to origin.

## Acceptance criteria

- Reject unsupported imported security and transport options without changing their meaning.
- Flush XUDP frames within the write deadline, resolve transport aliases and XHTTP auto correctly, and switch Vision Direct only at a TLS record boundary.
- Send a documented REALITY compatibility version and verify configured admission version bounds.
- Add regression tests, observe the relevant local gates, obtain independent review, and commit each fix separately on main before pushing.

## Ownership

- Main checkout: primary agent is the only writer and committer. This follows the explicit user request to work directly on main.
- Isolated import worktree: import worker owns native Kotlin URI/JSON parsers and their module tests. Prepare two separate patches: strict option rejection, then transport aliases.
- Isolated XHTTP worktree: XHTTP worker owns mode resolution, its relay-builder callers, and related Rust tests.
- Isolated Vision worktree: Vision worker owns vision.rs and its regression tests.
- Primary agent owns xudp.rs, reality_seal.rs, version-reference tests, and all task/OpenSpec records. No dependency, baseline, locale, JNI, protobuf, or schema-version changes are planned.
