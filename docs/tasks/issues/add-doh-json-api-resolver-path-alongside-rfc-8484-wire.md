---
title: Add DoH JSON API resolver path alongside RFC 8484 wire
type: task
status: backlog
area: dns
priority: low
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-25
updated: 2026-04-25
---

- [ ] #task Add DoH JSON API resolver path alongside RFC 8484 wire #repo/RIPDPI #area/dns #status/backlog 🔽

## Summary

Add a DoH-JSON probe path (Google `/resolve`, Cloudflare DoH JSON, AdGuard `/resolve`, Alibaba `/resolve`) to the diagnostics suite, alongside the existing RFC 8484 DoH-wire path, so the JSON-only endpoints are exercised independently.

## Motivation

dpi-detector probes both DoH formats because some resolver operators expose only one of the two paths, and some ISPs block the wire format (application/dns-message) while leaving the JSON API reachable, or vice versa. RIPDPI's resolver path uses DoH wire only; for diagnostic completeness — specifically when classifying which DoH endpoints the ISP filters — the JSON variant should be probed too. This is a diagnostics-only addition; the runtime resolver continues to use wire format.

## Scope

- **In scope:** new probe variant in `ripdpi-monitor-engine` / diagnostics crates that issues a DoH JSON GET (`?name=…&type=A`) and validates the JSON response. Surfaces as part of the resolver availability survey (Add public DNS resolver availability survey diagnostic (closed task)) and the authority-scoped DNS classifier as an extra evidence source.
- **Out of scope:** using DoH JSON in `ripdpi-dns-resolver` for actual resolution. The runtime path stays wire-only.

## Acceptance criteria

- [ ] DoH JSON probe is a separate `ResolverProbe` variant with its own URL list, parser, and verdict.
- [ ] Parser is permissive (handles Google's `Answer[].data` and Cloudflare's identical schema) but treats malformed JSON as a probe failure, not a panic.
- [ ] No allocation in the hot path beyond what the JSON parser requires; reuse the HTTP client path already used by the monitor/diagnostics crates.
- [ ] Probe verdict is reported per-endpoint independently of the wire probe to the same operator (so "Google wire blocked, Google JSON reachable" is a representable outcome).
- [ ] No fallback from wire to JSON in the runtime resolver; runtime stays wire-only.

## Source reference

dpi-detector v3.2.2: `core/dns_scanner.py` `_probe_doh_json_single`, `_probe_doh_json_all`, and `config.yml` `DNS_DOH_SERVERS` for the JSON endpoint URL set.

## Risks / open questions

- DoH JSON is non-standard (vendor-specific); scope must stay diagnostic-only to avoid baking dependence on a non-IETF interface into the runtime path.

## Links

- [[ripdpi-android]]
- Add public DNS resolver availability survey diagnostic (closed task)
- [[Epic - Encrypted DNS and HTTPS SVCB classifier]]
