---
title: Add public DNS resolver availability survey diagnostic
type: task
status: backlog
area: dns
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-25
updated: 2026-04-25
---

- [ ] #task Add public DNS resolver availability survey diagnostic #repo/RIPDPI #area/dns #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-public-dns-resolver-availability-survey-diagnostic`
- **Verify:** `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-diagnostics-dns`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-diagnostics-dns/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

A diagnostic that sweeps a curated panel of well-known public DNS
resolvers and reports per-resolver reachability across UDP/53 and DoH,
so the user can see at a glance which providers their ISP actually
permits.

## Motivation

RIPDPI's current resolver chain is fixed (AdGuard, DNS.SB, Google IP,
Mullvad) and used as a fallback ladder rather than as a
reachability survey. dpi-detector's Test 2 ("DNS server availability")
sweeps a much wider panel (Google, Cloudflare, Quad9, AdGuard, Yandex,
OpenDNS, ControlD, CleanBrowsing, NextDNS, Mullvad, Alibaba, DNS.SB,
LibreDNS) on both UDP and DoH and reports per-resolver verdicts. That
output is what informs the user-facing "which resolver should I pin?"
recommendation, and feeds the resolver recommendation surface RIPDPI
already exposes — but without the breadth.

## Scope

- **In scope:** static curated panel of public resolvers (UDP/53 and
DoH wire endpoints), parallel reachability probes with bounded
concurrency, per-resolver latency + verdict, integration into the
existing resolver-recommendation surface.
- **Out of scope:** dynamic resolver discovery; trust scoring of
resolver operators; rewriting the existing fallback chain in
`ripdpi-dns-resolver`.

## Acceptance criteria

- [ ] Resolver panel is a static list shipped in repo (extendable via
    strategy-pack); no runtime fetch from an external service (per
    "no backend" rule).
- [ ] Per-resolver result: `udp_ok`, `doh_ok`, median latency for each,
    and one of `reachable` / `degraded` / `blocked`.
- [ ] Bounded concurrency (≤8 in flight) with a hard wall-clock budget
    for the whole survey.
- [ ] Survey results feed the existing resolver-recommendation
    surface so the recommendation set is the intersection of "panel
    reachable on this network" and "passes integrity classification".
- [ ] Probe respects the `ipv4-only` setting when set.
- [ ] Survey is gated behind an explicit user toggle and is not part
    of automatic probing/audit by default.

## Design notes

The probe lives in `ripdpi-monitor`, parallel to existing DNS
classification. Reuse `ripdpi-dns-resolver` query construction; do not
inline a second DNS encoder. Verdict combines the UDP and DoH outcomes
per resolver — both reachable = `reachable`, mixed = `degraded`,
neither = `blocked`.

## Source reference

dpi-detector v3.2.2: `config.yml` `DNS_AVAILABILITY_SERVERS` panel and
`core/dns_scanner.py` `check_dns_availability`,
`_probe_udp_all`, `_probe_doh_wire_all`.

## Risks / open questions

- Some resolvers in the dpi-detector panel (Yandex, Mullvad) are
themselves blocked or degraded on Russian networks; the verdict
taxonomy must distinguish "this provider is censored" from "your
network is censoring it" without making strong claims either way.

## Links

- [[ripdpi-android]]
- [[Epic - Encrypted DNS and HTTPS SVCB classifier]]


## encrypted-dns-and-https
