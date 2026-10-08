---
id: DGN-1791479038917244
title: Reject diagnostic recommendations after a network change
kind: bug
status: review
area: diagnostics
priority: high
owner: Diagnostics network guard
parent: null
blocked_by: []
spec_mode: required
openspec_change: reject-diagnostics-after-network-change
created: 2026-10-08
updated: 2026-10-08
---

## Goal

Prevent a scan from changing network settings or recording validated policy when its physical network changes.

## Acceptance criteria

- Reject A to B, A to B to A, missing epoch, and network loss.
- Keep reports, but remove recommendation authority from mixed-network evidence.
- Preserve recommendations on a stable physical network across normal VPN stop and start.
- Pass diagnostics and service unit tests, static analysis, architecture checks, and independent review.

## Ownership

- Primary writer: diagnostics implementation, tests after regression import, and planning artifacts in the job worktree.
- Regression agent: one initial regression test in its own worktree; no production changes.
- Service adapter agent: shared model interface, epoch implementation and binding plus adapter tests in its own worktree.
- Review agents: read-only.
- Primary writer owns integration. No lockfile, proto, locale, baseline, or golden changes are planned.
