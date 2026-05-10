---
title: Add CallTransportLeakChecker for STUN and MTProto Leak Detection
type: task
status: backlog
area: diagnostics
priority: low
owner: unassigned
parent: detection-feature-parity-epic
blocks: []
blocked_by: [add-ip-consensus-synthesis]
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add CallTransportLeakChecker for STUN and MTProto Leak Detection #repo/RIPDPI #area/diagnostics #status/backlog 🔽

## Objective

Add `CallTransportLeakChecker` that performs STUN sweeps on both the VPN path and the underlying non-VPN path, probes Telegram DC2 reachability via MTProto, and checks WhatsApp UDP STUN — all to detect leaks where call traffic bypasses the tunnel.

## Context

VoIP/call transport (STUN, TURN, MTProto) often bypasses VPN tunnels due to UDP binding behavior or split-tunnel misconfiguration. If STUN reflexive addresses differ between the VPN path and the underlying network path, the device is leaking its real IP for calls. Telegram DC2 reachability via proxy confirms MTProto bypass.

This checker is disabled by default (same policy as RKNHardering). It uses `Socks5UdpAssociateClient` for UDP STUN via local proxy endpoints.

**STUN servers probed:** `stun.fitauto.ru`, `stun.sberbank.ru`, all `stun.l.google.com` variants, `stun.cloudflare.com`, `global.stun.twilio.com`, `stun.nextcloud.com`
**MTProto target:** Telegram DC2 — `149.154.167.51:443`

**Reference implementation:** `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/checker/CallTransportChecker.kt`
**STUN client reference:** `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/checker/` — `Socks5UdpAssociateClient`, `MtProtoProber`

**RIPDPI extension points:**
- New `CallTransportLeakChecker.kt` in `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/checker/`
- Add `PREF_CALL_TRANSPORT_PROBE_ENABLED` to detection settings; default `false`
- Feed STUN reflexive addresses into `IpConsensus` (see `add-ip-consensus-synthesis`)
- Add `CallTransportResult` to `DetectionModels.kt`

## Acceptance criteria

- [ ] Checker is disabled by default; no network calls made when disabled
- [ ] STUN binding requests sent on both active VPN path and underlying non-VPN path
- [ ] Reflexive address mismatch between paths → `EvidenceConfidence.HIGH` leak finding
- [ ] RU-scope STUN servers (`stun.sberbank.ru`, `stun.fitauto.ru`) handled separately from global-scope
- [ ] MTProto TCP connect to Telegram DC2 `149.154.167.51:443` — success via VPN path is recorded
- [ ] SOCKS5 UDP associate used when local proxy is detected (reuse `BypassChecker` proxy scan result)
- [ ] STUN reflexive addresses fed to `IpConsensusBuilder` as `CALL_TRANSPORT` channel
- [ ] Unit tests with mock STUN server and mock proxy

## TDD workflow

1. **Write tests first** — stub `StunClient` and `MtProtoProber` interfaces:
   - `core/detection/src/test/kotlin/com/poyka/ripdpi/core/detection/checker/CallTransportLeakCheckerTest.kt`:
     - `no_calls_when_disabled()` — checker disabled; assert neither stub is invoked; fails until guard exists
     - `reflexive_address_mismatch_between_vpn_and_underlying_path_is_high_confidence()` — VPN path STUN returns `1.2.3.4`, underlying path returns `5.6.7.8`; assert `EvidenceConfidence.HIGH` finding
     - `no_leak_finding_when_both_paths_return_same_reflexive_address()` — both paths return same IP; assert no leak finding
     - `mtproto_reachability_recorded_as_medium_confidence()` — fake `MtProtoProber` returns success; assert MTProto finding with `EvidenceConfidence.MEDIUM`
     - `stun_reflexive_address_added_to_ip_consensus_channel()` — assert `IpConsensusBuilder` receives `CALL_TRANSPORT` channel entry with reflexive address
2. **Confirm red** — `./gradlew :core:detection:test` — all 5 fail
3. **Implement** — `StunClient`, `MtProtoProber`, `Socks5UdpAssociateClient`, `CallTransportLeakChecker`, settings gate, port adapter
4. **Confirm green** — `./gradlew :core:detection:test`
5. **Refactor** — consolidate STUN server list constant

## Definition of done

Unit tests green. When enabled, call transport card visible in `DetectionCheckScreen`. Reflexive address leak shown as a distinct evidence item.
