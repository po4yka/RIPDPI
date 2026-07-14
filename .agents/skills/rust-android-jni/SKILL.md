---
name: rust-android-jni
description: Android JNI canonical patterns for the RIPDPI workspace — panic containment, AttachCurrentThread/Detach discipline, local-ref frames, JNIEnv non-Send-through-await, JByteArray-vs-DirectByteBuffer for hot paths, VpnService.protect callback wiring. Use when authoring or modifying any code under ripdpi-android, ripdpi-tunnel-android, ripdpi-warp-android, ripdpi-relay-android, or any JNI export.
---

# Rust Android JNI -- RIPDPI

## Purpose

Consolidate the five canonical JNI rules that LLM-generated Rust regularly violates on Android targets. The `rust-unsafe` skill covers the general JNI patterns (catch_unwind, JString::from_raw); this skill is the Android-specific extension that adds thread attachment lifetime, local-ref frame management, JNIEnv async-boundary discipline, hot-path data marshaling, and the VpnService.protect callback wiring.

## When to consult

- Authoring or modifying any `#[unsafe(no_mangle)] pub extern "system" fn Java_*` export.
- Adding a callback from Rust back into Java that requires `JavaVM::attach_current_thread`.
- Reviewing a diff that touches `ripdpi-android/`, `ripdpi-tunnel-android/`, `ripdpi-warp-android/`, or `ripdpi-relay-android/`.
- Wiring or auditing a `VpnService.protect()` callback path.

## The five canonical rules

### Rule 1 — `catch_unwind` in every JNI export

Already covered in `rust-unsafe` and enforced by `jni-bridge-verifier`. Reproduced here as floor:

```rust
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_ExampleBindings_jniDoWork(
    mut env: EnvUnowned<'_>,
    _thiz: JObject,
    config: JString,
) -> jlong {
    env.with_env(|env| -> jni::errors::Result<jlong> {
        // body
    })
    .into_outcome()
    .ok_or_throw(env, 0)
}
```

`with_env` plus `into_outcome` is the project's standard wrapper; raw `catch_unwind` is acceptable only in `JNI_OnLoad` / `JNI_OnUnload` where `EnvUnowned` is unavailable.

### Rule 2 — `AttachCurrentThread` paired with `DetachCurrentThread`

Tokio worker threads (and `std::thread::spawn` pthreads) are NOT JVM threads. To call a Java method from such a thread, you must attach. JVM tracks attached threads internally; on thread exit without `DetachCurrentThread`, JVM logs:

```
JNI WARNING: native thread exiting without DetachCurrentThread
```

On Android, the warning is sometimes upgraded to a fatal abort depending on `android:debuggable` and Android API level. Pattern:

```rust
// In a tokio worker that calls back to Java:
let vm = JVM.get().expect("JNI_OnLoad must populate JVM");
let _guard = vm.attach_current_thread()?;  // RAII guard
let env = _guard.deref_mut();              // &mut JNIEnv
env.call_method(&listener, "onUpdate", "(I)V", &[update.into()])?;
// `_guard` drop calls DetachCurrentThread.
```

The `jni` crate's `AttachGuard` implements Drop correctly. The trap is in code that calls `attach_current_thread()` without binding the result — the temporary `AttachGuard` drops at the end of the expression, detaching before the JNI call runs. Always bind to a `let _guard = ...` (NOT `let _ = ...` — that drops immediately).

For long-lived worker threads that make many JNI calls, prefer `attach_current_thread_as_daemon` so JVM shutdown is not blocked by the attached thread. Use `pthread_key_create` with a destructor that calls `DetachCurrentThread` if your worker is owned by pure pthread (not tokio):

```rust
// Once at startup:
let mut key: libc::pthread_key_t = 0;
unsafe { libc::pthread_key_create(&mut key, Some(detach_destructor)) };
// detach_destructor calls vm.detach_current_thread()
```

### Rule 3 — Local references are frame-scoped (16-slot limit)

