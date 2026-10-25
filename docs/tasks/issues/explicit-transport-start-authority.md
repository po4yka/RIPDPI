---
id: SVC-1792838679434393
title: Preserve captured authority for explicit transport activation
kind: bug
status: review
area: service
priority: high
owner: Android lifecycle
parent: null
blocked_by: []
spec_mode: required
openspec_change: explicit-transport-start-authority
created: 2026-10-24
updated: 2026-10-24
status_detail: Reviewed source and exact 47a5ee708a497086eb29739ca17f35e164119846 genuine artifacts passed all four owned physical USB scenarios and cleanup. Final published hosted gates remain required before closure.
---

## Goal

An explicit cold transport activation starts with its captured runtime authority and stops when that user intent is superseded.

## Acceptance criteria

- Preserve the exact captured runtime intent through the command queue and suspension.
- Reject absent or stale authority and cancel an active activation when user stop supersedes it.
- Keep automatic failover restart behavior unchanged.
- Pass focused lifecycle tests and the owned physical AWG acceptance run before closure.

## Ownership

Android lifecycle owns ServiceShellDelegate and focused activation tests in the isolated Android acceptance worktree. Other workers own native socket and migration changes. Root owns integration and physical acceptance scheduling.
