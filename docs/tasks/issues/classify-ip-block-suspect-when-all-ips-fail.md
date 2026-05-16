---
title: Classify IP_BLOCK_SUSPECT when all IPs fail
type: task
status: done
area: diagnostics
priority: medium
owner: unassigned
parent: epic-direct-mode-transport-policy-and-verdicts
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-16
---

- [x] #task Classify IP_BLOCK_SUSPECT when all IPs fail #repo/RIPDPI #area/diagnostics #status/done 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `classify-ip-block-suspect-when-all-ips-fail`
- **Verify:** `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-diagnostics-classification`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-diagnostics-classification/**`, `native/rust/crates/ripdpi-failure-classifier/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

When encrypted-DNS IPs and alternate address families all fail at connect
time, classify the host as `IP_BLOCK_SUSPECT`. Do **not** brute-force
transport tricks in this state.

## Plan reference

[[ripdpi-android-direct-mode-plan-2026-04-20]] §3 policy rule 3 and Phase
2 classification.

## Acceptance criteria

- [x] Classification fires only when: DoH-provided IPs fail at SYN,
    alternate IP family fails at SYN, and no CDN variant succeeds within
    the attempt budget.
- [x] On `IP_BLOCK_SUSPECT`, the engine jumps straight to owned-stack arms
    (A10/A9) — no TLS family arms.
- [x] False-positive guard: re-verify on the next flow before persisting,
    to avoid pinning on a transient network blip.

## Implementation note

The false-positive guard landed on 2026-04-23: runtime `ALL_IPS_FAILED`
learning now requires a second flow before it persists
`NO_DIRECT_SOLUTION` / `IP_BLOCK_SUSPECT`. Full owned-stack arm gating and
the stricter SYN-only classification budget are still open.

## Work log

- **2026-05-16**: Implemented `classify_ip_block_suspect` pure function in
  `ripdpi-diagnostics-classification::classification::ip_block_suspect`.
  Added `FailureClass::IpBlockSuspect`, `ArmGate`, `IpBlockVerdict`, and
  `IpBlockSuspectVerdict` types to `ripdpi-failure-classifier`. Six tests
  cover all decision-table branches. Verify: `cargo nextest run -p
  ripdpi-diagnostics-classification` exit 0, 146 tests passed.

## Links

- [[Epic - Direct-mode transport policy and verdicts]]
- [[ripdpi-android-direct-mode-plan-2026-04-20]]
