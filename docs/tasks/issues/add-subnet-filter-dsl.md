---
title: Add Subnet-Filter DSL with org/as/country/subnet/host Combinators
type: task
status: backlog
area: diagnostics
priority: high
owner: unassigned
parent: dpi-checkers-parity-epic
blocks: [add-webhost-farm-dynamic-host-discovery, add-cidr-whitelist-detector]
blocked_by: [add-geoip-db-and-geosite-db-runtime-loader-and-lookup]
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add Subnet-Filter DSL with org/as/country/subnet/host Combinators #repo/RIPDPI #area/diagnostics #status/backlog ⏫

## Objective

Add `SubnetFilterDsl` — a small recursive-descent parser + evaluator for filter expressions like `(org("hetzner", "digitalocean") && country("de", "fi")) || as(199524, 53667)`. Each leaf returns a `Set<IpRange>`; combinators apply set union/intersection. Evaluates locally against the existing geoip DB without internet.

## Context

dpi-ch's killer feature is dynamic host discovery: instead of shipping a fixed `tcp16.json`, each diagnostic run picks a fresh random sample of hosts from subnets matching a user-supplied filter. This prevents the censor from poisoning the test by whitelisting our static target list. The mechanism that makes it possible is the **subnetfilter** DSL — five combinators with AND/OR semantics that resolve to a `Set<IpRange>` locally:

| Combinator | Args | Behavior |
|---|---|---|
| `org(x1, ...)` | term / asn / ip | substring search on AS organization name; asn → org name → term; ip → org name → term |
| `as(x1, ...)` | asn / ip | direct AS number lookup; ip → asn → lookup |
| `country(x1, ...)` | ISO 3166-1 alpha-2 | all subnets in country |
| `subnet(x1, ...)` | CIDR / ip | direct CIDR; ip → minimal subnet from announcing AS |
| `host(x1, ...)` | hostname | DNS resolves hostname; returns subnets containing those IPs |

**Logical operators:** `&&` (intersection), `||` (union), parentheses for grouping. Precedence: `&&` binds tighter than `||`.

**Reference:** `/Users/po4yka/GitRep/dpi-checkers/ru/dpi-ch/docs/README.md` (`Killer features` → `The era of dynamic`) + `/Users/po4yka/GitRep/dpi-checkers/ru/dpi-ch/subnetfilter/` (Go source)

**RIPDPI placement:**
- DSL: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/SubnetFilterDsl.kt`
- AST: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/SubnetFilterAst.kt`
- Evaluator: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/SubnetFilterEvaluator.kt`

## Acceptance criteria

- [ ] `SubnetFilterAst` sealed class: `Org(args)`, `As(args)`, `Country(args)`, `Subnet(args)`, `Host(args)`, `And(l, r)`, `Or(l, r)`
- [ ] `SubnetFilterDsl.parse(expr: String): SubnetFilterAst` — recursive-descent parser; throws `SubnetFilterParseError` with position on malformed input
- [ ] `SubnetFilterEvaluator(geoipDb, dnsResolver).evaluate(ast): Set<IpRange>` — pure async function; cached per-input within a single diagnostic run
- [ ] Argument type detection per combinator: numeric → asn (in `as`), CIDR-shaped → subnet (in `subnet`), dotted-quad → ip, else → term/hostname/country-code
- [ ] `org()` two-phase: asn arg → resolve via geoip → use org name as substring; ip arg → resolve to asn → resolve to org name → substring
- [ ] `as()` accepts asn directly or ip→asn lookup
- [ ] `subnet()` accepts CIDR directly or ip→minimal-announcing-subnet
- [ ] `host()` resolves hostname via system + DoH (reuses `SystemDohDnsComparator`); returns subnets covering all returned IPs
- [ ] Operator precedence: `&&` tighter than `||`; parentheses override
- [ ] Empty filter expression → `Set.empty()`
- [ ] Unit tests cover parser + evaluator + each combinator + AND/OR composition + parser error positions

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/SubnetFilterDslTest.kt`:
     - `parses_single_org_term()` — `org("hetzner")`; assert AST `Org(["hetzner"])`; fails until parser exists
     - `parses_or_with_two_groups()` — `org("a") || as(123)`; assert `Or(Org, As)`
     - `parses_and_groups_tighter_than_or()` — `org("a") && country("de") || as(123)`; assert `Or(And(Org, Country), As)`
     - `parses_parenthesized_group()` — `(org("a") || as(123)) && country("de")`; assert `And(Or(...), Country)`
     - `parser_error_position_reported_on_unclosed_paren()` — `org("a"`; assert error mentions position
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/SubnetFilterEvaluatorTest.kt`:
     - `country_returns_all_subnets_in_country()` — fake geoip with 3 DE subnets; `country("de")`; assert size 3
     - `org_substring_match()` — fake geoip with `"Hetzner Online GmbH"`; `org("hetzner")`; assert match
     - `as_two_phase_via_ip()` — fake geoip resolves `1.2.3.4` → AS199524 → 5 subnets; `as("1.2.3.4")`; assert 5 subnets
     - `subnet_minimal_from_ip()` — fake geoip resolves `1.2.3.4` → minimal `1.2.3.0/24`; assert single subnet
     - `host_resolves_via_dns()` — mock DNS returns `["1.2.3.4", "5.6.7.8"]`; geoip maps to two subnets; assert union
     - `and_intersection()` — `org("hetzner") && country("de")`; assert only DE-Hetzner subnets
     - `or_union()` — `org("hetzner") || as(123)`; assert union
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 12 fail
3. **Implement** — parser, AST, evaluator
4. **Confirm green** — `./gradlew :core:diagnostics:test`
5. **Refactor** — extract argument-type detection into a single `inferArgType(s): ArgType` function

## Definition of done

All 12 unit tests green. `SubnetFilterDsl` evaluable against bundled geoip DB at runtime. Consumed by `add-webhost-farm-dynamic-host-discovery`.
