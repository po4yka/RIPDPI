---
id: RST-1794677660326601
title: Fix DPI-29 ordered TCP safety and ordering contracts
kind: bug
status: review
area: rust-native
priority: high
owner: Primary integrator
parent: null
blocked_by: []
spec_mode: required
openspec_change: dpi29-ordered-tcp-safety
created: 2026-11-14
updated: 2026-11-14
---

## Goal

Fix DPI-29 packet size bounds, exact-mode dispatch, and the hostfake ordering description.

## Acceptance criteria

- Validate complete raw batches before emission.
- Preserve exact custom modes or reject before payload commit.
- Document and test existing hostfake layouts.
- Pass regressions, independent review and ci-gates.

## Ownership

- ordered_mtu_fix: privileged-ops raw_packet code/tests in job worktree.
- dispatch_fix: decision.rs and runtime/adapter tests in dispatch worktree.
- ordering_docs_fix: proxy-engine.md and hostfake/emission_order.rs tests in docs worktree.
- Primary integrator: portfolio, OpenSpec, generated board, gates and Git integration.
- Serialized lanes: no manifests, Cargo.lock, locales or baselines may change.
