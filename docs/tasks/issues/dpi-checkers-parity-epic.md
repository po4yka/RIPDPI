---
title: Reach Parity with dpi-checkers (CIDR Whitelist + Subnet-Filter DSL + Dynamic Host Discovery)
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

- [ ] #task Epic — Reach Parity with dpi-checkers (CIDR Whitelist + Subnet-Filter DSL + Dynamic Host Discovery) #repo/RIPDPI #area/epic #status/backlog ⏫

## Goal

Add the dpi-checkers (`hyperion-cs/dpi-checkers`) feature surface to RIPDPI: CIDR-whitelist detection, a local subnet-filter DSL (`org()`, `as()`, `country()`, `subnet()`, `host()` with AND/OR), dynamic webhost-farm host discovery from filtered subnets, DoH bootstrap-spoofing detection, IPv4 whitelisted-subnet discovery via RIPE Stat, uTLS-based ClientHello fingerprinting for diagnostic probes, random-Host-header mode, HTTP compression probing, TLS keylog export, and an opt-in BYOH (bring-your-own-host) domain whitelist checker.

## Why now

dpi-detector and rkn-block-checker probe **fixed lists** of targets; dpi-checkers' `dpi-ch` v0.6 introduces **dynamic target discovery via a subnet-filter DSL** that prevents the censor from poisoning the test by adding the static target list to a whitelist. The same epic also covers two distinct censorship signals not addressed by either dpi-detector or rkn-block-checker:

1. **CIDR whitelist censorship** — the censor restricts traffic by IP subnet (not by SNI or DNS). Detected by comparing accessibility of "known-whitelisted" URLs against "regular" URLs: if only the whitelisted ones load, CIDR whitelisting is active.
2. **DoH bootstrap spoofing** — the user trusts DoH because it's HTTPS, but the IP they used to reach the DoH endpoint may itself have been spoofed via plain DNS at bootstrap time. Detected by validating the DoH endpoint's IP through an out-of-band channel against geoip / known-good ranges.

The dynamic-host approach also matters for **TCP 16-20** parity. dpi-detector uses a fixed `tcp16.json` (140 IPs); a sufficiently-motivated censor could whitelist all of them. dpi-ch picks a fresh random sample from filtered subnets per run, defeating that attack.

## Key decisions

- New diagnostic mode lives in `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/`
- Subnet-filter DSL implemented via Kotlin sealed-class AST + a small recursive-descent parser; reuses geoip data from existing `add-geoip-db-and-geosite-db-runtime-loader-and-lookup`
- Dynamic host discovery uses the same uTLS-equivalent client (existing `pin-utls-to-v1-8-2-...` task) as the transport layer for ClientHello-fingerprint consistency
- CIDR-whitelist detector reuses `RknLayeredProbePipeline` (from rkn-parity epic) for the per-URL probe — only the verdict aggregator differs
- BYOH domain whitelist checker is opt-in (requires user-controlled HTTPS server with ≥128KB static file); ships with a docs link to the dpi-ch DWC server-setup recipe
- TLS keylog export is debug-only, gated behind a hidden setting + Privacy Mode override

## Scope

### Infrastructure

| Task | What | Priority |
|---|---|---|
| `add-subnet-filter-dsl` | `org()` / `as()` / `country()` / `subnet()` / `host()` combinator parser + evaluator | high |
| `add-webhost-farm-dynamic-host-discovery` | Picks N test hosts from a filter-resolved subnet set; uTLS client | high |
| `add-utls-diagnostic-probe-clienthello-fingerprinting` | Wires the existing transport-side uTLS client into diagnostic probes | medium |

### Probes

| Task | dpi-checkers equivalent | Priority |
|---|---|---|
| `add-cidr-whitelist-detector` | `checkers/cidrwhitelist.go` — control-vs-regular URL group probe | high |
| `add-ipv4-whitelisted-subnet-discovery` | `ipv4-whitelisted-subnets/main.js` — RIPE Stat per-ASN + per-subnet host sampling | high |
| `add-doh-bootstrap-spoofing-detector` | `dns.go` `dohProvider.Filter` — validate DoH endpoint IP against expected provider geoip | medium |
| `add-byoh-domain-whitelist-checker` | `tcp-16-20_dwc/domain_whitelist_checker.py` — TCP 16-20 domain whitelist via user-controlled server | low |
| `add-http-compression-prober` | `utils/http_compression_prober.py` — gzip/br/zstd support detector | medium |

### Bypass / fingerprint controls

| Task | What | Priority |
|---|---|---|
| `add-random-host-header-mode` | Per-request random `Host` header to evade fixed-list censor whitelists | medium |
| `add-tls-keylog-path-for-pcap-debug` | Pre-master-secret keylog export for Wireshark debugging | low |
| `add-tls-cert-sni-discoverer` | Extracts hostnames from TLS cert SAN/CN of any IP for realistic-target selection | medium |
| `add-diagnostic-result-share-link-encoder` | Compact URL-encoded share link for diagnostic results (paired with deep-link decoder) | low |
| `add-pluggable-transport-reachability-probe` | obfs4 / Snowflake / meek reachability probe — bypass-fingerprint signal for which obfuscation classes survive locally | medium |

## Ship definition

