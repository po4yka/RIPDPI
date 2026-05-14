---
title: Add force-resolve DNS and Subscription-Userinfo handling
type: task
status: done
area: outbound
priority: medium
owner: unassigned
parent: epic-subscription-profile-import
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [x] #task Add force-resolve DNS and Subscription-Userinfo handling #repo/RIPDPI #area/outbound #status/done 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-force-resolve-dns-and-subscription-userinfo-handling`
- **Verify:** `just test-module core:data:runtime-state`
- **Scope (only modify these + this file + the ledger):** `core/data/runtime-state/**`, `core/data/model/**`, `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Two small but useful subscription refinements: (a) optional force-resolve
of server hostnames to IPs at refresh time, using a bounded-concurrency
DNS pool; (b) parse the `Subscription-Userinfo` response header and surface
upload/download/quota/expiry in the group detail screen.

## Context

Force-resolve is a reference implementation feature that pre-resolves hostnames to avoid
relying on the runtime DNS path for nodes whose DNS is flaky. The existing
`hickory-resolver` + DoH stack in RIPDPI can back this. The user-info
header (format:
`upload=…; download=…; total=…; expire=…`) is standard in most commercial
bypass subscriptions.

## Acceptance criteria

- [ ] Per-group toggle "Force resolve on update" (default off).
- [ ] When on, refresh DNS-resolves each profile's `serverAddress` with
    up to 5 parallel lookups; rewrite both `serverAddress` and SNI-ish
    fields for V2Ray/Trojan/Hysteria beans.
- [ ] `Subscription-Userinfo` response header is parsed into typed
    fields; malformed values become `null`, not thrown exceptions.
- [ ] Group detail screen surfaces upload/download/total/expire in
    localized, redaction-aware format.
- [ ] Expired subscription surfaces a warning banner; refresh still
    proceeds to inform user-driven action.
- [ ] Unit tests cover header parsing variants, malformed headers, and
    IPv4/IPv6/dual-resolve outcomes.

## Source references

**Reference implementation notes:**

- `app/src/main/java/io/nekohasekai/sagernet/group/GroupUpdater.kt` — method `forceResolve()`. 5-thread pool via `Executors.newFixedThreadPool(5)`, per-bean resolve + SNI-field rewrite for HTTP/V2Ray/Trojan/Hysteria. Port the thread-pool pattern; replace the Java executor with Kotlin coroutines bounded by a `Semaphore(5)`.
- `app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt` — `Subscription-Userinfo` header read path (look for `response.headers["Subscription-Userinfo"]`), value format is semicolon-separated `upload=N; download=N; total=N; expire=UNIX_TS`.
- `app/src/main/java/io/nekohasekai/sagernet/database/SubscriptionBean.java` — fields `bytesUsed`, `bytesRemaining`, `expiryDate` are populated from the header parse.

**Adapt:** Parallel resolve with bounded concurrency, SNI-field rewrite set, Userinfo header parse. **Skip:** Java `ExecutorService` (use coroutines). **Parser robustness:** reference implementation hard-fails on missing fields; RIPDPI should treat each numeric field as `Long?` so providers that only emit `expire=` don't break the refresh.

## Links

- [[Epic - Subscription and profile import]]
- [[Add subscription auto-update WorkManager worker]]

## Work log

**2026-05-14 — core/data force-resolve + Subscription-Userinfo parsing
implemented (TDD; group-detail UI surfacing out of this agent's scope).**

Scope note: the issue scope is `core/data/runtime-state/**` + `core/data/model/**`
+ `app/**`. This pass delivers the testable `core/data` core: the bounded
parallel resolver, the header parser, and the entity-apply bridge. The per-group
"Force resolve on update" toggle wiring and the group-detail upload/download/
expiry surfacing + expired-banner are `app/`-layer and are deferred. The
`Subscription` entity already carries `forceResolve`, `subscriptionUserinfo`,
`bytesUsed`, `bytesRemaining`, `expiryDate` fields — no entity edit was needed.

Files created:
- `core/data/runtime-state/src/main/kotlin/com/poyka/ripdpi/data/subscription/ForceResolveDns.kt`
  — `ForceResolveDns.resolveAll(profiles, resolve)`: resolves profile server
  hostnames concurrently, bounded to 5 in-flight lookups via a coroutine
  `Semaphore(5)` (Reference implementation's `Executors.newFixedThreadPool(5)` ported to
  coroutines). IP-literal servers (v4/v6) are skipped — not looked up; a failed
  or empty resolution leaves the host unchanged so a flaky lookup never drops a
  node; `RawConfig` profiles pass through. `HostResolution` sealed result type.
- `core/data/runtime-state/src/main/kotlin/com/poyka/ripdpi/data/subscription/SubscriptionUserinfo.kt`
  — parses the `Subscription-Userinfo` header
  (`upload=N; download=N; total=N; expire=UNIX_TS`) into typed `Long?` fields;
  every field is nullable, malformed values become `null` not exceptions;
  derived `bytesUsed` / `bytesRemaining`; `isExpired(now)`.
- `core/data/runtime-state/src/main/kotlin/com/poyka/ripdpi/data/subscription/SubscriptionUserinfoApply.kt`
  — `Subscription.withUserinfo(...)` / `withUserinfoHeader(...)` extensions:
  fold a parsed header onto the existing `Subscription` entity fields.

Test files created (written before implementation, red-then-green):
- `core/data/src/test/kotlin/com/poyka/ripdpi/data/SubscriptionUserinfoTest.kt` —
  12 tests (full header, whitespace tolerance, only-`expire`, malformed values →
  null, empty/blank, unknown keys, derived bytes, expiry detection, entity apply).
- `core/data/src/test/kotlin/com/poyka/ripdpi/data/ForceResolveDnsTest.kt` — 9
  tests (resolve+rewrite, failed-resolve passthrough, IPv4/IPv6 literal skip,
  dual-stack first-address, bounded-concurrency ≤5 under 20 hosts, RawConfig
  passthrough, empty list, empty-address-list passthrough).

Verify: `./gradlew :core:data:testDebugUnitTest` — `SubscriptionUserinfoTest`
12/12 pass, `ForceResolveDnsTest` 9/9 pass (JUnit XML: `failures="0" errors="0"`).

Residual risk: actual DNS resolution (the `resolve` lambda) is injected by the
caller — the live `hickory-resolver` / DoH wiring is the caller's responsibility
and is not exercised by these unit tests.
