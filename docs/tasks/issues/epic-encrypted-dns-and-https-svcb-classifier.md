---
title: Epic - Encrypted DNS and HTTPS SVCB classifier
type: epic
status: todo
area: dns
priority: critical
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-04-23
---

- [ ] #task Epic - Encrypted DNS and HTTPS SVCB classifier #repo/RIPDPI #area/dns #status/todo 🔺

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `epic-encrypted-dns-and-https-svcb-classifier`
- **Verify:** `all child rows in GOAL_LEDGER.md are DONE or BLOCKED`
- **Scope (only modify these + this file + the ledger):** _epic — coordination only; child tasks carry the file scope_
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Goal

Make DNS a first-class bypass layer and a first-class classifier. Separate DNS poisoning from SNI/TLS blocking from QUIC filtering from IP blocking before the diagnostic burns any transport-level attempts.

## Why now

middlebox blocks by DNS manipulation too, not only by SNI or IP. Without classifying DNS first, the diagnostic will cycle through transport tricks against a host it could have reached by simply switching resolvers. Also, HTTPS/SVCB records carry ECH config metadata that gates [[Epic - Owned-stack mode with Android 17 ECH]].

## Key decisions

- **DoH by default.** It rides HTTPS and survives hostile UDP.
- **DoQ gated.** Only activated after the transport policy engine marks UDP/443 healthy for the current network profile. Otherwise DoQ and QUIC fail together.
- **Always query HTTPS/SVCB** alongside A/AAAA/CNAME — these carry ALPN hints and ECH configs and are cheap enough to piggyback.
- **Five-state classification** produced for every target:

| State             | Meaning |
|-------------------|---------|
| `CLEAN`           | System and encrypted resolvers agree materially |
| `POISONED`        | System returns NXDOMAIN / empty / known bad; encrypted returns valid |
| `DIVERGENT`       | Both valid but different CDN answers; no strong poisoning evidence |
| `ECH_CAPABLE`     | HTTPS RR carries ECH config metadata |
| `NO_HTTPS_RR`     | No HTTPS/SVCB data available |

- **No broad preloaded scanning.** Measure only destinations the user is actually trying to reach (C-Saw consent posture).

## Scope

- **In scope:** DoH primary+secondary pipeline, DoQ gated on UDP-clean, HTTPS/SVCB RR queries with ECH config parsing, DNS classification, resolver selection logic, user-destinations-only measurement.
- **Out of scope:** running a DoH/DoQ resolver ourselves.

## Ship definition

- [ ] Resolver cascade runs per-target: system → DoH primary → DoH secondary → DoQ (if UDP clean).
- [ ] A/AAAA/CNAME/HTTPS/SVCB queried in one batch; HTTPS RR ECH config parsed into a typed `EchConfig`.
- [x] Classification produces exactly one of the five states above on the active native `dns_integrity` path, and that classifier is persisted into direct-path capability envelopes.
- [ ] No code path exists that probes a preloaded target list.
- [ ] Selection cache keyed by `(host, NetProfile)` with the same TTL as the family cache.

## Child tasks

**Resolver pipeline**
- [[Build DoH primary and secondary resolver pipeline]]
- [[Gate DoQ on UDP-clean classification]]

**HTTPS/SVCB**
- [[Parse HTTPS SVCB records with ECH config metadata]]

**Classification**
- [[Classify DNS as clean poisoned divergent ech-capable]]
- [[Select resolver mapping from DNS classification]]

**Privacy posture**
- [[Limit DNS measurement to user-requested destinations]]

Child tasks roll up via the TaskNotes relationships view on this note.

## Dependencies

- Feeds: [[Epic - Direct-mode diagnostic state machine]] Phase 1 and arms A0–A2.
- Coordinates with: [[Epic - Direct-mode transport policy and verdicts]] (DoQ gating depends on `udp443_ok` from transport policy).

## Risks / open questions

- DoH resolver selection: which providers, which redundancy? Decide in the pipeline task.
- Caching policy: HTTPS/SVCB TTL vs field-observed staleness — surface staleness via the Phase 5 revalidation triggers.
- "Known bad IP" heuristic for POISONED classification: start conservative to avoid false positives; tune from field data.

## Implementation note

As of 2026-04-23, RIPDPI now ships the classifier itself on the live native DNS-probe path, threads the result into direct-path policy storage, applies authority-scoped encrypted-DNS resolver selection on the native hostname- resolution path, and downgrades DoQ back to DoH whenever the current host is not UDP-clean under transport policy. VPN startup also now promotes converged hostname-backed `DOH_PRIMARY` / `DOH_SECONDARY` guidance into the active resolver selection instead of waiting for reactive failover. What remains open in this epic is the follow-on cache/policy work: a dedicated fastest-resolver cache keyed by `(host, NetProfile)` and any richer `DIVERGENT` correlation logic beyond the current policy-hint path.

## Links

- [[ripdpi-android]]
- [[ripdpi-android-direct-mode-plan-2026-04-20]] §2, Basic diagnostic Phase 1 + arms A0–A2
- Child issues: 3
