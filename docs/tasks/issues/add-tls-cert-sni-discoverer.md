---
title: Add TLS Certificate SAN/CN Hostname Discoverer for Test-Target Selection
type: task
status: todo
area: diagnostics
priority: medium
owner: unassigned
parent: dpi-checkers-parity-epic
blocks: []
blocked_by: []
created: 2026-05-10
updated: 2026-05-12
---

- [ ] #task Add TLS Certificate SAN/CN Hostname Discoverer for Test-Target Selection #repo/RIPDPI #area/diagnostics #status/todo 🔼

## Objective

Add `TlsCertSniDiscoverer` that, given an IP address, opens a TLS handshake **without specifying SNI**, reads the server's default certificate, and extracts hostnames from the cert's `subjectAlternativeName` extension and Common Name — letting downstream probes use realistic hostnames (e.g. `*.cloudflare.com`, `googleusercontent.com`) instead of bare random strings when probing IPs from `WebhostFarm`.

## Context

Android port of `utils/subnets2websites.py` (`domains_from_ip` function). The premise: when `WebhostFarm` discovers alive IPs in filtered subnets, those IPs are anonymous — just `1.2.3.4`. For probes that test SNI-aware DPI behavior (TCP 16-20, whitelist SNI finder), it matters which hostnames the IPs are advertised as. Reading the cert SAN/CN gives a list of real hostnames that the IP actually serves, which:

1. Are more realistic than random strings → less likely to trigger censor anomaly detection
2. Are usable as actual SNIs for follow-up probes (e.g. "send this SNI to test if TCP 16-20 is bypassed for it")
3. Can be cross-referenced against the user's real browsing patterns to find safer test targets

**Algorithm (matches `subnets2websites.py:domains_from_ip`):**
1. Open TCP socket to `<ip>:443` with timeout
2. Wrap in TLS without SNI (handshake will use the server's default cert)
3. Read server cert in DER form
4. Parse cert; extract:
   - All `subjectAlternativeName` entries of type `DNSName`
   - The `commonName` from the subject distinguished name
5. Filter: drop wildcards starting with `*.` (or convert to base domain `example.com`); drop entries that don't match the IP's reverse-DNS pattern
6. Return deduplicated list of hostnames

**Use case in RIPDPI:**
- After `WebhostFarm.discover()`, optionally run `TlsCertSniDiscoverer.discover(ip)` per host to enrich `DiscoveredHost` with `certHostnames: List<String>`
- `WhitelistSniFinder` can use these as candidate SNIs in addition to the bundled `whitelist_sni.txt`
- `Tcp16FatHeaderProbe` with `randomHostname = false` can pick a cert hostname instead of a random one for more-realistic probing

**Reference:** `/Users/po4yka/GitRep/dpi-checkers/utils/subnets2websites.py` (`domains_from_ip` function, lines ~16-50)

**RIPDPI placement:**
- Discoverer: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/TlsCertSniDiscoverer.kt`
- Result: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/CertHostnameDiscovery.kt`

## Acceptance criteria

- [ ] `TlsCertSniDiscoverer.discover(ip: String, port: Int = 443, timeoutMs: Long = 3000): CertHostnameDiscovery`
- [ ] `CertHostnameDiscovery`: `ip: String`, `port: Int`, `commonName: String?`, `subjectAltNames: List<String>`, `error: String?`
- [ ] Uses `add-utls-diagnostic-probe-clienthello-fingerprinting` for the TLS handshake to avoid leaking diagnostic-tool fingerprint
- [ ] **No SNI sent in the handshake** — that's the entire point; we want the server's default cert, not a SNI-specific one
- [ ] Cert parsing via `java.security.cert.X509Certificate.getSubjectAlternativeNames()` (built into Android, no extra deps)
- [ ] Wildcards normalized: `*.example.com` → kept as `*.example.com` (NOT collapsed to `example.com`); caller decides whether to expand
- [ ] CN extracted via `X500Principal.getName()` parsed for `CN=` field
- [ ] On TLS failure → returns `CertHostnameDiscovery(ip, port, null, [], error = "<reason>")` — no exception thrown to caller
- [ ] Concurrent: `discoverBatch(ips: List<String>, workers: Int = 8): List<CertHostnameDiscovery>` runs N parallel discoveries
- [ ] Optional integration: `WebhostFarm.discoverWithCertHostnames(...)` enriches each `DiscoveredHost` with cert hostnames
- [ ] Unit tests: cert with multiple SANs; cert with wildcard SANs; cert with only CN (no SANs); TLS handshake failure; verify no SNI in ClientHello bytes

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/TlsCertSniDiscovererTest.kt`:
     - `extracts_multiple_san_dns_names()` — fake cert with SANs `["a.example.com", "b.example.com"]`; assert both returned; fails until discoverer exists
     - `extracts_wildcard_san_unchanged()` — SAN `*.cloudflare.com`; assert returned as-is, not collapsed
     - `falls_back_to_cn_when_no_sans()` — cert with only `CN=example.com`; assert `commonName == "example.com"`, `subjectAltNames == []`
     - `tls_failure_returns_error_no_exception()` — fake handshake throws; assert result has `error` field, no thrown exception
     - `does_not_send_sni_in_clienthello()` — capture `MockSocket` write bytes; assert no `server_name` extension in ClientHello
     - `non_dns_san_types_filtered_out()` — cert with mixed SAN types (DNSName + IPAddress + email); assert only DNSNames returned
     - `concurrent_discover_batch_respects_worker_cap()` — discover 100 IPs with workers=4; instrument; assert max 4 in-flight
     - `webhost_farm_integration_enriches_results()` — `discoverWithCertHostnames` returns `DiscoveredHost` with populated `certHostnames`
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 8 fail
3. **Implement** — `TlsCertSniDiscoverer`, batch helper, optional `WebhostFarm` integration
4. **Confirm green** — `./gradlew :core:diagnostics:test`
5. **Refactor** — extract SAN extraction into `CertParseUtil.extractDnsNames(cert)` for reuse with TLS MITM detection (existing `add-dpi-error-classifier` MITM path)

## Definition of done

All 8 unit tests green. `TlsCertSniDiscoverer` consumed by `WhitelistSniFinder` and (optionally) `WebhostFarm`. Real-IP probe adds cert hostnames to result table for power-user inspection.
