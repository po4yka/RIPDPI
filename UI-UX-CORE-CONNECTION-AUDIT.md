# UI/UX & Core↔UI Connection Audit — RIPDPI

Date: 2026-06-12
Scope: `app/src/main/kotlin/com/poyka/ripdpi/` (Compose UI), `:core:service`, `:core:data`, `:core:engine` boundary, `native/rust/crates/ripdpi-relay-core` (relay surface only)
Method: 4 parallel read-only scans (UI surface map, core↔UI trace, 2 Compose category scans) + manual spot-verification of every load-bearing claim. Companion scored report: `COMPOSE-AUDIT-REPORT.md`.

---

## 1. Core → UI state pipeline — assessment: SOUND

The pipeline is a single in-process push path, with one pull stage at the JNI boundary:

```
Rust core ──(JNI poll, 1 s fg / 5 s bg)──> ProxyTelemetryCoordinator / VpnRuntimeTelemetryReporter
        ──> ServiceStatusReporter ──> DefaultServiceStateStore (@Singleton)
        ──> StateFlow<status> / StateFlow<ServiceTelemetrySnapshot> / SharedFlow<ServiceEvent>
        ──> ViewModels (collectAsStateWithLifecycle) ──> Compose screens
```

- No AIDL/Binder/broadcast layer; service and app share one process, one Hilt singleton (`core/data/runtime-state/.../ServiceStateStore.kt:90-106`). No rebind races possible.
- Process death resets `ServiceStateStore` to `Halted` (hardcoded initial value, `ServiceStateStore.kt:106`) — no stale "Running" state can survive a SIGKILL. [OK]
- Speed (bytes/s) is computed UI-side from poll deltas in `MainConnectionActions` — accurate, one poll-interval lagged. [OK]
- `onRevoke()` → `FailureReason.PermissionLost("VPN")` → `ServiceEvent.Failed` → user-visible error. Correctly wired (`VpnServiceSessionLifecycle.kt:33-43`). [OK]
- Android 17 memory-limiter kill detection conforms to `android-vpn-lifecycle.md` (`AppStartupInitializer` → `lastExitInspector.recordRecentMemoryLimiterExits()`). [OK]

### Risks

| # | Risk | Evidence |
|---|------|----------|
| R1 | "Live" diagnostics can be up to 5 s stale after screen-on (background poll interval), with no staleness indicator despite `RipDpiStaleDataBadge` existing in the component library | `ProxyTelemetryCoordinator.kt:24-26`, `DiagnosticsLiveHighlights.kt` |
| R2 | Boot/LMK resume shows `Halted` for 1–3 s while the service restarts; no intermediate "Reconnecting" status is written to `ServiceStateStore` by the boot-resume path | `BootSessionRecorder`, `ServiceStateStore.kt:106` |
| R3 | Whether `BootSessionStateStore` persists `wasRunningAtUpdate` with fsync before an LMK kill is unverified — if not, auto-resume after kill silently fails (see `.claude/rules/android-vpn-lifecycle.md` state-persistence rule) | `BootSessionRecorder` write path — needs verification |

## 2. Error propagation — assessment: MOSTLY SOUND

Two verified end-to-end failure paths reach the user with typed, actionable messages:

- **Native start failure**: `jniStart` non-zero → `ProxyStartupFailureResolver` → `FailureReason.NativeError("Proxy exited with code N")` → `ServiceEvent.Failed` → snackbar with `R.string.failed_to_start` + reason (`MainConnectionActions.kt:157-159`). [OK — though the numeric exit code is developer-facing, not user-actionable]
- **VPN permission revoked**: `onRevoke` → `PermissionLost("VPN")` → user-visible. [OK]

Weak spots:

- **W1**: `ServiceStartRejectionReason.ForegroundServiceBlocked` with a blank native message falls back to the generic `onboarding_validation_failed_generic` string — no actionable guidance, no retry differentiation (`activities/ServiceStartRejectionUiMapper.kt:39-42`).
- **W2**: Release builds strip JNI exception detail (`sanitize_error_message`, JNI_CONTRACT.md §7) — config-rejection errors reach users as generic labels. Acceptable security posture, but pair it with error *codes* the user can quote.
- **W3**: ViewModel-composed snackbar messages bypass localization: `BlockcheckViewModel.kt:190` and `StrategyTunerViewModel.kt:202` show the literal English `"Strategy applied"` in an 8-locale app.

## 3. Coverage gaps: core features without (honest) UI

- **G1 — SSH relay is a UI-complete, core-stub feature.** `SshProfileScreen`/`SshProfileViewModel` offer a full editor (auth types, host-key policy), config validates and persists — but the Rust builder intentionally fails every session with `Unimplemented` because the relay layer has no protected outbound connector to hand `russh` (`native/rust/crates/ripdpi-relay-core/src/backend/builder/builders/ssh.rs:9-16`). The UI shows **zero** warning (verified: no experimental/unavailable string anywhere under `ui/screens/ssh/`). A user configures SSH, taps connect, and gets a runtime `NativeError`. Until the russh unpin/connector work lands (tracked in `docs/tasks`), the editor should carry a "not yet functional" banner or be feature-flagged off.
- **G2 — Mieru is implemented but unverified.** The wire protocol is real (`builders/mieru.rs:12-16`: XChaCha20-Poly1305, open-session handshake), but "on-wire interoperability with an upstream mieru server is not yet verified against a live server." No UI caveat. Lower severity than SSH; an "experimental" tag would be honest.
- **G3 — Root mode is buried.** `root_mode_enabled` features surface only inside the Diagnostics tools panel; there is no first-class Settings toggle. Users on rooted devices are unlikely to discover `FakeRst`/`MultiDisorder`/`IpFrag2`.
- **G4 — Per-network policy is write-only from the user's perspective.** Remembered networks appear as a count badge only; there is no screen to inspect or delete individual per-network policies. Privacy-conscious users cannot audit what was learned (relevant to `network-fingerprint-privacy.md` posture).
- **G5 — Native telemetry surfaced only partially.** `NativeRuntimeSnapshot` carries DNS counters (`dnsQueriesTotal`, cache hits/misses, failures) and `latencyDistributions` histograms; no Diagnostics panel renders the latency histograms, and the DNS counters' UI surface was not found. Bytes/packets/trends are well covered.

