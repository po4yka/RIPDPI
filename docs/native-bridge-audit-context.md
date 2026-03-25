# RIPDPI Native-Android Bridge -- Audit Context

Android VPN/proxy app. Rust native modules provide a local SOCKS5 proxy and VPN
tunnel. This document covers the JNI boundary between Kotlin and Rust for
security audit purposes.

## 1. Architecture Overview

```
Kotlin (Android)                         Rust (NDK .so)
 +-------------------+                   +---------------------------+
 | RipDpiProxy       |  -- JSON -->      | ripdpi-android (libripdpi)|
 |  .create(json)    |  jlong handle     |  proxy lifecycle          |
 |  .start(handle)   |  <-- jint code    |  telemetry polling        |
 |  .stop(handle)    |                   |  diagnostics              |
 |  .destroy(handle) |                   +---------------------------+
 +-------------------+
 +-------------------+                   +---------------------------+
 | Tun2SocksTunnel   |  -- JSON -->      | ripdpi-tunnel-android     |
 |  .create(json)    |  jlong handle     |  (libripdpi-tunnel)       |
 |  .start(h, tunFd) |  <-- jint fd      |  TUN tunnel lifecycle     |
 |  .stop(handle)    |                   |  packet stats             |
 |  .destroy(handle) |                   +---------------------------+
 +-------------------+
```

**Design decisions:**
- Handle-based resource management: Rust stores sessions in a `HandleRegistry<T>`
  keyed by `u64`. Kotlin holds the handle as `Long`.
- JSON is the universal interchange format (`kotlinx.serialization` <-> `serde_json`).
- No callbacks from Rust to Kotlin. All data retrieval is poll-based.
- All JNI entry points wrap inner logic in `catch_unwind(AssertUnwindSafe(...))`.
- Two native libraries: `libripdpi.so` (proxy + diagnostics) and
  `libripdpi-tunnel.so` (TUN tunnel).

**Threat model:**
- Untrusted input: JSON config strings from app settings UI.
- FD passing: TUN file descriptor crosses the JNI boundary.
- Concurrent access: multiple Kotlin coroutines may call JNI concurrently.
- Resource lifecycle: handle leak, use-after-free, double-destroy.

---

## 2. Handle Registry (Shared Infrastructure)

File: `native/rust/crates/android-support/src/lib.rs`

```rust
pub struct HandleRegistry<T> {
    next: AtomicU64,
    inner: Mutex<HashMap<u64, Arc<T>>>,
}

impl<T> HandleRegistry<T> {
    pub fn new() -> Self {
        Self { next: AtomicU64::new(1), inner: Mutex::new(HashMap::new()) }
    }

    pub fn insert(&self, value: T) -> u64 {
        let handle = fetch_add_u64(&self.next, 1, Ordering::Relaxed);
        self.inner.lock().unwrap_or_else(PoisonError::into_inner)
            .insert(handle, Arc::new(value));
        handle
    }

    pub fn get(&self, handle: u64) -> Option<Arc<T>> {
        self.inner.lock().unwrap_or_else(PoisonError::into_inner)
            .get(&handle).cloned()
    }

    pub fn remove(&self, handle: u64) -> Option<Arc<T>> {
        self.inner.lock().unwrap_or_else(PoisonError::into_inner)
            .remove(&handle)
    }
}
```

Handle validation (both crates):
```rust
// ripdpi-tunnel-android/src/lib.rs
pub(crate) fn to_handle(value: jlong) -> Option<u64> {
    u64::try_from(value).ok().filter(|handle| *handle != 0)
}
```

The proxy crate has an equivalent `to_handle` in `ripdpi-android/src/lib.rs`.

**Audit notes:**
- `fetch_add` with `Relaxed` ordering wraps at `u64::MAX` -- handle reuse.
- `PoisonError::into_inner` recovers from poisoned mutex (panicked thread).
- No ABA protection on handle counter.

---

## 3. Proxy Session Lifecycle (Rust)

### Session types

