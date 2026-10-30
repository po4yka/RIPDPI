---
id: CIC-1793341538890939
title: Restore UI audit and Rust fuzz CI gates
kind: bug
status: doing
area: ci
priority: high
owner: CI regression repair
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-30
updated: 2026-10-30
spec_reason: test-only
---

## Goal

Restore the UI audit and Rust fuzz checks without reducing coverage or changing production APIs. The user explicitly authorizes separate commits directly on main and push to origin.

## Acceptance criteria

- The UI audit checks the exact approved route exclusions and retains typed metadata and exact flow partition checks.
- Every declared Rust fuzz target builds with normal dependency APIs; TLS mutation and split-record inputs remain exercised.
- Regression tests cover shared fuzz record construction, including boundaries and malformed inputs.
- The API 37 smoke resolves all six declared Kaspresso testcases and keeps exact execution and skip checks.
- Local guide and fuzz gates pass, followed by green hosted CI and CodeQL for the pushed revision.

## Scope

This is test and tooling repair only. No production dependency, Rust public export, feature gate, workflow condition, quality threshold, audit exclusion, or golden fixture changes. Synchronize only the standalone fuzz lockfile with the current path dependency graph; its lru 0.19.0 and tokio-boring 5.3.0 versions already match the main workspace lockfile.
