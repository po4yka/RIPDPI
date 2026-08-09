## Context

Portfolio task `RST-1786264762917181` owns this change. Allow diagnostic probes (TLS reachability, TCP 16-20KB cutoff, DNS resolver availability, HTTP injection) to be routed through an arbitrary upstream HTTP or SOCKS5 proxy supplied by the user, so the operator can compare results across paths without leaving the app

## Goals / Non-Goals

- Goal: deliver `Add upstream HTTP and SOCKS5 proxy override for diagnostic probes` with observable evidence.
- Goal: preserve the task's declared module, contract, and validation boundaries.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Treat the portfolio task as the source of priority, ownership, and lifecycle state.
- Treat this OpenSpec change as the normative behavior delta for the `rust-native` area.
- Keep implementation details in the affected modules and record exact verification evidence separately.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all mdtask steps and evidence categories to be resolved.
- Parallel work could collide in shared contracts. → Follow the serialized-file and worktree rules in `AGENTS.md`.

## Migration Plan

Implement in an isolated worktree, run the named local and hosted gates, then archive only through `taskctl` after review.
