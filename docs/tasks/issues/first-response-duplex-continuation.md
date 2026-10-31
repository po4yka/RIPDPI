---
id: PRX-1792849175606125
title: Forward request continuation during first-response observation
kind: bug
status: done
area: proxy
priority: high
owner: protocol-runtime
parent: null
blocked_by: []
spec_mode: required
openspec_change: first-response-duplex-continuation
created: 2026-10-24
updated: 2026-10-31
status_detail: Implementation, independent review and physical upload passed; the final execution step and hosted evidence remain open until CI passes on the published candidate.
closed_at: "2026-10-31T06:31:52Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Landed in b3bdf4263 (physical candidate 47a5ee708 passed all four owned USB scenarios); full push CI run 40122030612 on b3bdf4263 passed ci-required with 48 jobs; main 32a9b2c8a passed run 41170842447.
---

## Goal

Complete uploads when the first-response observer is active, without losing response classification or replaying an unretained request body. This fixes the Hysteria upload blocker found by TST-1792776319490914.

## Acceptance criteria

- A real proxy completes a split-header 1 MiB POST with autolearn enabled and an origin that responds only after the complete body; byte count and digest match.
- Client continuation uses bounded duplex forwarding with backpressure. Early responses, half-close and cancellation cannot deadlock or leak resources.
- First-response classification, autolearn and exact response delivery remain active on portable and io_uring paths.
- A flow cannot reconnect or replay after continuation handoff. Existing first-payload-only retries remain covered.
- Regression, full affected native gates and rebuilt physical Hysteria upload pass. Hosted and device evidence remain separate until observed.

## Ownership

- Protocol worker owns the shared native proxy relay first-exchange, first-outbound, response, session, execution and stream-copy code and its focused runtime tests in an isolated worktree.
- Protocol worker also owns `native/rust/crates/ripdpi-proxy-runtime/src/runtime/state/listener.rs` for explicit owner cancellation after peer FIN. Close write and read halves independently; preserve normal half-close and the existing drain deadlines.
- Security worker reviews cancellation, byte ownership, backpressure and replay safety independently. The coordinator owns this task, its OpenSpec records and integration.
- Android acceptance worker alone owns the authorized device. No Hysteria protocol, HTTP fixture, timeout, dependency, schema or production credential change is planned.
- Android acceptance worker also owns the Linux io_uring test step in `.github/workflows/ci.yml` and its bounded runner script. Protocol worker owns the runtime tests that prove the real io_uring path ran. The coordinator integrates both changes after independent review.
