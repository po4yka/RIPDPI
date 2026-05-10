---
title: Add Pluggable Transport (obfs4 / Snowflake / meek) Reachability Probe
type: task
status: backlog
area: diagnostics
priority: medium
owner: unassigned
parent: dpi-checkers-parity-epic
blocks: []
blocked_by: [add-dpi-error-classifier]
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add Pluggable Transport (obfs4 / Snowflake / meek) Reachability Probe #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Objective

Add `PluggableTransportReachabilityProbe` that probes whether each Tor pluggable transport class — obfs4 bridges, Snowflake brokers + STUN, meek front domains — is reachable from the user's network. Reports per-PT verdict matrix: `PT_OK`, `PT_BRIDGE_BLOCKED`, `PT_BROKER_BLOCKED`, `PT_FRONT_BLOCKED`. Surfaces a rich signal about *what kind* of obfuscation the censor is willing to let through, even though RIPDPI doesn't ship PTs as transports.

## Context

Tor's pluggable transports are battle-tested DPI evasion mechanisms. Each one is a different obfuscation strategy:

| PT | Obfuscation | Detection signature |
|---|---|---|
| **obfs4** | Random-looking bytes + IAT timing; pre-shared cert | Bridge IPs are public; reachability tests whether the censor IP-blocks Tor BridgeDB |
| **Snowflake** | WebRTC DataChannels via volunteer ephemeral peers; broker assigns peers | Tests whether broker URL is reachable AND whether STUN traversal works |
| **meek** | Domain-fronted HTTPS to a CDN front (e.g. Azure, Fastly) | Tests whether the front domain is reachable AND whether SNI != Host is permitted |

A user with all 3 blocked is in a heavily-locked-down network. A user with snowflake working but obfs4 blocked has IP-based filtering, not protocol filtering. A user with meek working but the others blocked has unrestricted HTTPS to CDNs but Tor-specific blocking.

Even though RIPDPI doesn't bundle Tor or PTs, this probe is valuable as a **bypass-reachability fingerprint**: it tells the user (and any community measurement project) which obfuscation classes survive on the local network.

**Per-PT probe:**