File: `native/rust/crates/ripdpi-android/src/proxy/registry.rs`

```rust
pub(crate) static SESSIONS: Lazy<HandleRegistry<ProxySession>> =
    Lazy::new(HandleRegistry::new);

pub(crate) struct ProxySession {
    pub(crate) config: RuntimeConfig,
    pub(crate) runtime_context: Option<ProxyRuntimeContext>,
    pub(crate) telemetry: Arc<ProxyTelemetryState>,
    pub(crate) state: Mutex<ProxySessionState>,
}

pub(crate) enum ProxySessionState {
    Idle,
    Running { listener_fd: i32, control: Arc<EmbeddedProxyControl> },
}

pub(crate) fn try_mark_proxy_running(
    state: &mut ProxySessionState,
    listener_fd: i32,
    control: Arc<EmbeddedProxyControl>,
) -> Result<(), &'static str> {
    match *state {
        ProxySessionState::Idle => {
            *state = ProxySessionState::Running { listener_fd, control };
            Ok(())
        }
        ProxySessionState::Running { .. } => Err("Proxy session is already running"),
    }
}

pub(crate) fn ensure_proxy_destroyable(state: &ProxySessionState) -> Result<(), &'static str> {
    if matches!(*state, ProxySessionState::Running { .. }) {
        Err("Cannot destroy a running proxy session")
    } else {
        Ok(())
    }
}
```

### create_session

File: `native/rust/crates/ripdpi-android/src/proxy/lifecycle.rs`

```rust
pub(crate) fn create_session(env: &mut JNIEnv, config_json: JString) -> jlong {
    let json: String = match env.get_string(&config_json) {
        Ok(value) => value.into(),
        Err(_) => {
            throw_illegal_argument(env, "Invalid proxy config payload");
            return 0;
        }
    };
    let payload = match parse_proxy_config_json(&json) {
        Ok(payload) => payload,
        Err(err) => { err.throw(env); return 0; }
    };
    let envelope = match runtime_config_envelope_from_payload(payload) {
        Ok(envelope) => envelope,
        Err(err) => { err.throw(env); return 0; }
    };
    // ... log level resolution omitted for brevity ...
    let config = envelope.config;
    if let Err(err) = runtime::create_listener(&config) {
        JniProxyError::Io(err).throw(env);
        return 0;
    }
    let telemetry = Arc::new(ProxyTelemetryState::new());
    set_android_log_scope_level(telemetry.log_scope().to_string(), native_log_level);
    telemetry.set_autolearn_state(config.host_autolearn.enabled, 0, 0);

    SESSIONS.insert(ProxySession {
        config, runtime_context: envelope.runtime_context, telemetry,
        state: Mutex::new(ProxySessionState::Idle),
    }) as jlong
}
```

### start_session (blocking)

```rust
pub(crate) fn start_session(env: &mut JNIEnv, handle: jlong) -> jint {
    let session = match lookup_proxy_session(handle) {
        Ok(session) => session,
        Err(err) => { err.throw(env); return libc::EINVAL; }
    };
    let config = session.config.clone();
    let (listener, listener_fd) = match open_proxy_listener(&config, &session.telemetry) {
        Ok(parts) => parts,
        Err(err) => {
            throw_io_exception(env, format!("Failed to open proxy listener: {err}"));
            return libc::EINVAL;
        }
    };
    session.telemetry.clear_last_error();
    let control = Arc::new(EmbeddedProxyControl::new_with_context(
        Some(Arc::new(ProxyTelemetryObserver { state: session.telemetry.clone() })),
        session.runtime_context.clone(),
    ));
    {
        let mut state = session.state.lock().unwrap_or_else(PoisonError::into_inner);
        if let Err(message) = try_mark_proxy_running(&mut state, listener_fd, control.clone()) {
            throw_illegal_state(env, message);
            return libc::EINVAL;
        }
    }
    // BLOCKING: runs the proxy event loop on the calling thread
    let result = runtime::run_proxy_with_embedded_control(config, listener, control);
    let mut state = session.state.lock().unwrap_or_else(PoisonError::into_inner);
    *state = ProxySessionState::Idle;
    match result {
        Ok(()) => 0,
        Err(err) => positive_os_error(&err, libc::EINVAL),
    }
}
```