Every `env.find_class()`, `env.get_field()`, `env.call_method()` that returns a `JObject` consumes a local-reference slot. JVM guarantees only 16 slots per frame; exceeding this is `JniLocalReferenceTableOverflow` on Android (a hard process abort).

Two cases need explicit `with_local_frame` wrapping:

```rust
// BAD: 32 local refs accumulate; aborts on the 17th.
for client in &accepted_clients {
    let s = env.new_string(client.host)?;
    listener_method(env, s)?;
}

// GOOD: each iteration gets its own 32-slot frame; refs drop on frame exit.
for client in &accepted_clients {
    env.with_local_frame(32, |env| -> jni::errors::Result<()> {
        let s = env.new_string(client.host)?;
        listener_method(env, s)?;
        Ok(())
    })?;
}
```

The 16-slot guarantee is the minimum; some VMs allow more, but Android bionic enforces the minimum strictly. Always wrap loops that touch JNI objects.

### Rule 4 — `JNIEnv<'a>` MUST NOT cross an `.await` boundary

`JNIEnv<'a>` is `!Send + !Sync`. The Rust compiler rejects holding it across an `.await` in async code that's `Send` (the default for `tokio::spawn`). The trap: code paths that LOOK like they don't cross await, but the closure captures `JNIEnv` and is later sent to `tokio::spawn`.

Forbidden patterns:

```rust
// FORBIDDEN: Box::leak<JNIEnv>. LLM "fix" for a lifetime error.
let env_static: &'static mut JNIEnv = Box::leak(Box::new(env));

// FORBIDDEN: transmute on JNIEnv. Also an LLM "fix".
let env_static: &'static mut JNIEnv = unsafe { std::mem::transmute(env) };

// FORBIDDEN: capturing &mut JNIEnv in a tokio::spawn closure.
tokio::spawn(async move {
    env.call_method(...);  // compile error or worse, UB through unsafe escape
});
```

Correct pattern: extract everything you need from `JNIEnv` synchronously, drop `env`, then `tokio::spawn`. If the spawned task must call back into Java, it attaches its own thread via `vm.attach_current_thread()` inside the spawn body.

```rust
fn handle(env: &mut JNIEnv, vm: Arc<JavaVM>, listener: GlobalRef) {
    let payload = extract_payload(env);  // sync use of env
    tokio::spawn(async move {
        do_async_work(&payload).await;
        let _guard = vm.attach_current_thread().unwrap();
        let env = _guard.deref_mut();
        env.call_method(&listener, "onComplete", "()V", &[]).ok();
    });
}
```

### Rule 5 — `JByteArray` copies; `DirectByteBuffer` does not

`env.get_byte_array_region` and `env.get_byte_array_elements` COPY the entire byte array between JVM heap and native memory. For hot-path data (per-packet, per-byte), this throughput-couples the JNI boundary to packet rate.

For TUN packets specifically: the canonical RIPDPI pattern is `tun_fd` ownership transfer at session start. The `RawFd` crosses JNI exactly ONCE as a `jint`. Rust then reads/writes the TUN device directly via `AsyncFd`; packet bytes never cross the JNI boundary again.

If you must transit bytes through JNI (control-plane only, NOT hot path):

```rust
// Acceptable for control-plane:
let bytes = env.convert_byte_array(jarray)?;  // copies, but rare
process_config(&bytes)?;

// For data plane (avoid where possible):
// Use ByteBuffer.allocateDirect on the Kotlin side. Rust:
let buf: JByteBuffer = jbuffer.into();
let ptr = unsafe { env.get_direct_buffer_address(&buf)? };
let len = unsafe { env.get_direct_buffer_capacity(&buf)? };
let slice = unsafe { std::slice::from_raw_parts(ptr, len) };
// slice points into the JVM-owned DirectByteBuffer; no copy.
```

Lifetimes: `DirectByteBuffer` memory is owned by the JVM but persists across the JNI call boundary as long as the Java-side reference exists. The Rust slice is valid until that Java reference goes out of scope.

## VpnService.protect callback wiring

