---
id: RST-1786264762917181
title: Add upstream HTTP and SOCKS5 proxy override for diagnostic probes
kind: feature
status: backlog
area: rust-native
priority: low
owner: unassigned
parent: null
blocked_by: []
spec_mode: required
openspec_change: rst-1786264762917181-add-upstream-http-and-socks5-proxy-override-for-diagnostic-probes
created: 2026-04-25
updated: 2026-06-05
---

## Summary

Allow diagnostic probes (TLS reachability, TCP 16-20KB cutoff, DNS resolver availability, HTTP injection) to be routed through an arbitrary upstream HTTP or SOCKS5 proxy supplied by the user, so the operator can compare results across paths without leaving the app.

## Motivation

dpi-detector exposes `-p socks5://user:pass@host:port` to push every probe through an external proxy, which is invaluable for A/B comparing "network as-is" against "network via my pinned VPS proxy" or against a neighbour's tunnel. RIPDPI is itself a local proxy/VPN, so the natural question is "compare RIPDPI's transparent verdict against the same verdict via my external server" — which today requires running diagnostics on a separate device.

This is opt-in and does not change the default diagnostic behavior.

## Scope

- **In scope:** a diagnostic-scoped upstream-proxy field with HTTP and SOCKS5 (with auth) support; routing through the proxy is per-run, not persisted across sessions; visible badge in the diagnostics card showing "via upstream: <host>" so results aren't misread.
- **Out of scope:** chained upstream proxies; proxy autodiscovery; reusing this proxy for the runtime relay/tunnel paths (those have their own profile editors).

## Acceptance criteria

- [ ] Diagnostic profile supports `upstream_proxy: socks5://… | http://…` including basic auth in the URL.
- [ ] When set, every TCP-based probe (TLS reachability, TCP 16-20KB, HTTP injection) routes through the proxy. DNS UDP probes are skipped or fall back to DoH-via-proxy and are flagged as such.
- [ ] Diagnostics summary clearly labels the result as proxy-routed and never persists a transparent verdict from a proxy-routed run into the per-network policy store.
- [ ] Proxy URL is treated as a credential: never logged at any level, never written to export bundles, redacted in summary.
- [ ] Setting is per-run via the diagnostics screen; no global default.

## Design notes

Reuse the existing local SOCKS5 client primitives in `ripdpi-socks5-core` / proxy-runtime adapters where possible; if HTTP CONNECT is missing, add a minimal HTTP CONNECT adapter strictly for diagnostic use. Keep the proxy plumbing inside `ripdpi-monitor-engine` / diagnostics code; do not leak proxy state into the policy store or host autolearn paths — proxy-routed results have different validity.

## Source reference

dpi-detector v3.2.2: `dpi_detector.py` `--proxy` CLI argument and `config.yml` `PROXY_URL`. Upstream proxy is wired into the shared `httpx.AsyncClient` for every probe.

## Risks / open questions

- Cross-mode invariant: if RIPDPI's proxy/VPN service is running and the user also sets a diagnostic upstream proxy, the request graph becomes "RIPDPI → external proxy → target". The diagnostic must either disable the local service for the run or surface the double-hop topology in the result so the user understands what is being measured.
- "No backend" rule still holds: the upstream proxy is user-supplied, not project-operated.

## Links

- [[ripdpi-android]]

## Work log

- 2026-06-05: `ScanRequest` has `proxy_host`/`proxy_port` (host+port only, no URL/auth) and `TransportConfig::Socks5` routes InPath probes through RIPDPI's own local service — not the user-supplied external proxy described here. No URL parsing (socks5://user:pass@…), no HTTP CONNECT variant, no credential-privacy enforcement, no "via upstream" result badge, no per-run UI input field for an arbitrary external proxy. All five acceptance criteria remain unimplemented.
