## Context

Portfolio task `TRN-1786264762917675` owns this change. sing-box v1.14.0-alpha.22 (2026-05-11) introduced a Hysteria Realm service that enables direct peer-to-peer Hysteria2 QUIC tunnels between two clients behind separate NATs — without a fixed listening server on a datacenter ASN. Datacenter-path QoS policies, including short-transfer stalls and session-volume caps, can affect conventional Hysteria2 deployments; Realm permits alternate peer placement because the data peer can live on a residential or mobile ASN behind NAT

## Goals / Non-Goals

- Goal: deliver `Wire Hysteria Realm STUN-discovered NAT traversal (sing-box v1.14.0-alpha.22)` with observable evidence.
- Goal: preserve the task's declared module, contract, and validation boundaries.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Treat the portfolio task as the source of priority, ownership, and lifecycle state.
- Treat this OpenSpec change as the normative behavior delta for the `transport` area.
- Keep implementation details in the affected modules and record exact verification evidence separately.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all mdtask steps and evidence categories to be resolved.
- Parallel work could collide in shared contracts. → Follow the serialized-file and worktree rules in `AGENTS.md`.

## Migration Plan

Implement in an isolated worktree, run the named local and hosted gates, then archive only through `taskctl` after review.
