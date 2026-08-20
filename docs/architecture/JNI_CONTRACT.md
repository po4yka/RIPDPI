# JNI Contract — the Kotlin ↔ Rust Boundary

The Kotlin/Rust JNI boundary is an **architecture contract**, not an
implementation detail. This document is the normative reference for what
crosses it, who owns each side, and the invariants every JNI method must
honor. Treat a violation of any rule here as a release blocker.

Companion docs: [`ARCHITECTURE.md`](ARCHITECTURE.md) (whole-app),
[`NATIVE_RUST.md`](NATIVE_RUST.md) (crate taxonomy),
[`CONFIG_CONTRACTS.md`](CONFIG_CONTRACTS.md) (the config JSON that crosses this
boundary), [`FEATURE_EXTENSION_GUIDE.md`](FEATURE_EXTENSION_GUIDE.md) (adding
features).
Derived from current source; exact paths and crate names are used throughout.

---

## 1. Native library map

Five JNI `cdylib` libraries are loaded into the app process. Each has its own
`JNI_OnLoad`.

| Library | Source crate | Loaded by (Kotlin) | `JNI_OnLoad` location |
|---------|--------------|--------------------|-----------------------|
| `libripdpi.so` | `ripdpi-android` | `RipDpiNativeLoader` → `System.loadLibrary("ripdpi")` | `native/rust/crates/ripdpi-android/src/lib.rs` |
| `libripdpi-tunnel.so` | `ripdpi-tunnel-android` | `Tun2SocksNativeBindings` companion → `System.loadLibrary("ripdpi-tunnel")` | `native/rust/crates/ripdpi-tunnel-android/src/lib.rs` |
| `libripdpi-relay.so` | `ripdpi-relay-android` | `RipDpiRelayNativeLoader` → `System.loadLibrary("ripdpi-relay")` | `native/rust/crates/ripdpi-relay-android/src/lib.rs` |
| `libripdpi-warp.so` | `ripdpi-warp-android` | `RipDpiWarpNativeLoader` → `System.loadLibrary("ripdpi-warp")` | `native/rust/crates/ripdpi-warp-android/src/lib.rs` |
| `libripdpi-amneziawg.so` | `ripdpi-amneziawg-android` | `RipDpiAmneziaWgNativeLoader` → `System.loadLibrary("ripdpi-amneziawg")` | `native/rust/crates/ripdpi-amneziawg-android/src/lib.rs` |

`JNI_OnLoad` behavior:

- **All five** wrap their body in `std::panic::catch_unwind` and return
  `jni::sys::JNI_ERR` on panic; on success they return `android_support::JNI_VERSION`
  (`JNI_VERSION_1_6`).
- `libripdpi.so` `JNI_OnLoad` (`ripdpi-android/src/lib.rs`): stores the
  `JavaVM` in a process-static `OnceCell<JavaVM>` (`JVM`), then calls
  `android_support::ignore_sigpipe()`, `init_android_logging("ripdpi-native")`,
  `android_support::install_panic_hook()`, and
  `ripdpi_android_telemetry_adapter::install_recorder()`.
- `libripdpi-tunnel.so`, `libripdpi-warp.so`, and `libripdpi-amneziawg.so` **do not** store the `JavaVM`
  passed to `JNI_OnLoad`; they call `ignore_sigpipe` + `init_android_logging`
  + `install_panic_hook`. `libripdpi-relay.so` routes through
  `lifecycle::jni_on_load_entry(vm)`.
- `RipDpiNativeLoader` is a Kotlin `object`; its `init` block runs
  `System.loadLibrary` exactly once. `RipDpiProxyNativeBindings`,
  `NetworkDiagnosticsNativeBindings`, `StrategyEngineNativeBindings`,
  `RipDpiCdnEchNativeBindings`, `RipDpiSharedPriorsNativeBindings`,
  `RipDpiPlatformCapabilities`, `NativeOwnedTlsHttpFetcher`, and
  `NativeEchTlsHandshakeBridge` all funnel through `RipDpiNativeLoader.ensureLoaded()`.

---

## 2 & 3. Boundary ownership map

Each row is one JNI boundary. The **Kotlin owner** declares the `external fun`s;
the **Rust owner** exports the matching `Java_*` symbols. The `*NativeBindings`
class holds the raw `external fun`s; a sibling wrapper class
(`RipDpiProxy`, `Tun2SocksTunnel`, …) adds the coroutine-friendly API.

