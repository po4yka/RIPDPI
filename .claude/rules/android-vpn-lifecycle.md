---
paths:
  - "app/**/*.kt"
  - "core/service/**/*.kt"
  - "core/engine/**/*.kt"
  - "native/rust/crates/ripdpi-*-android/**/*.rs"
  - "native/rust/crates/ripdpi-tunnel-*/**/*.rs"
---

## Android VPN Service lifecycle invariants

Android's process model imposes constraints that Rust code rarely encounters elsewhere: `SIGKILL` from LMK with no Drop running, Doze freezer-cgroup suspension, App Standby Buckets demoting Foreground Services, JNI-shutdown deadlocks. This rule documents the invariants that Rust code MUST honor.

### State persistence — assume process death

Low Memory Killer (LMK) terminates the process with `SIGKILL`. NO Drop runs. NO `tokio::runtime::Runtime::shutdown_background()` runs. Any state required across a kill cycle MUST be persisted via:

- DataStore for settings or Room for diagnostics/runtime history — durability is owned by the repository layer.
- For small native state only (`< 1 KiB`, infrequent updates): write a temporary file, explicitly `fsync`, then atomically rename it.

Writing required state without the repository's durable store or an fsynced atomic file is FORBIDDEN. The next LMK kill may discard it.

### Tokio runtime shutdown — avoid the self-deadlock

If a JNI method runs inside a tokio task (via `block_on`), and that method tries to `Runtime::shutdown_background()` on the same runtime, the runtime waits for itself to drain — deadlock. The Mullvad canonical pattern:

1. `Service.onDestroy()` → JNI callback `daemon_shutdown()`.
2. `daemon_shutdown` sends a `Shutdown` command over an mpsc channel and returns immediately.
3. The tokio main loop receives `Shutdown`, completes in-flight work, drops the runtime from OUTSIDE a tokio context.
4. `JNI_OnUnload` then runs cleanly.

NEVER call `runtime.block_on(runtime.shutdown_*)` from a JNI method.

### Foreground Service contract

`startForeground(NOTIFICATION_ID, notification)` must be called within the
platform deadline measured from `startForegroundService()` launch to foreground
promotion, not from `onStartCommand` returning. Promote immediately on service
entry; do not spend the budget on config or network work. The notification must
remain visible.

Worker threads must set a readable name:
- pthread: `pthread_setname_np(thread, "ripdpi-...")`.
- tokio: `Builder::new_multi_thread().thread_name_fn(|| { /* atomic counter + "ripdpi-tokio-worker-N" */ })`.

Unnamed threads in logcat are a debugging tax — enforce naming in `JNI_OnLoad` or runtime construction.

### Doze and App Standby Buckets

With Android 6+ Doze: timer-based alarms via `AlarmManager.setExactAndAllowWhileIdle` may be deferred. WorkManager periodic tasks may be skipped. App Standby Buckets (Android 9+) further demote inactive apps.

Rule: durable state may use DataStore or Room, but it must be persisted on every
significant transition, not only on a periodic timer. A timer that misfires loses
policy updates; an event-driven save captures each transition regardless of Doze.

### Signal handling

Signal disposition and per-thread signal masks are different contracts: a new pthread inherits the creating thread's signal mask, while setting `SIGPIPE` to `SIG_IGN` changes the process-wide disposition. RIPDPI does not depend on whichever mask a JVM or runtime thread happens to inherit. Every Android JNI cdylib calls `android_support::ignore_sigpipe()` from `JNI_OnLoad`, which installs the process-wide ignored disposition through `nix::sys::signal::signal(Signal::SIGPIPE, SigHandler::SigIgn)` before worker runtimes start. Keep that call in every loader entry point and treat per-write `MSG_NOSIGNAL` as defense in depth, not as the lifecycle contract.

An unhandled SIGPIPE terminates the process by signal; it is not a Rust panic and does not unwind through JNI. Diagnose it as a native signal death rather than looking for a panic or `JNI_OnUnload` event.

### Process death simulation in tests

CI matrix MUST include `adb shell am kill <package>` mid-session and verify the next session reconstructs state correctly. Without this, persistence regressions ship unnoticed.

### Detecting an Android 17 memory-limiter kill

Android 17 introduces a per-app memory cap: instead of letting one bloated process push well-behaved cached apps out of memory, the OS hard-caps the offender. A persistent foreground VPN service (RIPDPI) is exactly the kind of privileged, LMK-shielded process the cap now targets.

When the limiter caps the process, the kill surfaces through `ApplicationExitInfo` (API 30+) as `REASON_OTHER` with a `getDescription()` string containing `"MemoryLimiter:AnonSwap"`. On startup, read the recent exits and record a diagnostics event so a memory-cap kill is distinguishable from a crash or an ordinary background kill:

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    val am = context.getSystemService(ActivityManager::class.java)
    am?.getHistoricalProcessExitReasons(context.packageName, /* pid = */ 0, /* maxNum = */ 16)
        ?.filter { it.reason == ApplicationExitInfo.REASON_OTHER &&
            it.description?.contains("MemoryLimiter:AnonSwap") == true }
        ?.forEach { /* persist as a diagnostics event, keyed by (timestamp, pid) for idempotent re-scan */ }
}
```

Rules:
- Guard every call behind `SDK_INT >= R` — `ApplicationExitInfo` does not exist below API 30 and `minSdk` is 27.
- Key the recorded event by a deterministic id derived from `(timestamp, pid)` so re-scanning the same history on every later launch is idempotent (Room `OnConflictStrategy.REPLACE`).
- Do the read off the main thread (the diagnostics store is suspend/`Room`); failure-isolate it so a read error never affects startup.
- Implementation: `DefaultLastExitInspector` in `core/diagnostics`, invoked from `AppStartupInitializer.initialize()`. Pairs with `onTrimMemory` shedding (`TrimmableCache`) which lowers the chance of being capped in the first place.

### Cross-references

- `rust-async-internals` skill — JNI-to-async bridge canonical pattern.
- `rust-jni` skill — JNI panic safety and thread attachment discipline.
- `network-fingerprint-privacy.md` rule — state that must survive kill includes per-network policy.
