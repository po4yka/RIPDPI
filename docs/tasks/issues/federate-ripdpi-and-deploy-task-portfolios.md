---
id: CIC-1786277494692459
title: Federate RIPDPI and deploy task portfolios
kind: feature
status: review
area: ci
priority: high
risk: high
owner: Tasking federation maintainer
parent: null
blocked_by: []
spec_mode: required
openspec_change: cic-1786277494692459-federate-ripdpi-and-deploy-task-portfolios
created: 2026-08-09
updated: 2026-08-09
status_detail: Phase-1 federation contract is locally validated against deploy SHA 0026d72; hosted CI and phase-2 cascade purge remain pending.
---

## Goal

Make RIPDPI and ripdpi-vpn-deploy task portfolios independently usable while
allowing either repository to refer to a stable task in the other repository.
The combined graph must remain deterministic, fail closed for unresolved
dependencies, and require no global tasking installation or shared backend.

## Ownership

- This worktree owns `scripts/tasks/`, `tools/tasking/project.json`, the
  federation OpenSpec capability, task-contract tests, and RIPDPI task records.
- The deploy worktree owns every file in `ripdpi-vpn-deploy`; it must consume
  the same versioned export contract without editing this worktree.
- `docs/tasks/board.md` and generated skill hashes remain serialized outputs
  and are regenerated only after the source contract is complete.

## Acceptance criteria

- [x] Repository differences are declared in a checked-in project config rather
      than hard-coded in the shared tasking implementation.
- [x] `parent`, `blocked_by`, and `related_tasks` accept qualified references of
      the form `owner/repository#TASK-ID`; legacy `linked_task` is rejected.
- [x] `taskctl export --json` and `taskctl federation list|ready|graph|validate`
      expose a versioned two-repository graph with derived reverse edges.
- [x] Strict federation rejects missing peers/tasks, contract drift, cross-repo
      cycles, and dropped blockers while resolving completed blockers from Git
      terminal history.
- [x] RIPDPI task contracts, generated board, unit tests, and clean-install
      validation pass with the pinned tools.
