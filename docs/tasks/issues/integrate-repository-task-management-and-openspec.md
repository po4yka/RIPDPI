---
title: Integrate repository task management and OpenSpec
type: task
status: doing
area: ci
priority: high
owner: Codex coordinator
parent: null
blocks: []
blocked_by: []
created: 2026-08-09
updated: 2026-08-09
---

## Goal

Replace the repository's loose task-board workflow with a validated two-level portfolio and execution system backed by mdtask and OpenSpec.

## Ownership

- Coordinator: task schema, migration, integration, and combined-tree verification.
- Tasking tooling lane: pinned Node toolchain, `taskctl`, and automated tests.
- OpenSpec lane: project schema, templates, verification contract, and generated agent skills.
- Automation lane: CI, lefthook, just recipes, and pull-request contract.
- Documentation lane: repository guidance and task-board skill.

Shared task records, generated boards, lockfiles, agent skills, and CI entrypoints remain serialized under the coordinator.

## Ship definition

- All open portfolio records use stable IDs and the strict schema.
- mdtask and OpenSpec are repository-pinned and runnable without global installs.
- Simple and OpenSpec-backed task lifecycles are fail-closed locally and in CI.
- The migrated board preserves all open work and the integration test suite is green.