| Kotlin owner class | JNI symbol prefix | Rust owner crate · module | Native lib |
|--------------------|-------------------|---------------------------|------------|
| `RipDpiProxyNativeBindings` (`core/engine/.../core/RipDpiProxy.kt`) | `Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_*` | `ripdpi-android` · `src/ffi/proxy_bridge.rs` (+ `proxy_bridge/{core,geo,pcap}.rs`), `src/ffi/vpn_protect_bridge.rs` | `libripdpi.so` |
| `NetworkDiagnosticsNativeBindings` (`.../core/NetworkDiagnostics.kt`) | `Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_*` | `ripdpi-android` · `src/ffi/diagnostics_bridge.rs` | `libripdpi.so` |
| `StrategyEngineNativeBindings` (`.../core/StrategyEngineNativeBindings.kt`) | `Java_com_poyka_ripdpi_core_StrategyEngineNativeBindings_*` | `ripdpi-android` · `src/ffi/lua_bridge.rs`, `src/ffi/probe_results_bridge.rs` | `libripdpi.so` |
| `RipDpiCdnEchNativeBindings` (`.../core/RipDpiCdnEchNativeBindings.kt`) | `Java_com_poyka_ripdpi_core_RipDpiCdnEchNativeBindings_*` | `ripdpi-android` · `src/ffi/cdn_ech_bridge.rs` | `libripdpi.so` |
| `RipDpiSharedPriorsNativeBindings` (`.../core/RipDpiSharedPriorsNativeBindings.kt`) | `Java_com_poyka_ripdpi_core_RipDpiSharedPriorsNativeBindings_*` | `ripdpi-android` · `src/ffi/shared_priors_bridge.rs` | `libripdpi.so` |
| `RipDpiPlatformCapabilities` (`.../core/RipDpiPlatformCapabilities.kt`) | `Java_com_poyka_ripdpi_core_RipDpiPlatformCapabilities_*` | `ripdpi-android` · `src/ffi/platform_bridge.rs` | `libripdpi.so` |
| `NativeOwnedTlsHttpFetcherNativeBindings` (`.../core/NativeOwnedTlsHttpFetcher.kt`) | `Java_com_poyka_ripdpi_core_NativeOwnedTlsHttpFetcherNativeBindings_*` | `ripdpi-android` · `src/ffi/owned_tls_http_bridge.rs` | `libripdpi.so` |
| `NativeEchTlsHandshakeBridge` (`.../core/NativeEchTlsHandshakeBridge.kt`) | `Java_com_poyka_ripdpi_core_*` (native-ech-tls) | `ripdpi-android` · `src/ffi/native_ech_tls_bridge.rs` | `libripdpi.so` |
| `NativeDoqQuicClientNativeBindings` (`:core:diagnostics`) | `Java_com_poyka_ripdpi_diagnostics_dpi_NativeDoqQuicClientNativeBindings_*` | `ripdpi-android` · `src/ffi/doq_bridge.rs` | `libripdpi.so` |
| `NativeQuicInitialPacketBindings` (`:core:diagnostics`) | `Java_com_poyka_ripdpi_diagnostics_dpi_NativeQuicInitialPacketBindings_*` | `ripdpi-android` · `src/ffi/quic_initial_bridge.rs` | `libripdpi.so` |
| `JniNativeSignsBridge` (`:core:detection`) | `Java_com_poyka_ripdpi_core_detection_checker_JniNativeSignsBridge_*` | `ripdpi-android` · `src/ffi/native_signs_bridge.rs` | `libripdpi.so` |
| `Tun2SocksNativeBindings` (`.../core/Tun2SocksTunnel.kt`) | `Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_*` | `ripdpi-tunnel-android` · `src/entry.rs` (+ `src/session/`) | `libripdpi-tunnel.so` |
| `TunDeviceQualificationNativeBindings` (`.../core/Tun2SocksTunnel.kt`) | `Java_com_poyka_ripdpi_core_TunDeviceQualificationNativeBindings_*` | `ripdpi-tunnel-android` · `src/entry.rs`, `src/session/bind_to_device_probe.rs` | `libripdpi-tunnel.so` |
| `RipDpiRelayNativeBindings` (`.../core/RipDpiRelay.kt`) | `Java_com_poyka_ripdpi_core_RipDpiRelayNativeBindings_*` | `ripdpi-relay-android` · `src/lib.rs` (+ `lifecycle.rs`, `registry.rs`, `runtime.rs`, `telemetry.rs`) | `libripdpi-relay.so` |
| `RipDpiWarpNativeBindings` (`.../core/RipDpiWarp.kt`) | `Java_com_poyka_ripdpi_core_RipDpiWarpNativeBindings_*` | `ripdpi-warp-android` · `src/lib.rs` (+ `lifecycle.rs`, `provisioning.rs`, `endpoint_probe.rs`, `telemetry.rs`, `vpn_protect.rs`) | `libripdpi-warp.so` |

