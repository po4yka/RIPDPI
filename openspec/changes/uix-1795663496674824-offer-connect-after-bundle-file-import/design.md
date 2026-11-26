## Context

Portfolio task `UIX-1795663496674824` owns this change. It was split from `UIX-1795615855953573`, which gave link, QR code, and single-link file imports a result screen with Connect and Select. A RIPDPI bundle file goes through the subscription confirm flow, saves a SUBSCRIPTION group, and returns to Status with no way to pick an imported server.

## Goals / Non-Goals

- Goal: deliver `Offer Select and Connect after importing a RIPDPI bundle file` with observable evidence.
- Non-goal: change the subscription fetch, refresh, or failure text delivered by `UIX-1795615855953573`.

## Decisions

- Select reuses the selection path of the unified server list (`UIX-1795615852980919`) so that a subscription member is selected the same way everywhere; the import flow adds no second selection path.
- Connect reuses `MainViewModel.onStartRequested()`, which starts the configured mode only when nothing runs.

## Risks / Trade-offs

- A bundle can hold many servers. → The result screen lists their names and lets the user pick one; it does not choose for the user.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review.
