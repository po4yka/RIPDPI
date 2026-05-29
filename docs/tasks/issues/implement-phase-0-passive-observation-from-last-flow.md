---
title: Implement Phase 0 passive observation from last flow
type: task
status: done
area: diagnostics
priority: medium
owner: unassigned
parent: epic-direct-mode-diagnostic-state-machine
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-29
---

- [x] #task Implement Phase 0 passive observation from last flow #repo/RIPDPI #area/diagnostics #status/done 🔼

## Summary

Before active probing, extract what we can from the last real failed flow: DNS outcome, TCP SYN/SYN-ACK, did failure happen before or after ClientHello, did UDP/443 fail while TCP to same host worked, did the response look like a error-page.

## Plan reference

ripdpi-android-direct-mode-plan-2026-04-20 "Phase 0 — Passive observation first".

## Progress

Verified 2026-05-29. The typed passive-observation layer is now landed in the
orchestrator package:

- `PassiveObserver.observe(LastFailedFlow?)` emits a typed `PassiveObservation`
  (DNS verdict, `FailPhase`, `udp443FailedWhileTcpWorked`, `ErrorPageShape`),
  returning the `PassiveObservation.NONE` sentinel for the no-flow case;
- error-page detection uses a small conservative heuristic set — TLS certificate
  mismatch, known censor block-notice phrases, middlebox HTML interstitials, and
  legal-block size anomalies (451/403 + tiny body);
- `DirectModeOrchestrator` runs the observer as Phase 0 and threads the result
  into the `DnsClassifier` / `TransportPolicyClassifier` contracts so Phases 1–2
  are seeded instead of probing from zero;
- diagnostics finalization still consults the previously confirmed authority
  record before pinning a new verdict, giving a complementary persisted prior.

Still open (tracked under the epic's "wire the pure orchestrator to the
production probe executors" item, not this task): populating `LastFailedFlow`
from a live runtime failure once the production orchestrator is wired in.

## Acceptance criteria

- [x] Passive observer runs when a flow fails; emits a typed `PassiveObservation` struct.
- [x] Error-page detection uses a small heuristic set — TLS certificate mismatch, known middlebox block HTML shapes, response sizes, common block patterns.
- [x] Phase 0 observation is consumed by Phase 1/Phase 2 classification instead of them probing from zero.
- [x] Zero added cost on success paths.

## Links

- [[Epic - Direct-mode diagnostic state machine]]
- ripdpi-android-direct-mode-plan-2026-04-20
