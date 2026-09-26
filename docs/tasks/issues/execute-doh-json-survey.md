---
id: DGN-1790425188376122
title: Execute DoH JSON survey in monitor engine
kind: feature
status: doing
area: diagnostics
priority: high
owner: Diagnostics Rust
parent: null
blocked_by: []
spec_mode: required
openspec_change: dgn-1790425188376122-execute-doh-json-survey
created: 2026-09-26
updated: 2026-09-26
---

## Goal

Execute the selected DoH JSON survey stage and record resolver evidence for each requested DNS target.

## Acceptance criteria

- [ ] Kotlin selects the survey for requested DNS targets; Rust queries each distinct domain through the scan transport and reports each resolver's HTTP, DNS, or network result.
- [ ] TLS verification, JSON selection, input encoding, response bounds, cancellation, and scan deadlines apply to every request.
- [ ] A scan without DNS targets starts no JSON DoH requests; probe outcomes have stable report buckets.
- [ ] Focused Rust tests and the affected architecture, contract, and task gates pass on the integrated tree.

## Ownership

- This worktree owns the Kotlin diagnostics planner and admission, monitor stage, shared resolver panel, HTTP request helper, tests, documentation, and this task/OpenSpec change.
- Shared contract files and task files have one writer. Other worktrees retain their current ownership.
