---
title: Add custom domain bypass list screen
type: task
status: done
area: routing
priority: medium
owner: unassigned
parent: epic-advanced-routing-rules-and-geoip-enforcement
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-29
---

- [x] #task Add custom domain bypass list screen #repo/RIPDPI #area/routing #status/done 🔼

## Summary

Add a simple "Domain bypass list" screen where users paste domains they want kept on direct (out-of-proxy), without entering the full rule editor. The list compiles to a single high-priority routing rule.

## Context

Most users do not need the full rule editor; they just need to keep a handful of domestic services off the tunnel (banking, government, local maps). Giving this a dedicated, simpler surface separates the 90% case from the power-user rule editor.

## Acceptance criteria

- [ ] Screen under Settings or Routes; multi-line text-edit accepting newline-delimited domains.
- [ ] Accepts plain domains (`example.com`), suffixes (`.example.com`), and `domain:` / `domain_suffix:` / `domain_regex:` prefixes.
- [ ] Entries compile to a single internal rule with outbound=BYPASS and the highest user-configurable priority.
- [ ] Editing the list does not reorder other user rules.
- [ ] Import from clipboard and export to clipboard actions.
- [ ] Validation: malformed regex surfaces inline, the list saves only clean entries.

## Source references

**Reference implementation notes:** — no direct analog. reference implementation exposes only the full rule editor (`RouteSettingsActivity`). A simple bypass-list is NOT in reference implementation — this is an RIPDPI-original simplification for the common case.

**Adapt:** The domain-string classification prefixes (`domain:`, `domain_suffix:`, `domain_regex:`) from Reference implementation's `ConfigBuilder.kt` — see Add Rust rule matcher with domain ip port process matchers (closed task) for that reference.

**Invent:** The single-rule compile strategy (all entries → one high-priority BYPASS rule), the "move into full rule editor" migration action.

## Links

- [[Epic - Advanced routing rules and geoip enforcement]]
