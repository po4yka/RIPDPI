## Context

Portfolio task `RST-1786264762917193` owns this change. Add an outbound traffic-shaping layer that emits packets at a fixed rate and size (e.g. 200-byte UDP every 20 ms — Opus-over-RTP shape) regardless of payload arrival rate. This defeats both inter-packet-arrival-time (IPAT) and packet-size-distribution fingerprinting that DPI uses to distinguish "bulk file transfer masquerading as VoIP" from real VoIP

## Goals / Non-Goals

- Goal: deliver `Add constant-rate traffic shaping with VoIP camouflage profile` with observable evidence.
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
