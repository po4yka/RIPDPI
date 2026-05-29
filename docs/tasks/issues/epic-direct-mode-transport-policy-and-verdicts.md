---
title: Epic - Direct-mode transport policy and verdicts
type: epic
status: done
area: epic
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-29
---

- [x] #task Epic - Direct-mode transport policy and verdicts #repo/RIPDPI #area/epic #status/done ⏫

## Goal

Decide per-`(host, ip set, app family, network profile)` when to disable QUIC, detect apps that can't fall back to TCP without breaking, classify true IP blocks, and return honest `NO_DIRECT_SOLUTION` verdicts instead of burning attempts. The policy engine's job is to stop thrashing when direct transparent mode can't win.

## Why now

Aggressive QUIC packet rewriting in transparent TUN mode is expensive and brittle. The plan deliberately moves that complexity out of the default path. But without a policy engine, the diagnostic has no way to say "this is likely IP-blocked, stop trying transport tricks." That wasted effort is a battery, network, and detectability tax.

## Key decisions

- **QUIC suppression, not QUIC rewriting.** Drop outbound UDP/443 per tuple; let the app retry over TCP where the TLS family engine can do its job.
- **NO_TCP_FALLBACK** is a per-app-family memory. If soft-disable breaks an app that hard-depends on QUIC, remember and don't apply again.
- **Three outcome types:** `TRANSPARENT_OK`, `OWNED_STACK_ONLY`, `NO_DIRECT_SOLUTION`. Each with a structured reason code visible in diagnostics.
- **Relay-assisted QUICstep migration is out of scope** for this epic — it belongs in a second-tier rescue mode, not the "no remote proxy" default.

## Scope

- **In scope:** `TransportPolicy` struct, QUIC `SOFT_DISABLE` / `HARD_DISABLE` enforcement, `NO_TCP_FALLBACK` detection, `IP_BLOCK_SUSPECT` classification, `NO_DIRECT_SOLUTION` surfacing, per-tuple policy cache.
- **Out of scope:** QUIC packet-level rewriting, relay-assisted transport migration, non-443 QUIC ports (yagni).

## Ship definition

- [x] `TransportPolicy` type exists with all five fields (`quic_mode`, `preferred_stack`, `dns_mode`, `tcp_family`, `outcome`) and serializes stably across app updates.
- [x] `SOFT_DISABLE` is tuple-scoped — other hosts and other apps unaffected.
- [x] `NO_TCP_FALLBACK` heuristic is conservative by default; reverts on app package version change.
- [x] `IP_BLOCK_SUSPECT` classification re-verifies on the next flow before pinning, to avoid transient-blip false positives.
- [x] `NO_DIRECT_SOLUTION` surface in UI with a structured reason, and with cooldown to prevent immediate re-runs.

## Implementation note

Verified 2026-05-28 against the current transport-policy code: diagnostics keep distinct TLS, QUIC, and likely-IP-block `NO_DIRECT_SOLUTION` causes instead of collapsing them all into `IP_BLOCK_SUSPECT`, runtime `ALL_IPS_FAILED` learning requires a second flow before persisting the negative verdict, and the runtime enforcement path applies cached tuple-scoped QUIC suppression consistently. In particular, `NO_TCP_FALLBACK` no longer leaves the runtime in the contradictory state where UDP suppression is lifted but the adaptive UDP/QUIC hint layer still behaves as if QUIC is broken for the same authority.

Closed 2026-05-29: the true per-app-family `NO_TCP_FALLBACK` memory is now load-bearing in the live Kotlin policy path. `NoTcpFallbackAppMemory` (mirroring the native reference `AppFamilyMemory`) is consulted by `DirectPathPolicyLearner`: an attributed `NO_TCP_FALLBACK_DETECTED` remembers the owning app by `(packageName, versionCode)`, and a later `QUIC_BLOCKED_TCP_OK` for that same app is suppressed across hosts. Marks are version-scoped, so an app update reverts the memory automatically. The heuristic is conservative by default — signals with no package identity never arm or match the memory, so per-app suppression never fires on telemetry it cannot attribute. `SOFT_DISABLE` tuple-scoping and other-app/other-host isolation are proven by live-path tests in `DirectPathPolicyLearnerTest`. The remaining input — per-flow UID→package attribution that would populate `packageName` on production telemetry — is tracked as its own follow-up, deliberately kept out of this policy-engine epic because it is enforcement-plumbing adjacent to the out-of-scope packet layer.

## Child tasks

**Struct and cache**
- [[Define TransportPolicy struct and per-host state]]
- Cache transport policy per network and host tuple (closed task)

**QUIC control**
- Implement QUIC soft-disable per tuple (closed task)
- Detect NO_TCP_FALLBACK app families (closed task)

**Verdict classification**
- Classify IP_BLOCK_SUSPECT when all IPs fail (closed task)
- [[Surface NO_DIRECT_SOLUTION verdict honestly]]

**Follow-up (out of epic scope, tracked separately)**
- [[Attribute direct-mode flows to the owning app package]] — supplies the per-flow package identity that lets the per-app `NO_TCP_FALLBACK` memory fire on production telemetry.

Child tasks roll up via the TaskNotes relationships view on this note.

## Dependencies

- Feeds: [[Epic - Direct-mode diagnostic state machine]] Phase 2 + arm A3.
- Consumed by: Gate DoQ on UDP-clean classification (closed task) under [[Epic - Encrypted DNS and HTTPS SVCB classifier]] (DoQ gate reads `udp443_ok`).
- Unblocks: [[Report OWNED_STACK_ONLY verdict from diagnostic]] under Epic - Owned-stack mode with Android 17 ECH.

## Risks / open questions

- `NO_TCP_FALLBACK` heuristic: how to detect reliably without breaking the app the first time? Spike the detection signal before committing.
- Cooldown length for `NO_DIRECT_SOLUTION`: too short wastes retries, too long looks broken on recovery. Default 7 days (matches Phase 5 TTL), revalidate on ASN/access-type change.
- If a later second-tier rescue track evaluates relay-assisted QUICstep, keep it strictly post-`NO_DIRECT_SOLUTION` and outside the default transparent-mode path. See [[Spike relay-assisted QUICstep rescue mode after NO_DIRECT_SOLUTION]].

## Links

- [[ripdpi-android]]
- ripdpi-android-direct-mode-plan-2026-04-20 §3, Basic diagnostic Phase 2 + arm A3
- Child issues: 8
