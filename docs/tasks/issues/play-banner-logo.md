---
id: UIX-1791648381801240
title: Render the current app mark correctly in Play banners
kind: bug
status: doing
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
