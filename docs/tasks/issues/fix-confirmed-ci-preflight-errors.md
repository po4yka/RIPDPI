---
id: CIC-1791950438525162
title: Fix confirmed CI preflight errors
kind: bug
status: doing
area: ci
priority: high
owner: CI maintainers
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-14
updated: 2026-10-14
spec_reason: tooling-only
---

## Goal

Fix the two confirmed preflight failures in CI run 38508116683. Preserve the Android helper assertions, YAML configuration contracts and advisory enforcement.

## Acceptance criteria

- Pass the exact Android E2E result and bounded emulator helper commands in an environment without ripgrep.
- Remove unsafe-libyaml from the resolved dependency graph without an advisory waiver. Pass locked cargo-deny and affected YAML configuration tests.
- Pass combined architecture, task and source checks. Review the diff independently.
- Integrate through a dedicated worktree, publish main and observe successful hosted CI for the final code SHA.

## Ownership

- Android helper writer: isolated ci-helper-portability worktree; scripts/ci/test-android-emulator-helpers.sh and its regression coverage. Preserve every timeout, ownership and teardown assertion.
- YAML writer: isolated ci-yaml-maintenance worktree; serialized owner of native/rust/Cargo.toml, Cargo.lock and the two YAML configuration crates. No advisory, baseline, schema or toolchain weakening.
- Integration writer: ci-preflight-fixes worktree; task records, combined validation, review and authorized main publication.
- Writers are not alone in this repository. Preserve all other agents' work. Locale resources, fixtures and unrelated worktrees are outside scope.
