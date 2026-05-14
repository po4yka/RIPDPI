---
title: Add full routing rule editor screen
type: task
status: backlog
area: routing
priority: high
owner: unassigned
parent: epic-advanced-routing-rules-and-geoip-enforcement
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Add full routing rule editor screen #repo/RIPDPI #area/routing #status/backlog ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-full-routing-rule-editor-screen`
- **Verify:** `just test-module app`
- **Scope (only modify these + this file + the ledger):** `app/**`, `core/data/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Add the full rule editor: list of rules (drag-to-reorder), per-rule
editor with all matcher types, outbound-action picker including
specific-profile and specific-group targets.

## Context

The editor is the power-user surface. It lives on a dedicated Routes
screen in the main nav drawer. Matchers are the superset: domain,
domain_suffix, domain_regex, geosite, ip_cidr, geoip, port, source,
network, process, package. Outbound actions pick from the enum plus
existing profiles and groups.

## Acceptance criteria

- [ ] Routes screen in main nav shows the rule list with
    drag-to-reorder, enable-toggle per rule, name + summary line.
- [ ] Rule editor has collapsible sections per matcher type; empty
    matchers are absent from the compiled rule.
- [ ] Geosite / geoip pickers surface the categories / country codes
    from the loaded DBs; autocomplete on type.
- [ ] Package picker uses the existing `PackageCache` to show icon +
    label; multi-select.
- [ ] Outbound picker: Proxy / Bypass / Block / specific profile /
    specific group.
- [ ] Validation: empty rule cannot save; conflicting matchers (e.g.
    port 80 AND port 443 only) are not auto-corrected — first match
    wins at runtime.
- [ ] Rule list honors the first-match-wins runtime semantic; reorder
    persists immediately.
- [ ] Accessibility: drag-reorder has keyboard equivalents.

## Source references

**NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

- `app/src/main/java/io/nekohasekai/sagernet/ui/RouteSettingsActivity.kt` — the editor Activity: every matcher section (domains, IP CIDR, ports, source, network, protocol, process, packages, outbound). Port the section list and the ordering.
- `app/src/main/java/io/nekohasekai/sagernet/ui/RouteFragment.kt` — the routing rule list with drag-to-reorder.
- `app/src/main/res/xml/route_preferences.xml` — reference for the field ordering in the editor.
- `app/src/main/java/io/nekohasekai/sagernet/ui/AppListActivity.kt` — the package-picker sub-screen. Port the icon+label multi-select pattern.

**Adapt:** Matcher section set, drag-reorder, outbound picker (Proxy/Bypass/Block/specific profile), package multi-select. **Skip:** NekoBox's XML-Preference rendering (build Compose). **Improve over NekoBox:** add outbound-picker option "specific group" in addition to "specific profile" (NekoBox's group-selector outbound already supports this via ProxyGroup.isSelector; surface it explicitly in the rule outbound picker).

## Links

- [[Epic - Advanced routing rules and geoip enforcement]]
- [[Add RuleEntity Room table and repository]]
