---
id: CIC-1791550373013576
title: Raise approved native size growth budgets
kind: chore
status: done
area: ci
priority: high
owner: root
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-09
updated: 2026-10-09
spec_reason: tooling-only
status_detail: Approved byte-growth limits implemented. Eight real CI libraries and six verifier tests pass; measurements and the 2 percent cap are unchanged.
closed_at: "2026-10-09T13:05:07Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: User-approved 384 KiB per-library and 1.5 MiB total absolute limits; unchanged measurements and 2 percent cap; real four-ABI size gate passes eight libraries; six unit tests and two clean reviews; policy commit 20df27917.
---

## Goal

Raise the explicitly approved native size growth limits so the measured four-ABI JNI outputs pass. Keep the existing size measurements and the 2 percent total cap.

## Acceptance criteria

- Raise per-library growth from 131072 to 393216 bytes and total absolute growth from 262144 to 1572864 bytes.
- Preserve every measured library size, all four ABI entries and the 2 percent total cap.
- Verify all eight tracked libraries against real artifacts from CI run 37927644982 at source 6ce2271e7.
- Run the native size verifier unit tests and verify that the retained limits still reject excess growth.
- Review, commit, integrate on main and push each finished unit as authorized.

## Ownership and authorization

The user explicitly approved increasing the native size limits on 2026-10-09. Native-verifier is the only writer for `scripts/ci/native-size-baseline.json` and `docs/native/size-monitoring.md`, in an isolated worktree. Root owns task records and integration. Reviewers are read-only. No native implementation, compiler option, dependency, ABI set or bloat budget is changed.
