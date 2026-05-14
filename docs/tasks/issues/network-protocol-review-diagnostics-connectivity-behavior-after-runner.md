---
title: Network protocol review: diagnostics connectivity behavior after runner refactor
type: task
status: doing
area: rust-native
priority: high
owner: Senior Network Protocol Engineer
parent: null
blocks: []
blocked_by: []
created: 2026-05-04
updated: 2026-05-04
---

- [ ] #task Network protocol review: diagnostics connectivity behavior after runner refactor #repo/RIPDPI #area/rust-native #status/doing ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `network-protocol-review-diagnostics-connectivity-behavior-after-runner`
- **Verify:** `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-monitor-engine`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-monitor-engine/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Objective
Review the current connectivity runner refactor for DNS/TCP/QUIC/service/circumvention behavior equivalence and diagnostics semantics.

## Context
Parent POY-3 found monitor-engine connectivity.rs split into environment, dns, web, quic, tcp, service, circumvention, throughput, telegram, and support modules. The new support trait centralizes target collection, messages, latest target labels, artifact source mapping, and cancellation checks.

Priority:
High.

Parent issue or goal linkage:

## Acceptance criteria
- Compare old runner macro behavior to new per-stage modules for DNS, web reachability, QUIC, TCP, service, circumvention, throughput, telegram, and environment.
- Confirm phase names, target labels, artifact sources, tls_verifier usage, whitelist_sni usage, path_mode usage, and cancellation behavior are preserved.
- Identify any DNS/proxy/VPN/desync behavior risk requiring Security/AppSec or QA escalation.
- Define targeted network-behavior regression expectations without running live network experiments.

Expected artifact:
Paperclip review comment with approve/request-changes/block decision and concrete test matrix recommendations.

Constraints:
Do not run live network experiments. Do not implement code unless explicitly assigned. Work from current local repository diff.

## Risks
Small runner-label or artifact-source drift can break diagnostics summaries, exports, or user-visible audit interpretation.

## Required verification
Read diff and relevant local files. Recommend targeted cargo tests or fixture/golden checks; if no existing test covers behavior equivalence, call that out.

## Definition of done
Network behavior review is posted with explicit pass/fail and required verification before merge-readiness.
