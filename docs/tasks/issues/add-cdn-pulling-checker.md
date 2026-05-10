---
title: Add CdnPullingChecker with TLS MITM Detection
type: task
status: backlog
area: diagnostics
priority: high
owner: unassigned
parent: detection-feature-parity-epic
blocks: [add-ip-consensus-synthesis]
blocked_by: []
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add CdnPullingChecker with TLS MITM Detection #repo/RIPDPI #area/diagnostics #status/backlog ⏫

## Objective

Add `CdnPullingChecker` that fetches CDN trace endpoints over both IPv4 and IPv6 to detect the device's exit IP via CDN PoP assignment, and separately flags TLS certificate errors as DPI MITM evidence.

## Context

CDN trace endpoints (e.g. Cloudflare's `cdn-cgi/trace`) return the CDN-assigned client IP. Querying these over IPv4 and IPv6 independently, and across RU-relevant CDNs (Cloudflare, Google Video, rutracker), reveals whether traffic exits Russia. A TLS certificate error on these HTTPS endpoints is a distinct finding: it indicates DPI is injecting a forged certificate (MITM). This check is disabled by default.

**Endpoints:**
- `redirector.googlevideo.com/report_mapping`
- `cloudflare.com/cdn-cgi/trace`
- `one.one.one.one/cdn-cgi/trace`
- `rutracker.org/cdn-cgi/trace`
- `meduza.io/cdn-cgi/trace` (optional, RU-blocked target)

**Reference implementation:** `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/checker/CdnPullingChecker.kt`

**RIPDPI extension points:**
- New checker in `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/checker/`
- Add `PREF_CDN_PULLING_ENABLED` to detection settings; default `false`
- Add `CdnPullingResult` to `DetectionModels.kt`
- Wire port in `DetectionCheckerPortAdapters.kt` + `DetectionCheckerPortsModule.kt`

## Acceptance criteria

- [ ] Checker is disabled by default; no network calls made when disabled
- [ ] Each endpoint probed over IPv4 and IPv6 independently (IPv6 absence is not an error)
- [ ] CDN-assigned IP parsed from response body per endpoint format
- [ ] TLS certificate error on any HTTPS endpoint recorded as a separate `DPI_MITM` evidence item with `EvidenceConfidence.HIGH`
- [ ] Cross-endpoint IP mismatch (same endpoint different IPs over v4 vs v6) flagged as bypass evidence
- [ ] `actionableTargets` list includes `redirector.googlevideo.com` and `meduza.io` when mismatches detected
- [ ] Unit tests with MockWebServer: valid response, TLS error simulation, IPv6 absent scenario

## TDD workflow

1. **Write tests first** — create before any implementation:
   - `core/detection/src/test/kotlin/com/poyka/ripdpi/core/detection/checker/CdnPullingCheckerTest.kt` using OkHttp `MockWebServer`:
     - `no_calls_made_when_disabled()` — instantiate checker with enabled=false; call check(); assert MockWebServer received 0 requests; fails until disabled guard exists
     - `cdn_ip_parsed_from_valid_cloudflare_trace()` — serve `"ip=1.2.3.4\nuag=..."` from MockWebServer; assert result contains `1.2.3.4`; fails until parser exists
     - `tls_error_produces_dpi_mitm_finding_with_high_confidence()` — serve response with mismatched TLS cert (use `HeldCertificate` + `HandshakeCertificates`); assert `DPI_MITM` finding with `EvidenceConfidence.HIGH`
     - `ipv4_ipv6_mismatch_flagged_as_bypass_evidence()` — serve different IPs on two MockWebServer instances (simulating v4 vs v6 path); assert mismatch finding
2. **Confirm red** — `./gradlew :core:detection:test` — all 4 fail
3. **Implement** — `CdnPullingChecker`, port adapter, settings gate
4. **Confirm green** — `./gradlew :core:detection:test`
5. **Refactor** — extract endpoint list to a constant; unify response parsers

## Definition of done

Unit tests green. When enabled in detection settings, CDN card appears in `DetectionCheckScreen`. TLS MITM finding shown as a distinct high-confidence evidence row.