- [ ] Subnet-filter DSL parses all 5 combinator types + AND/OR/groups; evaluates locally with geoip DB
- [ ] Dynamic host discovery picks N alive hosts from a filter-resolved set; configurable count + port + Host/SNI
- [ ] CIDR-whitelist detector returns `CIDR_WHITELIST_DETECTED` / `OK` / `NO_INTERNET` matching dpi-ch's three-state logic
- [ ] IPv4 whitelist discovery: per-ASN RIPE Stat fetch, /24 sampling, alive-min threshold; results cached locally
- [ ] DoH bootstrap-spoofing detector verifies endpoint IP via out-of-band geoip lookup before trusting answers
- [ ] uTLS-based diagnostic probes use the same Chrome/Firefox ClientHello fingerprint as the transport layer
- [ ] Random Host header mode generates per-request random hostnames
- [ ] HTTP compression prober detects gzip / deflate / brotli / zstd and reports decompression success
- [ ] BYOH DWC opt-in: works with user-supplied `<server-ip>:<url-path>` + downloads ≥128KB to confirm whitelisting
- [ ] TLS keylog export gated behind a hidden setting; Privacy Mode forces OFF
- [ ] All probes have TDD-first unit tests (interfaces for network calls, fake responses)
- [ ] New "DPI-CH Comprehensive" card in DiagnosticsScreen Tools section

## Child tasks

### Infrastructure
- [[add-subnet-filter-dsl]]
- [[add-webhost-farm-dynamic-host-discovery]]
- [[add-utls-diagnostic-probe-clienthello-fingerprinting]]

### Probes
- [[add-cidr-whitelist-detector]]
- [[add-ipv4-whitelisted-subnet-discovery]]
- [[add-doh-bootstrap-spoofing-detector]]
- [[add-byoh-domain-whitelist-checker]]
- [[add-http-compression-prober]]

### Bypass / fingerprint controls
- [[add-random-host-header-mode]]
- [[add-tls-keylog-path-for-pcap-debug]]
- [[add-tls-cert-sni-discoverer]]
- [[add-diagnostic-result-share-link-encoder]]
- [[add-pluggable-transport-reachability-probe]]

## TDD policy

Same as prior epics: write tests first, confirm red, implement, confirm green, refactor. Stub all network interfaces. RIPE Stat API mocked via `MockWebServer`; geoip DB injected via fake; uTLS client interface stubbed.

## Dependencies

- `add-subnet-filter-dsl` blocks `add-webhost-farm-dynamic-host-discovery` and `add-cidr-whitelist-detector`
- `add-webhost-farm-dynamic-host-discovery` blocks `add-cidr-whitelist-detector` and `add-ipv4-whitelisted-subnet-discovery`
- `add-utls-diagnostic-probe-clienthello-fingerprinting` blocks `add-webhost-farm-dynamic-host-discovery` and `add-cidr-whitelist-detector`
- `add-byoh-domain-whitelist-checker` requires `add-tcp16-fat-header-dpi-probe` (reuses TCP 16-20 logic)
- All probes require `add-geoip-db-and-geosite-db-runtime-loader-and-lookup` for filter evaluation

## Risks / open questions

- **uTLS on Android JVM**: uTLS itself is a Go library. RIPDPI's transport already includes `pin-utls-to-v1-8-2-...` so a uTLS-equivalent must already exist in the codebase (likely Conscrypt customizations or BouncyCastle TLS hooks). Diagnostic probes need to share that infrastructure rather than re-implement it.
- **RIPE Stat rate limits**: free tier allows ~1000 req/day. The IPv4 whitelist discovery task should cache subnet lists in `filesDir` and only refresh on user request (matches the dpi-ch web checker's "Cache" button UX).
- **Subnet-filter DSL scope creep**: full geoip/AS resolution with arbitrary substring search on org names requires a non-trivial database (~100MB compressed). Reuse existing geoip DB; cap features to what's evaluable from that DB without external lookups.
- **BYOH UX**: Android users typically don't run their own VPS. Offer a docs deep-link with the dpi-ch nginx setup recipe, but acknowledge this task is for power users only.
- **Dynamic host discovery may pick targets the user has no business probing**: clearly label probes as "diagnostic — sends ~1KB to a random host inside ASN N for whitelist detection". Avoid heavy traffic per host (matches dpi-ch's "no more than a couple MB per check" budget).

## Reference files

- Comprehensive checker: `/Users/po4yka/GitRep/dpi-checkers/ru/dpi-ch/`
- Docs: `/Users/po4yka/GitRep/dpi-checkers/ru/dpi-ch/docs/README.md`
- CIDR whitelist checker: `/Users/po4yka/GitRep/dpi-checkers/ru/dpi-ch/checkers/cidrwhitelist.go`
- DNS checker: `/Users/po4yka/GitRep/dpi-checkers/ru/dpi-ch/checkers/dns.go`
- Webhost checker: `/Users/po4yka/GitRep/dpi-checkers/ru/dpi-ch/checkers/webhost.go`
- IPv4 whitelisted subnets web: `/Users/po4yka/GitRep/dpi-checkers/ru/ipv4-whitelisted-subnets/main.js`
- TCP 16-20 web: `/Users/po4yka/GitRep/dpi-checkers/ru/tcp-16-20/main.js`
- Domain whitelist checker (DWC): `/Users/po4yka/GitRep/dpi-checkers/ru/tcp-16-20_dwc/`
- HTTP compression prober: `/Users/po4yka/GitRep/dpi-checkers/utils/http_compression_prober.py`
- TCP 16-20 prober utility: `/Users/po4yka/GitRep/dpi-checkers/utils/tcp1620_prober.py`
