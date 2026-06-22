# AmneziaWG Standalone Transport — Implementation Blueprint

Companion to `audits/claude-amneziawg-operability-audit.md` and the open task `docs/tasks/issues/wire-standalone-amneziawg-profile-transport.md` (status: doing). This is the implementation plan for the audit's **F1 (P0)**, **F2 (P0)**, **F3 (P1)** — making a standalone AmneziaWG (AWG) profile run as a real system VPN.

- **Base commit:** `e187dded4`. All file:line refs are from there (verified via a clean worktree + a 6-agent subsystem map).
- **Verification constraint:** this is a VPN data-path change. It **must be device/emulator + real-AWG-endpoint verified** — the offline CI here cannot build `:app` (uncached `androidx.camera`/`zxing`). Treat "compiles + unit-green" as necessary, not sufficient.
- **Scope discipline:** the AWG **native** side (`ripdpi-amneziawg-android`), the **data plane** (`ripdpi-warp-core`), **persistence** (`AwgProfileRepository`/`AwgCredentialStore`/DB v2), the **editor**, and the **supervisor** all already exist and are tested. This blueprint adds **only Kotlin service-layer wiring** — **no Rust change** is required for the P0.

---

## 0. The one decision that determines everything

**AWG is a whole-tunnel *provider that replaces* the native composition — mirror the embedded-Xray delegate seam, do NOT add AWG to `SharedProxyRuntimeStack`.**

Why this is load-bearing (two mappers flagged the wrong path as a defect):

- `SharedProxyRuntimeStack.start()` (`SharedProxyRuntimeStack.kt:13-27`) **always** starts `proxyRuntimeSupervisor` and returns *its* `LocalProxyEndpoint`; relay/WARP are *additive upstream layers under* that proxy SOCKS. If AWG were added there, the proxy supervisor would still start and produce a **competing endpoint**, and both would contend for the loopback SOCKS (AWG hardcodes `127.0.0.1:10808` in `AmneziaWgRuntimeConfigResolver.kt:62-73`).
- The correct precedent is **embedded-Xray**: `VpnRuntimeCompositionCoordinator.startComposedRuntime` (`VpnRuntimeCompositionCoordinator.kt:121-159`) consults an optional `providerDelegate` **first** (`:128`); the native proxy/warp/relay path runs **byte-identical** only when the delegate **declines** (returns `false`). Xray, like AWG, runs a *separate* native runtime and then drives `vpnTunnelRuntime` against *its own* `LocalProxyEndpoint`.

**Therefore: add an `AwgConnectFlowDelegate` that takes over the session when AWG is durably selected, exactly as `XrayConnectFlowDelegate` does — and the proxy stack is never started in that case.**

---

## 1. Target architecture (data flow once wired)

```
AmneziaWgProfileViewModel.onConnect()                            [app]
   1. profileRepository.save(...)            (already present; mints stable awg-<UUID>)
   2. awgProviderSelectionStore.update(active=true, activeProfileId=<id>)   ← NEW durable selection
   3. serviceController.start(Mode.VPN)      (existing Intent entry; VpnService.prepare consent)
        │
RipDpiVpnService.onStartCommand                                   [core:service]
   • startForeground(...) ≤5s                (reused verbatim)
   • VpnServiceSessionLifecycle.createShellDelegate():
       - VpnProtectSocketServer.start()      (UDS+SCM_RIGHTS, for the tun2socks worker)
       - VpnNativeProtectRegistration.register(service)  ← now registers proxy+warp+AWG GlobalRefs (F1)
        │
VpnServiceRuntimeCoordinator.start → VpnRuntimeCompositionCoordinator.startComposedRuntime
   • awgProviderDelegate.tryStart(session) ──► if AWG selected:                ← NEW seam, FIRST
       a. load SavedAwgProfile via AwgProfileRepository.load(activeProfileId)  (reload secrets by id)
       b. AmneziaWgRuntimeConfigResolver.resolve(request) → ResolvedRipDpiAmneziaWgConfig
       c. AmneziaWgRuntimeSupervisor.start(...)   ← now @ServiceSessionScope, not application scope
          └─ RipDpiAmneziaWg.start → libripdpi-amneziawg.so
                 • protect slot already armed by F1 → UDP/WS carrier bind SUCCEEDS
                 • exposes loopback SOCKS 127.0.0.1:10808
       d. vpnTunnelRuntime.start(localProxyEndpoint=127.0.0.1:10808, tunnelMtu=1330)
          └─ establishes TUN (0.0.0.0/0, ::/0) → Tun2Socks → AWG loopback SOCKS
       return true (handled; proxy/warp/relay stack NOT started)
   • else → providerDelegate (Xray) → native proxy stack   (unchanged)
        │
Disconnect / onRevoke / LMK kill
   • VpnSupervisorExitHandler.handleAwgExit (mirror handleWarpExit) → stop()
   • VpnNativeProtectRegistration.unregister() releases all three GlobalRefs
   • durable selection cleared on EXPLICIT stop only; START_STICKY re-delivery re-reads it → resumes AWG
```

