# CIC-1786277494692459: Federate RIPDPI and deploy task portfolios

## Objective

Deliver a versioned task export and a fail-closed combined graph while keeping
both repositories independently operable.

## Ownership

This change owns RIPDPI taskctl/config/tests/task records. The sibling deploy
worktree owns its port and migration. Generated board and skill hashes are
updated only after their source inputs stabilize.

## Execution

- [x] CIC-1786277545105474 Make repository task policy data-driven through a validated project config #feature !high @item:CIC-1786277494692459
- [x] CIC-1786277545125976 Add qualified references plus versioned export and federation commands #feature !high @item:CIC-1786277494692459 @blocked_by:CIC-1786277545105474
- [x] CIC-1786277545147177 Migrate RIPDPI task records away from linked_task and correct stale cross-repo prose #feature !high @item:CIC-1786277494692459 @blocked_by:CIC-1786277545125976
- [x] CIC-1786277545167878 Cover combined readiness, cycles, history, privacy, and contract mismatch with tests #feature !high @item:CIC-1786277494692459 @blocked_by:CIC-1786277545125976
- [x] CIC-1786277545187486 Regenerate contracts and observe clean local validation on the final source tree #feature !high @item:CIC-1786277494692459 @blocked_by:CIC-1786277545147177 @blocked_by:CIC-1786277545167878

## Verification

Run the taskctl unit suite, `just task-check`, clean pinned-tool installation,
and strict federation validation against the deploy worktree. Hosted CI and the
reciprocal required gate remain rollout evidence rather than local proof.
