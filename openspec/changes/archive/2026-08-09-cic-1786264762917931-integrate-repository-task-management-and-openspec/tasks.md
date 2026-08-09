# CIC-1786264762917931: Integrate repository task management and OpenSpec

## Objective

Replace the loose task board with a validated portfolio, executable mdtask steps, and strict OpenSpec changes.

## Ownership

- `tools/tasking/`, `taskctl`, and `scripts/tasks/`: tasking toolchain and lifecycle.
- `docs/tasks/` and `openspec/`: portfolio migration and specification records.
- `.github/`, `lefthook.yml`, and `justfile`: automation and repository entrypoints.
- `.agents/skills/` and harness mirrors: generated/adapted agent workflows.

## Execution

- [x] CIC-1786264762918661 Pin mdtask 0.1.17 and OpenSpec 1.8.0 locally with telemetry disabled and generated-asset hashes #feature !high @item:CIC-1786264762917931
- [x] CIC-1786267423492106 Implement strict task schema, shared worktree ID allocation, blockers, ready frontier, lifecycle receipts, and terminal-history validation #feature !high @item:CIC-1786264762917931
- [x] CIC-1786267423508622 Migrate all 48 open portfolio tasks while preserving status, priority, owner, relationships, acceptance criteria, and partial work as open steps #feature !high @item:CIC-1786264762917931
- [x] CIC-1786267423524098 Add the RIPDPI OpenSpec schema, stable requirements, verification matrices, and strict archive/drop workflows #feature !high @item:CIC-1786264762917931
- [x] CIC-1786267423539623 Add task-contract CI, Lefthook and just gates, PR metadata, private security reporting guidance, and remove public issue forms #feature !high @item:CIC-1786264762917931
- [x] CIC-1786267423555362 Pass focused unit, worktree-concurrency, upstream strict-validation, harness, and clean-copy installation gates #feature !high @item:CIC-1786264762917931
- [x] CIC-1786267423572903 Obtain owner/legal approval, push a review branch, pass required remote task-contract CI, then disable public GitHub Issues while confirming Private Vulnerability Reporting remains enabled #feature !high @item:CIC-1786264762917931

## Verification

Use `verification.md` as the evidence map. External GitHub settings and remote CI remain deliberately open until the owner authorizes those actions.
