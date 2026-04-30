---
name: rust-unsafe
description: Rust unsafe guidance for FFI, raw pointers, transmute, UnsafeCell, ioctl/tun, and safe wrappers.
---

# Rust unsafe -- RIPDPI

## Purpose

Guide agents through writing, reviewing, and auditing unsafe Rust in RIPDPI's 23 native crates. The dominant unsafe patterns are JNI FFI, Linux ioctl/tun device operations, and signal handling.

## Governance: `#![forbid(unsafe_code)]`

Pure-logic crates MUST carry `#![forbid(unsafe_code)]` at the crate root. Currently enforced in: `ripdpi-failure-classifier`, `ripdpi-ipfrag`, `ripdpi-desync`, `ripdpi-session`, `ripdpi-config`, `ripdpi-packets`. When creating a new crate that has no FFI or OS-level calls, add the attribute. When reviewing, verify it has not been removed without justification.

## The five unsafe superpowers

1. **Dereference raw pointers** (`*const T`, `*mut T`)
2. **Call unsafe functions** (including `extern "C"` / `extern "system"`)
3. **Access or modify mutable static variables**
4. **Implement unsafe traits** (`Send`, `Sync`)
5. **Access fields of unions**

## JNI FFI patterns (ripdpi-android, ripdpi-tunnel-android)

### Export and no_mangle

Every JNI entry point uses `#[unsafe(no_mangle)]` (Rust 2024 syntax) and `extern "system"`:

```rust
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniCreate(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    config_json: JString,
) -> jlong {
    tunnel_create_entry(env, config_json)
}
```

### Panic safety at the FFI boundary

Unwinding across `extern "system"` is UB. All JNI entry points MUST catch panics. The project uses `EnvUnowned::with_env` + `Outcome`:

```rust
pub(crate) fn proxy_create_entry(mut env: EnvUnowned<'_>, config_json: JString) -> jlong {
    match env.with_env(move |env| -> jni::errors::Result<jlong> {
        Ok(create_session(env, config_json))
    }).into_outcome() {
        Outcome::Ok(handle) => handle,
        Outcome::Err(err) => { /* throw Java exception, return 0 */ }
        Outcome::Panic(payload) => { /* throw Java exception with panic message, return 0 */ }
    }
}
```

**Rule:** Never write a bare `extern "system" fn` body without `with_env`/`catch_unwind` wrapping.

### JString::from_raw and JLongArray::from_raw

These take ownership of a raw JNI local reference. Safety invariants:
- The raw pointer must be a valid JNI local ref in the current frame
- Only call once per raw pointer (double-free otherwise)
- The resulting object must not outlive the JNI local frame

```rust
// Safety: `raw` is a valid jstring local ref returned by the JVM;
// null-checked above; consumed exactly once.
let string = unsafe { JString::from_raw(env, raw) };
```

### EnvUnowned::from_raw

Used in tests to convert a raw `JNIEnv` pointer. The resulting `EnvUnowned` must not outlive the `Env` it was derived from:

```rust
// Safety: env pointer is valid for the lifetime of the Env borrow.
unsafe { EnvUnowned::from_raw(env.get_raw()) }
```

### JavaVM::from_raw — liveness invariant

`JavaVM::from_raw(vm.get_raw())` clones the VM handle without incrementing a refcount. The resulting handle is a plain pointer copy; the JVM retains ownership.

Every call MUST have a `// SAFETY:` comment documenting:
1. **Liveness** — the JVM is guaranteed alive for the full lifetime of the clone (typically: the original `JavaVM` is held by a `'static OnceCell`, so the pointer is valid for program lifetime).
2. **Non-aliasing** — the clone is used only to call `attach_current_thread`, which is thread-safe on the JVM side. No mutation of VM state occurs through the clone.

```rust
// SAFETY: `vm` is held by the static `JVM: OnceCell<JavaVM>` in lib.rs, so its
// raw pointer is valid for program lifetime. `JavaVM::from_raw` copies the
// pointer only; the JVM manages its own lifetime.
let vm_clone = unsafe { JavaVM::from_raw(vm.get_raw()) };
```

Anchor: `native/rust/crates/ripdpi-android/src/vpn_protect.rs:58-60` (currently lacks a formal `SAFETY:` block — flag in review).

