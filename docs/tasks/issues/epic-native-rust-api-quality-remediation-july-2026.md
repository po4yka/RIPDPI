---
title: Fix July 2026 native Rust API and quality findings
type: epic
status: doing
area: epic
priority: medium
owner: Codex
parent: null
blocks: []
blocked_by: []
created: 2026-07-14
updated: 2026-07-14
---

## Goal

Eliminate every confirmed problem and implement every improvement from the API and quality growth section of the July 2026 repeated native Rust audit, with one regression-backed atomic commit per item.

## Why now

The P1 and P2 remediation series closed the prioritized runtime defects. The remaining API and test-topology debt still exposes raw descriptor ownership, keeps synchronous address conversion in an async server path, and makes the advertised all-features test composition fail.

## Key decisions

- Stack this worktree on the completed P2 remediation branch so the final checks cover the full post-audit native tree.
- Keep each API or quality correction and its regression tests in a separate Conventional Commit.
- Express descriptor ownership with standard owned and borrowed fd types rather than documentation around raw integers.
- Keep blocking name resolution off async executor workers and preserve typed SOCKS5 errors.
- Keep ordinary tests out of loom configurations unless they run inside `loom::model`.
- Do not integrate, delete, or push the worktree branch without explicit user confirmation.

## Scope

- [ ] Replace raw descriptor transfer in the root-helper SCM_RIGHTS API with `BorrowedFd` and `OwnedFd`.
- [ ] Remove synchronous address resolution/conversion from the public async SOCKS5 server path.
- [ ] Separate ordinary `HandleRegistry` tests from loom feature builds so `--all-features` is green.

## Ship definition

- Three atomic fix commits exist, each with a regression test or compile-time contract that fails against the audited behavior.
- Focused crate tests and clippy pass after each commit.
- Workspace formatting, clippy, all-features nextest, dependency policy, architecture health, API snapshots, and native CI guards pass on the combined branch.
- The task-board issue is removed after verification; the worktree remains available for review and later explicit integration.

## Work log

- 2026-07-14: Goal started in `worktree-fix-native-api-quality` from `9fe4a96e1`; ownership assigned to Codex.