## 4. UI without core wiring / dead UI

- **D1 — Orphaned screen**: `CensorshipSignatureScreen` (`ui/screens/diagnostics/CensorshipSignatureScreen.kt:61`) is a public `@Composable` with zero call sites in the app — unreachable, and contains hardcoded English (`"Censorship signatures"`, line 111).
- **D2 — Orphaned component**: `RipDpiCommandPalette` (`ui/components/feedback/RipDpiCommandPalette.kt`) has no call sites outside its own file; also carries unlocalized strings (`"Type a command…"` :117, `"No matching command"` :148). Either wire them up or delete them — dead UI accumulates localization and maintenance debt.
- Correction to a tempting false positive: `ui_persona` (proto field 409) **is** consumed — by `SettingsUiStateFactory`, `ConfigViewModel`, and the config screens — it is a UI-side customization field, not a dead setting.
- No TODO/stub settings found that write to DataStore without a consumer.

## 5. UX findings (user-facing quality)

### Localization (app ships 8 locales; `MissingTranslation` is a CI error, but only for `strings.xml` keys — these bypass it)
- **U1**: `ClipData.newPlainText(label, …)` labels hardcoded in English across 9 files — the label is user-visible in the Android 13+ clipboard overlay. Examples: `DomainBypassListScreen.kt:113` ("RIPDPI domains"), `DetectionCheckScreen.kt:682,703,771`, `DiagnosticsToolsSection.kt:542`, `ProfileShareRoute.kt:34,36`.
- **U2**: ViewModel-built strings (`"Strategy applied"` — see W3 above).
- **U3**: `RipDpiNavRail.kt:101` — brand badge `contentDescription = "RIPDPI"` hardcoded; use `R.string.app_name` for consistent TalkBack output.

### State persistence across rotation / process death
- **U4**: Diagnostics pager uses `rememberPagerState` under plain `remember` (`DiagnosticsRoute.kt:56`) — on rotation the pager flashes to page 0, then animates back to the ViewModel-restored section (`LaunchedEffect(selectedSection)` at :85-87).
- **U5**: `StrategyConfigRoute.kt:48` — `configText by rememberSaveable { mutableStateOf(uiState.desync.chainDsl) }` captures the DSL once with no key; if the chain DSL changes elsewhere, the editor silently shows a stale draft.
- **U6**: Logs screen scroll position not saveable across rotation.

### Flow & feedback
- **U7**: Battery-optimization guidance lives only inside Settings (`SettingsPreferencesScreen.kt:63-69`); never proactively offered during onboarding or after a background-kill — the user category most affected will not find it.
- **U8**: `MainActivityContent.kt:50-96` renders nothing when `startupState.isReady == false`; if the splash is dismissed early (warm relaunch), the user sees a blank themed window with no progress indicator.
- **U9**: Config screen has no loading state for the initial profile list (`ConfigViewModel` exposes no `isLoading`); contrast with History which uses `RipDpiEmptyStateCard`.
- **U10**: `SettingsPreferencesScreen.kt:62-63` reads `isBatteryOptimizationIgnored()` once inside keyless `remember {}` — stale if the exemption changes while visible; never refreshed on resume.

### Strengths worth keeping
- Type-safe `@Serializable` navigation for all 43 routes; deep links (`ripdpi://connect|config|diagnostics|settings`) and Quick Settings tile/widget/shortcut entry points all converge on one intent factory.
- Error events are typed (`FailureReason` sealed hierarchy with 9 variants) and all route to a single user-visible snackbar path — no swallowed failures found in the traced paths.
- ~60-component design-system library (`ui/components/`) with consistent modifier convention, light+dark previews on nearly all files, and token-routing through `RipDpiThemeTokens` enforced by unit tests.

## 6. Prioritized recommendations

1. **Gate or label the SSH editor** (G1): add a non-dismissable "not functional yet" banner (string in all 8 locales) or hide the route behind a debug flag until the protected-connector work lands. Cheapest honest fix; prevents guaranteed user-visible failure.
2. **Move clipboard labels and ViewModel snackbar strings to resources** (U1, U2/W3): mechanical, ~11 call sites, closes the largest localization bypass.
3. **Surface staleness + reconnecting states** (R1, R2): show `RipDpiStaleDataBadge` (already in the library) on Diagnostics live panels when the last snapshot is older than 2× the poll interval; write a `Reconnecting` status from the boot-resume path.
4. **Per-network policy inspector** (G4): list remembered networks (scope-hash keyed, no raw identifiers per `network-fingerprint-privacy.md`) with per-entry delete.
5. **Delete or wire orphans** (D1, D2): `CensorshipSignatureScreen`, `RipDpiCommandPalette`.
6. **Verify `BootSessionStateStore` fsync** (R3) against the `android-vpn-lifecycle.md` persistence rule — if `serde_json`-style write-without-fsync, auto-resume is unreliable under LMK.
7. **Proactive battery-optimization prompt** (U7) after the first background service death, not only in Settings.
