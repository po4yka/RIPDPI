---
title: Add Fake-IP and ISP-CGNAT IP Classifier
type: task
status: backlog
area: diagnostics
priority: high
owner: unassigned
parent: dpi-probe-parity-epic
blocks: [add-dns-integrity-checker, add-domain-reachability-scanner]
blocked_by: []
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add Fake-IP and ISP-CGNAT IP Classifier #repo/RIPDPI #area/diagnostics #status/backlog ⏫

## Objective

Add `IpAddressClassifier` that categorises IP addresses into `FAKE_IP` (VPN tun pool), `ISP_STUB` (ISP captive portal CGNAT), `LOCAL` (private/loopback/link-local), and `PUBLIC` — mirroring dpi-detector's `get_fake_ip_type()`.

## Context

dpi-detector uses IP classification in two places: (1) DNS scanner marks any IP in `198.18.0.0/15` returned by a DNS probe as `FAKE-IP` (a VPN/tun Fake-IP pool, not an ISP block), and (2) TLS scanner pre-checks resolved IPs against `100.64.0.0/10` (CGNAT, typically ISP stub/block pages) before making an HTTPS connection. Without this classifier, probes can't distinguish a DNS-replaced ISP redirect from a VPN-mode Fake-IP artifact.

**CIDR ranges:**

| Label | CIDR | Meaning |
|---|---|---|
| `FAKE_IP` | `198.18.0.0/15` | VPN Fake-IP pool (Clash/Mihomo tun mode); not an ISP block |
| `ISP_STUB` | `100.64.0.0/10` | CGNAT range used by ISPs for captive/block page stub IPs |
| `LOCAL` | `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `127.0.0.0/8`, `::1/128`, `169.254.0.0/16`, `fe80::/10`, `0.0.0.0` | Private/loopback/link-local/unspecified |
| `PUBLIC` | Everything else | Regular public IP — classify further if needed |

**Reference**: `/Users/po4yka/GitRep/dpi-detector/utils/network.py` — `get_fake_ip_type()`

**RIPDPI placement:** `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/dpi/IpAddressClassifier.kt`

Note: RIPDPI already has `DetectionPrivacyMask` which identifies private/loopback ranges. Extract shared logic into `IpAddressClassifier`; update `DetectionPrivacyMask` to delegate to it for the local-range check.

## Acceptance criteria

- [ ] `IpAddressType` enum: `FAKE_IP`, `ISP_STUB`, `LOCAL`, `PUBLIC`
- [ ] `IpAddressClassifier.classify(ip: String): IpAddressType` — pure function, no side effects
- [ ] All CIDR ranges from table above correctly matched using `InetAddress` + bitwise subnet check (no external library)
- [ ] IPv6 support: `::1` → `LOCAL`, `fe80::1` → `LOCAL`, public IPv6 → `PUBLIC`
- [ ] Invalid/unparseable IP string → `LOCAL` (safe fallback)
- [ ] `DetectionPrivacyMask` delegates to `IpAddressClassifier` for its local-range check (refactor, no behaviour change)
- [ ] Unit tests: one test per CIDR range boundary (in-range, out-of-range, boundary address)

## TDD workflow

1. **Write tests first**:
   - `core/detection/src/test/kotlin/com/poyka/ripdpi/core/detection/dpi/IpAddressClassifierTest.kt`:
     - `198_18_0_0_classified_as_fake_ip()` — assert `FAKE_IP`; fails until classifier exists
     - `198_19_255_255_classified_as_fake_ip()` — upper boundary of `/15`; assert `FAKE_IP`
     - `198_20_0_0_classified_as_public()` — just outside `/15`; assert `PUBLIC`
     - `100_64_0_0_classified_as_isp_stub()` — assert `ISP_STUB`
     - `100_127_255_255_classified_as_isp_stub()` — upper boundary of `/10`; assert `ISP_STUB`
     - `192_168_1_1_classified_as_local()` — private range
     - `127_0_0_1_classified_as_local()` — loopback
     - `fe80_1_classified_as_local()` — link-local IPv6
     - `8_8_8_8_classified_as_public()` — regular public IP
     - `invalid_string_classified_as_local()` — assert safe fallback
2. **Confirm red** — `./gradlew :core:detection:test` — all 10 fail
3. **Implement** — `IpAddressType`, `IpAddressClassifier`; refactor `DetectionPrivacyMask`
4. **Confirm green** — `./gradlew :core:detection:test`
5. **Refactor** — consolidate CIDR check into a reusable `isInSubnet(ip, network, prefixLen)` utility

## Definition of done

All 10 unit tests green. `DetectionPrivacyMask` refactored to delegate local-range check. `IpAddressClassifier` used by `add-dns-integrity-checker` and `add-domain-reachability-scanner`.
