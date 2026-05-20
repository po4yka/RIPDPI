# ripdpi-diagnostics-dns

**Role:** per-protocol probe. **Layer:** L6 — diagnostics / monitor.

## Responsibility

DNS-layer diagnostics: DNS integrity probes, DNS tampering analysis
(`dns_analysis` — anomaly signals, UDP-vs-encrypted record comparison,
compression-pointer validation), the DNS oracle, CDN/ECH config tracking
(`cdn_ech`), and the resolver panel.

## Main dependencies

`ripdpi-diagnostics-contracts`, `ripdpi-diagnostics-transport`,
`ripdpi-dns-resolver`, `ripdpi-tls-profiles`; `hickory-proto`,
`hickory-resolver`, `tokio`.

## Extension points

A new DNS anomaly signal, a new resolver probe, or CDN/ECH source support.

## What must not be added here

HTTP or TLS probe logic, scan orchestration, or verdict classification. DNS
resolution transport itself belongs in `ripdpi-dns-resolver`.

---
See [`DIAGNOSTICS_ARCHITECTURE.md`](../../../../docs/architecture/DIAGNOSTICS_ARCHITECTURE.md)
for the scan pipeline and
[`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md)
§3 for adding a probe.