**stop_session**: Reads `listener_fd` and `control` from state. Calls
`control.request_shutdown()` then `shutdown(listener_fd, Both)` via
`nix::sys::socket::shutdown`. Resets state to `Idle` in `start_session`'s return.

**destroy_session**: Validates state is `Idle`, calls `SESSIONS.remove(handle)`.

**Audit notes:**
- `start_session` is **blocking** -- occupies the calling JNI thread.
- `listener_fd` stored as raw `i32` -- becomes stale after proxy exits.
- Race window: `stop_session` reads `Running { listener_fd, .. }` but proxy
  may have already exited and reset state to `Idle`.

---

## 4. Tunnel Lifecycle + TUN FD Passing (Highest Risk)

### Session types

File: `native/rust/crates/ripdpi-tunnel-android/src/session/registry.rs`

```rust
pub(crate) static SESSIONS: Lazy<HandleRegistry<TunnelSession>> =
    Lazy::new(HandleRegistry::new);

pub(crate) struct TunnelSession {
    pub(crate) runtime: Arc<Runtime>,  // shared Tokio multi-thread (2 workers)
    pub(crate) config: Arc<ripdpi_tunnel_config::Config>,
    pub(crate) last_error: Arc<Mutex<Option<String>>>,
    pub(crate) telemetry: Arc<TunnelTelemetryState>,
    pub(crate) state: Mutex<TunnelSessionState>,
}

pub(crate) enum TunnelSessionState {
    Ready,
    Starting,
    Running { cancel: Arc<CancellationToken>, stats: Arc<Stats>, worker: JoinHandle<()> },
}
```

### start_session (fd dup + worker spawn)

File: `native/rust/crates/ripdpi-tunnel-android/src/session/lifecycle.rs`

```rust
pub(crate) fn start_session(env: &mut JNIEnv, handle: jlong, tun_fd: jint) {
    let session = match lookup_tunnel_session(handle) {
        Ok(session) => session,
        Err(message) => { throw_illegal_argument(env, message); return; }
    };
    if let Err(message) = validate_tun_fd(tun_fd) {
        throw_illegal_argument(env, message);
        return;
    }
    // Duplicate the fd so run_tunnel owns an independent copy.
    // If VpnService revokes the original fd, the dup'd fd remains valid.
    let owned_fd = match nix::unistd::dup(tun_fd) {
        Ok(fd) => fd,
        Err(err) => {
            throw_io_exception(env, format!("Failed to dup TUN fd: {err}"));
            return;
        }
    };
    let runtime = session.runtime.clone();
    let cancel = Arc::new(CancellationToken::new());
    let stats = Arc::new(Stats::new());
    let config = session.config.clone();
    let last_error = session.last_error.clone();
    let telemetry = session.telemetry.clone();
    let dns_histogram = telemetry.dns_histogram.clone();
    stats.set_dns_latency_observer(Arc::new(move |ms| dns_histogram.record(ms)));

    {
        let mut state = session.state.lock().unwrap_or_else(PoisonError::into_inner);
        if let Err(message) = ensure_tunnel_start_allowed(&state) {
            let _ = nix::unistd::close(owned_fd);
            throw_illegal_state(env, message);
            return;
        }
        *state = TunnelSessionState::Starting;
    }

    // ... clear last_error, mark_started omitted ...
    telemetry.mark_started(format!("{}:{}", session.config.socks5.address,
                                    session.config.socks5.port));

    let worker_cancel = cancel.clone();
    let worker_stats = stats.clone();
    let worker = match std::thread::Builder::new()
        .name("ripdpi-tunnel-worker".into())
        .spawn(move || {
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                runtime.block_on(ripdpi_tunnel_core::run_tunnel(
                    config, owned_fd, (*worker_cancel).clone(), worker_stats.clone(),
                ))
            }));
            match result {
                Ok(Ok(())) => {}
                Ok(Err(err)) => {
                    // ... log + record error ...
                    let mut guard = last_error.lock()
                        .unwrap_or_else(PoisonError::into_inner);
                    *guard = Some(err.to_string());
                }
                Err(panic) => {
                    let msg = if let Some(s) = panic.downcast_ref::<&str>() {
                        s.to_string()
                    } else if let Some(s) = panic.downcast_ref::<String>() {
                        s.clone()
                    } else { "unknown panic".to_string() };
                    let mut guard = last_error.lock()
                        .unwrap_or_else(PoisonError::into_inner);
                    *guard = Some(format!("Tunnel worker panicked: {msg}"));
                }
            }
            telemetry.mark_stopped();
        }) {
        Ok(worker) => worker,
        Err(err) => {
            rollback_failed_tunnel_start(&session, owned_fd,
                format!("failed to spawn tunnel worker thread: {err}"));
            throw_io_exception(env, format!("Failed to spawn tunnel worker: {err}"));
            return;
        }
    };

    let mut state = session.state.lock().unwrap_or_else(PoisonError::into_inner);
    *state = TunnelSessionState::Running { cancel, stats, worker };
}
```

