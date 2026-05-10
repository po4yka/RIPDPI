---
title: Upgrade BypassChecker with MTProto Prober and SOCKS5 UDP-Associate STUN
type: task
status: backlog
area: diagnostics
priority: medium
owner: unassigned
parent: detection-feature-parity-epic
blocks: []
blocked_by: []
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Upgrade BypassChecker with MTProto Prober and SOCKS5 UDP-Associate STUN #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Objective

Extend `BypassChecker`'s local proxy scan pipeline with two additional probes: MTProto reachability to Telegram DC2 via discovered local proxies, and SOCKS5 UDP-associate STUN reflexive address collection.

## Context

RIPDPI's `BypassChecker` already does: local proxy port scan (SOCKS5/HTTP), Xray gRPC API detection, and underlying network probe. RKNHardering's version adds two probes on top of each discovered local proxy:

1. **MTProto prober** — TCP connect to Telegram DC2 `149.154.167.51:443` via the local proxy; success confirms the proxy routes Telegram traffic
2. **SOCKS5 UDP-associate STUN** — sends a STUN binding request over UDP via SOCKS5 UDP associate to collect the reflexive address, revealing the actual exit IP for UDP traffic

**Reference — MtProtoProber:** `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/checker/` — `MtProtoProber.kt`
**Reference — SOCKS5 UDP associate:** `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/checker/` — `Socks5UdpAssociateClient.kt`
**Reference — BypassChecker orchestration:** `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/checker/BypassChecker.kt`

**RIPDPI file to modify:** `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/checker/BypassChecker.kt`
**RIPDPI supporting files:** `probe/ProxyScanner.kt`, `probe/ProxyProber.kt`

## Acceptance criteria

- [ ] For each confirmed local proxy (SOCKS5), `MtProtoProber` attempts TCP connect to `149.154.167.51:443` via the proxy; success recorded as `MTProto reachable` with `EvidenceConfidence.MEDIUM`
- [ ] For each confirmed SOCKS5 proxy, `Socks5UdpAssociateClient` sends STUN binding request to `stun.l.google.com:19302`; reflexive address captured and added to `BypassResult`
- [ ] Reflexive addresses from STUN forwarded to `IpConsensusBuilder` as `BYPASS_STUN` channel (after `add-ip-consensus-synthesis` task is done)
- [ ] Probes run in parallel per discovered proxy; individual probe failures do not abort the scan
- [ ] New findings visible in `BypassResult` model and rendered in `DetectionCheckScreen` bypass card
- [ ] Unit tests: mock SOCKS5 proxy server, assert MTProto connect attempt; assert STUN reflexive address captured

## TDD workflow

1. **Write tests first** — stub `MtProtoProber` and `Socks5UdpAssociateClient` interfaces:
   - `core/detection/src/test/kotlin/com/poyka/ripdpi/core/detection/checker/BypassCheckerMtProtoStunTest.kt`:
     - `mtproto_success_via_proxy_recorded_as_medium_confidence()` — fake SOCKS5 proxy discovered; fake `MtProtoProber` returns success; assert `mtProtoReachable=true` finding with `EvidenceConfidence.MEDIUM`; fails until MtProtoProber is wired
     - `stun_reflexive_address_captured_via_socks5_udp_associate()` — fake `Socks5UdpAssociateClient` returns reflexive address `5.6.7.8`; assert address in `BypassResult.stunReflexiveAddresses`
     - `mtproto_failure_does_not_abort_scan()` — fake prober throws `IOException`; assert scan completes and other findings still present
     - `stun_reflexive_addresses_forwarded_to_ip_consensus_channel()` — assert `IpConsensusBuilder` receives `BYPASS_STUN` channel with collected reflexive addresses
2. **Confirm red** — `./gradlew :core:detection:test` — all 4 fail
3. **Implement** — `MtProtoProber`, `Socks5UdpAssociateClient`, extend `BypassChecker` proxy scan sub-pipeline
4. **Confirm green** — `./gradlew :core:detection:test`
5. **Refactor** — consolidate Telegram DC target constant (reuse across ICMP + MTProto)

## Definition of done

Unit tests green. MTProto reachability and STUN reflexive address shown in the Bypass card. Reflexive address wired into IpConsensus channel.
