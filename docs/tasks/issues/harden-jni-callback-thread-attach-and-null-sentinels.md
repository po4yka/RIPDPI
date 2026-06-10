---
title: "Harden JNI callbacks: daemon thread-attach, nullable array returns, drop runBlocking"
type: task
status: todo
area: android
priority: medium
owner: unassigned
parent: epic-june-2026-audit-remediation
blocks: []
blocked_by: []
created: 2026-06-10
updated: 2026-06-10
source_wiki_pages: []
linked_task: null
---

## Motivation

The 2026-06-10 JNI boundary audit confirmed the boundary is solid (all exports panic-contained via `ffi_boundary`, every signature cross-checks, protect() invariant holds) but found three hardening gaps:

1. **Non-daemon thread attach (5 sites)** — `ripdpi-android-vpn-protect-adapter/src/protect_callback.rs:40`, `ripdpi-warp-android/src/vpn_protect.rs:55`, `ripdpi-warp-android/src/readiness.rs:39`, `ripdpi-relay-android/src/readiness.rs:38`, `ripdpi-tunnel-android/src/flow_attribution.rs:73` use `attach_current_thread` instead of `attach_current_thread_as_daemon`. RAII detach prevents leaks today, but a non-daemon attach during JVM shutdown can block the shutdown sequence. One-word change per site.

2. **Non-nullable Kotlin returns vs null Rust panic sentinel** — `jniGetStats` (Kotlin `LongArray`, Rust sentinel `null_mut()`), `luaListStrategies`/`luaLoadedScriptPaths` (Kotlin `Array<String>`, Rust sentinel null). A contained panic returns null, which marshals to a null Kotlin value and throws NPE inside `TunnelStats.fromNative` / call sites. Declare `LongArray?` / `Array<String>?` with a null-coalesce, or make `ffi_boundary` return a valid empty array on panic for these return types.

3. **`runBlocking` on the Lua mutation lock** — `core/engine/src/main/kotlin/com/poyka/ripdpi/core/StrategyEngineNativeBindings.kt:105` wraps `withProcessGlobalLuaMutationLock` in `runBlocking`, which can occupy an IO-dispatcher thread for the lock duration under concurrent strategy loads. Make it `suspend` and let callers dispatch via `withContext(Dispatchers.IO)`.

## Proposed change

1. Replace `attach_current_thread` → `attach_current_thread_as_daemon` at all 5 callback sites (RAII guard contract is identical in jni 0.22.4).
2. Make `jniGetStats`, `luaListStrategies`, `luaLoadedScriptPaths` null-safe end to end (nullable Kotlin type + coalesce, or guaranteed-non-null empty array on the Rust panic arm).
3. Convert `withProcessGlobalLuaMutationLock` to `suspend`, remove `runBlocking`.

## Acceptance criteria

- [ ] PR confirms current state at all cited sites.
- [ ] All 5 callback attach sites use the daemon variant.
- [ ] The three array-returning JNI methods cannot NPE on a contained panic (nullable + coalesce, or non-null empty sentinel).
- [ ] `withProcessGlobalLuaMutationLock` is `suspend`; no `runBlocking` on the IO path.
- [ ] `./gradlew :core:engine:testDebugUnitTest --locked` green; native crates build clean.

## Risks / open questions

- Confirm jni 0.22.4 `attach_current_thread_as_daemon` RAII detach semantics match the non-daemon variant (audit asserts they do).
- The runBlocking change is low practical impact (strategy config loads are infrequent) — keep the diff minimal.

## References

- Audit memory `project_native_audit_findings.md` (2026-06-10, item 9 / W-1, W-3, S-1, S-3).
- `rust-android-jni` skill (AttachCurrentThread discipline).