### Helpers

```rust
pub(crate) fn validate_tun_fd(tun_fd: jint) -> Result<(), &'static str> {
    if tun_fd < 0 { Err("Invalid TUN file descriptor") } else { Ok(()) }
}

pub(crate) fn rollback_failed_tunnel_start(
    session: &TunnelSession, owned_fd: i32, message: String,
) {
    let _ = nix::unistd::close(owned_fd);
    {
        let mut guard = session.last_error.lock()
            .unwrap_or_else(PoisonError::into_inner);
        *guard = Some(message.clone());
    }
    session.telemetry.record_error(message);
    session.telemetry.mark_stopped();
    {
        let mut state = session.state.lock()
            .unwrap_or_else(PoisonError::into_inner);
        *state = TunnelSessionState::Ready;
    }
}
```

**stop_session**: Cancels via `CancellationToken`, joins worker thread, resets
state to `Ready`.

**destroy_session**: Validates state is `Ready`, removes from `SESSIONS`.

**Audit notes:**
- `validate_tun_fd` only checks `>= 0` -- does not verify it's a TUN device.
- If worker thread panics before `File::from_raw_fd(owned_fd)`, the fd leaks
  inside the panicked thread (only rollback path closes it explicitly).
- State transitions: `Ready -> Starting -> Running` uses two separate lock
  acquisitions (lines 101-108 and 163-164) -- non-atomic.
- Worker thread duplicates panic message extraction logic (vs `errors.rs`).

---

## 5. Error Handling Across the Bridge

### Rust: JniProxyError

File: `native/rust/crates/ripdpi-android/src/errors.rs`

```rust
#[derive(Debug, thiserror::Error)]
pub(crate) enum JniProxyError {
    #[error("invalid configuration: {0}")]
    InvalidConfig(String),       // -> IllegalArgumentException

    #[error("{0}")]
    InvalidArgument(String),     // -> IllegalArgumentException

    #[error("{0}")]
    IllegalState(&'static str),  // -> IllegalStateException

    #[error("I/O failure: {0}")]
    Io(#[from] std::io::Error),  // -> IOException

    #[error("{0}")]
    Serialization(#[from] serde_json::Error),  // -> RuntimeException
}

impl JniProxyError {
    pub(crate) fn throw(self, env: &mut JNIEnv) {
        let (class, msg) = match &self {
            Self::InvalidConfig(_) | Self::InvalidArgument(_) =>
                ("java/lang/IllegalArgumentException", self.to_string()),
            Self::IllegalState(_) => ("java/lang/IllegalStateException", self.to_string()),
            Self::Io(_) => ("java/io/IOException", self.to_string()),
            Self::Serialization(_) => ("java/lang/RuntimeException", self.to_string()),
        };
        let _ = env.throw_new(class, &msg);
    }
}

pub(crate) fn extract_panic_message(payload: Box<dyn Any + Send>) -> String {
    payload.downcast_ref::<String>().map(String::as_str)
        .or_else(|| payload.downcast_ref::<&str>().copied())
        .unwrap_or("unknown panic").to_string()
}
```

