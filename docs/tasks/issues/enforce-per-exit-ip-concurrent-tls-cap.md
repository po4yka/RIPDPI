---
title: "Enforce per-exit-IP concurrent-TLS-connection cap (~12, RU home-ISP policing)"
type: task
status: doing
area: transport
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-22
updated: 2026-06-05
source_wiki_pages:
  - "tls-policing-home-isps"
linked_task: null
---

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

- [x] Concurrent-TLS-session counter in proxy runtime, configurable per-transport. (`ExitIpSessionLimiter` + `ExitIpSessionCaps` in `ripdpi-proxy-runtime/src/exit_ip_cap.rs`, per-`(exit_ip, transport)` counting with a RAII release guard.)
- [x] Default cap of 8 for VLESS+Reality+Vision on port 443. (`DEFAULT_EXIT_IP_SESSION_CAP = 8`; per-transport overrides via `ExitIpSessionCaps::with_transport`.)
- [ ] Mux-preference logic: new streams reuse existing TLS sessions when cap is approached. (The limiter returns `None` at cap as the mux-preference signal; the reuse wiring at the session-establishment site is the follow-up.)
- [ ] Integration test in `test-lab/tls/` reproduces the 5-simultaneous-connections probe. (Unit-level equivalents land in `exit_ip_cap::tests` — 5-under-cap succeed, 9th refused; the live `test-lab/tls/` network probe + hot-path wiring remain.)
- [x] LOW-confidence dedup resolved: confirmed no overlap — `ripdpi-runtime-adaptive` carries timeout adaptation only (no per-exit-IP / concurrent-TLS counter), and `ripdpi-strategy-window` is TCP-window manipulation; this limiter is net-new.

## Risks / open questions

- Cap value (~12) was measured on MTS Novosibirsk; per-ASN variance may require adaptive tuning.
- Trigger may be post-handshake behavior rather than ClientHello — captured Wireshark from a real reproduction would resolve.
- Adaptive vs static cap: should the cap auto-tune based on observed drop rate?

## References

- tls-policing-home-isps — wiki concept page with full mechanism + workarounds
- censorship-update-net4people-2026-05-15 — source digest with operational quick-probe
- Linked deploy task: `add-non-443-fallback-port-to-xray-role`

## Work log

- 2026-06-05: No implementation found; ripdpi-proxy-runtime and ripdpi-runtime-adaptive have no per-exit-IP TLS counter, no session cap, no mux-preference logic, and test-lab/tls/ has no concurrent-connection probe. All acceptance criteria remain open.
- 2026-06-05: Landed the accounting primitive — `ExitIpSessionLimiter` / `ExitIpSessionCaps` / `ExitIpSessionGuard` in `ripdpi-proxy-runtime/src/exit_ip_cap.rs` (per-`(exit_ip, transport)` counter, configurable caps, `DEFAULT_EXIT_IP_SESSION_CAP = 8`, RAII release guard, `try_acquire` returns `None` at cap as the mux-preference signal). 5 unit tests (5-under-cap succeed / 9th refused / drop-frees-slot / per-transport + per-IP independence) pass, clippy clean. Dedup confirmed (criterion 5). **Remaining (kept `doing`):** wire the limiter into the outbound session-establishment path with mux-preference reuse, and add the live `test-lab/tls/` 5-simultaneous-connections probe — both need the proxy-runtime hot-path integration + network harness.
- 2026-06-05: Re-audit confirms prior log is accurate. `exit_ip_cap.rs` fully implements criteria 1, 2, 5 (verified: `ExitIpSessionLimiter`, `DEFAULT_EXIT_IP_SESSION_CAP = 8`, `ExitIpSessionCaps::with_transport`, 5 unit tests). No call to `try_acquire` found anywhere outside `exit_ip_cap.rs` itself — criterion 3 (mux-preference wiring into session-establishment path) remains unimplemented. `test-lab/tls/` contains only `caddy/` and `certs/` subdirs with no concurrent-connection probe — criterion 4 remains open. Status unchanged: `doing`.
