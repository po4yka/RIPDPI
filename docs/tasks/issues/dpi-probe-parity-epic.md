---
title: Bring Active DPI Probe Suite to Parity with dpi-detector
type: epic
status: backlog
area: epic
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Epic — Bring Active DPI Probe Suite to Parity with dpi-detector #repo/RIPDPI #area/epic #status/backlog ⏫

## Goal

Add an active DPI probe suite to RIPDPI's `core/diagnostics` that covers all 6 test types from dpi-detector v3.3.0: DNS integrity, DNS server survey, domain reachability (TLS 1.3/1.2/HTTP), TCP16 FAT-header block detection, whitelist SNI finder, and Telegram speed/throttling. All probes share a common error classifier, fake-IP classifier, and bundled target assets.

## Why now

dpi-detector is the most technically complete open-source tool for detecting Russian DPI/TSPU censorship techniques. Its probes expose patterns (TCP16 RST injection at 16KB, SNI whitelist bypass, DNS interception vs substitution distinction, TLS-version-specific blocking) that RIPDPI's current diagnostics pipeline does not detect. Adding these probes turns RIPDPI's detection screen into a full-coverage censorship measurement tool.

## Key decisions

- All probes run on the Android JVM (no root, no NDK) using OkHttp + `DatagramSocket` + `SSLContext` version pinning — same techniques as dpi-detector translated from Python `asyncio`/`httpx` to Kotlin coroutines.
- Bundled assets (`tcp16.json`, `domains.txt`, `whitelist_sni.txt`) live in `core/diagnostics/src/main/assets/dpi/` alongside existing `diagnostics/default_profiles.json`.
- Error classifier is a sealed class hierarchy in `core/detection` shared across all probes and the existing detection checkers.
- TCP16 probe and Whitelist SNI finder surface in the existing `DiagnosticsScreen` → Tools section (not in `DetectionCheckScreen`).
- Telegram speed test surfaces in `DiagnosticsTelegramCards.kt` (file already exists).
- `add-dpi-error-classifier` and `add-ip-fake-ip-classifier` must land first — all other tasks depend on them.

## Scope

### Infrastructure (must be first)

| Task | What | Priority |
|---|---|---|
| `add-dpi-error-classifier` | Sealed DPI error class hierarchy: TLS SPOOF, RST, ALERT, MITM, SYN DROP, TCP RST, DNS FAIL, REFUSED, NET UNREACH | high |
| `add-ip-fake-ip-classifier` | Fake-IP (198.18/15), ISP-CGNAT (100.64/10), local range classifier | high |
| `add-dpi-target-assets` | Bundle tcp16.json (140 CDN IPs), domains.txt (40 domains), whitelist_sni.txt (188 SNIs) | high |

### Active probes

| Task | dpi-detector equivalent | Priority |
|---|---|---|
| `add-dns-integrity-checker` | Test 1 — wire-format UDP DNS + DoH, substitution/interception detection | high |
| `add-dns-server-availability-survey` | Test 2 — 21 servers (UDP + DoH Wire), latency table | medium |
| `add-domain-reachability-scanner` | Test 3 — TLS 1.3/1.2/HTTP stage-tracked, 40 blocked domains | high |
| `add-tcp16-fat-header-dpi-probe` | Test 4 — keep-alive HEAD with oversized X-Pad headers, TSPU RST detection | high |
| `add-whitelist-sni-finder` | Test 5 — brute-force 188 SNIs against blocked CDN IPs | medium |
| `add-telegram-speed-test` | Test 6 — download/upload speed + DC TCP ping (all 5 DCs) | medium |

### Orchestration

| Task | What | Priority |
|---|---|---|
| `add-dpi-probe-suite-runner` | Suite controller: probe-selection UI, sequencing (DNS → reachability stub-IP handoff), aggregate verdict, custom-domain override | high |

### Modern signals (beyond dpi-detector v3.3.0)

| Task | What | Priority |
|---|---|---|
| `add-ech-encrypted-client-hello-probe` | RFC 9737 ECH readiness + handshake-acceptance probe; flags networks where ECH bypasses local SNI-DPI | high |
| `add-doq-dns-over-quic-integrity-probe` | RFC 9250 DoQ resolution + DoH cross-check to detect UDP/853 censorship invisible to TCP-based DNS probes | high |
| `add-quic-h3-fingerprint-probe` | Chrome/Firefox/generic QUIC Initial fingerprint probes to detect selective UDP DPI and fingerprint-aware blocking | high |

## Ship definition