Two implementations both satisfy the `vpnservice-protect-invariant` rule. Choose based on Rust-side context (tokio? raw pthread?).

### Option A — UDS + SCM_RIGHTS (preferred when many sockets need protect)

Kotlin runs a UDS listener on a known abstract socket name. Rust opens a connection, sends the fd as ancillary data via `sendmsg` with `SCM_RIGHTS`, reads a 1-byte status reply. No JNI call on the Rust hot path.

```rust
use nix::sys::socket::*;
fn protect_socket(uds: &mut UnixStream, fd: RawFd) -> io::Result<()> {
    let cmsg = [ControlMessage::ScmRights(&[fd])];
    sendmsg::<UnixAddr>(
        uds.as_raw_fd(),
        &[IoSlice::new(b"P")],
        &cmsg,
        MsgFlags::empty(),
        None,
    )?;
    let mut reply = [0u8; 1];
    uds.read_exact(&mut reply)?;
    if reply[0] == b'1' { Ok(()) } else { Err(io::Error::other("protect denied")) }
}
```

The Kotlin side reads the fd, calls `VpnService.protect(fd)`, replies `'1'` on success.

### Option B — Direct JNI callback (simpler when sockets are rare)

```rust
fn protect_socket(vm: &Arc<JavaVM>, service: &GlobalRef, fd: RawFd) -> jni::errors::Result<()> {
    let _guard = vm.attach_current_thread()?;
    let env = _guard.deref_mut();
    let result: bool = env
        .call_method(service.as_obj(), "protect", "(I)Z", &[fd.into()])?
        .z()?;
    if result { Ok(()) } else { Err(jni::errors::Error::JavaException) }
}
```

Cost: `attach_current_thread` is ~5–15 µs on Android. Acceptable for control-plane sockets (DNS resolver, single SOCKS5 connect); unacceptable for per-flow socket creation at scale.

## Tokio thread naming

Worker threads without names appear as `Thread-42` in logcat. Always name them:

```rust
let runtime = tokio::runtime::Builder::new_multi_thread()
    .worker_threads(2)
    .thread_name_fn(|| {
        static N: AtomicUsize = AtomicUsize::new(0);
        format!("ripdpi-tokio-{}", N.fetch_add(1, Ordering::Relaxed))
    })
    .enable_all()
    .build()?;
```

For non-tokio threads, set the name via `pthread_setname_np` (Linux/Android) or directly through `std::thread::Builder::new().name("...")` if you spawn via `std::thread`.

## Audit checklist when reviewing a JNI diff

1. Every new `Java_*` export uses `EnvUnowned::with_env` + `into_outcome` OR raw `catch_unwind`.
2. Every callback from a tokio task captures `Arc<JavaVM>` + `GlobalRef`, never raw `JNIEnv`.
3. Every loop that calls `env.find_class` / `env.new_string` / `env.call_method` is wrapped in `with_local_frame(32, ...)`.
4. No `Box::leak`, `mem::transmute`, or other escape patterns near `JNIEnv` types.
5. New worker thread creation includes `pthread_setname_np` or `thread_name_fn`.
6. Every outbound socket (non-loopback) is preceded by `protect_socket(fd)`. See `vpnservice-protect-invariant.md` rule.
7. Hot-path data (TUN packets) does NOT cross JNI as `JByteArray`. Either fd-ownership-transfer or `DirectByteBuffer`.

## Related skills

- `rust-unsafe` — `catch_unwind`, `JString::from_raw`, `EnvUnowned::with_env`, JNI signal handling.
- `rust-async-internals` — JNI-to-async bridge canonical pattern, `block_on` from JNI rules.
- `rust-discipline` — panic policy, RAII, allocation hot-path rules apply.
- `rust-android-build` — `.so` size, ELF symbol allowlist, 16 KiB alignment.
- `vpnservice-protect-invariant.md` — the rule that drives the protect-callback work in this skill.
- `android-vpn-lifecycle.md` — Foreground Service, Doze, thread-naming context.
- `jni-bridge-verifier` agent — the automated audit that enforces this skill's checklist.
