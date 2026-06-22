# AmneziaWG Operability Audit — RIPDPI

- **Scope:** Can RIPDPI import, store, edit, validate, activate, run, stop, and recover a **standalone AmneziaWG (AWG)** profile on Android — correctly, without confusing it with relay_kind/WARP/VLESS, without fd/socket leaks, without VPN recursion, and without silently producing a non-operable tunnel.
- **Commit audited:** `e187dded4` (branch `main`, HEAD at audit time).
- **Method:** All claims verified against the **git object store** (`git show HEAD:<path>`, `git grep … HEAD`), **not** the working tree — see *Methodology & Environment Caveat*. This caveat is load-bearing: it changed the verdict twice during the audit.

---

## Executive Verdict

**NOT OPERABLE (end-to-end), by a single, well-isolated, currently-tracked gap.**

The standalone AWG feature is ~90% built and genuinely well-engineered: a dedicated native library (`libripdpi-amneziawg.so`, crate `ripdpi-amneziawg-android`), its own JNI surface, a complete obfuscation field set threaded all the way to the wire codec (jc/jmin/jmax/**s1–s4**/h1–h4/**i1–i5**), AES-GCM secret-at-rest, a stable-id Room persistence layer (DB v2 + `awg_profiles`), and a Mutex-serialized activation supervisor. Import (via pasted `.conf` + cohort presets), edit, validate, persist, and the placeholder-key inactivity gate all work.

**It cannot run a tunnel.** Two last-mile wiring gaps make any activation fail:

1. **P0 — the AWG library's `VpnService.protect` callback is never registered.** `libripdpi-amneziawg.so` statically links its **own** `ripdpi-native-protect` (a separate process-global protect slot). Nothing in the app ever calls `RipDpiAmneziaWgNativeBindings.jniRegisterVpnProtect`. Because `protect_socket_if_configured` is (correctly) **fail-closed**, every WireGuard UDP socket bind returns `NotConnected`, the runtime errors, `start` returns `2`, and the UI lands on `Failed`. The connect path can **never** succeed.
2. **P0/P1 — the AWG session is not wired into a `VpnService`/TUN/foreground-service.** `StandaloneAmneziaWgActivator` runs the supervisor on an application-scope coroutine; it never starts `RipDpiVpnService`, never establishes a TUN, never shows a foreground notification, and `RipDpiVpnService` contains zero AWG references. The runtime only publishes a loopback SOCKS5 on `127.0.0.1:10808` that nothing routes into. Even with protect fixed, there is no system-wide VPN coverage, no Always-on participation, and no SIGKILL survivability.

Both gaps are the explicit subject of the repo's own open task `docs/tasks/issues/wire-standalone-amneziawg-profile-transport.md` (**status: doing, priority: high**), which describes the editor as *"UI-complete, core-stub."*

**Good news:** the protect invariant is **not** violated (it fails *closed*, so there is no TUN packet-recursion hazard), AWG is correctly modelled as a separate tunnel surface (never a `relay_kind`/`ProxyProfile`), secrets are not logged, and the full obfuscation parameter set reaches the data plane. The defect is *operability*, not *correctness/safety*.

| Question | Answer |
|---|---|
| Import? | **Partial** — pasted `.conf` + cohort presets work; `amneziawg://` URI and INI-subscription import are **not wired** (dead code paths). |
| Store / Edit / Validate? | **Yes** — Room persistence (stable `awg-<UUID>` id), AES-GCM secrets, editor validation, placeholder-key gate all functional. |
| Activate / Run? | **No** — protect callback unregistered → fail-closed bind → start fails. |
| Stop / Recover? | **N/A** — never reaches a running state; also no foreground/SIGKILL-survival even if it did. |
| Confused with relay/WARP/VLESS? | **No** — distinct cdylib, distinct config type, distinct supervisor; absent from relay/proxy code. |
| fd/socket leaks? | **No leak observed** — registry-based handle lifecycle, idempotent stop/destroy, protect fails closed. |
| VPN recursion? | **No** — protect is fail-closed; an unprotected socket is never used. |
| Silent non-operable tunnel? | **Yes, this is the core defect** — activation silently resolves to `Failed`; nothing tells the user the transport isn't wired. |

---

## Top 10 Blockers (ranked)

| # | Sev | Title | One-line |
|---|-----|-------|----------|
| 1 | **P0** | AWG protect callback never registered | `RipDpiAmneziaWgNativeBindings.jniRegisterVpnProtect` has zero call sites; the AWG `.so`'s protect slot stays empty → fail-closed bind → tunnel DOA. |
| 2 | **P0** | AWG session not wired into VpnService/TUN/transport | Activator runs on app-scope; no `RipDpiVpnService`, no TUN, no route into the loopback SOCKS:10808 → zero system VPN coverage. |
| 3 | **P1** | No foreground service / Always-on / kill-survival | The AWG tunnel (if it ran) has no FGS notification, no Always-on integration, and no SIGKILL-survivable running-state. |
| 4 | **P2** | `amneziawg://` URI import unwired | `AmneziaWgUriCodec.decode` has no production caller — the advertised share-URI import does nothing. |
| 5 | **P2** | WireGuard-INI subscription import unwired | `WireGuardIniSubscriptionParser` has no production caller — multi-peer AWG subscription import is dead. |
| 6 | **P3** | Weak activation gate | `isActivatable()` is presence-only: no private-key format check, no `Jmin≤Jmax`, no `Jmax`-vs-MTU check. (Empty key *is* correctly rejected.) |
| 7 | **P3** | AES-128 vs documented AES-256 at rest | `KeystoreEncryptedPreferences` omits `setKeySize(256)`; doc comments claim AES-256. (Verify.) |
| 8 | **P3** | Junk/padding ceiling decoupled from MTU | `Jmax`/`S*` validated against a fixed `1280` constant, never the profile MTU → possible fragmentation/fingerprint on sub-1280 paths. |
| 9 | **P3** | `jniStart` panic sentinel drift | Contained panic may surface `-1` instead of the documented `{0,1,2}` (verify for the AWG `.so`). |
| 10 | **P3** | Loopback SOCKS host not asserted loopback | `runtime.rs` binds the SOCKS listener to a config host with no loopback assertion (resolver does pin `127.0.0.1`, but the runtime doesn't enforce it). |

---

## Findings

> Severity reflects the **HEAD** code. Several workflow-emitted findings were **stale-tree artifacts** and are explicitly retired in *Corrected Non-Findings* below.

### F1 — P0 · Standalone AWG never registers its native `VpnService.protect` callback → tunnel cannot start

- **Evidence:**
  - `core/service/src/main/kotlin/com/poyka/ripdpi/services/VpnNativeProtectRegistration.kt:26-49` — the only live registrar wires **proxy + warp only**: `proxyRegister = { RipDpiProxyNativeBindings.jniRegisterVpnProtect(it) }`, `warpRegister = { RipDpiWarpNativeBindings.jniRegisterVpnProtect(it) }`. There is **no** `amneziaRegister`.
  - `core/engine/src/main/kotlin/com/poyka/ripdpi/core/RipDpiAmneziaWg.kt:120` declares `external fun jniRegisterVpnProtect(...)` on `RipDpiAmneziaWgNativeBindings`, but `git grep` over all `*.kt` finds **only** the declaration (`:120`) and a Hilt binding (`EngineDependencies.kt:214`) — **zero call sites**.
  - `RipDpiAmneziaWg.kt:186-188` (KDoc) states protect "must be registered … via `jniRegisterVpnProtect` before `start`"; `start()` (`:207`) does **not** self-register.
  - `native/rust/crates/ripdpi-amneziawg-android/Cargo.toml:17` — links its **own** `ripdpi-native-protect` (separate static `PROTECT_CB`, not shared with warp/proxy).
  - `native/rust/crates/ripdpi-amneziawg-android/src/vpn_protect.rs:83-84` — `amneziawg_platform()` **always** installs `with_socket_protector(protect_socket_via_callback)`; `lifecycle.rs:41` builds the runtime with it.
  - `native/rust/crates/ripdpi-warp-core/src/platform.rs:70-74` — `protect_socket_if_configured` is **fail-closed**: `if let Some(p)=… { p.protect_socket(fd)?; }`.
  - `ripdpi-native-protect` returns `Err(NotConnected, "VPN protect callback not registered")` when the slot is empty.
- **What's wrong:** The dedicated AWG `.so` has its own protect slot, which no code path ever fills. Every activation therefore hits a configured-but-empty protector.
- **Why it breaks operability:** `AmneziaWgRuntime.run` → `open_carrier` → `bind_tunnel_socket` → `protect_socket_if_configured` → `Err(NotConnected)` → `?` propagates → `run()` errors → `jniStart` → `2` → `AmneziaWgRuntimeSupervisor.start` `awaitReady` throws `SupervisorStartupFailureException` → `AmneziaWgProfileViewModel.onConnect` surfaces `Failed`. The tunnel is **dead on arrival**. (Fail-closed = no packet loop; the protect invariant itself is preserved.)
- **Repro / test:** Enter a valid AWG profile, tap Connect → always `Failed`. Missing regression test: *"`StandaloneAmneziaWgActivator.activate` registers `RipDpiAmneziaWgNativeBindings.jniRegisterVpnProtect` before `RipDpiAmneziaWg.start`."*
- **Fix direction:** Add an `amnezia` arm to `VpnNativeProtectRegistration` (register `RipDpiAmneziaWgNativeBindings.jniRegisterVpnProtect` against the live `VpnService` GlobalRef, unregister on stop, store the generation token), and route standalone AWG activation **through** `RipDpiVpnService` so the protect slot is populated before `RipDpiAmneziaWg.start`. Pair with finding F2.

### F2 — P0 · Standalone AWG is not wired into a VpnService/TUN/transport path → no system VPN coverage

- **Evidence:**
  - `core/service/src/main/kotlin/com/poyka/ripdpi/services/StandaloneAmneziaWgActivator.kt:48-86` — `activate()` creates an `AmneziaWgRuntimeSupervisor` with `scope = applicationScope, dispatcher = dispatchers.io` and starts it. No `VpnService`, no `startForeground`, no TUN.
  - `core/service/src/main/kotlin/com/poyka/ripdpi/services/AmneziaWgRuntimeSupervisor.kt:47-107` — resolves config, launches `runtime.start(resolvedConfig)` UNDISPATCHED, `awaitReady`. No protect, no FGS, no `ParcelFileDescriptor`/`establish`.
  - `core/service/src/main/kotlin/com/poyka/ripdpi/service/awg/AmneziaWgRuntimeConfigResolver.kt:62-73` — runtime exposes only a loopback SOCKS5 inbound `127.0.0.1:10808`; it does **not** open a TUN.
  - `git grep -i amnezia HEAD -- …/RipDpiVpnService.kt …/VpnServiceSessionLifecycle.kt` → **empty**: the VPN service has no knowledge of AWG.
  - `docs/tasks/issues/wire-standalone-amneziawg-profile-transport.md` (**status: doing**, priority: high, updated 2026-06-18): *"the app could not run it. The editor was preview-only: no Save/Connect, no persistence, no engine path."*
- **What's wrong:** The AWG runtime is a local SOCKS5 server with no TUN and no traffic routed into it; nothing carries device traffic through the tunnel.
- **Why it breaks operability:** No TUN ⇒ no traffic is captured ⇒ even past F1 the "VPN" moves no packets. No FGS ⇒ LMK-eligible at any moment. No Always-on / "Block connections without VPN" coverage. No running-state persistence ⇒ on process death the application-scope job vanishes with no resume.
- **Repro / test:** After fixing F1, activate AWG and background the app → no VPN/foreground notification; no device traffic egresses via the tunnel. Missing integration test: *"activating a standalone AWG profile establishes a TUN that routes into the AWG loopback SOCKS and shows a foreground notification within 5s."*
- **Fix direction:** Drive activation through `RipDpiVpnService` (reuse `VpnForegroundNotificationController` + `VpnServiceSessionLifecycle`), establish/own the TUN, route TUN→`127.0.0.1:10808`, and persist running-state event-driven for kill recovery. This is the body of the open transport task.

### F3 — P1 · No foreground service / Always-on / SIGKILL survival for the AWG session

- **Evidence:** Same as F2 — `StandaloneAmneziaWgActivator` uses `applicationScope`; `core/service/src/main/AndroidManifest.xml` `SUPPORTS_ALWAYS_ON` applies to `RipDpiVpnService`, which the AWG path never starts.
- **Why it breaks operability:** `android-vpn-lifecycle.md` requires a persistent tunnel to hold a visible FGS notification (within 5s) and to persist state across `SIGKILL`. The AWG path satisfies neither.
- **Fix direction:** Folded into F2 (route through `RipDpiVpnService`).

### F4 — P2 · `amneziawg://` URI import is not wired into any dispatcher

- **Evidence:** `git grep AmneziaWgUriCodec HEAD -- '*.kt'` → only `AmneziaWgUriCodec.kt` itself, `AmneziaWgUriCodecTest`, and a doc-comment reference in `AmneziaWgProfile.kt:10`. `decode()` has **no** production caller (clipboard/share/deeplink/import dispatcher).
- **Why it breaks correctness:** The documented share-URI import (`docs/amneziawg-uri-scheme.md`) is unreachable; a user pasting an `amneziawg://` link cannot import it. The codec round-trips perfectly in tests, masking the unwired state.
- **Fix direction:** Wire `AmneziaWgUriCodec.decode` into the import/clipboard/deeplink entry that feeds the AWG editor (or document it as encode-only for now).

### F5 — P2 · WireGuard-INI subscription import is not wired

- **Evidence:** `git grep WireGuardIniSubscriptionParser HEAD -- '*.kt'` → only the parser, its test, and a doc-comment in `SingBoxSubscriptionParser.kt:467`. No production consumer.
- **Why it breaks correctness:** Multi-peer AWG `.conf` subscription import is dead code; the only working import into the editor is `onConfPasted` (single `.conf` via `WireGuardConfParser`) + cohort presets.
- **Fix direction:** Wire the subscription parser into the subscription-refresh pipeline, or remove it until needed.

### F6 — P3 · Activation gate is presence-only (no key-format / cross-field / MTU validation)

- **Evidence:** `app/.../awg/AmneziaWgEditorState.kt:169-174` — `isActivatable()` checks only non-blank server, `port>0`, non-blank `interfacePrivateKey`/`peerPublicKey`/`ADDRESS`. `validate()` (`:71-73`) does per-field range only; no `Jmin≤Jmax`. Native `amneziawg.rs::AwgParams::from_config` enforces `Jmin>Jmax`/`Jmax`-too-large **only when `jc>0`**.
- **What's wrong:** A non-blank but malformed private key passes the gate (caught later natively as an opaque `Failed`); an inverted junk range with `jc=0` is accepted silently. **The placeholder/empty-key case is correctly handled** — a blank key keeps `isActivatable()=false` (rule honored).
- **Fix direction:** Validate the private key as WireGuard base64 (length/charset), add a derived `obfuscationValid` (`jmin≤jmax`, `jmax≤effectiveMtu-headroom` when `jc>0`) and surface field-level errors; optionally tighten the native range check regardless of `jc`.

### F7 — P3 · Secrets sealed with AES-128-GCM despite AES-256 claim (verify)

- **Evidence (medium confidence — flagged for first-party re-read):** `core/data/runtime-state/.../WarpStores.kt` `KeystoreEncryptedPreferences` builds the AndroidKeyStore `KeyGenParameterSpec` with GCM/NoPadding but no `.setKeySize(256)`; `AwgCredentialStore`/`AwgProfileRepository` KDoc assert "AES-256-GCM". AndroidKeyStore defaults AES to 128-bit when `setKeySize` is omitted.
- **Why it matters:** AES-128-GCM is strong, but contradicts the documented contract and the project's stated AES-256 floor; blast radius is local (keystore-bound key requires device compromise).
- **Fix direction:** Add `.setKeySize(256)` (note: alias rotation re-encrypts existing blobs — version the alias), or correct the docs to AES-128 if intentional.

### F8 — P3 · Junk/padding ceiling fixed at 1280, decoupled from configured MTU

- **Evidence:** `native/rust/crates/ripdpi-warp-core/src/amneziawg.rs` — `JUNK_PACKET_SIZE_LIMIT = 1280` (and `PADDING_SIZE_LIMIT`); `Jmax`/`S*` validated against the constant, never against `AwgActivationRequest.mtu` (default `1330`) or the effective path MTU.
- **Why it matters:** A junk/padding size up to 1280 can exceed a low cellular MTU, fragmenting obfuscation datagrams — itself a DPI fingerprint and a black-hole risk on restrictive paths.
- **Fix direction:** Derive the ceiling from `min(JUNK_PACKET_SIZE_LIMIT, effective_mtu - headroom)`, or add an editor warning when `Jmax`/`S*` exceed the MTU.

### F9 — P3 · `jniStart` panic sentinel may escape the documented `{0,1,2}` contract

- **Evidence (verify for the AWG `.so`):** The sibling WARP bridge wraps `jniStart` with a `-1` panic default (`ripdpi-warp-android/src/lib.rs`), outside the documented `0/1/2`. `ripdpi-amneziawg-android/src/lifecycle.rs` start returns `2` on runtime failure; confirm its FFI panic default also maps to `2`.
- **Why it matters:** Contract drift only (Kotlin treats any non-zero as failure today), but future telemetry switching on documented codes could misclassify a contained panic.
- **Fix direction:** Use the `2` sentinel for the panic default, or document the panic sentinel explicitly in `JNI_CONTRACT.md §6/§7`.

### F10 — P3 · Loopback SOCKS host not asserted loopback in the runtime

- **Evidence:** `AmneziaWgRuntimeConfigResolver.kt:62-73` pins `localSocksHost = 127.0.0.1`, but `ripdpi-warp-core/src/runtime.rs` `TcpListener::bind(local_socks_host)` performs no loopback assertion. (The SOCKS listener is exempt from the protect invariant — it is a passive inbound, not an outbound-into-TUN.)
- **Why it matters:** Defense-in-depth; a future non-loopback `local_socks_host` would create a routable inbound surface, contrary to RIPDPI's no-inbound posture.
- **Fix direction:** `debug_assert`/validate `local_socks_host` parses to a loopback IP before `bind`.

---

## End-to-End Flow Map (HEAD-verified)

```
IMPORT/AUTHOR
  Route.AmneziaWgProfile ─► AmneziaWgProfileScreen ─► AmneziaWgProfileViewModel
    • paste .conf:  onConfPasted ─► AmneziaWgEditorState.populateFromConf
                    ─► WireGuardConfParser.parse (handles jc/jmin/jmax/s1-s4/h1-h4/i1-i5)
                    ─► AmneziaWgConfig.awg : AmneziaWgParameters (full field set)
    • cohort preset: AwgCohortCatalog (awg-cohorts.json) byte-match
    • amneziawg:// URI ........ AmneziaWgUriCodec.decode  ✗ NO PRODUCTION CALLER (F4)
    • INI subscription ........ WireGuardIniSubscriptionParser ✗ NO PRODUCTION CALLER (F5)
        │
GATE  ▼
  AmneziaWgEditorState.isActivatable()  → canActivate
    requires server / port>0 / privateKey / peerPublicKey / ADDRESS non-blank
    (placeholder/empty private key ⇒ inactive ✓; no format/cross-field checks — F6)
        │
PERSIST (onConnect, AmneziaWgProfileViewModel.kt:190-205)
  profileRepository.save(name, draft, existingId=savedProfileId)   [AwgProfileRepository]
    • mints opaque "awg-<UUID>" once, reused thereafter
    • strips profileId + privateKey + presharedKey from the Room blob
    • seals secrets in AwgCredentialStore (AndroidKeyStore AES-GCM — F7 keysize)
    • AwgProfileDao.upsert ─► awg_profiles (RipDpiDatabase v2, MIGRATION_1_2)
        │
ACTIVATE (service)
  StandaloneAmneziaWgActivator.activate(AwgActivationRequest)   (Mutex-serialized)
    ─► AmneziaWgRuntimeSupervisor.start
         ─► AmneziaWgRuntimeConfigResolver.resolve  (fail-closed require()s; SOCKS 127.0.0.1:10808)
              ─► ResolvedRipDpiAmneziaWgConfig (top-level; NO Cloudflare/WARP fields ✓)
        │      ✗ NO VpnService / NO TUN / NO foreground service / app-scope only (F2,F3)
JNI CREATE/START
  RipDpiAmneziaWg (System.loadLibrary("ripdpi-amneziawg") → libripdpi-amneziawg.so)
    ✗ jniRegisterVpnProtect NEVER CALLED for this .so (F1)
    jniCreate(configJson) ─► jniStart (blocks; IO dispatcher; readiness push)
        │
RUST RUNTIME  (ripdpi-amneziawg-android → ripdpi-warp-core::AmneziaWgRuntime.run)
    enabled-gate ─► resolve_endpoint ─► open_carrier
      UDP carrier: bind_tunnel_socket ─► protect_socket_if_configured (FAIL-CLOSED)
                   ──► protect_socket_via_callback ⇒ Err(NotConnected)  ✗✗ DEAD HERE (F1)
      WS carrier:  connect_protected_carrier (protect-before-connect, fail-closed)
    [if it reached here] WireGuardTunnel = boringtun Tunn + AwgWireCodec
      (H1-H4 header substitution, S1-S4 padding), send_amnezia_junk
      (I1-I5 special junk via special_junk_hex + Jc random junk)  ← full field set threaded ✓
        │
EGRESS  ✗ unreachable: start returns 2 ─► SupervisorStartupFailureException ─► UI "Failed"
```

**Parallel/legacy path (still present, distinct):** AWG-inside-WARP — `RipDpiWarp` (`libripdpi-warp.so`) `WarpRuntimeNativeConfig.amnezia` → `WarpRuntime.run` → same `WireGuardTunnel`/`AwgWireCodec`, but Cloudflare-WARP-provisioned and driven by settings, **not** the standalone editor. This path's codec construction hardcodes empty I1-I5 (`tunnel.rs build_awg_codec(&["","","","",""])`) — a real limitation **of the WARP path only**, out of scope for standalone-AWG operability.

---

## Missing Test Matrix

| Behavior | Suggested test | Layer | Exact assertion |
|---|---|---|---|
| Protect registered before start | `StandaloneAmneziaWgActivatorProtectTest` | service unit | `activate()` invokes `RipDpiAmneziaWgNativeBindings.jniRegisterVpnProtect` **before** `RipDpiAmneziaWg.start`, and `jniUnregisterVpnProtect(token)` after stop/destroy. **Would have caught F1.** |
| Tunnel establishes TUN + foreground | `AmneziaWgVpnServiceIntegrationTest` | service/instrumented | activation starts `RipDpiVpnService`, calls `startForeground` ≤5s, establishes a TUN routed into `127.0.0.1:10808`. **Would have caught F2/F3.** |
| End-to-end activation success (fake native) | `AmneziaWgActivationHappyPathTest` | service unit | with a fake native runtime + registered protect, `onConnect` reaches `awaitReady` success (not `Failed`). |
| `amneziawg://` import round-trip into editor | `AmneziaWgUriImportDispatcherTest` | data/app | pasting an `amneziawg://` URI populates the editor form (currently no dispatcher — F4). |
| INI subscription import wired | `WireGuardIniSubscriptionImportTest` | data | a subscribed `.conf` list produces AWG profiles via the live refresh path (F5). |
| Key-format + cross-field gate | `AmneziaWgEditorValidationTest` | app unit | `isActivatable()` is false for a malformed base64 key and for `Jmin>Jmax`/`Jmax>MTU`. |
| Placeholder-key inactivity (regression) | extend `AmneziaWgEditorStateTest` | app unit | blank/placeholder private key ⇒ `canActivate=false` (currently implicit — pin it). |
| Secret key size | `AwgCredentialStoreKeySizeTest` | data unit | generated AES key is 256-bit (or doc says 128) — F7. |
| Jmax/S* vs MTU | extend `amneziawg.rs` unit tests | rust | `from_config` rejects/clamps junk size above `effective_mtu - headroom` — F8. |
| `jniStart` panic sentinel | `ripdpi-amneziawg-android` FFI test | rust | a contained panic returns the documented sentinel, not `-1` — F9. |

**Coverage that already exists (verified present in HEAD via `git ls-tree`):** `AmneziaWgUriCodecTest`, `WireGuardConfParserTest`, `WireGuardIniSubscriptionAmneziaWgTest`, `AwgCohort{CatalogLoad,MatchOnImport,PresetApply}Test`, `AwgActivationRequestTest`, `RipDpiAmneziaWgConfigSerializationTest`, `AmneziaWgRuntimeConfigResolverTest`, `AmneziaWgRuntimeSupervisorTest`, `StandaloneAmneziaWgActivatorTest`, `AwgProfileRepositoryRoomTest`, `RipDpiDatabaseMigrationTest`, `AmneziaWg{EditorState,ProfileViewModel,ProfileScreen}Test`, `AmneziaWgProfileScreenshotTest`, plus inline Rust unit tests in `amneziawg.rs`/`platform.rs`/`amneziawg_runtime.rs`. **The gap is integration: no test asserts protect-registration or VpnService/TUN wiring — which is exactly why F1/F2 shipped green.**

> **Test execution (actual):** The audit's *primary* CWD `/Users/npochaev/GitHub/RIPDPI` is a bare repo whose stray on-disk tree is stale — but the canonical sibling worktree `/Users/npochaev/GitHub/RIPDPI-main` is clean at HEAD `e187dded4`. Run there:
> - **`cargo test -p ripdpi-warp-core --locked` → 64/64 PASS** (0 failures). Validates the AWG obfuscation codec (`amneziawg.rs`: passthrough byte-identity, junk/prelude, `from_config` range validation incl. s3/s4), the `AmneziaWgRuntime`/`AmneziaWgObfuscation` (incl. `is_active` s3/s4/i1-i5), and the **fail-closed** protect helper (`platform.rs`). **Notably, none of these tests exercise the Kotlin-side protect registration — confirming F1 is an integration-wiring absence, not a native-codec defect.**
> - Not executed here (slower / native-NDK build): `cargo test -p ripdpi-amneziawg-android --locked` (cdylib JNI crate) and `./gradlew :core:service:testDebugUnitTest :core:data:testDebugUnitTest :app:testDebugUnitTest --tests '*AmneziaWg*' --tests '*Awg*'`. These suites exist and should be run from `/Users/npochaev/GitHub/RIPDPI-main` (a worktree needs a gitignored `local.properties` with `sdk.dir`).
>
> The passing native suite is consistent with the verdict: the built layers are sound; the feature is non-operable purely because the activation lane never registers protect and never owns a VpnService/TUN.

---

## Corrected Non-Findings (stale-tree artifacts — do NOT action)

The orchestration's own agents disagreed because some read HEAD and some read the stale working tree; several "findings" and several "INVALID" verdicts were wrong. Confirmed against HEAD and **retired**:

| Retired claim | Verdict on HEAD | Evidence |
|---|---|---|
| "S3/S4 dropped on every import path" | **FALSE** | `WireGuardConfParser.kt:204-207` handles s1-s4; `AmneziaWgParameters` has `s3/s4` (`WireGuardConfig.kt:66-67`); `AwgProfileForm:32-33`, `AwgActivationObfuscation:80-81`, native `AmneziaWgObfuscation:141-143` all carry s3/s4 (`to_warp_amnezia:204-205`). |
| "I1-I5 never reach the data plane" | **FALSE for standalone path** | `amneziawg_runtime.rs:388-393` threads `special_junk_hex: [i1..i5]`. (True only for the legacy WARP-nested path's `build_awg_codec`.) |
| "`applyField` drops S3/S4/I1-I5 from the form" | **FALSE** | `AwgProfileForm:27-42` and `AmneziaWgEditorState:283-303` carry the full set. |
| "No AWG persistence/activation at HEAD; ViewModel has no save/activate" | **FALSE** | `AmneziaWgProfileViewModel.kt:90-91,190-205` injects `StandaloneAmneziaWgActivator`+`AwgProfileRepository` and `onConnect` saves+activates; `RipDpiDatabase` is `version=2` with `awgProfileDao()`. |
| "`isActivatable()` does not exist" | **FALSE** | `AmneziaWgEditorState.kt:169-174`. |
| "`protect_socket_if_configured` is fail-OPEN (`let _ =`)" | **FALSE on HEAD** | `platform.rs:70-74` is fail-closed (`?`). (The `let _ =` form was the stale on-disk version.) |
| "There is no separate `ripdpi-amneziawg-android` crate / `libripdpi-amneziawg.so`" (the audit brief's premise) | **FALSE** | `native/rust/Cargo.toml:61`, gradle artifact spec `:1187`, `RipDpiAmneziaWg.kt:89` `loadLibrary("ripdpi-amneziawg")`. |

---

## Methodology & Environment Caveat (read this before re-verifying)

The working directory `/Users/npochaev/GitHub/RIPDPI` is a **bare git repository with a stale, partial stray checkout** on disk (the "bare-repo worktree trap" noted in project memory). Consequences observed and worked around:

- `git status` → *"fatal: this operation must be run in a work tree."*
- `find` / `ls` / `git ls-files` and naïve `Read`/grep over the filesystem return a **stale tree** that predates the AWG runtime/persistence commits (it shows `RipDpiDatabase version=1`, no `ripdpi-amneziawg-android`, no `StandaloneAmneziaWgActivator`). This is what produced the workflow's contradictory "no persistence/activation at HEAD" verdicts and very nearly produced a wrong **"editor-only / NOT operable for lack of any runtime"** conclusion.
- **Authoritative source = the git object store.** Every finding here was verified with `git show HEAD:<path>` and `git grep … HEAD`, which read HEAD blobs regardless of the working tree.

Anyone re-running this audit **must** do so from a fresh `git worktree add … e187dded4`, or read exclusively via `git show HEAD:` — otherwise they will reproduce the stale picture.

---

## Final Recommendation

**BLOCK on standalone-AWG operability — needs one focused fix lane (F1 + F2/F3), already scoped by the open transport task.**

- The feature is **safe and correct** as far as it goes: no relay/WARP confusion, no protect-invariant violation (fails closed), no secret leakage, full obfuscation parameter fidelity, sound persistence.
- It is **non-operable end-to-end** for exactly two last-mile reasons: the AWG `.so`'s protect callback is never registered (F1), and the session is never attached to a `VpnService`/TUN/foreground lifecycle (F2/F3). Both are concentrated in `core/service` activation wiring and are the explicit content of `wire-standalone-amneziawg-profile-transport.md` (status: doing).
- **Do not ship the AWG profile editor as a connectable feature** until F1+F2+F3 land with the two integration tests above (protect-before-start; TUN+foreground establishment). F4/F5 (unwired imports) should land with it or the editor should hide those entry points. F6–F10 are quality hardening that can follow.
- **Action for maintainers:** correct the project-memory/architecture notes — the standalone AWG `.so` and its **independent** protect slot are the authoritative surface; the "AWG rides inside WARP" model is stale and is precisely what let F1 hide.