- [ ] `DpiErrorClassifier` sealed hierarchy covers all labels from dpi-detector's `error_classifier.py`
- [ ] Fake-IP / ISP-CGNAT / local range classifier ported with unit tests for all CIDR ranges
- [ ] All 3 asset files bundled and loadable at runtime
- [ ] DNS integrity checker: wire-format UDP probe + DoH JSON + DoH Wire RFC 8484; classifies substitution, interception, FAKE NXDOMAIN, stub IP
- [ ] DNS server latency survey: 21 servers, per-server availability + avg latency; results shown in DiagnosticsScreen Tools section
- [ ] Domain reachability scanner: TLS 1.3/1.2/HTTP per domain; stage-tracked; ISP page detection via stub IP cross-reference
- [ ] TCP16 probe: 140 CDN targets, FAT-header keep-alive, RTT-adaptive timeout, DETECTED/OK per ASN
- [ ] Whitelist SNI finder: per-blocked-ASN, batch of 5 SNIs, stops at top-3 working; results shown with copy action
- [ ] Telegram test: download speed + stall detection, upload speed to DC2 IP, DC ping for all 5 DCs
- [ ] Suite runner: probe-selection UI, custom-domain override, aggregate verdict panel matching dpi-detector's "Итог"
- [ ] All probes have TDD-first unit tests (interfaces for network calls, fake responses)
- [ ] New UI cards for each probe in DiagnosticsScreen Tools or dedicated sub-screens

## Child tasks

### Infrastructure
- [[add-dpi-error-classifier]]
- [[add-ip-fake-ip-classifier]]
- [[add-dpi-target-assets]]

### Active probes
- [[add-dns-integrity-checker]]
- [[add-dns-server-availability-survey]]
- [[add-domain-reachability-scanner]]
- [[add-tcp16-fat-header-dpi-probe]]
- [[add-whitelist-sni-finder]]
- [[add-telegram-speed-test]]

### Orchestration
- [[add-dpi-probe-suite-runner]]

### Modern signals (beyond dpi-detector v3.3.0)
- [[add-ech-encrypted-client-hello-probe]]
- [[add-doq-dns-over-quic-integrity-probe]]
- [[add-quic-h3-fingerprint-probe]]

## TDD policy

Same as `detection-feature-parity-epic`: write tests first, confirm red, implement, confirm green, refactor. Stub all network interfaces. Named Gradle commands per module:

| Scope | Command |
|---|---|
| `core/diagnostics` unit tests | `./gradlew :core:diagnostics:test` |
| `core/detection` unit tests | `./gradlew :core:detection:test` |
| `app` unit tests + goldens | `./gradlew :app:test :app:verifyRoborazziDebug` |

## Dependencies

- `add-dpi-error-classifier` and `add-ip-fake-ip-classifier` block all probes
- `add-dpi-target-assets` blocks `add-dns-integrity-checker`, `add-domain-reachability-scanner`, `add-tcp16-fat-header-dpi-probe`, `add-whitelist-sni-finder`
- `add-tcp16-fat-header-dpi-probe` blocks `add-whitelist-sni-finder` (SNI finder reuses TCP16 probe + RTT hint)

## Risks / open questions

- Android 9+ blocks cleartext HTTP by default — domain reachability HTTP probe requires `android:usesCleartextTraffic="true"` scoped to test domains via network security config, or a `CleartextTrafficPermittedException` workaround
- UDP `DatagramSocket` on Android may behave differently under VPN: the underlying network binding from `add-detection-resolver-network-stack` should be reused
- TCP16 probe on Android: `OkHttp` `keep-alive` + `max-keepalive-connections=1` is achievable; verify it doesn't get recycled by the pool before all 16 requests complete
- `whitelist_sni.txt` may need updates as Russian DPI whitelists evolve — design asset loader to support user-provided overrides
- Telegram upload to DC2 raw IP (`149.154.167.220:443`) may be classified as cleartext by network security config even over TLS — test carefully

## Reference files

- DNS scanner: `/Users/po4yka/GitRep/dpi-detector/core/dns_scanner.py`
- TLS scanner: `/Users/po4yka/GitRep/dpi-detector/core/tls_scanner.py`
- TCP16 scanner: `/Users/po4yka/GitRep/dpi-detector/core/tcp16_scanner.py`
- Telegram scanner: `/Users/po4yka/GitRep/dpi-detector/core/telegram_scanner.py`
- Error classifier: `/Users/po4yka/GitRep/dpi-detector/utils/error_classifier.py`
- Network utils: `/Users/po4yka/GitRep/dpi-detector/utils/network.py`
- Config: `/Users/po4yka/GitRep/dpi-detector/config.yml`
- TCP16 targets: `/Users/po4yka/GitRep/dpi-detector/tcp16.json`
- Domains: `/Users/po4yka/GitRep/dpi-detector/domains.txt`
- SNI whitelist: `/Users/po4yka/GitRep/dpi-detector/whitelist_sni.txt`