---

## 2. Pre-flight de-risk checks (do these *before* writing code)

| # | Check | Command / where | Why it matters |
|---|---|---|---|
| D1 | AWG `.so` exports the JNI protect symbols & uses the **same generation-token contract** as proxy/warp | `nm -D libripdpi-amneziawg.so \| grep jniRegisterVpnProtect`; read `RipDpiAmneziaWg.kt:104-131` token doc vs `JNI_CONTRACT.md §8` | Determines whether AWG can join `VpnNativeProtectRegistration` as a third token, or needs a direct-JNI controller like `VpnServiceXrayProtectController` |
| D2 | AWG loopback SOCKS supports **UDP ASSOCIATE** | `buildTun2SocksConfig` sets `socks5Udp="udp"` unconditionally (`RipDpiVpnService.kt:276`); confirm `ripdpi-warp-core::socks` `handle_udp_associate` is reachable on the AWG runtime | If AWG SOCKS is TCP-only, UDP app traffic black-holes through the TUN |
| D3 | Supervisor-start-before-tun2socks **ordering** | `AmneziaWgRuntimeSupervisor.start` binds the SOCKS listener; `vpnTunnelRuntime.start` must attach **after** readiness (`awaitReady`) | tun2socks dialing 10808 before the listener is up = startup race |
| D4 | AWG runtime **MTU** honored at 1330 | `RipDpiAmneziaWgRuntime.kt:107 DefaultAmneziaWgTunnelMtu=1330` vs `VpnTunnelNetworkPolicy` default 1500 | Routing 1500-MTU TUN packets into a 1330 WG tunnel fragments/black-holes |
| D5 | DNS ownership | does the WG tunnel carry interface DNS, or must the TUN run `mapDNS`/Tun2Socks DNS (`RipDpiVpnService.kt:255-317`)? | Decides whether the AWG branch passes the full DNS plan to `vpnTunnelRuntime.start` |

