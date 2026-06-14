---
title: "Harden JNI callbacks: scoped thread-attach, nullable array returns, drop runBlocking"
type: task
status: done
area: android
priority: medium
owner: unassigned
parent: epic-june-2026-audit-remediation
blocks: []
blocked_by: []
created: 2026-06-10
updated: 2026-06-14
source_wiki_pages: []
linked_task: null
---

## Motivation

The 2026-06-10 JNI boundary audit confirmed the boundary is solid (all exports panic-contained via `ffi_boundary`, every signature cross-checks, protect() invariant holds) but found three hardening gaps:

1. **Permanent (non-daemon) thread attach** — JNI callbacks on long-lived runtime threads used `JavaVM::attach_current_thread`, which in jni 0.22.4 requests a *permanent* attachment (detached only when the thread exits). All attaches in jni 0.22.4 are non-daemon (`sys_attach_current_thread` always calls `AttachCurrentThread`), and a non-daemon attached thread blocks `DestroyJavaVM`. Audit named 5 sites; source review at HEAD found **8 production callback sites** (relay/warp/amneziawg/proxy readiness, warp/amneziawg vpn_protect, the protect adapter, tunnel flow-attribution).

2. **Non-nullable Kotlin returns vs null Rust panic sentinel** — `jniGetStats` (Kotlin `LongArray`, Rust sentinel `null_mut()`), `luaListStrategies`/`luaLoadedScriptPaths` (Kotlin `Array<String>`, Rust sentinel null). A contained panic returns null, which marshals to a null Kotlin value and throws NPE inside `TunnelStats.fromNative` / call sites.

3. **`runBlocking` on the Lua mutation lock** — `ProcessGlobalStrategyEngineBindings.withProcessGlobalLuaMutationLock` wrapped a coroutine `Mutex` in `runBlocking`, occupying an IO-dispatcher thread for the lock duration under concurrent strategy loads.

## Premise corrections (verified against source at HEAD `d3bdbf1b6`)

- **`attach_current_thread_as_daemon` does not exist in jni 0.22.4.** The 0.22 redesign deliberately removed daemon attach (`java_vm.rs`: "jni-rs doesn't directly support attaching or detaching 'daemon' threads"). The literal one-word swap would not compile. The teardown-safe equivalent is **`attach_current_thread_for_scope`**, which detaches the thread when the callback returns, so a long-lived runtime thread is never left permanently (non-daemon) attached.
- **8 production sites, not 5.** The three other `attach_current_thread` matches (`ripdpi-android-bridge-support/src/lib.rs:259`, `android-support/src/tests.rs`, `ripdpi-tunnel-android/src/session/jni_tests.rs`) are test-only helpers on an in-process test JVM and are correctly left as permanent attach.
- **Gap 3 went with a JVM `ReentrantLock`, not `suspend`.** `luaLoadScript`/`luaReloadConfig` are direct `external` (JNI) functions and `external suspend fun` is illegal in Kotlin; a `suspend` interface would force either a coordinated JNI symbol rename (Rust + ELF allowlist) or a raw-delegate refactor propagating through the Compose UI layer. A process-global `ReentrantLock` removes `runBlocking` and the parked-thread problem with no suspend/UI/JNI churn.

## Implemented change

1. `attach_current_thread` → `attach_current_thread_for_scope` at all 8 production callback sites.
2. `jniGetStats` external → `LongArray?`, coalesced `?: LongArray(0)` in `getStats`. `StrategyEngineBindings.luaListStrategies`/`luaLoadedScriptPaths` → `Array<String>?`; coalesced at the app-facing boundaries (`NativeStrategyConfigRuntime`, `StrategyProbeService`, instrumented test). No Rust changes, no JNI symbol diff (nullability is not part of the JVM descriptor).
3. `withProcessGlobalLuaMutationLock` uses a process-global `ReentrantLock`; `runBlocking`/coroutine `Mutex` removed.

## Acceptance criteria

- [x] PR confirms current state at all cited sites (and corrects the count to 8).
- [x] All production callback attach sites use the scoped variant (`attach_current_thread_for_scope`).
- [x] The three array-returning JNI methods cannot NPE on a contained panic (nullable + coalesce).
- [x] `withProcessGlobalLuaMutationLock` no longer uses `runBlocking`; no coroutine `Mutex` parked via `runBlocking` on the IO path.
- [x] `./gradlew :core:engine:testDebugUnitTest` green (plus `:core:data` / `:core:service` / `:core:diagnostics`); native crates `cargo check --target aarch64-linux-android` clean.

## References

- Audit memory `project_native_audit_findings.md` (2026-06-10, item 9 / W-1, W-3, S-1, S-3).
- `rust-android-jni` skill (AttachCurrentThread discipline).
