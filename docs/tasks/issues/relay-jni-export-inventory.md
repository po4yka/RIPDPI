---
id: CIC-1788596896171354
title: Synchronize the existing relay JNI export inventory
kind: chore
status: done
area: ci
priority: medium
owner: Audit integration
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-09-05
updated: 2026-09-05
spec_reason: tooling-only
closed_at: "2026-09-05T08:32:16Z"
closed_reason: Existing JNI inventory synchronized from the verified packaged library
evidence_summary: CI artifact 9966063979 at f3f3328c1 has verified SHA256 b1723952cc866de881f480897b1a4706fa528d86b143baa51ba329c8a7fd32cd. Exact checker reproduced one missing existing SSH export, then passed all five JNI libraries after documented regeneration. Independent source review confirmed the pre-audit Kotlin/Rust contract. Corrected remote JNI gate remains required before merge.
---

## Goal

Make the JNI export inventory agree with the existing SSH host-key probe contract. PR 495 exposed the stale relay inventory in JNI Symbol Diff run 33953850038.

## Acceptance criteria

- Confirm the export is used by the existing Kotlin bridge and predates this audit.
- Regenerate the inventory from the SHA-verified CI native artifact through the documented symbol extraction command.
- Check all five packaged JNI libraries and preserve every existing export.
- Record exact artifact provenance and the remote JNI gate required before integration.

## Evidence

The export was introduced by `4d6f848bc27b23992a833028889f065ad2593033`, before this audit. The Kotlin bridge and JNI contract require it. Native artifact 9966063979 from CI run 33953850044 has head `f3f3328c1b79043c4bc795f8077d13d7978e853d` and SHA256 `b1723952cc866de881f480897b1a4706fa528d86b143baa51ba329c8a7fd32cd`. The documented extraction reproduced exactly one missing relay export. The corrected remote JNI Symbol Diff run must pass before merge.

## Ownership

Audit integration owns the relay JNI inventory, this task record, and the audit report. The native reviewer is read-only. No application source or JNI signature changes are planned.