**Layering inside `libripdpi.so`:** the `ripdpi-android` crate is an
**export facade only**. `src/ffi.rs` defines the `export_jni!` macro;
`src/ffi/bridges.rs` aggregates the per-feature bridge modules. Each bridge
delegates real work to an adapter crate — `ripdpi-android-proxy-adapter`,
`ripdpi-android-diagnostics-adapter`, `ripdpi-android-platform-adapter`,
`ripdpi-android-fetch-adapter`, `ripdpi-android-telemetry-adapter`,
`ripdpi-android-vpn-protect-adapter`. Shared JNI machinery lives in
`android-support` and `ripdpi-android-bridge-support`.

---

## 4. Handle lifecycle rules

Native sessions are passed across the boundary as an opaque `jlong` **handle**,
never a raw pointer.

- **Registry.** `android_support::HandleRegistry<T>`
  (`native/rust/crates/android-support/src/handles.rs`) is
  `AtomicU64 next` (starts at `1`) + `Mutex<HashMap<u64, Arc<T>>>`. `insert`
  returns a handle, keeping it inside positive `i64` range; `get` clones the
  `Arc`; `remove` retires it.
- **`0` is the "no handle" sentinel.** `jniCreate` returns `0` on failure.
  Kotlin treats `0L` as failure (`RipDpiProxy.startProxy` /
  `Tun2SocksTunnel.start` throw `NativeError.SessionCreationFailed`). Rust-side
  `to_handle(jlong) -> Option<u64>` (`ripdpi-android-bridge-support` and
  `ripdpi-tunnel-android`) rejects `0` and negative values.
- **Tunnel config validation precedes handle allocation.** Android tunnel
  sessions accept only the required JNI flat-JSON `schemaVersion: 3`:
  `Tun2SocksTunnel.start` rejects other versions before entering JNI, and
  `ripdpi-tunnel-android` independently rejects missing, retired, or future
  versions before registry insertion. This JNI envelope is separate from the
  standalone `ripdpi-tunnel-config` YAML schema `2`.
  The additive immutable, validated `splitDnsPolicy` section carries
  ordered exact/suffix/geosite rules, numeric resolver candidates and bootstrap
  pins, canonical policy digests, and a bounded redaction-safe coverage token.
  Rust validates the complete section and its MapDNS/encrypted-resolver binding
  before registry insertion, then compiles it once during tunnel setup; no JNI
  environment or Java reference crosses an async suspension point.
- **Lifecycle ordering:** `create` → `start` → `stop` → `destroy`. `destroy`
  retires the handle from the registry; a handle must **never** be used after
  `destroy`. `stop` is idempotent on the Rust side.
- **Kotlin owns mutual exclusion.** The wrapper class holds one handle field
  guarded by a coroutine `Mutex`. `RipDpiProxy` keeps `handle` `@Volatile` and
  routes lifecycle-sensitive calls through `withActiveHandle { … }` under the
  mutex so `stop`/`destroy` cannot retire the handle mid-call.
  `RipDpiProxy.startProxy` registers `Job.invokeOnCompletion` to dispatch
  `stop`, and always `destroy`s in `finally`.