### Rust: throw helpers

File: `native/rust/crates/android-support/src/lib.rs`

```rust
pub fn throw_illegal_argument(env: &mut JNIEnv, message: impl AsRef<str>) {
    let _ = env.throw_new("java/lang/IllegalArgumentException", message.as_ref());
}
pub fn throw_illegal_state(env: &mut JNIEnv, message: impl AsRef<str>) {
    let _ = env.throw_new("java/lang/IllegalStateException", message.as_ref());
}
pub fn throw_io_exception(env: &mut JNIEnv, message: impl AsRef<str>) {
    let _ = env.throw_new("java/io/IOException", message.as_ref());
}
pub fn throw_runtime_exception(env: &mut JNIEnv, message: impl AsRef<str>) {
    let _ = env.throw_new("java/lang/RuntimeException", message.as_ref());
}
```

### Kotlin: NativeError

File: `core/data/src/main/kotlin/com/poyka/ripdpi/data/NativeError.kt`

```kotlin
sealed interface NativeError {
    class AlreadyRunning(component: String)
        : IllegalStateException("$component is already running"), NativeError

    class NotRunning(component: String)
        : IllegalStateException("$component is not running"), NativeError

    class SessionCreationFailed(component: String)
        : Exception("Native $component session was not created"), NativeError

    class NativeIoError(message: String, cause: IOException)
        : IOException(message, cause), NativeError
}
```

**Audit notes:**
- All `throw_*` helpers discard `env.throw_new()` result with `let _ =` --
  if throw fails, no exception is set and execution continues with error sentinel.
- Proxy uses `JniProxyError` enum; tunnel uses raw string errors with
  `throw_illegal_argument` -- inconsistent error handling.
- Rust error messages (including I/O details) propagate into Java exception
  messages -- potential information leakage.

---

## 6. Kotlin Bridge Layer

### Proxy bindings

File: `core/engine/src/main/kotlin/com/poyka/ripdpi/core/RipDpiProxy.kt`

```kotlin
interface RipDpiProxyBindings {
    fun create(configJson: String): Long
    fun start(handle: Long): Int
    fun stop(handle: Long)
    fun pollTelemetry(handle: Long): String?
    fun destroy(handle: Long)
    fun updateNetworkSnapshot(handle: Long, snapshotJson: String)
}

class RipDpiProxyNativeBindings @Inject constructor() : RipDpiProxyBindings {
    companion object { init { RipDpiNativeLoader.ensureLoaded() } }

    override fun create(configJson: String): Long = jniCreate(configJson)
    override fun start(handle: Long): Int = jniStart(handle)
    override fun stop(handle: Long) { jniStop(handle) }
    override fun pollTelemetry(handle: Long): String? = jniPollTelemetry(handle)
    override fun destroy(handle: Long) { jniDestroy(handle) }
    override fun updateNetworkSnapshot(handle: Long, snapshotJson: String) {
        jniUpdateNetworkSnapshot(handle, snapshotJson)
    }

    private external fun jniCreate(configJson: String): Long
    private external fun jniStart(handle: Long): Int
    private external fun jniStop(handle: Long)
    private external fun jniPollTelemetry(handle: Long): String?
    private external fun jniDestroy(handle: Long)
    private external fun jniUpdateNetworkSnapshot(handle: Long, snapshotJson: String)
}
```

### Proxy lifecycle (Kotlin side -- abbreviated)

`RipDpiProxy.startProxy()` flow:
1. `mutex.withLock`: guard against double-start, call `nativeBindings.create()`,
   check for 0L handle (throw `SessionCreationFailed`).
2. `yield()` to let UNDISPATCHED caller regain control.
3. `invokeOnCompletion`: cancellation handler calls `nativeBindings.stop()`
   using `mutex.tryLock()` -- **if lock is held, stop is skipped**.
