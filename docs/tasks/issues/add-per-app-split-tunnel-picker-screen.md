---
title: Add per-app split tunnel picker screen
type: task
status: backlog
area: routing
priority: medium
owner: unassigned
parent: epic-advanced-routing-rules-and-geoip-enforcement
blocks: []
blocked_by: []
created: 2026-05-29
updated: 2026-05-29
---

- [ ] #task Add per-app split tunnel picker screen #repo/RIPDPI #area/routing #status/backlog 🔼

## Summary

Add a friendly per-app picker screen (search + multi-select installed apps) for split tunneling, as the simple surface over the existing package-routing primitive — analogous to how [[Add custom domain bypass list screen]] is the simple surface for domain rules.

## Context

Split tunneling already exists at the VPN-service layer (`RipDpiVpnService` calls `addDisallowedApplication`, with `PackageRoutingRule` / `VpnAppExclusionPolicy` in the data layer), and the geoip epic covers package rules inside the full rule engine. What is missing is a dedicated, user-friendly app-selection UI — most users want to tick a few apps to include/exclude without entering the full rule editor. xivpn provides this via `AppSelectActivity` / `SplitTunnelActivity`.

## Acceptance criteria

- [ ] Screen lists installed apps (icon, label, package) with search/filter and include/exclude multi-select.
- [ ] Selection compiles to package-routing rules that interoperate with `VpnAppExclusionPolicy` without double-matching (same constraint the epic states for package rules).
- [ ] Supports both modes consistently: allowlist (only these apps tunneled) vs. blocklist (these apps bypass) — clearly labeled, mutually coherent with existing policy.
- [ ] `QUERY_ALL_PACKAGES` usage (or the scoped alternative) is justified for Play Data Safety; no package list is logged or exported in violation of `.claude/rules/network-fingerprint-privacy.md`.
- [ ] RDS tokens only; all 7 locales; compose-preview render added.
- [ ] Changing the app selection does not reorder unrelated user routing rules.

## Source references

**Reference (xivpn):** `AppSelectActivity`, `SplitTunnelActivity`, `BaseAppListActivity`, `InstalledAppsAdapter` — interaction pattern only; reimplement in Compose under RIPDPI's own license.

**Adapt:** the include/exclude multi-select + search UX.

**Invent:** the compile-to-`PackageRoutingRule` mapping and the allowlist/blocklist coherence with `VpnAppExclusionPolicy`.

## Links

- [[Epic - Advanced routing rules and geoip enforcement]]
- [[Add custom domain bypass list screen]]
