---
title: Audit Cloudflare-only dependencies
type: task
status: done
area: relay
priority: critical
owner: unassigned
parent: epic-remove-cloudflare-from-critical-path
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-15
---

- [x] #task Audit Cloudflare-only dependencies #repo/RIPDPI #area/relay #status/done 🔺

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `audit-cloudflare-only-dependencies`
- **Verify:** `just lint`
- **Scope (only modify these + this file + the ledger):** `core/data/settings/**`, `core/service/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Find every Cloudflare-only dependency in the fleet, client profiles, subscription delivery, DNS, public site, API/update path, and emergency access flows.

## Context

Cloudflare must be treated as a degraded/failable edge for Russian users. The first step is to identify single points of failure before building replacement paths.

## Acceptance criteria

- [ ] Inventory every Cloudflare-backed delivery hostname, subscription URL, DoH/DoT/DoQ resolver, XHTTP frontend, public site, API/update endpoint, Worker/Pages/Tunnel, and reverse-proxy path.
- [ ] Classify each dependency as primary, fallback, optional, or unused.
- [ ] Mark which dependencies currently block IP rotation, subscription refresh, profile recovery, or emergency migration if Cloudflare is unreachable.
- [ ] Assign a non-Cloudflare replacement or fallback plan to each critical dependency.
- [ ] Store live hostnames and sensitive findings only in `ops/live-infra/`; keep TaskNotes summary sanitized.

## Notes

This audit should happen before any DNS-only flip or origin exposure.

## Audit findings

Scan perimeter: `core/data/settings/**`, `core/service/**`, plus read-only scan of entire repo.
Terms searched: `cloudflare`, `trycloudflare`, `workers.dev`, `pages.dev`, `1.1.1.1`, `1.0.0.1`, `cloudflareclient`.

### Inventory

| # | File : line | Dependency type | Classification | Blocks if CF unreachable | Replacement / fallback plan |
|---|-------------|-----------------|----------------|--------------------------|----------------------------|
| 1 | `core/data/settings/…/WarpSettings.kt:26-32` | WARP control-plane API hosts (`api.cloudflareclient.com`, `connectivity.`, `engage.`, `downloads.`, `zero-trust-client.`, `pkg.`, `consumer-masque.`) | primary | IP rotation (WARP provisioning + scanner); WARP tunnel startup | Replace with Zero Trust proxy or self-hosted registration mirror; de-prioritise WARP for RU users |
| 2 | `core/data/settings/…/RelaySettings.kt:11` | `RelayKindCloudflareTunnel = "cloudflare_tunnel"` relay kind constant | primary | Profile recovery / relay path if CF Tunnel is the active relay | Provide equivalent xHTTP/VLESS Reality profile as the default; mark CF Tunnel as optional |
| 3 | `core/data/settings/…/RelaySettings.kt:26` | `RelayMasqueAuthModeCloudflareMtls = "cloudflare_mtls"` MASQUE auth mode | optional (feature-gated via `masque_cloudflare_direct`) | Emergency migration if MASQUE CF path is selected | Feature flag already off; fallback to bearer/preshared MASQUE auth modes |
| 4 | `core/data/settings/…/RelaySettings.kt:174-176,191,259` | `cloudflareTunnelMode`, `cloudflarePublishLocalOriginUrl`, `cloudflareCredentialsRef`, `masqueCloudflareGeohashEnabled` profile model fields | primary (fields) / optional (geohash) | Relay startup for CF Tunnel profiles; geohash is additive only | Fields remain; runtime must treat blank values as graceful no-op |
| 5 | `core/data/settings/…/FakePayloadProfiles.kt:8` | `HttpFakeProfileCloudflareGet = "cloudflare_get"` fake HTTP payload profile | optional | No — desync only; fallback profiles exist | Already one of many profiles; no action needed |
| 6 | `core/service/…/strategy-packs/catalog.json:105,110,120` | Feature flags: `cloudflare_consume_validation` (enabled=true), `cloudflare_publish` (enabled=false), `masque_cloudflare_direct` (enabled=false) | primary (`consume_validation`) / optional (others) | `cloudflare_consume_validation=true` gates CF Tunnel preflight; disabling it skips validation | Disable `cloudflare_consume_validation` as part of CF removal; publish and direct flags already off |
| 7 | `core/service/…/WarpProvisioningClient.kt:417` | `WarpRegistrationBaseUrl = "https://api.cloudflareclient.com/v0a4005/reg"` | primary | WARP provisioning (device registration) entirely blocked | Mirror registration endpoint behind own infra or drop WARP as a supported relay for RU |
| 8 | `core/service/…/CloudflareMasqueGeohashResolver.kt` | CF MASQUE geohash header builder (location-based routing hint for CF MASQUE edge) | optional | No — additive routing hint only; MASQUE still works without it | No action needed; geohash feature disabled by `masque_cloudflare_direct=false` |
| 9 | `core/service/…/CloudflarePublishBinary.kt:10-11,22` | `ripdpi-cloudflared` + `ripdpi-cloudflare-origin` native binaries extracted to `filesDir/cloudflare-runtime/<abi>/` | primary (publish mode) | CF Tunnel publish-mode relay; subscription delivery if publish is the only relay | Binary assets only needed when `cloudflare_publish=true`; gate extraction on flag; plan `just install-cloudflare-binaries-once` task |
| 10 | `core/service/…/UpstreamRelayValidationSupport.kt:111-143` | CF Tunnel validation logic (TLS fingerprint, hostname, publish-mode credential check) | primary | CF Tunnel relay startup | Validated by `cloudflare_consume_validation`; will be obsolete after CF Tunnel demotion |
| 11 | `core/service/…/VpnStartupDnsProbe.kt:32` | `REFERENCE_DNS_SERVER = "1.1.1.1"` — startup DNS integrity canary probe (read-only UDP, not user-facing resolver) | primary (probe) | DNS tamper detection at VPN cold start would lose its reference point | Replace reference server with anycast fallback set (e.g., AdGuard `94.140.14.14`, Mullvad `194.242.2.2`) |
| 12 | `core/service/…/WarpEndpointScannerSupport.kt:273` | `engage.cloudflareclient.com` as fallback scanner target when no provisioned host available | primary (fallback) | WARP endpoint scanner falls back to CF host | Provide alternative fallback — e.g., stored last-known provisioned host |
| 13 | `core/data/settings/…/AppSettingsSerializer.kt:105` | Default `relayCloudflareTunnelMode = consume_existing` in serializer | primary (default) | Determines tunnel mode default on fresh install | Change default to empty/off as part of CF demotion task |

### Blocking matrix

| Scenario | Blocked by |
|----------|-----------|
| IP rotation | #1 (WARP control-plane), #7 (registration URL) |
| Subscription refresh | #9 (if publish mode is delivery path) |
| Profile recovery | #2 (if CF Tunnel is the saved relay kind) |
| Emergency migration | #3 (if MASQUE CF mTLS selected), #10 (validation blocks alternate relay) |

### Critical-path annotation markers added in scope files

`// TODO(cloudflare-removal):` comments have been added to the following locations:

- `core/data/settings/…/WarpSettings.kt:24` — `BuiltInWarpControlPlaneHosts` list
- `core/service/…/WarpProvisioningClient.kt:417` — `WarpRegistrationBaseUrl`
- `core/service/…/VpnStartupDnsProbe.kt:32` — `REFERENCE_DNS_SERVER`
- `core/service/…/CloudflarePublishBinary.kt:22` — `cloudflare-runtime/` extraction path

Live credentials and per-server hostname details are in `ops/live-infra/` (not duplicated here).

## Links

- [[Epic - Remove Cloudflare from critical path]]
- [[cloudflare-ru-critical-path-removal-2026-05-01]]
