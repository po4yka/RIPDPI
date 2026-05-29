---
title: Spike - native core crash isolation tradeoffs
type: task
status: backlog
area: service
priority: low
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-29
updated: 2026-05-29
---

- [ ] #task Spike - native core crash isolation tradeoffs #repo/RIPDPI #area/service #status/backlog 🔽

## Summary

Investigate whether RIPDPI's in-process JNI Rust core should adopt the crash/panic isolation that simpler clients (xivpn) get by running their proxy core in a **separate OS process**, and record the decision as an ADR. This is a decision spike, not an implementation commitment.

## Context

xivpn's headline architectural feature is running Xray-core in a separate process so memory leaks and core panics cannot crash the app and restarts cannot leak. RIPDPI runs its Rust relay-core **in-process via JNI**, which is exactly why `.claude/rules/` carry heavy invariants: SIGPIPE/panic crashing the whole process, `JNI_OnUnload` boundaries, LMK SIGKILL with no Drop, tokio shutdown self-deadlock. RIPDPI already runs *some* relays as supervised external subprocesses (`naiveproxy`, `snowflake`, `obfs4`), so the subprocess-supervision pattern partially exists. The question is whether the panic-prone surface of the native core deserves the same treatment, and at what cost to the `VpnService.protect()` JNI-callback path. Related but distinct existing work: `adopt-process-based-per-package-routing-via-xray-tun-routeonly` and [[Epic - Runtime lifecycle and supervisors]] (exit-cause semantics, readiness events).

## Acceptance criteria

- [ ] ADR under `docs/adr/` weighing: (a) status quo + panic sentinels, (b) isolating the relay-core into a supervised subprocess with an IPC boundary, (c) hybrid (isolate only the highest-panic-risk crates).
- [ ] Document the impact on the `vpnservice-protect-invariant` (how `protect(fd)` is delivered across a process boundary — UDS + SCM_RIGHTS already in scope per that rule).
- [ ] Quantify cost: IPC overhead on the data path, added memory/process count, LMK behavior with two processes, complexity vs. the current panic-sentinel approach.
- [ ] Explicit recommendation (GO / NO-GO / HYBRID) with rationale; if NO-GO, record why so it is not re-litigated.
- [ ] If GO/HYBRID, spawn a follow-up epic; if NO-GO, this task closes with the ADR as the artifact.

## Source references

**Reference (xivpn):** "Separate process for Xray core" — README headline feature; `XiVPNService` runs the core out-of-process. Concept only.

**Adapt:** RIPDPI's existing external-PT subprocess supervision (`naiveproxy`/`snowflake`/`obfs4`) as the precedent for a supervised-subprocess boundary.

**Invent:** the protect-across-process delivery analysis and the panic-surface risk ranking of the relay-core crates.

## Links

- [[Epic - Runtime lifecycle and supervisors]]
- `.claude/rules/vpnservice-protect-invariant.md`, `.claude/rules/android-vpn-lifecycle.md`, `.claude/rules/llm-rust-prompts.md`
