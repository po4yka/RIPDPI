---
title: Epic - Advanced routing rules and geoip enforcement
type: epic
status: backlog
area: epic
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-14
---

- [ ] #task Epic - Advanced routing rules and geoip enforcement #repo/RIPDPI #area/epic #status/backlog ⏫

## Goal

Finish the routing-rule story end to end. Today RIPDPI has the Protobuf schema for geosite and partial enforcement, plus per-app VPN exclusion, but no user-editable rule engine, no CIDR rules, no runtime geoip.db/geosite.db enforcement, and no configurable asset provider. reference implementation exposes all of these.

## Why now

Without a rule engine, users cannot express "bypass Russian domestic banking while routing everything else through the proxy." This is the single most- requested routing primitive for bypass clients operating under whitelist- style censorship where split-tunneling is the norm.

## Key decisions

- **Rule engine lives in Rust (runtime fast path), not Kotlin.** The Kotlin layer owns CRUD and serialization; the matcher is native.
- **Rule types match reference implementation for subscription/config parity:** domain, domain_suffix, domain_regex, geosite, ip_cidr, geoip, port, source, network (tcp/udp), process, package (per-app).
- **Outbound actions:** proxy / bypass / block / specific-profile.
- **Asset provider is configurable** with four built-in options mirroring reference implementation: SagerNet, soffchen, Chocolate4U Iran rules, L11R antizapret. Updating is user-triggered, not silent background refresh.
- **Integrate with the existing strategy learner:** per-domain learned app-family routing is a derived rule layer stacked above user rules, not a replacement.
- **Custom domain bypass list has a first-class UI surface** — simpler than the full rule editor, for users who only want to say "keep these domains on direct".

## Scope

- **In scope:** RuleEntity + Room table, Rust rule-matcher, runtime geoip/ geosite.db loader, asset provider picker, custom bypass/block list UI, rule editor screen, rule reordering, rule enable/disable.
- **Out of scope:** Clash-style rule-import parsers (Clash rules differ in semantics and aren't the point; stick with sing-box-compatible routing), DNS-level per-rule overrides (separate concern), automatic rule generation from strategy learner output (future).

## Ship definition

- [ ] User can create, edit, reorder, disable, and delete routing rules from a dedicated Routes screen.
- [ ] Rules support all matcher types listed in "Key decisions".
- [ ] `geoip.db` / `geosite.db` are loaded at service start and consulted by the Rust matcher; lookups are O(1) after first hit.
- [ ] Asset provider picker surfaces four built-in providers; manual file import via SAF also works.
- [ ] Custom bypass list accepts newline-separated domains; entries can be moved into the full rule engine if needed.
- [ ] Per-app routing (package rules) interoperates with the existing `VpnAppExclusionPolicy` without double-matching.
- [ ] Rules are portable via the backup/restore flow (once shipped).
- [ ] Rule evaluation ordering is user-controllable (drag-reorder); first match wins.

## Child tasks

**Data and schema**
- Add RuleEntity Room table and repository (closed task)

**Runtime**
- Add Rust rule matcher with domain ip port process matchers (closed task)
- Add geoip.db and geosite.db runtime loader and lookup (closed task)

**Asset pipeline**
- [[Add configurable asset provider picker with four presets]]

**UI**
- [[Add custom domain bypass list screen]]
- [[Add full routing rule editor screen]]

## Dependencies

- Feeds: [[Epic - Settings backup and restore]] — rules are part of backup schema.
- Depends on: Epic - Subscription and profile import — rule outbound actions can target specific profiles or groups.

## Risks / open questions

- Rule count at scale: some power-users carry 500+ rules. Keep matcher allocation-free in the hot loop.
- Geosite.dat vs geosite.db formats: SagerNet and upstream have subtly different binary formats. Support only the SagerNet-compatible binary format; document.
- Rule-engine performance with native geoip CIDR tries must beat a naive linear scan by a clear margin; benchmark before shipping.
- Asset staleness: providers push updates at varying cadence; surface "asset is N days old" passively without nagging.

## Links

- [[ripdpi-android]]
- Epic - Subscription and profile import
- [[Epic - Settings backup and restore]]
- Child issues: 8
