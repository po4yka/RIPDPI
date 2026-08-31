## Context

Portfolio task `TRN-1786264762917184` owns this change. Proxy-runtime's direct path already applies a per-exit-IP concurrent-TLS admission gate; this change reuses the extracted `ripdpi-session-limit` primitive for relay-core's physical VLESS+Reality carriers while keeping the two counter instances independent.

## Goals / Non-Goals

- Goal: deliver `Per-exit-IP TLS cap with true mux-preference in relay-core backend` with observable evidence.
- Goal: preserve the task's declared module, contract, and validation boundaries.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Treat the portfolio task as the source of priority, ownership, and lifecycle state.
- Treat this OpenSpec change as the normative behavior delta for the `transport` area.
- Keep implementation details in the affected modules and record exact verification evidence separately.
- Count physical VLESS+Reality TCP/TLS carriers, never logical mux streams.
- Key admission by the single resolved socket address that is also used for the connection; resolving again after admission could count one IP and dial another.
- Extract the existing limiter into a neutral leaf crate shared by proxy-runtime and relay-core. Each data plane owns a separate limiter instance, so one physical connection is never counted by both paths.
- Preserve explicit mux configuration. A mux-enabled backend reuses its cached carrier from the second stream onward; a non-mux backend at cap fails admission because no compatible carrier exists to reuse.
- Keep near-cap UI/schema work outside this change because it is not an acceptance requirement or execution step.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all mdtask steps and evidence categories to be resolved.
- Parallel work could collide in shared contracts. → Follow the serialized-file and worktree rules in `AGENTS.md`.
- Counting a hostname instead of the connected peer can split or merge unrelated budgets. → Resolve once, acquire by the resulting IP, and dial that exact address.
- Treating a logical stream as a TLS session can exhaust the budget while only one carrier exists. → Hold the slot in the physical carrier wrapper or session object until that carrier drops.
- A non-mux profile has no compatible carrier to reuse at cap. → Reject the new carrier without silently changing the profile's transport contract.

## Migration Plan

Implement in an isolated worktree, run the named local and hosted gates, then archive only through `taskctl` after review.
