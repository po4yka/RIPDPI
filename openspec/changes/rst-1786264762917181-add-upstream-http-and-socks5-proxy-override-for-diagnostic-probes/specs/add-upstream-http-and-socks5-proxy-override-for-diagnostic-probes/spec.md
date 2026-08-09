## Purpose

Define the observable completion contract for Add upstream HTTP and SOCKS5 proxy override for diagnostic probes. Allow diagnostic probes (TLS reachability, TCP 16-20KB cutoff, DNS resolver availability, HTTP injection) to be routed through an arbitrary upstream HTTP or SOCKS5 proxy supplied by the user, so the operator can compare results across paths without leaving the app

## ADDED Requirements

### Requirement: REQ-RST-1786264762917181-001 — Diagnostic profile supports upstreamproxy: socks5://… | http://… including basi…

The RIPDPI implementation MUST satisfy this portfolio criterion: Diagnostic profile supports upstreamproxy: socks5://… | http://… including basic auth in the URL.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Diagnostic profile supports upstreamproxy: socks5://… | http://… including basic auth in the URL

### Requirement: REQ-RST-1786264762917181-002 — When set, every TCP-based probe (TLS reachability, TCP 16-20KB, HTTP injection)…

The RIPDPI implementation MUST satisfy this portfolio criterion: When set, every TCP-based probe (TLS reachability, TCP 16-20KB, HTTP injection) routes through the proxy. DNS UDP probes are skipped or fall back to DoH-via-proxy and are flagged as such.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that When set, every TCP-based probe (TLS reachability, TCP 16-20KB, HTTP injection) routes through the proxy. DNS UDP probes are skipped or fall back to DoH-via-proxy and are flagged as such

### Requirement: REQ-RST-1786264762917181-003 — Diagnostics summary clearly labels the result as proxy-routed and never persist…

The RIPDPI implementation MUST satisfy this portfolio criterion: Diagnostics summary clearly labels the result as proxy-routed and never persists a transparent verdict from a proxy-routed run into the per-network policy store.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Diagnostics summary clearly labels the result as proxy-routed and never persists a transparent verdict from a proxy-routed run into the per-network policy store

### Requirement: REQ-RST-1786264762917181-004 — Proxy URL is treated as a credential: never logged at any level, never written…

The RIPDPI implementation MUST satisfy this portfolio criterion: Proxy URL is treated as a credential: never logged at any level, never written to export bundles, redacted in summary.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Proxy URL is treated as a credential: never logged at any level, never written to export bundles, redacted in summary

### Requirement: REQ-RST-1786264762917181-005 — Setting is per-run via the diagnostics screen; no global default

The RIPDPI implementation MUST satisfy this portfolio criterion: Setting is per-run via the diagnostics screen; no global default.

#### Scenario: Verify criterion 5

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Setting is per-run via the diagnostics screen; no global default
