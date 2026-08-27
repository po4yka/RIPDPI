## Context

Portfolio task `VPN-1786264762917166` owns this change. On Linux kernel 5.7+ (predominantly Android 12+, API 31+), SOBINDTODEVICE privilege was dropped — any unprivileged app can bind a socket directly to a network interface (e.g., tun0) and bypass all Android VPN split-tunneling routing rules. Standard tun2socks reads packets off the TUN interface but has no UID attribution, so any per-app split-tunnel enforcement done at the routing layer is invisible to it

## Goals / Non-Goals

- Goal: deliver `Add tun2socks UID validation to close SO_BINDTODEVICE escape (kernel 5.7+)` with observable evidence.
- Goal: preserve the task's declared module, contract, and validation boundaries.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Treat the portfolio task as the source of priority, ownership, and lifecycle state.
- Treat this OpenSpec change as the normative behavior delta for the `vpn` area.
- Keep implementation details in the affected modules and record exact verification evidence separately.
- Admit the original kernel-visible TCP/UDP tuple before any egress interceptor can emit raw traffic. Unresolved packets wait in bounded storage; an admitted packet invokes the interceptor once. Denied TCP still reaches the local reset path without invoking an egress hook.
- Pending TCP listeners own their attribution generations until admission transfers ownership to the active session, or timeout/pressure cleanup invalidates the outstanding lookup. UDP association ownership metadata must remain bounded under tuple/generation churn.
- Physical qualification uses the real production capability decision on both sides of kernel 5.7, including permission-denied old kernels and supported backports. Socket-table evidence requires a synchronized denial window and a visible positive control; unreadable or unobservable tables remain blocked evidence.

- smoltcp LISTEN sockets match only the destination. After every packet poll, reconcile accepted handles to their actual source/destination owners before GC or another input batch. Keep attribution generations and timestamps with the original tuple, and never allocate a second owner for an active flow's retransmitted SYN.
- Scope `FlowAttributionBridge` to the application singleton so the native runtime and acceptance observers share one activation epoch. Physical evidence reads the live armed state after `Running` and before export; a separate default-initialized bridge cannot qualify either kernel branch.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all mdtask steps and evidence categories to be resolved.
- Parallel work could collide in shared contracts. → Follow the serialized-file and worktree rules in `AGENTS.md`.

## Migration Plan

Implement in an isolated worktree, run the named local and hosted gates, then archive only through `taskctl` after review.
