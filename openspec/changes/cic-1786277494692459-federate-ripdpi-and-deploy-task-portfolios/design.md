## Context

RIPDPI taskctl currently hard-codes project areas, evidence axes, and a local
`linked_task` field. A clean checkout must remain self-contained, while strict
CI needs enough peer history to distinguish an active task from a purged task
that previously reached `done` or `dropped`.

## Goals / Non-Goals

- Goal: provide stable one-sided cross-repository parent, blocker, and related
  relationships with derived reverse edges and a combined ready frontier.
- Goal: keep both repositories independently usable without network access.
- Non-goal: create a central service, duplicate peer state in a committed
  aggregate board, or require reciprocal task edits.

## Decisions

- Store project ID, areas, evidence categories, OpenSpec schema, and allowed
  peers in `tools/tasking/project.json`; taskctl validates this file before
  loading portfolio state.
- Use `owner/repository#TASK-ID` as the only external reference syntax. Local
  IDs remain unchanged; fully-qualified identity is the federation key.
- Make one outbound relation canonical. Reverse `blocks` and parent/child edges
  are derived after loading both exports.
- Export schema version, project, Git revision, task path, status, progress,
  relationships, and OpenSpec change. Never export task bodies or evidence that
  could contain sensitive operational detail.
- Resolve a missing active peer record through full Git history only when a
  strict terminal record exists. `done` satisfies a blocker; `dropped` does not.
- Keep local validation offline: it validates qualified syntax and allowed peer
  names. Federation validation explicitly receives a peer checkout path.

## Contracts and ownership

- `scripts/tasks/taskctl.py` owns parsing, export, federation graph, history
  resolution, and commands.
- `tools/tasking/project.json` owns per-repository configuration.
- Each repository owns its own task files, board, OpenSpec schema, and terminal
  history. Neither repository writes into the peer checkout.
- Common federation fixtures and the export schema version are byte-equivalent
  in both repositories and covered by contract tests.

## Risks / Trade-offs

- Peer main can change between coordinated PRs. → A new outbound link may only
  target a task already present in peer main; no reciprocal edit is required.
- A shallow peer checkout cannot resolve purged terminal tasks. → Strict CI uses
  `fetch-depth: 0` and fails when history is incomplete.
- Configuration could let implementations drift. → Export contract version and
  fixture parity fail closed before graph evaluation.
- Local ready output lacks peer state. → External blockers remain unresolved
  unless the explicit federation command receives a peer checkout.

## Migration Plan

Land the RIPDPI export contract first without making peer CI required. Port the
same contract to deploy and enable its strict peer gate. After deploy main
supports the export, enable the reciprocal required gate in RIPDPI. Rollback is
removal of federation commands and external refs; local task lifecycle remains
usable throughout the staged rollout.
