---
title: "Restore discarded adaptive-routing feedback in proxy-runtime UDP and retry paths"
type: task
status: todo
area: proxy
priority: medium
owner: unassigned
parent: epic-june-2026-audit-remediation
blocks: []
blocked_by: []
created: 2026-06-10
updated: 2026-06-10
source_wiki_pages: []
linked_task: null
---

## Motivation

The 2026-06-10 Rust API audit confirmed the previously-tracked F8/F9 findings are still open. Six `let _ =` discards drop adaptive-routing feedback results, leaving the policy learner blind:

- `ripdpi-proxy-runtime/src/runtime/udp/feedback.rs` (3 sites): `note_direct_path_quic_success`, `note_direct_path_udp_failure`, `note_direct_path_all_ips_failed` results are discarded — adaptive routing never receives UDP path outcomes.
- `ripdpi-proxy-runtime/src/runtime/routing/retry.rs:34,59,65` (3 sites): `note_direct_path_all_ips_failed` on route exhaustion is discarded — policy cannot converge after repeated group failures.

The `let _ =` swallows both the success signal *and* any error, so a failing feedback channel is silently invisible.

## Proposed change

1. For each of the six sites, replace `let _ = note_...(...)` with explicit handling: propagate or at minimum log the error path (the project forbids per-packet logging on hot paths, so confirm these are per-event, not per-packet — they are route/path-outcome events, not per-datagram).
2. Confirm the feedback actually reaches the adaptive/learning state (the point of the calls). If the return is `Result`, decide whether a feedback failure should be surfaced to telemetry or retried.
3. Add a test asserting that a UDP-path failure and a route-exhaustion event each produce the corresponding learner state transition (no longer dropped).

## Acceptance criteria

- [ ] PR confirms current state at the 6 cited sites.
- [ ] No `let _ =` discard of adaptive feedback results remains in `udp/feedback.rs` or `routing/retry.rs`.
- [ ] Errors from feedback calls are surfaced (logged at event granularity or propagated), not swallowed.
- [ ] Test: UDP-path-failure and route-exhaustion events drive the expected learner state change.
- [ ] `cargo nextest run -p ripdpi-proxy-runtime --locked` green; clippy clean.

## Risks / open questions

- Verify these are per-event (not per-packet) call sites before adding any logging — per-packet `tracing` is a ~3 µs/event JNI tax per `llm-rust-prompts.md`.
- The learner being "blind" may have masked a deeper convergence bug; watch for behavior change in adaptive routing tests.

## References

- Audit memory `project_native_audit_findings.md` (2026-06-10, item 6 / F8, F9).