4. `withContext(IO) { nativeBindings.start(handle) }` -- **blocking** native call.
5. `finally`: `mutex.withLock { nativeBindings.destroy(handle); handle = 0L }`.
```

### Tunnel bindings

File: `core/engine/src/main/kotlin/com/poyka/ripdpi/core/Tun2SocksTunnel.kt`

Same pattern as proxy. `Tun2SocksNativeBindings` loads `System.loadLibrary("ripdpi-tunnel")`
directly (not via `RipDpiNativeLoader`). Additional methods vs proxy:
`start(handle, tunFd: Int)` (fd passing), `getStats(handle): LongArray`.

**Audit notes:**
- `invokeOnCompletion` uses `mutex.tryLock()` -- if lock is held, stop is
  skipped entirely. Comment says "finally block will handle" but this is the
  cancellation path, not the normal return path.
- Handle `0L` used as sentinel, but Kotlin `Long` is signed -- a negative
  handle from JNI would not be caught (Rust side converts via `u64::try_from`).
- In `Tun2SocksTunnel.start()`: if `nativeBindings.start()` throws, it calls
  `destroy` in catch. If `destroy` also throws, handle stays 0L (correct) but
  Rust handle leaks in the registry.

---

## 7. JNI Entry Points + Panic Boundaries

### JNI_OnLoad

```rust
// ripdpi-android/src/lib.rs
static JVM: OnceCell<JavaVM> = OnceCell::new();

#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut c_void) -> jint {
    let _ = JVM.set(vm);
    android_support::ignore_sigpipe();     // unsafe signal handler
    init_android_logging("ripdpi-native");
    ripdpi_telemetry::recorder::install();
    JNI_VERSION  // JNI_VERSION_1_6
}