### Raw fd across JNI

When receiving a file descriptor from Java (e.g., TUN fd from VpnService), always dup before taking ownership:

```rust
// Safety: BorrowedFd does not take ownership; dup creates an independent fd.
let owned_fd = unsafe { nix::unistd::dup(BorrowedFd::borrow_raw(tun_fd)) };
```

## Linux ioctl / tun-driver patterns (ripdpi-tun-driver)

### mem::zeroed for C structs

`libc::ifreq` is a plain C struct with no Rust-level invariants. All-zero bytes is a valid representation:

```rust
// Safety: ifreq is a plain C struct; all-zero bytes is valid.
let mut ifr: libc::ifreq = unsafe { mem::zeroed() };
ifr.ifr_name = self.make_ifr_name();
```

**Do not** use `mem::zeroed()` for types with Rust invariants (bool, enum, NonNull, references).

### ioctl calls

Each ioctl call needs a safety comment documenting: (1) fd validity, (2) struct field validity, (3) which ioctl number and what it does:

```rust
// Safety: sock is a valid AF_INET/SOCK_DGRAM fd; &ifr has ifr_name set and
// ifru_mtu populated; SIOCSIFMTU (0x8922) sets the interface MTU.
let res = unsafe { libc::ioctl(sock.as_raw_fd(), libc::SIOCSIFMTU, &ifr as *const _) };
if res < 0 {
    return Err(TunnelError::Ioctl(format!("SIOCSIFMTU: {}", std::io::Error::last_os_error())));
}
```

### Union field access

`ifreq.ifr_ifru` is a C union. Access is unsafe because Rust cannot guarantee which variant was last written. Always zero-initialize first, then write-before-read:

```rust
unsafe {
    ifr.ifr_ifru.ifru_flags = IFF_TUN | IFF_NO_PI;
    if multi_queue {
        ifr.ifr_ifru.ifru_flags |= IFF_MULTI_QUEUE;
    }
}
```

### sockaddr casts

Casting `sockaddr` to `sockaddr_in` is valid because they are layout-compatible (both start with `sa_family_t`). Document this in the safety comment:

```rust
// Safety: sockaddr_in is layout-compatible with sockaddr; we set sin_family
// and sin_addr which are the fields the kernel reads for SIOCSIFADDR.
unsafe {
    let sin = &mut ifr.ifr_ifru.ifru_addr as *mut _ as *mut libc::sockaddr_in;
    (*sin).sin_family = libc::AF_INET as libc::sa_family_t;
    (*sin).sin_addr.s_addr = libc::htonl(u32::from(addr));
}
```

## Syscall FFI wrappers (ripdpi-runtime/platform/linux.rs)

The Linux platform module (`native/rust/crates/ripdpi-runtime/src/platform/linux.rs`, 83 unsafe blocks — the largest concentration in the workspace) wraps raw `libc::setsockopt` / `getsockopt` / `recvmsg` for kernel-specific options unavailable in `socket2`: `TCP_INFO`, `TCP_MD5SIG`, `TCP_FASTOPEN_CONNECT`, `SO_ORIGINAL_DST`, `IP_RECVTTL`, and CMSG-carrying `recvmsg`.

The canonical wrapper idiom — `zeroed::<T>() + cast-to-*mut-T` for variadic kernel structs:

```rust
/// # Safety
/// `fd` must be a live socket descriptor; `T` must match the kernel's expected
/// output layout for the given `level`/`name` combination.
unsafe fn getsockopt_raw<T>(
    fd: libc::c_int,
    level: libc::c_int,
    name: libc::c_int,
) -> io::Result<(T, libc::socklen_t)> {
    let mut val: T = unsafe { zeroed() };
    let mut len = size_of::<T>() as libc::socklen_t;
    let rc = unsafe { libc::getsockopt(fd, level, name, (&mut val as *mut T).cast(), &mut len) };
    if rc == 0 { Ok((val, len)) } else { Err(io::Error::last_os_error()) }
}
```

**Rule**: every new syscall wrapper in this module MUST:

