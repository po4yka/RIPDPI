---
title: Add Detection Resolver Network Stack with System/Direct/DoH Modes
type: task
status: backlog
area: diagnostics
priority: high
owner: unassigned
parent: detection-feature-parity-epic
blocks: [add-ip-comparison-checker, add-cdn-pulling-checker, add-icmp-spoofing-checker, add-rtt-triangulation-checker]
blocked_by: []
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add Detection Resolver Network Stack with System/Direct/DoH Modes #repo/RIPDPI #area/diagnostics #status/backlog ⏫

## Objective

Add a `DetectionResolverNetworkStack` that wraps OkHttp with configurable DNS resolver modes (System / Direct UDP / DoH) and network binding (Android `Network` object or `SO_BINDTODEVICE`), with a native curl fallback for TUN-path probing.

## Context

All new detection checkers (IpComparison, CdnPulling, GeoIP multi-provider, IcmpSpoofing, RTT) issue HTTP requests. Currently RIPDPI's detection module uses a plain OkHttp client. RKNHardering's `ResolverNetworkStack` adds three critical capabilities:

1. **DNS resolver modes** — System (default), Direct UDP (hand-builds DNS wire format, queries configured servers on port 53, 3s timeout), DoH (OkHttp `DnsOverHttps` with bootstrap IPs bound to same network)
2. **Network binding** — bind OkHttp to a specific `android.net.Network` or interface via `SO_BINDTODEVICE` — essential for the underlying-network probes in `BypassChecker`
3. **Native curl fallback** — `NativeCurlBridge` (pre-built `.so`) with own CA bundle when OkHttp fails; required for TUN-probe strict-same-path mode

**Reference — ResolverNetworkStack:** `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/` — `ResolverNetworkStack.kt`, `DirectDns.kt`, `FilteringDns.kt`, `CancellableDns.kt`
**Reference — NativeCurlBridge:** `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/NativeCurlBridge.kt`

**RIPDPI placement:** `core/diagnostics-data/src/main/kotlin/com/poyka/ripdpi/data/diagnostics/` (alongside existing `DiagnosticsHttpClientFactory.kt`)

**Config caching:** Synchronized double-checked locking on `(resolverConfig, dns, client)` triple — avoids rebuilding the OkHttp client on every request.

## Acceptance criteria

- [ ] Three DNS modes: `SYSTEM` (default), `DIRECT` (custom UDP servers, 3s timeout, port 53), `DOH` (OkHttp `DnsOverHttps`, bootstrap IPs)
- [ ] DNS preset constants: Cloudflare (1.1.1.1/1.0.0.1, cloudflare-dns.com), Google (8.8.8.8/8.8.4.4, dns.google), Yandex (77.88.8.8/77.88.8.1, common.dot.dns.yandex.net)
- [ ] `AndroidNetworkBinding` — binds OkHttp socket factory to `android.net.Network`; DNS bound to same network
- [ ] `OsDeviceBinding` — `SO_BINDTODEVICE` via `BindToDeviceSocketFactory` for interface-level binding
- [ ] `CancellableDns` — registers cancellation signal that calls `dispatcher.cancelAll()`
- [ ] `FilteringDns` — filters results to requested address family (A or AAAA); throws `UnknownHostException` if none remain
- [ ] Native curl fallback: `NativeCurlBridge` wrapping prebuilt `libnative_curl_probe.so` + bundled CA store; `canExecute()` guard prevents crash if `.so` absent
- [ ] `CombinedTransportIOException` wraps both OkHttp and curl failures when both transports fail
- [ ] Unit tests: mock DNS server (UDP echo), assert DIRECT mode query format; assert config caching (client rebuilt only when config changes)

## TDD workflow

1. **Write tests first** — create before any implementation:
   - `core/diagnostics-data/src/test/kotlin/com/poyka/ripdpi/data/diagnostics/DirectDnsTest.kt` — assert UDP packet bytes for a single A-record query match expected DNS wire format; fails until `DirectDns` exists
   - `core/diagnostics-data/src/test/kotlin/com/poyka/ripdpi/data/diagnostics/FilteringDnsTest.kt` — assert IPv4-only mode drops AAAA results; assert `UnknownHostException` thrown when all results filtered; fails until `FilteringDns` exists
   - `core/diagnostics-data/src/test/kotlin/com/poyka/ripdpi/data/diagnostics/DetectionResolverNetworkStackTest.kt` — assert same `OkHttpClient` instance returned on second call with unchanged config; assert new instance created after resolver mode change; fails until caching logic exists
2. **Confirm red** — `./gradlew :core:diagnostics-data:test` — verify compile errors or assertion failures on all 3 test files
3. **Implement** — `DetectionResolverNetworkStack`, `DirectDns`, `FilteringDns`, `CancellableDns`, `NativeCurlBridge` stub
4. **Confirm green** — `./gradlew :core:diagnostics-data:test` — zero failures, zero regressions
5. **Refactor** — extract constants, clean up locking

## Definition of done

Unit tests green. All new detection checkers use `DetectionResolverNetworkStack` rather than a raw OkHttp client. TUN-probe mode (strict/curl-compat) works end-to-end in `BypassChecker`.
