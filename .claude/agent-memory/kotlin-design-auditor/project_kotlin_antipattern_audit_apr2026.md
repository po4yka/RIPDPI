---
name: kotlin_antipattern_audit_apr2026
description: Results of the April 2026 Kotlin anti-pattern audit covering memory leaks, coroutine cancellation, flow discipline, foreground service, logging, and serialization stability across 759 Kotlin source files.
type: project
---

Audit completed 2026-04-19 against main branch (c157ef73).

**Why:** New anti-pattern rules added to CLAUDE.md/AGENTS.md required a first-pass baseline scan.
**How to apply:** Use these findings as the baseline for trend tracking in future audits. Re-check the HIGH items first.

## Key Findings

### HIGH severity
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/RipDpiVpnService.kt:241` — `Logger.v { "DNS: $dns" }` logs resolver IP unconditionally, present in release builds.
- `core/diagnostics-data/.../DiagnosticsDatabase.kt:17,28,61` — Room `@Entity` classes also annotated `@Serializable` with non-defaulted, non-nullable primary-key fields (`id`, `packId`, `sessionId`, etc.); Room schema evolution is managed destructively (`fallbackToDestructiveMigration`), so this is low immediate risk but the dual serialization is a landmine.
- `core/detection/.../DetectionHistoryStore.kt` — `Context` stored in a plain class constructor (no `@ApplicationContext` qualifier, no Hilt injection); class is instantiated manually with `context` parameter — could hold an Activity context if caller passes one.

### MED severity
- `catch (e: Exception)` without CancellationException rethrow in 14 sites across suspend functions: `XrayApiClient.kt:78`, `IfconfigClient.kt:84`, `CommunityComparisonClient.kt:40`, `GeoIpChecker.kt:37`, `IndirectSignsChecker.kt:178/249/304/414/494/582`, `Tun2SocksTunnel.kt:115`, `NotificationUtils.kt:40`, `CrashReportReader.kt:24`, `OnboardingConnectionTestRunner.kt:48`.
- `SharingStarted.Eagerly` in 5 stateIn/shareIn sites: `MainViewModel.kt:297,368`, `ActiveConnectionPolicyStore.kt:86`, `DefaultDiagnosticsTimelineSource.kt:73`, `DiagnosticsBoundarySources.kt:110` — all use `@ApplicationScope` (app-lifetime), so no leak, but Eagerly prevents natural suspension.
- `DetectionCheckScheduler.kt:128-135` — `NotificationChannel` created inside `postNotification()`, which is called from the foreground notification path. Channel creation should happen at app/service startup, not on every notification post.
- `ServiceManager.kt:66,75,92,101` — `ContextCompat.startForegroundService()` call sites have no `ForegroundServiceStartNotAllowedException` handling (API 31+ requirement).

### LOW severity
- `RelayCredentialRecord` persisted to SharedPreferences as JSON with sensitive fields (`masqueAuthToken`, `cloudflareTunnelToken`, `masqueClientPrivateKeyPem`). No `@Transient` or encryption annotation. This is by design (encrypted storage handled externally) but worth tracking.
- `ScreenStateObserver` uses `callbackFlow` + `awaitClose { unregisterReceiver }` — correct pattern, no issue.
- No `GlobalScope`, no `runBlocking` on main, no `withContext(NonCancellable)` outside finally — clean.
- No `setAllowFileAccess(true)` or `MODE_WORLD_READABLE` found.
- No `conflate()` misuse found.

## Confirmed Clean
- Context in singletons: all `@Singleton` classes use `@ApplicationContext` qualifier correctly (30+ checked).
- BroadcastReceiver registration: only `ScreenStateObserver` registers a receiver; `awaitClose` correctly unregisters it.
- Closeable/use{}: extensive correct `use {}` patterns throughout; no bare InputStream/Socket found.
- TypedArray: not used anywhere in main source.
- withContext(NonCancellable): zero occurrences outside finally (zero occurrences total).
- GlobalScope: zero occurrences.
- Log.d/v / Timber.d/v: project uses Kermit `Logger.d/v` — only one sensitive value found (`DNS: $dns` in VpnService).
