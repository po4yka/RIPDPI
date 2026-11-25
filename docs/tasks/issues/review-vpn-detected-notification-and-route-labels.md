---
id: UIX-1795615862795492
title: Review the VPN-detected notification and route labels
kind: research
status: backlog
area: ui
priority: low
owner: unassigned
parent: EPC-1795615697939690
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-25
updated: 2026-11-25
spec_reason: research-only
---

## Goal

Decide whether a new user should get the `VPN detected` notification without asking, and how to name routes in Details.

## Context

Found in the 2026-11-25 on-device user-flow audit (Pixel 8 Pro, githubDebug at 66673515dd, clean install). During the audit an unprompted notification `VPN detected: score 31/100` arrived. The Details panel shows `Route 6`, which a new user cannot interpret.

## Acceptance criteria

- A written decision states when the VPN-detected notification fires and what it tells the user.
- A written decision states the user-facing route label, with follow-up tasks if code changes are needed.

## Ownership

One writer in a dedicated worktree owns no code; the decision is recorded in this task.