// ripdpi-tunnel-android/src/lib.rs (does NOT cache JavaVM)
#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(_vm: JavaVM, _reserved: *mut c_void) -> jint {
    android_support::ignore_sigpipe();
    init_android_logging("ripdpi-tunnel-native");
    JNI_VERSION
}
```

### Panic boundary pattern (proxy)

File: `native/rust/crates/ripdpi-android/src/proxy.rs`

```rust
pub(crate) fn proxy_create_entry(mut env: JNIEnv, config_json: JString) -> jlong {
    init_android_logging("ripdpi-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        create_session(&mut env, config_json)
    })).unwrap_or_else(|panic_payload| {
        let msg = extract_panic_message(panic_payload);
        throw_runtime_exception(&mut env,
            format!("Proxy session creation panicked: {msg}"));
        0
    })
}
```

Tunnel crate uses the same pattern but **discards** panic payload (`|_|`) instead
of extracting the message.

### All JNI functions (19 total)

| # | JNI function | Args | Return | Crate |
|---|-------------|------|--------|-------|
| 1 | `jniCreate(configJson)` | JString | jlong | proxy |
| 2 | `jniStart(handle)` | jlong | jint | proxy |
| 3 | `jniStop(handle)` | jlong | void | proxy |
| 4 | `jniPollTelemetry(handle)` | jlong | jstring? | proxy |
| 5 | `jniDestroy(handle)` | jlong | void | proxy |
| 6 | `jniUpdateNetworkSnapshot(handle, json)` | jlong, JString | void | proxy |
| 7 | `jniCreate()` | -- | jlong | diagnostics |
| 8 | `jniStartScan(handle, json, id)` | jlong, JString, JString | void | diagnostics |
| 9 | `jniCancelScan(handle)` | jlong | void | diagnostics |
| 10 | `jniPollProgress(handle)` | jlong | jstring? | diagnostics |
| 11 | `jniTakeReport(handle)` | jlong | jstring? | diagnostics |
| 12 | `jniPollPassiveEvents(handle)` | jlong | jstring? | diagnostics |
| 13 | `jniDestroy(handle)` | jlong | void | diagnostics |
| 14 | `jniCreate(configJson)` | JString | jlong | tunnel |
| 15 | `jniStart(handle, tunFd)` | jlong, jint | void | tunnel |
| 16 | `jniStop(handle)` | jlong | void | tunnel |
| 17 | `jniGetStats(handle)` | jlong | jlongArray | tunnel |
| 18 | `jniGetTelemetry(handle)` | jlong | jstring? | tunnel |
| 19 | `jniDestroy(handle)` | jlong | void | tunnel |

**Audit notes:**
- `AssertUnwindSafe` wrapping `&mut JNIEnv` is technically unsound if unwound
  code leaves `env` in an inconsistent state.
- Proxy entry points extract panic messages; tunnel entry points discard them
  (uses `|_|` closure).
- Proxy `JNI_OnLoad` caches `JavaVM` in `OnceCell`; tunnel does not.
- `ignore_sigpipe()` uses `unsafe` signal handler -- called from both.

---

## 8. Telemetry Data Flow

### Proxy telemetry polling

File: `native/rust/crates/ripdpi-android/src/proxy/telemetry.rs`

```rust
pub(crate) fn poll_proxy_telemetry(env: &mut JNIEnv, handle: jlong) -> jstring {
    let result = env.with_local_frame_returning_local(4, |env| {
        let session = match lookup_proxy_session(handle) {
            Ok(session) => session,
            Err(err) => { err.throw(env); return Ok(JObject::null()); }
        };
        match serde_json::to_string(&session.telemetry.snapshot()) {
            Ok(value) => env.new_string(value).map(Into::into),
            Err(err) => {
                JniProxyError::Serialization(err).throw(env);
                Ok(JObject::null())
            }
        }
    });
    match result {
        Ok(obj) => obj.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
```

### Tunnel stats (u64 -> i64 boundary)

File: `native/rust/crates/ripdpi-tunnel-android/src/session/stats.rs`

```rust
pub(crate) fn stats_session(env: &mut JNIEnv, handle: jlong) -> jlongArray {
    let session = match lookup_tunnel_session(handle) {
        Ok(session) => session,
        Err(message) => {
            throw_illegal_argument(env, message);
            return std::ptr::null_mut();
        }
    };
    let snapshot = {
        let state = session.state.lock().unwrap_or_else(PoisonError::into_inner);
        stats_snapshots_for_state(&state).0
    };
    match env.new_long_array(4) {
        Ok(arr) => {
            let values: [i64; 4] = [
                snapshot.0 as i64, snapshot.1 as i64,
                snapshot.2 as i64, snapshot.3 as i64,
            ];
            if env.set_long_array_region(&arr, 0, &values).is_ok() {
                arr.into_raw()
            } else { std::ptr::null_mut() }
        }
        Err(_) => std::ptr::null_mut(),
    }
}
```

Kotlin `TunnelStats.fromNative(LongArray)` reads indices 0-3 with `getOrElse`
defaulting to 0L.

**Audit notes:**
- `u64 as i64` cast: values above `i64::MAX` silently become negative in Kotlin.
- `getOrElse` silently zeros if Rust returns fewer than 4 elements.
- `with_local_frame_returning_local(4, ...)` prevents JNI reference table leaks.

---

## 9. Configuration Parsing Surface

**Proxy path**: Kotlin `RipDpiProxyPreferences.toNativeConfigJson()` ->
JSON string -> Rust `parse_proxy_config_json()` (serde) ->
`runtime_config_envelope_from_payload()` -> validated `RuntimeConfig`.

**Tunnel path**: Kotlin `Tun2SocksConfig` (`@Serializable`) -> JSON string ->
Rust `parse_tunnel_config_json()` (serde) -> `config_from_payload()` ->
validated `Config`.

Both parsers have proptest fuzz tests (`fuzz_*_never_panics`).

### Tun2SocksConfig (full config surface crossing JNI)

```kotlin
@Serializable
data class Tun2SocksConfig(
    val tunnelName: String = "tun0",
    val tunnelMtu: Int = 1500,
    val multiQueue: Boolean = false,
    val tunnelIpv4: String? = null,
    val tunnelIpv6: String? = null,
    val socks5Address: String = "127.0.0.1",
    val socks5Port: Int,
    val socks5Udp: String? = "udp",
    val socks5UdpAddress: String? = null,
    val socks5Pipeline: Boolean? = null,
    val username: String? = null,
    val password: String? = null,
    val mapdnsAddress: String? = null,
    val mapdnsPort: Int? = null,
    val mapdnsNetwork: String? = null,
    val mapdnsNetmask: String? = null,
    val mapdnsCacheSize: Int? = null,
    val encryptedDnsResolverId: String? = null,
    val encryptedDnsProtocol: String? = null,
    val encryptedDnsHost: String? = null,
    val encryptedDnsPort: Int? = null,
    val encryptedDnsTlsServerName: String? = null,
    val encryptedDnsBootstrapIps: List<String> = emptyList(),
    val encryptedDnsDohUrl: String? = null,
    val encryptedDnsDnscryptProviderName: String? = null,
    val encryptedDnsDnscryptPublicKey: String? = null,
    val dnsQueryTimeoutMs: Int? = null,
    val resolverFallbackActive: Boolean? = null,
    val resolverFallbackReason: String? = null,
    val taskStackSize: Int = 81_920,
    val tcpBufferSize: Int? = null,
    val udpRecvBufferSize: Int? = null,
    val udpCopyBufferNums: Int? = null,
    val maxSessionCount: Int? = null,
    val connectTimeoutMs: Int? = null,
    val tcpReadWriteTimeoutMs: Int? = null,
    val udpReadWriteTimeoutMs: Int? = null,
    val logLevel: String = "warn",
    val limitNofile: Int? = null,
)
```

Rust `TunnelConfigPayload` uses `#[serde(rename_all = "camelCase")]` and
includes deprecated compatibility fields (`doh_resolver_id`, `doh_url`,
`doh_bootstrap_ips`). Validation: only blank checks on `socks5_address` and
`tunnel_name`. No IP format, port range, or MTU bound validation.
`taskStackSize` and buffer sizes accepted without upper-bound checks.

---

## 10. Audit Checklist

1. **Memory safety**: Are all handle-to-resource lookups bounds-checked?
   -> Yes, via `HashMap::get()` returning `Option`.

2. **Panic safety**: Are all JNI entry points wrapped in `catch_unwind`?
   -> Yes. All 19 entry points verified.

3. **FD safety**: Is the dup'd fd always closed on all error paths?
   -> Mostly. `rollback_failed_tunnel_start` closes it. But if worker thread
   panics before `File::from_raw_fd`, fd may leak inside the panicked closure.

4. **Thread safety**: Are all shared state accesses mutex-protected?
   -> Yes. But lock ordering is not documented (potential for deadlock if
   two locks are acquired in different orders).

5. **Exception safety**: Does Rust always throw a Java exception before
   returning an error sentinel?
   -> By convention, yes. But `throw_new` failures are silently ignored.

6. **Resource lifecycle**: Can handles leak?
   -> If `destroy` is never called after `stop`, session stays in registry.
   If Kotlin exception prevents `destroy` call, Rust-side resources leak.

7. **Integer safety**: u64 handle counter overflow? u64->i64 stats truncation?
   -> Counter overflow: theoretically possible (wraps at u64::MAX).
   Stats: values > i64::MAX become negative on Kotlin side.

8. **Input validation**: JSON parsing fuzzing coverage? fd validation?
   -> JSON: proptest fuzz tests. FD: only `>= 0` check (no device type check).

9. **Concurrency**: State machine transitions atomic? Race between stop/destroy?
   -> Tunnel: `Ready -> Starting -> Running` uses two separate lock
   acquisitions. Proxy: `start_session` blocking means stop races with return.

10. **Information leakage**: Error messages contain internal details?
    -> Yes. I/O error messages, config parsing errors, and panic messages
    propagate to Java exception strings.
