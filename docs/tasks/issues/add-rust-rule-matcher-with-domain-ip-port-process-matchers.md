---
title: Add Rust rule matcher with domain ip port process matchers
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

- [ ] #task Add Rust rule matcher with domain ip port process matchers #repo/RIPDPI #area/routing #status/backlog ⏫

## Summary

Add a Rust rule matcher crate that evaluates user rules at flow-dispatch
time, in first-match-wins order, producing an outbound action.

## Context

Matcher lives in Rust for the same reason the rest of the fast path does:
allocation-free hot loop, predictable p99. Domain matching uses a suffix
trie; IP CIDR uses a ranged-tree. Process name comes from the existing
package→UID lookup; package set is pre-hashed.

## Acceptance criteria

- [ ] `ripdpi-routing` crate with `RuleMatcher` type; FFI surface
    exposed to JNI.
- [ ] Suffix-trie domain matcher; benchmark beats linear scan by 10×
    at 10K domain entries.
- [ ] IP CIDR matcher supports IPv4 and IPv6; uses a trie or interval
    tree, not linear scan.
- [ ] Port matcher supports single port and range (`80-90`); source
    ports handled symmetrically.
- [ ] Package matcher uses the existing package→UID cache; cold lookup
    does not stall the flow dispatch.
- [ ] Matcher allocation on the hot path is zero in steady state;
    benchmark proves it.
- [ ] Unit tests cover: first-match-wins order, disabled rules
    skipped, no-rule default action configurable.

## Source references

**NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

- `app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt` — the rule-translation pass (search for `Rule_DefaultOptions` and `makeSingBoxRule`). Shows how domain strings get classified into `domain` / `domain_suffix` / `domain_regex` / `geosite` prefix categories. **Port this classification logic.**
- `app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt` — `applyRouteRules()` shows the built-in rule set (DNS hijack on port 53, LAN bypass, multicast block) appended to user rules.

**Upstream sing-box** ([repo](https://github.com/SagerNet/sing-box)) — the actual rule-matching Go code lives in `route/rule_default.go`. RIPDPI implements in Rust but the algorithm is simple: first-match-wins, each rule a boolean conjunction of matchers. Not a port, just a reference for correctness.

**Adapt:** Domain-string classification (prefixes like `domain:`, `geosite:`, `ip_cidr:`), first-match-wins semantic, built-in rule set (LAN bypass, multicast block). **Skip:** sing-box's Go implementation; allocation-free Rust is a separate engineering concern.

## Links

- [[Epic - Advanced routing rules and geoip enforcement]]
- [[Add RuleEntity Room table and repository]]
