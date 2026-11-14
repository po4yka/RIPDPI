# ENG-1794637897912969

## Objective

Fix the three audit defects and retain the direct synthetic-decoy contract.

## Ownership

Use the path ownership in design.md and the portfolio record. Each writer has an isolated worktree. The integrator alone owns task/OpenSpec artifacts and integration.

## Verification

Run targeted core:data:model unit tests, locked Cargo tests for config/desync/packets/runtime adapter, independent host wire decoding, read-only review, just ci-gates, architecture health, and locked Cargo metadata.

## Execution

- [ ] ENG-1794638032319330 Bound CID churn configuration and planner work with regression tests #bug !high @item:ENG-1794637897912969
- [ ] ENG-1794638033677169 Make CID churn IDs distinct across the generated plan and verify packet integrity #bug !high @item:ENG-1794637897912969
- [ ] ENG-1794638037254859 Fix Initial padding width and verify maximum IPv4 loopback emission #bug !high @item:ENG-1794637897912969
- [ ] ENG-1794638039475187 Review the combined fixes and run required integration gates #bug !high @item:ENG-1794637897912969
