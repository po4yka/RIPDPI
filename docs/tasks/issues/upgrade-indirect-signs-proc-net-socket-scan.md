---
title: Upgrade IndirectSignsChecker with /proc/net/tcp Socket Scan for Proxy Ports
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

- [ ] #task Upgrade IndirectSignsChecker with /proc/net/tcp Socket Scan for Proxy Ports #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Objective

Extend `IndirectSignsChecker` with a sub-check that reads `/proc/net/tcp` and `/proc/net/tcp6` to find listening sockets on known proxy ports, then resolves each socket's owning UID to an installed package.

## Context

RIPDPI's `IndirectSignsChecker` already covers NOT_VPN capability absence, network interface enumeration, routing table analysis, DNS classification, and dumpsys VPN. The missing sub-check is the proxy technical signals scan: reading the kernel TCP socket table to detect local-proxy-only tools (apps that listen on proxy ports without declaring a `VpnService`). RKNHardering implements this as sub-check `e` in `IndirectSignsChecker`.

Known proxy ports to flag: 80, 443, 1080, 3127, 3128, 4080, 5555, 7000, 7044, 8000–8082, 8888, 9000, 9050–9051, 9150, 12345, 16000–16100.

**Reference implementation:** `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/checker/IndirectSignsChecker.kt` — sub-check `e` (proxy technical signals)

**RIPDPI file to modify:** `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/checker/IndirectSignsChecker.kt`

**Implementation notes:**
- `/proc/net/tcp` columns: local address in hex (little-endian), state (`0A` = LISTEN), UID
- `/proc/net/tcp6` same format, 16-byte address
- Filter for state `LISTEN` and port in known proxy port list
- Resolve UID → package name via `PackageManager.getPackagesForUid()`
- Cross-reference resolved package against `VpnAppCatalog` (already exists in `core/detection`)

## Acceptance criteria

- [ ] `/proc/net/tcp` and `/proc/net/tcp6` both parsed; parse errors are non-fatal
- [ ] Only `LISTEN` state sockets on known proxy ports are reported
- [ ] Each socket's UID resolved to package name; unresolved UIDs recorded as `uid:<n>`
- [ ] Resolved packages cross-referenced against `VpnAppCatalog`; catalog matches produce `EvidenceConfidence.MEDIUM` findings
- [ ] Non-catalog packages listening on proxy ports produce `EvidenceConfidence.LOW` informational entries
- [ ] Finding added as a new `ProxyTechnicalSignals` sub-result inside `IndirectSignsResult`
- [ ] Unit tests: mock `/proc/net/tcp` content with known listening ports; assert correct UID → package resolution

## TDD workflow

1. **Write tests first** — stub a `ProcNetTcpReader` interface to inject fake file content:
   - `core/detection/src/test/kotlin/com/poyka/ripdpi/core/detection/checker/IndirectSignsCheckerProcNetTest.kt`:
     - `listening_socket_on_known_proxy_port_produces_finding()` — fake reader returns line with port 1080 in LISTEN state (hex `0438`); assert finding produced; fails until socket scanner exists
     - `non_listen_state_socket_not_reported()` — inject established socket on port 1080 (state `01`); assert no finding
     - `catalog_match_upgrades_confidence_to_medium()` — inject listening port 1080, fake PackageManager returns package matching `VpnAppCatalog`; assert `EvidenceConfidence.MEDIUM`
     - `non_catalog_package_produces_low_confidence_informational()` — inject listening port 1080, unknown package; assert `EvidenceConfidence.LOW`
     - `read_error_is_non_fatal()` — fake reader throws `IOException`; assert checker result still non-null with `hasError=false`
2. **Confirm red** — `./gradlew :core:detection:test` — all 5 fail
3. **Implement** — `ProcNetTcpReader`, hex-port parser, UID→package resolution, extend `IndirectSignsChecker`
4. **Confirm green** — `./gradlew :core:detection:test`
5. **Refactor** — merge IPv4 and IPv6 socket table parsing into one function

## Definition of done

Unit tests green. Proxy socket findings visible in the IndirectSigns card in `DetectionCheckScreen`.
