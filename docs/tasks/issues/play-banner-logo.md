---
id: UIX-1791648381801240
title: Render the current app mark correctly in Play banners
kind: bug
status: done
area: ui
priority: medium
owner: Play banner logo correction
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-10
updated: 2026-10-10
spec_reason: tooling-only
status_detail: Default Android vector paths match exactly. Production capture passed 49 layout checks and 56 strict RGB PNG checks; seven full and 360 px banners and actual EN/FA JPEG exports passed independent review. Only eight banner PNGs changed. Implementation 06408414a7176a621e9e8f4f1d2a367a36db4cda is verified on remote main.
closed_at: "2026-10-10T16:15:38Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Implementation 06408414a7176a621e9e8f4f1d2a367a36db4cda is published and verified on remote main. Native vector paths match exactly; production build, 49 layout checks, 56 RGB PNG checks, source hash validation, seven full and 360 px visual checks and actual EN/FA JPEG exports passed. Independent reviewer found no defects. Architecture health, locked Cargo metadata, task contracts and commit hooks passed. Phone posters and all raw captures are byte-identical.
---

## Goal

Show the current app mark clearly beside RIPDPI in every Play feature graphic. Remove the white launcher square and excessive internal padding without changing the icon geometry or wordmark.

## Acceptance criteria

- Use the unchanged paths from the default clean Android launcher vector.
- Check all seven banners at full size and 360 px, including Persian RTL.
- Production capture and strict RGB PNG validation pass; phone posters and raw captures stay unchanged.
- Actual EN and FA banner JPEG exports preserve the vector mark.
- Independent source and visual review passes; commit and push to main are verified.

## Ownership

The integration owner writes the banner component and generated marketing outputs in the dedicated Play remediation worktree. The independent reviewer reads only. No application, native engine, locale catalog, raw capture or dependency change is needed.