- **`jniStart` blocking contract differs per library** — see [§12](#12-data-plane-work-must-not-cross-jni-frequently).

---

## 5. Threading / JVM attachment assumptions

- **JNI export threads are JVM-attached.** Every `Java_*` function receives a
  `jni::EnvUnowned<'_>` valid only for that call, on the calling thread. Enter
  an owned `Env` with `.with_env(|env| …)`.
- **`Env` / `EnvUnowned` are `!Send`.** They must not cross threads, be stored,
  or be held across an `.await`. JNI work happens synchronously inside the
  export.
- **Rust worker threads are NOT attached.** Tokio runtime workers and native
  event loops created inside Rust have no `JNIEnv`. To call back into Java they
  must `JavaVM::attach_current_thread(|env| …)` per call — see
  `JniProtectCallback::protect` in
  `ripdpi-android-vpn-protect-adapter/src/lib.rs`.
- **`JavaVM` is `Send + Sync`** (a pointer wrapper). `libripdpi.so` keeps it in
  the process-static `JVM` `OnceCell`; the VPN-protect adapter re-derives a
  `JavaVM` handle via `JavaVM::from_raw(vm.get_raw())`.
- Worker threads must carry a readable `ripdpi-*` name (logcat hygiene; see
  [`.claude/rules/android-vpn-lifecycle.md`](../../.claude/rules/android-vpn-lifecycle.md)).
  The Unix-socket protect accept thread is named `vpn-protect-socket`.

---

## 6. Panic containment policy

The `android-jni` Cargo profile sets `panic = "unwind"`. **Unwinding across an
`extern "system"` boundary is undefined behavior** — every export must contain
panics.

- **Every `Java_*` export** wraps its delegate in
  `android_support::ffi_boundary(default_on_panic, || …)`
  (`native/rust/crates/android-support/src/ffi_boundary.rs`), which
  `catch_unwind`s and substitutes the sentinel. In `ripdpi-android` the
  `export_jni!` macro (`src/ffi.rs`) applies this uniformly; `ripdpi-tunnel-android`,
  `ripdpi-warp-android`, and `ripdpi-relay-android` call `ffi_boundary` per
  function.
- **`JNI_OnLoad`** uses `std::panic::catch_unwind` directly and returns
  `JNI_ERR` on panic.
- **C callbacks from foreign code** also contain panics — the VPN-protect
  registration entry (`ripdpi-android-vpn-protect-adapter/src/entry.rs`) handles
  `Outcome::Panic` explicitly.
- `android_support::install_panic_hook()` logs the panic via `log::error!`
  *before* `catch_unwind` returns, so `ffi_boundary` itself logs nothing.
- **Panic-default sentinels** (must match the value the Kotlin caller already
  treats as failure):

  | Return type | Sentinel | Meaning |
  |-------------|----------|---------|
  | `jstring`, `jobjectArray` | `core::ptr::null_mut()` | null payload |
  | `jboolean` | `jni::sys::JNI_FALSE` | false |
  | `jlong` | `0` | "no handle" |
  | `jlongArray` | `core::ptr::null_mut()` | null stats array |
  | `jint` | a caller-chosen **failure** code (e.g. `-1`) | never `0` — `0` means success |
  | `()` | `()` | no-op |

- `TunDeviceQualificationNativeBindings.jniProbeUnprivilegedBindToDevice()` is
  an instance JNI export in `ripdpi-tunnel-android/src/entry.rs`. Its inner
  probe returns only categorical codes: `0` unavailable, `1` supported, and
  `2` permission denied. The outer `ffi_boundary` reserves `-1` for a panic;
  Kotlin maps that distinct sentinel to `bridge_failure` and treats both
  `bridge_failure` and `permission_denied` as ineligible.

- CI scanners `scripts/ci/check_ffi_panic_boundary.py` and
  `scripts/ci/check_ffi_headers.py` enforce the boundary; do not bypass them.

---

## 7. Error mapping policy

Two error channels exist; pick one deliberately per method.

1. **Throw a Java exception.** `JniProxyError`
   (`ripdpi-android-bridge-support/src/lib.rs`) maps Rust errors to Java
   classes — this mapping is a **golden contract** (`error_exception_mapping.json`):

   | `JniProxyError` variant | Java exception |
   |-------------------------|----------------|
   | `InvalidConfig`, `InvalidArgument` | `java.lang.IllegalArgumentException` |
   | `IllegalState` | `java.lang.IllegalStateException` |
   | `Io` | `java.io.IOException` |
   | `Serialization` | `java.lang.RuntimeException` |

   `JniProxyError::throw(env)` logs `log::error!` then throws via the
   `android_support::throw_*` helpers (`src/exceptions.rs`).
   `android_support::sanitize_error_message(detail, user)` returns
   `"{user}: {detail}"` in debug builds and just `user` in release — internal
   detail is **stripped in release builds**.

2. **Return a sentinel value.** `jniCreate` → `0`; `jniStart` → non-zero
   `errno`-style code (`proxy_start_codes.json`: `success = 0`,
   `fallbackError = 22` = `EINVAL`, `positive_errno` semantics);
   `jboolean` predicates → `JNI_FALSE`. Telemetry/poll methods return a JSON
   string and surface errors in-band; a `null`/blank string maps to an idle
   snapshot on the Kotlin side.

Never let a Rust `Result::Err` silently become a success sentinel.

### Shared handle-error helpers

`ripdpi-android-bridge-support` owns the canonical "bad handle" message
wording so every adapter's exception text is byte-identical per subsystem:

- `invalid_handle_message(kind)` / `unknown_handle_message(kind)` →
  `"Invalid {kind} handle"` / `"Unknown {kind} handle"`.
- `JniProxyError::invalid_handle()` / `unknown_handle()` — the proxy-typed
  constructors (`InvalidArgument` variant) built on those messages.

`ripdpi-android-proxy-adapter` and `ripdpi-android-diagnostics-adapter` use
these; new adapters that validate a `jlong` handle should too.

### Known duplication — consolidation follow-ups

Two duplications are **intentionally not consolidated** yet; consolidating
either would change observable behaviour or the dependency graph, so each is
a deliberate follow-up rather than a mechanical refactor:

1. **Per-entry `with_env(...).into_outcome()` match.** Every entry function in
   `ripdpi-android-proxy-adapter/src/entry.rs` and
   `ripdpi-android-diagnostics-adapter/src/lib.rs` repeats the same
   `Ok` / `Err` / `Panic` arm shape. It is not extracted into a generic
   `run_jni_entry` helper because the two crates diverge on error wording:
   the proxy adapter routes `Err`/`Panic` through `log_and_throw`, which
   applies `sanitize_error_message` (detail stripped in release builds), while
   the diagnostics adapter throws a raw `format!("...: {err}")`. Unifying them
   would change the Java-visible exception message for one crate. A future
   change must first align both on one sanitisation policy, then extract the
   helper.
2. **`to_handle` reimplemented in relay/warp.** `ripdpi-relay-android` and
   `ripdpi-warp-android` carry their own handle decoders (a private
   `to_handle` and inline `u64::try_from` respectively) instead of
   `ripdpi-android-bridge-support::to_handle`. The local copies accept handle
   `0`; the canonical helper rejects it — behaviourally equivalent only
   because those registries never issue handle `0`. Consolidating requires
   relay/warp-android to depend on `ripdpi-android-bridge-support`, adding an
   internal dependency edge, so it is deferred to a deliberate decision.

---

## 8. Callback registration / unregistration rules

- **VPN protect callback** — registered by
  `jniRegisterVpnProtect(vpnService): jlong`, cleared by
  `jniUnregisterVpnProtect(token: jlong)`. These are `@JvmStatic` companion
  `external fun`s on `RipDpiProxyNativeBindings`, `RipDpiRelayNativeBindings`,
  `RipDpiWarpNativeBindings`, and `RipDpiAmneziaWgNativeBindings`. Register returns a generation token;
  `VpnNativeProtectRegistration`
  (`core/service/.../services/VpnNativeProtectRegistration.kt`) keeps the proxy,
  relay, WARP, and AWG tokens, calls all registers on VPN start, and passes each token
  back to its unregister on VPN stop. Registration and unregistration must be
  **symmetric** — see [§10](#10-vpnserviceprotect-callback-rules); the
  generation guard makes an *asymmetric* (stale) unregister a safe no-op rather
  than a clobber — see below.
- **Direct-DNS underlay binder** — `Tun2SocksNativeBindings` loads
  `libripdpi-tunnel.so` before its static register/unregister wrappers call the
  private JNI exports. Registration preflights
  `directDnsLeaseGeneration()J`, `isDirectDnsLeaseCurrent(J)Z`, and
  `bindDirectDnsSocket(IJ)Z` before publishing the `GlobalRef`. Rust snapshots
  one non-zero lease token per request, and both Kotlin and Rust recheck it
  after `protect(fd)` plus `Network.bindSocket(dup(fd))`. Registry replacement
  and stale unregister are generation guarded, so an in-flight call from an
  old VPN session cannot publish a result into a newer session.
- **Runtime readiness callback** (ADR 0003) — registered by
  `jniRegisterReadinessListener(handle: jlong, listener): jlong`, cleared by
  `jniUnregisterReadinessListener(handle: jlong)`, on `RipDpiProxyNativeBindings`,
  `RipDpiRelayNativeBindings`, `RipDpiWarpNativeBindings`, and
  `RipDpiAmneziaWgNativeBindings`. `listener` is a
  `RuntimeReadinessListener` whose `onRuntimeReady()V` the native runtime
  invokes **exactly once**, from the runtime thread the moment the listener
  binds (right after the `runtime_ready` telemetry event), so the Kotlin
  wrappers no longer wait out a 50 ms telemetry-poll interval. This is a strict
  **lifecycle-class** callback — one `attach_current_thread` + one `call_method`
  per session — and is the explicitly-permitted exception to the
  no-callback-per-event rule in
  [§12](#12-data-plane-work-must-not-cross-jni-frequently); it MUST NOT be
  reused for higher-frequency events. Unlike VPN-protect, the slot is
  **per-handle** (stored on the session's runtime, not a process-global slot),
  so it needs no generation token — the handle is the guard, and the `GlobalRef`
  is released when the observer is replaced (unregister) or the session is
  destroyed. The `onRuntimeReady` method is kept from R8 stripping by a
  consumer ProGuard rule (`core/engine/consumer-rules.pro`). The 50 ms poll in
  `RuntimeReadiness.awaitRuntimeReady` / `RipDpiWarp.awaitReady` stays as a
  graceful-degradation fallback (the register returns `0` when unsupported —
  e.g. the Apps Script relay backend, or an older `.so`). Every Android JNI
  cdylib has a symbol allowlist in its crate's `jni-symbols.baseline`: proxy,
  tunnel, relay, warp, and AmneziaWG. The `jni-symbol-diff.yml` workflow builds
  all five arm64-v8a ELFs and verifies each one against its own allowlist with
  `.github/scripts/check-jni-symbols.sh`.
- **Telemetry recorder** — installed process-wide once, in `libripdpi.so`
  `JNI_OnLoad` via `ripdpi_android_telemetry_adapter::install_recorder()`. Not
  per-session; no unregister.
- **Event rings** — `android_support::events` exposes `drain_*` / `clear_*`
  per domain (`proxy`, `tunnel`, `relay`, `warp`, `diagnostics`); Kotlin drains
  them through the poll methods.
- **Rule:** any callback that captures a `GlobalRef` or `JavaVM` must have a
  matching unregister tied to a lifecycle event, or the referenced Java object
  is pinned against GC for the process lifetime.

### Stale-unregister guard — VPN protect registry

The VPN protect callback lives in a single process-global slot
(`ripdpi-native-protect`, one slot per `.so`). If a VPN session is torn down
and a new one starts before the old session's unregister runs, an
*unconditional* unregister would clear the **new** session's callback —
outbound sockets would then fail `protect_socket_via_callback` and risk a
routing loop into the TUN (see `vpnservice-protect-invariant.md`).

The registry is generation-guarded. `register_protect_callback_versioned`
stamps the slot with a monotonic `ProtectGeneration` and returns it;
`unregister_protect_callback_if(generation)` clears the slot **only** on a
generation match. Because register and unregister are two separate JNI calls
with no Rust object spanning them, the token round-trips through Kotlin:

1. `jniRegisterVpnProtect` returns the generation as a `jlong` (`0` when
   registration failed).
2. `jniUnregisterVpnProtect(jlong)` takes it back; a stale token (a superseded
   session) or a `0` token clears nothing — a safe no-op.
3. `VpnNativeProtectRegistration` holds the proxy, relay, WARP, AWG, and
   tunnel direct-DNS tokens between
   register and unregister.

`register_protect_callback` / `unregister_protect_callback` remain as
unconditional back-compat wrappers for callers that do not race a later
session. The sibling root-helper registry
(`ripdpi-runtime-platform::root_helper`) uses the same generation pattern, but
fully Rust-internally — its register/unregister callers are RAII guards that
hold the token, so no JNI round-trip is needed there.

---

## 9. TUN fd and socket fd ownership

- **TUN fd.** Kotlin's `VpnService.Builder.establish()` yields a
  `ParcelFileDescriptor`; its raw `int` is passed as `tunFd` to
  `Tun2SocksNativeBindings.jniStart(handle, tunFd)`. Rust duplicates and adopts
  the duplicate TUN fd on `start` — fd handling lives in
  `ripdpi-tunnel-android/src/session/lifecycle/fd.rs`. Kotlin must keep the
  `ParcelFileDescriptor` open for the lifetime of the native session and close
  it only after `destroy`. The TUN device is the data-plane ingress/egress and
  is **never** read/written from Kotlin.
- **Upstream socket fds.** Created inside Rust. Every non-loopback upstream
  socket must be passed to `VpnService.protect()` before `connect`/`bind`
  returns (see [§10](#10-vpnserviceprotect-callback-rules) and
  [`.claude/rules/vpnservice-protect-invariant.md`](../../.claude/rules/vpnservice-protect-invariant.md)).
  Rust retains ownership of upstream fds for their whole life.
- **Unix-socket protect path** does not transfer fd ownership: Rust sends a
  duplicate of the fd via `SCM_RIGHTS`; Kotlin (`VpnProtectSocketServer`)
  receives a dup, calls `protect`, acks, and closes its dup. The original fd
  stays owned by Rust.
- **Root-helper fd-passing** — see [§11](#11-root-helper-ipc-and-fd-passing-boundary).

---

## 10. `VpnService.protect()` callback rules

Two mechanisms exist; selection is automatic.

**Mechanism A — JNI callback (preferred).**
`jniRegisterVpnProtect` → `ripdpi-android/src/ffi/vpn_protect_bridge.rs` →
`ripdpi_android_vpn_protect_adapter::register_entry(env, vpn_service)`. The
adapter calls `env.get_java_vm()` and `env.new_global_ref(&vpn_service)`, then
builds a `JniProtectCallback { vm, vpn_service: Global<JObject<'static>> }` and
registers it via `ripdpi_native_protect::register_protect_callback_versioned`,
returning the generation token (see
[§8](#8-callback-registration--unregistration-rules)).
`JniProtectCallback::protect(fd)` (`ripdpi-android-vpn-protect-adapter/src/lib.rs`)
does `vm.attach_current_thread(|env| env.call_method(vpn_service, "protect",
"(I)Z", [fd]))`. Result mapping: `true` → `Ok(())`; `false` → `PermissionDenied`;
JNI error → `io::Error::other`. The same path exists for relay xHTTP, WARP, and
AmneziaWG via the sibling `vpn_protect.rs` modules in their Android cdylib crates.

**Mechanism B — Unix-domain-socket fallback.**
`VpnProtectSocketServer` (`core/service/.../services/VpnProtectSocketServer.kt`)
binds a filesystem-namespace UDS, accepts on a `vpn-protect-socket` thread,
receives fds via `SCM_RIGHTS`, calls `VpnService::protect`, and writes a 1-byte
ack.

**Registry & selection.** `ripdpi_native_protect`
(`native/rust/crates/ripdpi-native-protect/src/lib.rs`) holds a global
`RwLock<Option<Arc<dyn ProtectCallback>>>` with `register_protect_callback`,
`unregister_protect_callback`, `has_protect_callback`, and
`protect_socket_via_callback`. `ripdpi-runtime-platform` picks Mechanism A when
`has_protect_callback()` is true, else Mechanism B.

**Rules.**

- Protection requirement is explicit per runtime. Relay JSON crossing JNI carries `socketProtection=inactive|vpn_required`; proxy mode selects `inactive` even if a stale callback happens to exist, while VPN mode selects `vpn_required` and fails closed if the callback is absent or rejects an fd. Callback presence must never be used as a proxy for runtime mode.
- `protect()` **must succeed before `connect`/`bind` returns** control to the
  caller; on failure the socket is closed and the connection fails — never
  proceed unprotected.
- Registration happens at VPN service start, unregistration at stop, via
  `VpnNativeProtectRegistration`. The `Global<JObject>` pins the `VpnService`
  against GC until unregister drops it.
- Diagnostics RAW_PATH scans stop the VPN service first (which unregisters both
  mechanisms) and connect directly — no protection needed there.
- Loopback (`127.0.0.1` / `[::1]`) sockets are exempt.

---

## 11. Root-helper IPC and fd-passing boundary

The root helper is a **separate process**, not a `.so` — this boundary is
Unix-socket IPC, **not JNI**.

- **Kotlin lifecycle.** `RootHelperManager`
  (`core/service/.../services/RootHelperManager.kt`) — when `root_mode_enabled`
  is set — extracts the `ripdpi-root-helper` binary from APK assets, launches it
  via `su`, and polls a filesystem-namespace Unix socket (`root_helper.sock`)
  for readiness, guarded by a 32-byte secure-random session nonce
  (`root_helper.sock.nonce`). `RootDetector` performs the `su` access test.
- **Protocol crate.** `ripdpi-root-helper-protocol` — `commands.rs` (the
  `CMD_*` string constants: `probe_capabilities`, `send_fake_rst`,
  `send_seqovl_tcp`, `send_multi_disorder_tcp`, `send_ip_fragmented_tcp/udp`,
  `send_raw_ip_packet`, `shutdown`, …), `params.rs`, `wire.rs`, `scm_rights.rs`.
- **Helper binary.** `ripdpi-root-helper` crate (`src/main.rs`, `dispatch.rs`,
  `handlers.rs`) runs as uid 0; privileged primitives live in
  `ripdpi-privileged-ops`.
- **Rust client & dispatch.** `ripdpi-runtime-platform/src/root_helper_client.rs`
  connects per operation, sends a JSON command plus a socket fd via
  `SCM_RIGHTS`, and receives a response plus an optional **replacement fd**
  (`TCP_REPAIR`-class operations) which is swapped in via `dup2()`.
  `ripdpi-runtime-platform/src/root_helper.rs` is the global registry; each
  privileged function checks `with_root_helper()` first.
- **Mandatory non-root fallback.** Every privileged op must fall back to a
  local non-privileged path (or be inert) when no helper is registered. The app
  must fully function on non-rooted devices; root features are opt-in behind
  `root_mode_enabled`.
- **Trust boundary.** The helper is a uid-0 process — treat every input as
  untrusted; protocol changes are security-sensitive.

---

## 12. Data-plane work must not cross JNI frequently

**The data plane stays entirely in Rust.** JNI is crossed only at coarse
granularity.

- A JNI call carries measurable overhead (~3 µs/event of JVM/JNI work). A
  per-packet or per-byte JNI call is a CPU bottleneck at line rate.
- Packet processing — SOCKS5 sessions, the TUN packet pump, desync mutation,
  relay transport, DNS forwarding — runs **fully native** with **no JNI on the
  hot path**.
- The boundary is crossed only for: **lifecycle** (`create`/`start`/`stop`/
  `destroy`), **~1 Hz telemetry polling**, **network-snapshot updates**, and
  **per-socket** (not per-packet) `protect()` calls.
- **Telemetry is pull-model.** `:core:service` polls `jniPollTelemetry` /
  `jniGetTelemetry` / `jniPollProgress` once per second; Rust accumulates into
  a bounded event ring drained on poll. Do **not** add a callback-per-event
  JNI path from Rust into Kotlin.
- **`jniStart` blocking contract differs:**
  - `RipDpiProxyNativeBindings.jniStart` runs the **blocking** native proxy
    event loop on the caller thread — `RipDpiProxy` invokes it under
    `withContext(Dispatchers.IO)` and `yield()`s first.
  - `Tun2SocksNativeBindings.jniStart` is **bounded-blocking**: it duplicates
    the TUN fd, spawns the tunnel worker, and waits up to five seconds for the
    packet-loop readiness barrier after all fallible loop setup. Long-running
    packet I/O stays on the worker; `stop` may block briefly waiting for a
    running worker to exit. A pre-readiness timeout never joins on the JNI
    caller: a shared-runtime reaper owns the cancelled worker and duplicated fd
    until exit while the failed-start session remains safe to destroy.

---

## 13. Checklist — adding a new JNI method

> **Constraint:** never change or rename an existing JNI method — the symbol
> name is an ABI contract. This checklist is for **adding** one.

1. **Kotlin side.** Add the `external fun` to the correct `*NativeBindings`
   class (e.g. `RipDpiProxyNativeBindings` in `RipDpiProxy.kt`). Keep the name
   `jni<Verb>`; expose it through the wrapper class's coroutine API. Run
   lifecycle-sensitive calls under the wrapper's `Mutex` and on
   `Dispatchers.IO`.
2. **Rust export.** Add the `Java_<pkg>_<Class>_<method>` function in the
   matching bridge module (e.g. `ripdpi-android/src/ffi/proxy_bridge/…`, or the
   `entry.rs` of the tunnel/relay/warp crate). The package/class segments must
   exactly match the Kotlin owner. Re-export it from `bridges.rs` if it is in
   `ripdpi-android`.
3. **Panic boundary.** Wrap the delegate in `android_support::ffi_boundary`
   (or use `export_jni!`). Choose the correct panic-default sentinel from the
   [§6](#6-panic-containment-policy) table — for `jint`, never `0`.
4. **Delegate, don't implement.** Put real logic in the adapter crate
   (`ripdpi-android-*-adapter`) or the runtime crate; the `*-android` crate
   stays an export facade.
5. **Handles.** If it creates a session, return a `HandleRegistry` handle as
   `jlong` (`0` on failure). If it operates on a session, accept a `jlong` and
   validate with `to_handle`. Pair every `create` with a `destroy`.
6. **Errors.** Decide: throw (`JniProxyError::throw`) or sentinel return. Keep
   the `JniProxyError` → Java-class mapping intact; in release builds detail is
   sanitized.
7. **Threading.** Do JNI work synchronously inside the export. Never hold
   `Env`/`EnvUnowned` across `.await` or move it between threads. A Rust worker
   calling back into Java must `attach_current_thread`.
8. **Sockets.** Any new non-loopback outbound socket must call `protect()`
   before `connect`/`bind` returns ([§10](#10-vpnserviceprotect-callback-rules)).
9. **Hot path.** Confirm the method is lifecycle/telemetry/control, not
   per-packet ([§12](#12-data-plane-work-must-not-cross-jni-frequently)).
10. **Symbol baseline.** Adding a `#[no_mangle]` export changes
    `native/rust/crates/ripdpi-android/jni-symbols.baseline`, and a unit test in
    `ripdpi-android/src/lib.rs` (`jni_baseline_is_non_empty_and_contains_expected_symbols`)
    plus the `jni_facade_exports_stable_native_entrypoints` test guard the
    symbol set. **Note:** `jni-symbols.baseline` matches the hook-protected
    `*baseline*` pattern — regenerating it is a human/explicitly-approved step,
    not an agent edit.
11. **Tests / goldens.** Add adapter-crate tests
    (`ripdpi-android-bridge-support` has a `test-support` JVM harness). If the
    method changes a wire payload, update the relevant golden contract under
    human supervision (see
    [`.claude/rules/golden-bless-discipline.md`](../../.claude/rules/golden-bless-discipline.md)).
12. **CI.** `scripts/ci/check_ffi_panic_boundary.py`,
    `scripts/ci/check_ffi_headers.py`, and `scripts/ci/check_unsafe_boundaries.py`
    must stay green.

---

## Cross-references

| Topic | Source |
|-------|--------|
| Whole-app architecture, control/data plane | [`ARCHITECTURE.md`](ARCHITECTURE.md) |
| Crate taxonomy, Android-adapter layer | [`NATIVE_RUST.md`](NATIVE_RUST.md) |
| Adding strategies / relays / settings | [`FEATURE_EXTENSION_GUIDE.md`](FEATURE_EXTENSION_GUIDE.md) |
| Socket-protect invariant | [`.claude/rules/vpnservice-protect-invariant.md`](../../.claude/rules/vpnservice-protect-invariant.md) |
| LMK / Doze / tokio-shutdown / thread naming | [`.claude/rules/android-vpn-lifecycle.md`](../../.claude/rules/android-vpn-lifecycle.md) |
| AI-generated JNI diff acceptance gate | [`.claude/rules/llm-rust-prompts.md`](../../.claude/rules/llm-rust-prompts.md) |
| JNI authoring patterns | `rust-jni` skill |
| Root helper IPC narrative | [`AGENTS.md`](../../AGENTS.md) § Root Helper IPC |
