---
id: UIX-1795615861956333
title: Validate AWG editor fields and explain the disabled Connect
kind: bug
status: review
area: ui
priority: medium
owner: unassigned
parent: EPC-1795615697939690
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-25
updated: 2026-11-26
spec_reason: regression-tested-single-module
---

## Goal

The AmneziaWG editor rejects invalid input where it is typed and tells the user what blocks Connect.

## Context

Found in the 2026-11-25 on-device user-flow audit (Pixel 8 Pro, githubDebug at 66673515dd, clean install). The port field accepted about 50 characters of base64 text with no error. Connect stays disabled with no indication of the missing field. The Address placeholder `10.8.0.2/32` looks like an entered value.

## Acceptance criteria

- The port field accepts only 1 to 65535 and shows an error for other input; a test covers it.
- While Connect is disabled, the editor names the fields that block it.
- Example values are shown as supporting text, not as a value-like placeholder.

## Ownership

One writer in a dedicated worktree owns the AmneziaWG editor in `app` and its tests.
