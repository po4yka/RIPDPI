---
title: Add force-resolve DNS and Subscription-Userinfo handling
type: task
status: backlog
area: outbound
priority: medium
owner: unassigned
parent: epic-nekobox-subscription-and-profile-import
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Add force-resolve DNS and Subscription-Userinfo handling #repo/RIPDPI #area/outbound #status/backlog 🔼

## Summary

Two small but useful subscription refinements: (a) optional force-resolve
of server hostnames to IPs at refresh time, using a bounded-concurrency
DNS pool; (b) parse the `Subscription-Userinfo` response header and surface
upload/download/quota/expiry in the group detail screen.

## Context

Force-resolve is a NekoBox feature that pre-resolves hostnames to avoid
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

**NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

- `app/src/main/java/io/nekohasekai/sagernet/group/GroupUpdater.kt` — method `forceResolve()`. 5-thread pool via `Executors.newFixedThreadPool(5)`, per-bean resolve + SNI-field rewrite for HTTP/V2Ray/Trojan/Hysteria. Port the thread-pool pattern; replace the Java executor with Kotlin coroutines bounded by a `Semaphore(5)`.
- `app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt` — `Subscription-Userinfo` header read path (look for `response.headers["Subscription-Userinfo"]`), value format is semicolon-separated `upload=N; download=N; total=N; expire=UNIX_TS`.
- `app/src/main/java/io/nekohasekai/sagernet/database/SubscriptionBean.java` — fields `bytesUsed`, `bytesRemaining`, `expiryDate` are populated from the header parse.

**Adapt:** Parallel resolve with bounded concurrency, SNI-field rewrite set, Userinfo header parse. **Skip:** Java `ExecutorService` (use coroutines). **Parser robustness:** NekoBox hard-fails on missing fields; RIPDPI should treat each numeric field as `Long?` so providers that only emit `expire=` don't break the refresh.

## Links

- [[Epic - NekoBox subscription and profile import]]
- [[Add subscription auto-update WorkManager worker]]