1. Carry a `# Safety` rustdoc block on the `unsafe fn` signature listing the fd-validity and layout-match invariants.
2. Use `zeroed()` only for plain C structs (no Rust-level invariants — no `bool`, `enum`, `NonNull`, or references).
3. Cast `&mut val as *mut T` via `.cast()` rather than `as *mut _` — the method preserves pointer provenance and plays well with Miri's Tree Borrows checker.
4. Check the syscall return value and convert `io::Error::last_os_error()` — never discard `errno`.
5. Preserve the module's `Last audited: <date> against socket2 <ver>` header when adding new wrappers. The header signals a human has reconciled the kernel-ABI-vs-socket2 boundary; bumping the date on each audit is mandatory.

Anchors:
- `native/rust/crates/ripdpi-runtime/src/platform/linux.rs:46-60` — `setsockopt_raw` reference
- `native/rust/crates/ripdpi-runtime/src/platform/linux.rs:69-83` — `getsockopt_raw` reference

## Signal handling (android-support)

```rust
// Safety: Ignoring SIGPIPE is async-signal-safe. The previous handler is
// discarded; we don't need to restore it.
let _ = unsafe { signal(Signal::SIGPIPE, SigHandler::SigIgn) };
```

Call `ignore_sigpipe()` exactly once from `JNI_OnLoad`. On Android, ART does not ignore SIGPIPE for native code; writing to a closed socket delivers SIGPIPE and terminates the process.

## Transmute safety table

| From | To | Safe? | Preferred alternative |
|------|-----|-------|----------------------|
| `u32` | `f32` | Yes | `f32::from_bits(u)` |
| `[u8; 4]` | `u32` | Yes | `u32::from_ne_bytes(arr)` |
| `&T` | `*const T` | Yes | `ptr as *const T` |
| `Box<T>` | `*mut T` | Yes | `Box::into_raw(b)` |
| `&'a T` | `&'b T` (longer lifetime) | **No** | Restructure lifetimes |
| `u8` | `bool` | **No** unless 0/1 | Match on value |
| `u8` | `MyEnum` | **No** unless valid tag | `MyEnum::try_from(u)` |
| `Vec<T>` | `Vec<U>` | **No** | Manual conversion |

## Audit checklist

When reviewing an `unsafe` block:

- [ ] Is there a `// Safety:` comment explaining which invariant is upheld?
- [ ] For raw pointers: non-null, aligned, initialized, valid lifetime?
- [ ] For `extern "system"` JNI: is the body wrapped in `with_env`/`catch_unwind`?
- [ ] For JNI object construction (`from_raw`): is the raw ref valid and consumed exactly once?
- [ ] For `mem::zeroed()`: is the type a plain C struct with no Rust invariants?
- [ ] For ioctl: is fd valid, struct populated correctly, return value checked?
- [ ] For union access: was the field written before being read?
- [ ] For `Send`/`Sync` impl: is thread safety actually guaranteed?
- [ ] Is the unsafe block as small as possible?
- [ ] Can this be tested under Miri with Tree Borrows? (`MIRIFLAGS="-Zmiri-tree-borrows" cargo +nightly miri test`) — Tree Borrows is the formal aliasing model published at PLDI 2025 and is now the recommended default. It permits more valid unsafe patterns than Stacked Borrows, so code that failed the older model may pass now.

## When to use unsafe in RIPDPI

```
Legitimate (already present):
  - JNI FFI exports (#[unsafe(no_mangle)], extern "system")
  - JNI object construction (JString::from_raw, EnvUnowned::from_raw)
  - Linux TUN device (ioctl, mem::zeroed, union field access, raw fd)
  - Signal handling (ignore_sigpipe)

Should NOT need unsafe:
  - Pure packet parsing / protocol logic -> use #![forbid(unsafe_code)]
  - Configuration / session management -> use #![forbid(unsafe_code)]
  - Anything a safe crate (nix, jni) already wraps
```

## Related skills

- `rust-sanitizers-miri` -- Miri is the essential tool for testing unsafe code
- `rust-ffi` -- FFI patterns, bindgen, cbindgen
- `rust-debugging` -- debugging panics in unsafe code
- `memory-model` -- aliasing and memory ordering in unsafe

For detailed reference patterns, see [references/unsafe-patterns.md](references/unsafe-patterns.md).