1. **obfs4 reachability** — pull a small set of obfs4 bridges from the bundled `bridges.obfs4` list (last 10 published by the Tor Project, refreshable via user override); for each, attempt TCP connect + first 32 bytes of obfs4 handshake; verdict `PT_OK` if any bridge accepts initial bytes, else `PT_BRIDGE_BLOCKED`
2. **Snowflake** — HTTP POST to the Snowflake broker (`https://snowflake-broker.torproject.net/`) with an offer SDP; verdict `PT_BROKER_BLOCKED` if broker unreachable; if reachable, attempt STUN binding to `stun.l.google.com:19302` (matches snowflake's default); verdict `PT_OK` if both succeed, else `PT_STUN_BLOCKED`
3. **meek** — HTTPS HEAD to known front domains: `https://ajax.aspnetcdn.com/` (Azure), `https://www.fastly.com/` (Fastly), `https://d2zfqthxsdq309.cloudfront.net/` (AWS); verdict `PT_OK` if any front responds 200 with `Server: AmazonS3` / `Server: Microsoft-IIS` / etc.; verdict `PT_FRONT_BLOCKED` otherwise

**No actual Tor traffic is sent.** This is a *reachability* probe, not a usage probe — it tests whether the channels are open, not whether circuits work end-to-end. That bounds the scope dramatically and avoids any "running Tor" connotation in app store review.

**Reference:** Tor Pluggable Transports specification (`https://gitweb.torproject.org/pluggable-transports/`), Snowflake source (`https://gitweb.torproject.org/pluggable-transports/snowflake.git`)

**RIPDPI placement:**
- Probe: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/PluggableTransportReachabilityProbe.kt`
- Per-PT subprobes: `Obfs4ReachabilityProbe.kt`, `SnowflakeReachabilityProbe.kt`, `MeekReachabilityProbe.kt`
- Result: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/PluggableTransportResult.kt`
- Bundled bridges: `core/diagnostics/src/main/assets/dpich/obfs4_bridges.txt` (10 pinned bridges, refreshable via user override)
- Bundled meek fronts: `core/diagnostics/src/main/assets/dpich/meek_fronts.txt`

## Acceptance criteria

- [ ] `PluggableTransportResult`: `obfs4: PtVerdict`, `snowflake: PtVerdict`, `meek: PtVerdict`, plus per-PT detail traces
- [ ] `PtVerdict` sealed: `PtOk(detail)`, `PtBridgeBlocked(reason)`, `PtBrokerBlocked(reason)`, `PtFrontBlocked(reason)`, `PtStunBlocked(reason)`, `PtError(reason)`
- [ ] obfs4 probe: TCP connect to bridge IP:port, send 32 bytes of mock-obfs4 handshake (no real cert exchange — we're not establishing a Tor circuit), wait for any TCP response; success = bridge sent ≥1 byte
- [ ] Snowflake probe: HTTP POST to broker URL with empty SDP offer (broker returns 400 with valid JSON if reachable; that counts as `PT_OK` for the broker leg); STUN probe via `Socket.connect(stun.l.google.com:19302, UDP)` + send minimal STUN binding request; success = STUN binding response received
- [ ] meek probe: HTTPS HEAD to each front in parallel; success = any front returns 2xx/3xx
- [ ] No real Tor circuits, no SAM control port, no real obfs4 cert validation — strictly a reachability fingerprint
- [ ] Bridge list bundled at `assets/dpich/obfs4_bridges.txt` with 10 pinned bridges + user-override at `filesDir/dpich/obfs4_bridges.txt`
- [ ] Privacy: this probe makes outbound connections to Tor infrastructure. Privacy Mode disables it; settings entry under "Diagnostic — advanced" with a clear explainer that the probe contacts the Tor broker and obfs4 bridges
- [ ] All 3 PT subprobes run in parallel via `coroutineScope`
- [ ] Per-PT timeout 8s
- [ ] Unit tests: each PT subprobe verdict path; no-cross-bleed if one PT fails

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/PluggableTransportReachabilityProbeTest.kt`:
     - `all_three_pts_reachable_returns_all_ok()` — fakes for all 3 PTs return success; assert all 3 verdicts `PtOk`; fails until probe exists
     - `obfs4_bridge_blocked_isolated()` — fake obfs4 fails, others succeed; assert `obfs4 = PtBridgeBlocked`, others `PtOk`
     - `snowflake_broker_unreachable_returns_broker_blocked()` — fake broker HTTP returns 503; assert `snowflake = PtBrokerBlocked`
     - `snowflake_broker_ok_but_stun_blocked_returns_stun_blocked()` — broker OK, STUN throws; assert `snowflake = PtStunBlocked`
     - `meek_all_fronts_blocked()` — all 3 fronts time out; assert `meek = PtFrontBlocked`
     - `meek_one_front_ok_returns_ok()` — only Azure responds 200; assert `meek = PtOk(detail = "azure")`
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/Obfs4ReachabilityProbeTest.kt`:
     - `bridge_responds_with_any_byte_returns_ok()` — fake socket returns 1 byte; assert `PtOk`
     - `bridge_tcp_rst_returns_bridge_blocked()` — fake socket throws `ConnectionResetException`; assert `PtBridgeBlocked`
     - `tries_all_10_bridges_before_giving_up()` — instrument; 10 bridges all fail; assert all 10 attempted
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 9 fail
3. **Implement** — `PluggableTransportReachabilityProbe` + 3 subprobes; STUN minimal client; mock-obfs4 handshake byte sequence
4. **Confirm green** — `./gradlew :core:diagnostics:test`
5. **Refactor** — extract `connectAndSend(host, port, bytes): ConnectResult` shared between obfs4 and meek

## Definition of done

All 9 unit tests green. PT reachability surfaced in DiagnosticsScreen Tools as "Pluggable Transport Reachability" card with 3 per-PT rows. Privacy explainer present. No actual Tor traffic generated. Bridge list refreshable via user override.