**Recommended answers** (resolve in review; defaults chosen for minimal blast radius):
- D1 → **same token contract** (the externals are byte-identical to WARP's; `vpn_protect.rs:99-119` mirrors `ripdpi-warp-android`). Join the triplet.
- D5 → **reuse the proxy/VPN DNS plumbing** (pass the same DNS plan); AWG's interface DNS is an inner concern of the WG netstack and does not conflict.

---

## 3. Phased plan (each phase = one or more atomic commits)

> Phases are ordered so each commit compiles; the feature only becomes *operable* at the end of Phase 4. Phase 1 alone is inert until Phase 3 (commit them together or keep Phase 1 first with a `// armed; consumer lands in Phase 3` note).

### Phase 1 — P0 protect arm *(Kotlin-only, low-risk, no Rust)*

| File | Change |
|---|---|
| `core/service/.../services/VpnNativeProtectRegistration.kt:18-49` | Add `private var awgToken: Long = 0L`; `internal var awgRegister: (VpnService)->Long = { RipDpiAmneziaWgNativeBindings.jniRegisterVpnProtect(it) }`; `awgUnregister: (Long)->Unit = { RipDpiAmneziaWgNativeBindings.jniUnregisterVpnProtect(it) }`. Extend the `@Synchronized` double-registration guard and the `register()`/`unregister()` pair to treat **all three** tokens as one atomic set. `import com.poyka.ripdpi.core.RipDpiAmneziaWgNativeBindings`. |
| `core/service/src/test/.../VpnNativeProtectRegistrationTest.kt` | Add `awgRegister`/`awgUnregister` fakes + counter assertions + stale-token guard, paralleling the warp arm (test already stubs the JNI statics via the `internal var` hooks — no JNI/mockk). |

Closes the P0 mechanism: the AWG cdylib's own process-global `PROTECT_CB` slot gets a live `GlobalRef<VpnService>` before any AWG socket binds, so `protect_socket_if_configured` (fail-closed, `platform.rs:70-75`) stops rejecting the bind.

### Phase 2 — Durable AWG selection store *(Kotlin, low-risk, no proto)*

| File | Change |
|---|---|
| `core/data/runtime-state/.../data/awg/AwgProviderSelectionStore.kt` *(new)* | Mirror `XrayProviderStores.kt:404-469`: `data class AwgSelectionRecord(val active: Boolean = false, val activeProfileId: String = "")` + `interface AwgProviderSelectionStore { suspend fun current(): AwgSelectionRecord; suspend fun update(record) }` + a **SharedPreferences** impl (event-driven, survives SIGKILL) + Hilt `@Binds`. **No proto field, no secrets in the record** (reload by `activeProfileId`). |

Mirrors the Xray precedent exactly — **SharedPreferences, not `app_settings.proto`** — which sidesteps the schema-8 / `NativeBinaryContractTest` / translatable-keys gates a proto change would trip.

### Phase 3 — Session-scoped supervisor + AWG provider delegate *(the core; high-risk)*

| File | Change |
|---|---|
| `core/service/.../service/session/vpn/VpnServiceSessionModule.kt:114-256` | `@Provides @ServiceSessionScope provideVpnAmneziaWgRuntimeSupervisor(host, AmneziaWgRuntimeSupervisorFactory, dispatchers) = factory.create(scope = host.serviceScope, dispatcher = dispatchers.io)` — **mirror `provideVpnWarpRuntimeSupervisor:116-120`**. Also provide `AmneziaWgRuntimeConfigResolver` + the new `AwgProviderSessionController`/`AwgConnectFlowDelegate`; thread them into `provideVpnCoordinator`. |
| `core/service/.../services/AwgProviderSessionController.kt` *(new)* | Mirror `XrayProviderSessionController.kt:43-115`: `isAwgSelected()` reads `AwgProviderSelectionStore.current().active`; `start()` → `AwgProfileRepository.load(activeProfileId)` → `AmneziaWgRuntimeConfigResolver.resolve` → session-scoped `AmneziaWgRuntimeSupervisor.start` → `LocalProxyEndpoint("127.0.0.1", 10808)`; stale/deleted profile → fail-safe (`HandoffOutcome.Failed`, **not** fall-through to native, per `XrayProviderSessionController.kt:160-165`). |
| `core/service/.../services/AwgConnectFlowDelegate.kt` *(new)* | Mirror `XrayConnectFlowDelegate.kt:59-115`: thin `tryStart/tryStop/tryRestart` returning **true-when-handled**; on handle, after supervisor readiness, call `vpnTunnelRuntime.start(localProxyEndpoint = 10808, tunnelMtu = 1330)`. |
| `core/service/.../services/VpnRuntimeCompositionCoordinator.kt:34-159` | Add optional `awgProviderDelegate` ctor param; consult it **first** in `startComposedRuntime` (`:121-130`), `stop` (`:55-59`), `restartAfterHandover` (`:82-89`) — **order: AWG → Xray → native**. Each declining keeps the rest byte-identical. |
| `core/service/.../service/runtime/vpn/VpnServiceRuntimeCoordinator.kt:96-153,314-323` | Thread the AWG supervisor + delegate + selection read; route AWG exit + telemetry like WARP/Xray (`VpnRuntimeTelemetryReporter`, `:177`). |
| `core/service/.../services/VpnSupervisorExitHandler.kt` | Add `handleAwgExit` mirroring `handleWarpExit` (detach + `stopService(skipRuntimeShutdown=true)`); `detachAll` detaches the AWG supervisor. |
| `core/service/.../services/VpnTunnelRuntime.kt:52-78` + `RipDpiVpnService.kt:260` | Plumb a `tunnelMtuOverride` so the AWG branch sources **1330** (ceiling, not raised above a cellular clamp); addresses/routes unchanged (full-tunnel). |

**LoC watch:** `VpnRuntimeCompositionCoordinator.kt`, `VpnNativeProtectRegistration.kt`, `VpnServiceRuntimeCoordinator.kt` sit on tight per-file production-LoC budgets (`check_native_hotspot_budgets.py`/`check_file_loc_limits.py`). Put new logic in the **new files** (`AwgConnectFlowDelegate.kt`, `AwgProviderSessionController.kt`) rather than enlarging the coordinator.

### Phase 4 — Activation entry rewire *(app + service; medium/high)*

| File | Change |
|---|---|
| `app/.../ui/screens/awg/AmneziaWgProfileViewModel.kt:190-221` | Replace `amneziaWgActivator.activate(request)` (`:205`) with: keep `profileRepository.save(...)`; `awgProviderSelectionStore.update(AwgSelectionRecord(active=true, activeProfileId=stableId))`; `serviceController.start(Mode.VPN)`. Inject `AwgProviderSelectionStore` + `ServiceController` (or a thin `:core:service` `AwgConnectCoordinator`) **instead of** `StandaloneAmneziaWgActivator`. Handle `ServiceStartResult.Rejected(VpnConsentMissing)` → surface the `VpnService.prepare` consent prompt (mirror proxy/VPN connect). Keep `pendingActivation`/`onActivationConsumed`. A Disconnect clears the selection + `ServiceController.stop()`. |
| `core/service/.../services/StandaloneAmneziaWgActivator.kt:42-99` | **Demote**: either delete the `:app`-facing app-scope path, or keep strictly as the *in-session* supervisor wrapper called by `AwgProviderSessionController` (no longer `@ApplicationScope`, no longer called from `:app`). Make `activate()` idempotent (single native handle — `RipDpiAmneziaWg` throws `AlreadyRunning`). |

### Phase 5 — Kill-recovery / Always-on / boot-resume *(medium)*

| File | Change |
|---|---|
| `core/service/.../services/RipDpiVpnService.kt:101-104` (START_STICKY null-action) + `BootResumeWorker`/`StartOnBootController` | On re-delivery / boot, **read the durable AWG selection** and re-enter the AWG branch (not the default proxy path). Clear the AWG selection only on **explicit user stop**, never on LMK — so a killed AWG session resumes as AWG. Persist on transition (SharedPreferences commit), not on a timer (`android-vpn-lifecycle.md`). |

---

## 4. Mandatory tests (close the audit's missing-test matrix)

| Test | Layer | Assertion |
|---|---|---|
| `VpnNativeProtectRegistrationTest` (extend) | `:core:service` unit | the AWG token registers on `register()` and unregisters on `unregister()`; a stale unregister can't clear a newer AWG token. **(Phase 1)** |
| `AwgProviderSessionControllerTest` *(new)* | `:core:service` unit | when AWG is selected, `tryStart` registers AWG protect (or the triplet is armed) **before** `AmneziaWgRuntimeSupervisor.start`, resolves the endpoint to `127.0.0.1:10808`, and a deleted-profile selection fails safe (no fall-through to native). **(Phase 3 — would have caught F1.)** |
| `VpnServiceRuntimeCoordinator` / composition test (extend) | `:core:service` unit | AWG delegate consulted **before** Xray/native; when AWG declines, the native path is byte-identical; AWG exit routes through `VpnSupervisorExitHandler`. **(Phase 3.)** |
| `AmneziaWgProfileViewModelTest` (extend) | `:app` unit | `onConnect` persists + sets the durable selection + calls `serviceController.start(Mode.VPN)`; consent-missing surfaces the prompt. **(Phase 4.)** |
| **Instrumented / device** *(the real gate)* | androidTest + real AWG endpoint | activating an AWG profile starts `RipDpiVpnService`, `startForeground` ≤5s, establishes a TUN routed into `10808`, traffic egresses via the protected WG socket, stop/onRevoke/reconnect/LMK-resume all behave. **No unit test substitutes for this.** **(Phases 3–5.)** |

Update/reconcile the existing `StandaloneAmneziaWgActivatorTest` and `AmneziaWgRuntimeSupervisorTest` for the scope migration (application → session).

---

## 5. Risk register

| Risk | Severity | Mitigation |
|---|---|---|
| **Endpoint-ownership inversion** — adding AWG to `SharedProxyRuntimeStack` starts a competing proxy endpoint + port-10808 race | **High** | Use the delegate seam (AWG *replaces* the stack); never start the proxy stack when AWG is selected. |
| **Two-lane race** — old app-scope activator + new session lane both drive the single native handle | High | Pick the session lane; demote `StandaloneAmneziaWgActivator`; make `activate` idempotent. |
| **Protect ordering** — register AWG GlobalRef *after* `RipDpiAmneziaWg.start` reintroduces the fail-closed bind failure | High | Register in `VpnServiceSessionLifecycle.createShellDelegate()` (`:27`) **before** any runtime start, exactly like proxy/warp. Token `0` = failed → treat as fatal for AWG start. |
| **Scope migration** breaks `StandaloneAmneziaWgActivatorTest`/`AmneziaWgRuntimeSupervisorTest` | Medium | Reconcile/retire those tests in the same phase. |
| **Stale selection** (profile deleted) falls through to native | Medium | Fail-safe `HandoffOutcome.Failed`, mirror `XrayProviderSessionController.kt:160-165`. |
| **Secrets via Intent** | Medium (privacy) | Reload `AwgActivationRequest` by `profileId` from `AwgProfileRepository`+`AwgCredentialStore` inside the session; never put keys in the selection record or Intent. |
| **WS carrier protect is `dead_code`** (`carrier_protect.rs:69`, deferred slice) | Medium | UDP-carrier AWG works once the slot is armed; WS-carrier profiles still fail until that follow-up — gate/flag WS in the UI until then. |
| **GlobalRef leak** if `unregister()` misses the AWG token on revoke/destroy | Medium | All three tokens unregistered as a set in `unregister()`. |
| **Telemetry blank** — AWG status not surfaced | Low | Keep AWG telemetry on `supervisor.pollTelemetry()` and thread into `VpnRuntimeTelemetryReporter` like `xrayController` (`VpnServiceRuntimeCoordinator.kt:177`). |

---

## 6. CI / gate checklist

- **`VpnNativeProtectRegistrationTest`, `VpnServiceRuntimeCoordinatorTest`, `AmneziaWgRuntimeSupervisorTest`, `StandaloneAmneziaWgActivatorTest`** — update; run `:core:service:testDebugUnitTest`.
- **`:core:engine:testDebugUnitTest` (full module)** before any ff-merge — `NativeBinaryContractTest`/`NativeTelemetryGoldenTest` are **N/A** *iff* AWG telemetry stays on `supervisor.pollTelemetry()` (do **not** fold AWG fields into `NativeRuntimeSnapshot`), but the module-subset gate misses contract drift, so run the whole module.
- **`check_native_hotspot_budgets.py` / `check_file_loc_limits.py`** — per-file Kotlin LoC budgets on the coordinator files; prefer new files. Run the full `scripts/ci` python battery and check the **real** exit code (don't pipe to `tail`).
- **arch-health** — `--check` (CI) is stricter than `--staged` (lefthook); Kotlin DI edges don't trip `DEPENDENCY_HUB_LIMITS` (Rust-crate-only) but baseline drift can red.
- **8-locale strings + `translatable-keys.txt`** — only if a *new* user-facing string is added. **Reuse the existing `vpn_notification_content`/consent strings to avoid this entirely.** If unavoidable: all 8 locales (`values` + `ru/es/de/fr/fa/ar/zh-rCN`) in the same commit + regenerate via `scripts/ci/export-strings-for-translation.sh`.
- **No proto / wire-schema change** (selection in SharedPreferences) — keeps schema at `8`, avoids the serialization gates.
- **detekt `maxIssues=0`** (no baseline) on all new `:app`/`:core:service` Kotlin.
- **lefthook rustfmt/clippy** — untouched (no Rust change).

---

## 7. Suggested commit sequence (atomic, by module boundary)

1. `feat(awg): durable standalone-AWG provider selection store` — Phase 2 (`:core:data:runtime-state`, self-contained).
2. `fix(awg): register the AmneziaWG VpnService.protect callback` — Phase 1 (`:core:service` + test).
3. `feat(awg): session-scoped supervisor + provider delegate` — Phase 3 part A (DI + new delegate/controller files).
4. `feat(awg): route the TUN into the standalone AmneziaWG SOCKS` — Phase 3 part B (composition coordinator branch + MTU + exit handler + tests).
5. `feat(awg): connect via the foreground VpnService` — Phase 4 (ViewModel rewire + activator demotion + `:app` test).
6. `feat(awg): resume standalone AmneziaWG across kill/boot` — Phase 5 (START_STICKY/boot-resume branch).

Keep high-risk shared files (`VpnRuntimeCompositionCoordinator.kt`, `VpnServiceRuntimeCoordinator.kt`) in a single serialized lane. **Do not merge to `main`** until the device/instrumented test passes on a networked machine and the full gate battery is green on the combined tree.

---

## 8. Effort & confidence

- **Phase 1 + 2:** ~½ day, low risk, high confidence (pure mirror of WARP/Xray).
- **Phase 3:** ~1–2 days, high risk — the genuinely new code (provider delegate that *produces* the endpoint). Confidence hinges on D1–D3.
- **Phase 4 + 5:** ~1 day, medium risk.
- **Device verification + interop hardening:** open-ended; this is what makes "operable" real and is the part this environment cannot do.

Total: a focused **3–4 day** effort for one engineer with an AWG server to test against — consistent with the open task's "doing" status. The data plane, native protect, persistence, and editor are all already in place; this blueprint is purely the service-layer takeover seam plus its kill-recovery.
