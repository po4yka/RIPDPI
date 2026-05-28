---
title: "Enforce per-exit-IP concurrent-TLS-connection cap (~12, RU home-ISP policing)"
type: task
status: backlog
area: transport
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-22
updated: 2026-05-22
source_wiki_pages:
  - "[[tls-policing-home-isps]]"
linked_task: null
---

- [ ] #task Enforce per-exit-IP concurrent-TLS-connection cap #repo/RIPDPI #area/transport #status/backlog ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `enforce-per-exit-ip-concurrent-tls-cap`
- **Verify:** `TODO(verify): cargo test -p <transport-crate>`
- **Scope (only modify these + this file + the ledger):** TODO(scope): <module path(s) this task may modify>
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Motivation

50+ RU home-ISP ASNs (MTS/MGTS, JustLan, LanInterCom, RTK Izhevsk, etc.) apply a TLS-handshake-level block when ~12 simultaneous TLS connections to a single foreign IP:port are opened on port 443. The block silently drops new ClientHellos for ~60–120 seconds. Block specifically targets VLESS+Reality+Vision; non-vision VLESS and non-TLS transports are unaffected.

If the RIPDPI client opens more than ~12 concurrent TLS sessions to a single exit IP, the user's connection enters the silent-drop window. Confirmed workarounds: move off port 443 OR remove Vision flow + add mux OR switch to XHTTP+mux.

> [!warning] LOW dedup confidence
> Adjacent: `ripdpi-runtime-adaptive` (timeout adaptation) and `ripdpi-strategy-window` (TCP window manipulation). Connection-count cap at the exit-IP level may already be implicit somewhere; PR description must explicitly confirm no overlap.

## Proposed change

1. Add per-exit-IP concurrent-TLS-session counter in the proxy runtime (likely in `ripdpi-proxy-runtime` or `ripdpi-runtime-services`).
2. Enforce a configurable cap (default 8, well below the ~12 threshold) per exit IP for VLESS+Reality+Vision specifically.
3. When the cap is approached, prefer multiplexing the next stream onto an existing session rather than opening a new one.
4. Surface the cap-near-limit state in the diagnostic UI.
5. Add a measurement probe: "5 simultaneous TLS connections in 60 s" reproducibility test from RIPDPI integration tests.

### Linked deploy task

`linked_task:` points to the sibling deploy task adding a non-443 fallback port to the xray role. Client should prefer the non-443 endpoint when the cap is enforced.

## Acceptance criteria

- [ ] Concurrent-TLS-session counter in proxy runtime, configurable per-transport.
- [ ] Default cap of 8 for VLESS+Reality+Vision on port 443.
- [ ] Mux-preference logic: new streams reuse existing TLS sessions when cap is approached.
- [ ] Integration test in `test-lab/tls/` reproduces the 5-simultaneous-connections probe.
- [ ] LOW-confidence dedup resolved in PR description: explicitly confirmed no overlap with `ripdpi-runtime-adaptive` connection limit (if any) or `ripdpi-strategy-window`.

## Risks / open questions

- Cap value (~12) was measured on MTS Novosibirsk; per-ASN variance may require adaptive tuning.
- Trigger may be post-handshake behavior rather than ClientHello — captured Wireshark from a real reproduction would resolve.
- Adaptive vs static cap: should the cap auto-tune based on observed drop rate?

## References

- [[tls-policing-home-isps]] — wiki concept page with full mechanism + workarounds
- [[censorship-update-net4people-2026-05-15]] — source digest with operational quick-probe
- Linked deploy task: `add-non-443-fallback-port-to-xray-role`
