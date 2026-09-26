---
id: DGN-1790419498655006
title: Correct diagnostics Rust audit defects
kind: bug
status: doing
area: diagnostics
priority: high
owner: Diagnostics Rust audit
parent: null
blocked_by: []
spec_mode: required
openspec_change: dgn-1790419498655006-correct-diagnostics-rust-audit-defects
created: 2026-09-26
updated: 2026-09-26
---

## Goal

Correct confirmed Rust diagnostics defects found in the 14-crate audit. Keep probe verdicts, runtime ownership, and scan deadlines faithful to observed results.

## Acceptance criteria

- [ ] Successful HTTPS baselines produce no failure class; a current strategy lists every required runtime capability.
- [ ] Truncated HTTP responses and headers do not count as successful probes; Telegram transfers require a successful HTTP response.
- [ ] A completed scan report is not lost on session reuse; abandoned DNS lookups retain their concurrency permit until the worker exits.
- [ ] TCP, UDP, and proxy-start operations stop at the active scan deadline.
- [ ] Confirmed dormant probe parser and byte-cap defects have focused regression tests and fixes.
- [ ] Focused Rust tests, architecture-health, Cargo metadata, and affected workspace checks are observed on the integrated tree.

## Ownership

- Diagnostics candidate and classification writer owns those two crates and this task/OpenSpec change.
- Protocol writer owns diagnostics-contracts HTTP helper, diagnostics-http, and diagnostics-telegram.
- Monitor writer owns monitor-engine session and DNS concurrency files.
- Transport writer owns diagnostics-transport and diagnostics-probes.
- Task/OpenSpec files and any shared lockfile are serialized to the diagnostics candidate writer. No writer edits another lane.
