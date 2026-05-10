---
title: Reach Parity with rkn-block-checker for Layered RKN/TSPU Block Diagnosis
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

- [ ] #task Epic — Reach Parity with rkn-block-checker for Layered RKN/TSPU Block Diagnosis #repo/RIPDPI #area/epic #status/backlog ⏫

## Goal

Add an `RknBlockChecker` diagnostic mode that mirrors `rkn-block-checker` v0.3.x: a layered DNS→TCP→TLS→HTTP cascade per target with stop-at-first-failure semantics, set-based system-vs-DoH DNS comparison, Russian-language HTTP stub-page marker matching, confidence-calibrated verdicts, and a control-vs-test aggregate verdict that is *aware of whitelist health* (won't claim "blocked" when the control baseline is also failing).

## Why now

dpi-detector covers DPI mechanism detection (TCP16 byte-counting, SNI whitelist enumeration, wire-format DNS). `rkn-block-checker` answers a different question: **"Is the network I'm sitting on currently in an RKN-blocked zone, and if so, what kind of block?"** Its key contributions over dpi-detector are:

1. **Layered probe** — DNS → TCP → TLS → HTTP cascade per target, stops at first failure; the layer that broke becomes the verdict (DNS_BLOCK, TCP_RESET, TLS_BLOCK, HTTP_STUB)
2. **Set-based DNS comparison** — system `getaddrinfo` vs Cloudflare DoH; only flags poisoning when address sets are **completely disjoint** (avoids false positives on multi-A-record sites that rotate IPs per query)
3. **HTTP stub-page detector** — substring match against ~10 Russian-language ISP block-page markers; HTTP 451 → high confidence
4. **Confidence-calibrated verdicts** — `HIGH` (two independent signals), `MEDIUM` (pattern matches but server-side cause not ruled out), `LOW` (ambiguous)
5. **Control-aware aggregate verdict** — returns "Inconclusive" when the whitelist itself is mostly failing; the diagnosis is only meaningful with a working baseline

These signals are complementary to dpi-probe-parity-epic: rkn-block-checker is the "does my home connection look like RKN territory?" diagnostic; dpi-detector is the "what specific DPI mechanism is the censor running?" diagnostic. Both belong in a complete RIPDPI detection pipeline.

## Key decisions

- New diagnostic mode lives in `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/rkn/`
- Reuses existing infrastructure: `OkHttpProbeEventListener` from `add-dpi-error-classifier`, `DpiAssetLoader` extended for `whitelist_control.txt` + `blacklist_test.txt`
- Mode runs as a new card in the existing `DiagnosticsScreen` Tools section labeled "RKN Block Diagnosis"
- DNS comparison uses Android `InetAddress.getAllByName` for the system side (NOT the wire-format UDP probe from `add-dns-integrity-checker` — different signal: this catches transparent ISP DNS rewriting at the resolver layer, not just UDP/53 interception)
- DoH side uses the `DnsWireBuilder` from `add-dns-integrity-checker` against Cloudflare DoH JSON for symmetry; the rkn-checker JSON-API path was already extracted to `add-doh-json-api-resolver-path-alongside-rfc-8484-wire`

## Scope

### Infrastructure

| Task | What | Priority |
|---|---|---|
| `add-rkn-control-target-list` | Bundle 21-site whitelist + 15-site blacklist as new asset files | high |
| `add-rkn-stub-page-marker-detector` | Russian stub-marker library (`STUB_MARKERS`) + body substring matcher | high |
| `add-rkn-system-doh-dns-comparison` | Set-based system `InetAddress.getAllByName` vs Cloudflare DoH; disjoint detection | high |

### Probe pipeline

| Task | rkn-checker equivalent | Priority |
|---|---|---|
| `add-rkn-layered-probe-pipeline` | `core.check_url` — DNS→TCP→TLS→HTTP cascade, stop at first failure, verdict per layer | high |
| `add-rkn-control-vs-test-aggregate-verdict` | `output._summary_verdict` — whitelist-aware aggregate ("Inconclusive when control fails") | high |

### Context & privacy

| Task | rkn-checker equivalent | Priority |
|---|---|---|
| `add-rkn-self-info-context-card` | `core.get_self_info` — opt-in IP/ASN/location header | medium |
| `add-rkn-privacy-conscious-probe-headers` | `http.GENERIC_USER_AGENT` + `--identify` toggle | medium |

## Ship definition

- [ ] All 21 control + 15 test target sites bundled, loadable via extended `DpiAssetLoader`
- [ ] Stub-marker library covers at least the 10 markers from `targets.STUB_MARKERS` plus a TDD harness for adding new markers
- [ ] System vs DoH DNS comparison uses **set disjointness** (not first-IP comparison); shared address → no flag
- [ ] Layered probe pipeline returns verdict from the first failing layer; verdict types match rkn-checker's `Verdict` enum (`OK`, `DNS_BLOCK`, `TCP_RESET`, `TLS_BLOCK`, `HTTP_STUB`, `TIMEOUT`, `DOWN`, `UNKNOWN`)
- [ ] Confidence levels (`HIGH`, `MEDIUM`, `LOW`) attached to each verdict per the rkn-checker rules
- [ ] Aggregate verdict refuses to claim "blocked" when whitelist <50% healthy → returns `INCONCLUSIVE_CONTROL_DOWN`
- [ ] Self-info card opt-in only (default OFF); honors `RipDpi.privacyMode` to suppress entirely
- [ ] Default probe headers are generic Chrome (no `rkn-block-checker/<ver>` fingerprint); identify toggle in settings
- [ ] All probes have TDD-first unit tests (interfaces for network calls, fake responses)
- [ ] New "RKN Block Diagnosis" card in DiagnosticsScreen Tools section

## Child tasks

### Infrastructure
- [[add-rkn-control-target-list]]
- [[add-rkn-stub-page-marker-detector]]
- [[add-rkn-system-doh-dns-comparison]]

### Probe pipeline
- [[add-rkn-layered-probe-pipeline]]
- [[add-rkn-control-vs-test-aggregate-verdict]]

### Context & privacy
- [[add-rkn-self-info-context-card]]
- [[add-rkn-privacy-conscious-probe-headers]]

## TDD policy

Same as `dpi-probe-parity-epic`: write tests first, confirm red, implement, confirm green, refactor. Stub all network interfaces. `MockWebServer` for HTTP/TLS, fake `DnsResolver` for system-side DNS.

## Dependencies

- `add-rkn-system-doh-dns-comparison` blocks `add-rkn-layered-probe-pipeline`
- `add-rkn-stub-page-marker-detector` blocks `add-rkn-layered-probe-pipeline`
- `add-rkn-control-target-list` blocks `add-rkn-control-vs-test-aggregate-verdict`
- `add-rkn-layered-probe-pipeline` blocks `add-rkn-control-vs-test-aggregate-verdict`
- `add-rkn-privacy-conscious-probe-headers` blocks `add-rkn-layered-probe-pipeline` (default headers must be in place before pipeline ships)

## Risks / open questions

- Android `InetAddress.getAllByName` honors Private DNS / DoT system settings on Android 9+ — the "system resolver" comparison may not actually use the ISP's resolver if the user has Private DNS enabled. Surface this in the result: `dns_method = SYSTEM | PRIVATE_DNS` so users understand the comparison's validity.
- `ipinfo.io` rate limits without a token (50k req/month free); for a one-shot diagnostic this is fine, but the task should document the rate limit and offer a fallback (`ip-api.com` or `ifconfig.co`).
- The 15-site blacklist is small — a connection that allows 14/15 but blocks the 1 user actually cares about will report "not blocked". Document this caveat in the UI.
- Stub-marker substring matching needs to balance recall (catch ISP variants) vs precision (avoid false positives on news articles mentioning Roskomnadzor). The current 10-marker list is calibrated for this; resist adding overly generic markers.

## Reference files

- Pipeline: `/Users/po4yka/GitRep/rkn-block-checker/rkn_checker/core.py`
- DNS: `/Users/po4yka/GitRep/rkn-block-checker/rkn_checker/dns.py`
- TCP/TLS: `/Users/po4yka/GitRep/rkn-block-checker/rkn_checker/network.py`
- HTTP / stub: `/Users/po4yka/GitRep/rkn-block-checker/rkn_checker/http.py`
- Targets / stub markers: `/Users/po4yka/GitRep/rkn-block-checker/rkn_checker/targets.py`
- Models / verdicts: `/Users/po4yka/GitRep/rkn-block-checker/rkn_checker/models.py`
- Aggregate verdict: `/Users/po4yka/GitRep/rkn-block-checker/rkn_checker/output.py` (`_summary_verdict`)
- Custom list parser: `/Users/po4yka/GitRep/rkn-block-checker/rkn_checker/lists.py`
