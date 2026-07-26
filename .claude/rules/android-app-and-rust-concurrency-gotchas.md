---
paths:
  - "app/**/*.kt"
  - "core/**/*.kt"
  - "native/rust/**/*.rs"
---

# Android app layer and Rust concurrency gotchas

These are conventions and pitfalls confirmed against live source that are not yet documented in `.claude/rules/`, `AGENTS.md`, or `docs/architecture/`. They split across two areas: Rust-side concurrency primitives shared by the native crates, and Kotlin/Compose app-layer patterns in `app/` and `core/`. `docs/rust-soundness-policy.md` is the CI-enforced authority for deeper Rust soundness topics (lock ordering, callback reentrancy, FFI panic containment) — read it first for anything that sounds like it belongs there; the items below are the gaps that remain outside its scope.

### Rust: ordering for cross-thread lifecycle atomics

Use `Release`/`Acquire` when an atomic transition publishes or consumes other
memory and document that invariant at the site. `Relaxed` is valid for pure
counters and for flags whose correctness does not depend on visibility of
separate state; synchronization-backed cancellation tokens do not need a
blanket ordering rule here. Add a short comment when the ordering supplies a
specific happens-before edge.

### Rust: isolate panics in bare `thread::spawn` workers that never cross the FFI boundary

`docs/rust-soundness-policy.md`'s "FFI panic-unwind containment" section requires `catch_unwind` on every function exported via an `extern` ABI or handed to foreign code as a callback pointer — but a bare `thread::spawn` worker that updates shared completion or lifecycle state and never itself crosses that boundary is out of that section's scope. `ripdpi-monitor-engine/src/session/worker.rs`'s `spawn_scan_worker` is the canonical example already in the workspace: it wraps the spawned closure in `std::panic::catch_unwind(AssertUnwindSafe(...))` and records a terminal progress state from the `Err` branch, so a panic still reaches the polling caller as a defined outcome instead of an apparent hang. Apply the same shape to any new long-running `thread::spawn` worker that publishes shared state — `native/rust/crates/ripdpi-diagnostics-transport/src/transport/address.rs`'s DNS-resolution worker is one existing spawn site that does not yet follow it and is worth revisiting.

### Kotlin/Compose: cache system-service lookups

`LocalContext.current.getSystemService(...)` re-resolves the service through the `Context` binder call on every recomposition if called directly in a composable body. The established pattern in this codebase — see `HomeScreen.kt`, `DomainBypassListScreen.kt`, and `DiagnosticsBottomSheets.kt`, all of which wrap the call as `remember(context) { context.getSystemService(...) }` — keeps the lookup to once per `Context` instance. Keep following it for any new composable that needs a system service; a direct unwrapped call is a regression, not the current state.

### Kotlin: audit every catch/when site when reshaping an exception hierarchy

When a `when` or `catch` block branches on a sealed exception hierarchy's concrete subtypes (as the many `is NativeError.AlreadyRunning` / `is NativeError.NotRunning` / `is NativeError.SessionCreationFailed` checks across `core/engine` do today), adding a new subtype or changing which base class an existing subtype extends means every branch site needs a pass, not just the throw site — a stale `catch (e: SomeOldType)` silently falls through to a more generic handler instead of the intended one. Grep every call site before shipping the change, and keep the classification logic centralized in one function rather than duplicated per call site.

### Compose testing: Robolectric viewport and Hilt default-param gotchas

Robolectric's default test viewport (roughly 320x470px) means off-screen `LazyColumn` items are never composed, so `assertIsDisplayed()` fails on below-fold items even though they logically exist in the list. Use `performScrollToKey(key)` to bring an item into view before asserting on it — already the pattern in `AdvancedSettingsScreenCharacterizationTest.kt` — since `performScrollToNode(hasText(...))` fails when the node has not been composed yet, or set `Modifier.height(2000.dp)` on the `LazyColumn` under test so every item composes without scrolling. Several section headers render through `.uppercase()` (see `DetectionResultCards.kt`, `DetectionHistoryCommunityCards.kt`), so assertions on those headers must match the uppercased string, not the source string.

Hilt/Dagger ignores Kotlin default parameter values on `@Inject` constructors, which surfaces as a `MissingBinding` error at compile or runtime rather than falling back to the default. This codebase's existing workaround — see `BackupRestoreViewModel.kt`'s `@Named("appVersionName")` parameter and its matching `@Provides` — is to add `@Named("paramName")` on the constructor parameter plus a matching `@Provides` method in the Hilt module; tests can still pass the parameter by name as normal.

### Android manifest: backup deny-all is the whole privacy contract

`android:allowBackup="false"` in `app/src/main/AndroidManifest.xml`, combined with the explicit deny-all `<exclude>` rules in `app/src/main/res/xml/backup_rules.xml` (pre-API-31 full-backup-content) and the `cloud-backup` / `device-transfer` blocks in `app/src/main/res/xml/data_extraction_rules.xml` (API 31+), is the defense-in-depth for this privacy-sensitive VPN app — both files currently exclude every domain (`root`, `file`, `database`, `sharedpref`, `external`). An empty or commented-out rule file means allow-everything by default, not deny-by-default. Any new persisted field — a new `SharedPreferences` key, database table, or file under app-private storage — is a potential new leak surface until it is confirmed to fall under one of these existing domain-wide excludes; do not narrow these exclude blocks without an explicit, reviewed reason.

### Navigation: keep the bottom-bar fallback set in sync with the nav graph

`RipDpiNavHost.kt`'s `selectedTopLevel` picks the highlighted bottom tab via an exact stable-route match plus the explicit `configSubRouteStableKeys` fallback set — it does not walk `NavDestination.hierarchy`. Locate both symbols by name instead of relying on line-number snapshots. Whenever a new sub-destination is added inside a nested nav graph (for example, a new screen under the Config tab), add its stable route key to `configSubRouteStableKeys` in the same change, or the parent tab goes unselected when a user navigates into that sub-screen.

### Cross-references

- `docs/rust-soundness-policy.md` § "Callback reentrancy" and § "Deadlock from nested locks" — the workspace's canonical, CI-enforced treatment of same-lock and cross-lock reentrancy deadlocks (including the historical issue #29 fix and its `lock_held_across_callback` scanner); consult it before writing any new code that calls back into a method while already holding a `Mutex`/`RwLock`, including the same-thread self-method-reentrancy shape that motivated this note.
- `docs/rust-soundness-policy.md` § "FFI panic-unwind containment" — the CI-enforced `catch_unwind` requirement at every `extern` export and foreign-invoked callback; the worker-thread panic-isolation note above extends the same discipline to pure background workers that never cross that boundary.
- `llm-rust-prompts.md` — sentinel-pattern list for AI-generated Rust review gates.
- `android-vpn-lifecycle.md` — thread naming and state-persistence rules for the same native worker threads discussed above.
