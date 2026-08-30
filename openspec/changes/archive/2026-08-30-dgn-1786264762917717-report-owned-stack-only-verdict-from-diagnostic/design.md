## Context

Portfolio task `DGN-1786264762917717` owns this change. When transparent arms (A3–A8) all fail but an owned-stack arm (A9/A10) works, the diagnostic returns OWNEDSTACKONLY. Surface that as a real verdict, not a failure — "open this host inside the RIPDPI browser" is a legitimate outcome

## Goals / Non-Goals

- Goal: deliver `Report OWNED_STACK_ONLY verdict from diagnostic` with observable evidence.
- Goal: preserve the task's declared module, contract, and validation boundaries.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Treat the portfolio task as the source of priority, ownership, and lifecycle state.
- Treat this OpenSpec change as the normative behavior delta for the `diagnostics` area.
- Preserve the adaptive suppression reason as a typed decision instead of inferring policy from an empty target list.
- Run owned-stack admission before WebSocket fallback and delayed-connect success replies. A rejected request MUST NOT open an upstream socket or report success first.
- Apply the domain policy only when the inbound path carries a non-empty DNS hostname and the capability's hostname authority matches. IP literals carried in a SOCKS domain field or HTTP host field are not hostname attribution. When both exact and wildcard capability records exist for the authority, prefer the exact IP-set digest; a hostless transparent listener MUST NOT infer a domain policy from an IP address shared by unrelated authorities.
- Project `OWNED_STACK_REQUIRED` as SOCKS5 `REP=0x02`, as HTTP `403 Forbidden` with a static `X-RIPDPI-Reason` header, and as a wire-preserving runtime telemetry event. The event is emitted once per authority/IP-set for each runtime lifetime.
- Keep response bodies and logs free of destination host or IP data. No telemetry schema bump is required because direct-path event names are already wire-preserving strings.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all mdtask steps and evidence categories to be resolved.
- Parallel work could collide in shared contracts. → Follow the serialized-file and worktree rules in `AGENTS.md`.
- Repeated rejected connections could grow the telemetry queue before polling. → Deduplicate the structured event per authority/IP-set in runtime policy state.
- IP-only transparent ingress cannot identify a domain safely. → Preserve connectivity for hostless traffic and reject only hostname-attributed flows.

## Migration Plan

Implement in an isolated worktree, run the named local and hosted gates, then archive only through `taskctl` after review.
