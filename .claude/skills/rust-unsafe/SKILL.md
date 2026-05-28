---
name: rust-unsafe
description: Use when adding or reviewing any unsafe Rust block, FFI/JNI export, raw-pointer arithmetic, transmute, ManuallyDrop, mem::zeroed, ioctl call, union access, manual unsafe impl Send/Sync, Box::leak, JString::from_raw, EnvUnowned::from_raw, or any change that removes #![forbid(unsafe_code)] from a previously-safe crate. Triggers on "unsafe", "FFI", "extern", "raw pointer", "transmute", "*mut/*const", "SAFETY comment", "undefined behavior", or any soundness question.
---

# Rust unsafe -- RIPDPI

## Purpose

Guide agents through writing, reviewing, and auditing unsafe Rust in RIPDPI's 97 native crates. The dominant unsafe patterns are JNI FFI, Linux ioctl/tun device operations, and signal handling.

## Governance: `#![forbid(unsafe_code)]`

Pure-logic crates MUST carry `#![forbid(unsafe_code)]` at the crate root. Currently enforced in: `ripdpi-failure-classifier`, `ripdpi-ipfrag`, `ripdpi-desync`, `ripdpi-session`, `ripdpi-config`, `ripdpi-packets`. When creating a new crate that has no FFI or OS-level calls, add the attribute. When reviewing, verify it has not been removed without justification.

## Lint floor for unsafe crates

**Severity: CRITICAL — these lints are non-negotiable for any crate that contains `unsafe`**

Pure-logic crates carry `#![forbid(unsafe_code)]`. Crates that legitimately contain `unsafe` (FFI adapters, syscall wrappers, the io_loop bridge) MUST carry the lint floor below at the crate root:

```rust
// Required for every crate that contains `unsafe`.
#![deny(unsafe_op_in_unsafe_fn)]
#![warn(
    clippy::undocumented_unsafe_blocks,
    clippy::multiple_unsafe_ops_per_block,
    clippy::missing_safety_doc,
)]
```

What each lint forces:

| Lint | Forces | What it catches in LLM-generated code |
|------|--------|----------------------------------------|
| `unsafe_op_in_unsafe_fn` | Every unsafe op inside an `unsafe fn` must still be inside `unsafe { ... }` | LLM sees `unsafe fn` and stops writing `// SAFETY:` per-operation — this lint forces the discipline back |
| `clippy::undocumented_unsafe_blocks` | Every `unsafe { ... }` block needs a preceding `// SAFETY:` comment | Bare `unsafe` blocks with no justification |
| `clippy::multiple_unsafe_ops_per_block` | Each unsafe operation needs its OWN SAFETY entry; one comment for a block of three deref + cast + transmute is rejected | LLM writes one paragraph for a block containing 3 unsafe ops, missing that each has its own invariant |
| `clippy::missing_safety_doc` | Every `pub unsafe fn` needs a `# Safety` rustdoc section listing caller obligations | LLM writes `pub unsafe fn` and skips the rustdoc block |

These lints belong in `[workspace.lints.clippy]` at deny/warn level (see `rust-lints` skill); applying them at the crate root reinforces the workspace setting and makes the requirement visible in the crate's own source.

### Audit checklist when adding `unsafe` to a previously safe crate

1. Add the lint floor above to `lib.rs` / `main.rs`.
2. If the crate previously carried `#![forbid(unsafe_code)]`, removing it is a change that requires a tracking comment with a tracking issue number — see the `Governance` section above.
3. Run `cargo clippy -p <crate> --all-targets -- -D warnings` BEFORE writing the unsafe body, to confirm the lint floor is active and would catch a bare `unsafe { ... }`.
4. Write the unsafe body, run clippy again, and confirm the SAFETY comments pass.
5. Add a Miri test (if non-FFI) or a `cargo-careful` test (if FFI) under `#[cfg_attr(miri, ignore)]` gating.
6. Cross-reference in this skill's "When to use unsafe in RIPDPI" section: does the new unsafe site fit a documented category, or does it need a new category entry?

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

## Syscall FFI Wrappers

The Linux syscall surface is split across `native/rust/crates/ripdpi-privileged-ops/src/linux/` and `native/rust/crates/ripdpi-root-helper-protocol/src/scm_rights.rs`. `ripdpi-privileged-ops` wraps raw `libc::setsockopt` / `getsockopt` for kernel-specific options unavailable in `socket2`: `TCP_INFO`, `TCP_MD5SIG`, `TCP_FASTOPEN_CONNECT`, `SO_ORIGINAL_DST`, `IP_RECVTTL`, raw-socket headers, BPF filter attachment, and TCP repair options. `ripdpi-root-helper-protocol` owns the CMSG-carrying `recvmsg` helpers for SCM_RIGHTS fd passing.

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

**Rule**: every new syscall wrapper in these modules MUST:

1. Carry a `# Safety` rustdoc block on the `unsafe fn` signature listing the fd-validity and layout-match invariants.
2. Use `zeroed()` only for plain C structs (no Rust-level invariants — no `bool`, `enum`, `NonNull`, or references).
3. Cast `&mut val as *mut T` via `.cast()` rather than `as *mut _` — the method preserves pointer provenance and plays well with Miri's Tree Borrows checker.
4. Check the syscall return value and convert `io::Error::last_os_error()` — never discard `errno`.
5. Refresh the relevant module-level audit note when adding new wrappers. The note signals a human has reconciled the kernel-ABI-vs-socket2 boundary.

Anchors:
- `native/rust/crates/ripdpi-privileged-ops/src/linux/socket_options.rs:24-38` — `setsockopt_raw` reference
- `native/rust/crates/ripdpi-privileged-ops/src/linux/socket_options.rs:48-65` — `getsockopt_raw` reference
- `native/rust/crates/ripdpi-root-helper-protocol/src/scm_rights.rs` — SCM_RIGHTS `recvmsg` / control-message traversal

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

## Pointer reads from untrusted byte buffers

**Severity: CRITICAL — silent UB on ARM64**

`std::ptr::read(buf.as_ptr() as *const T)` requires `buf.as_ptr()` to be aligned for `T`. Bytes that arrive from the network, a file, or any boundary outside your allocator carry arbitrary alignment.

On x86, a misaligned read of a `repr(C)` struct containing `u32`/`u64` fields is silently slower. On ARM64 (RIPDPI's target), the result is either a `SIGBUS` trap or garbage data depending on kernel configuration. Tests on a dev x86 machine pass; Android device runs return random bytes or crash on the same input.

```rust
// BAD: assumes alignment that the input byte slice does not promise.
let header: Header = unsafe { std::ptr::read(buf.as_ptr() as *const Header) };

// CORRECT: explicit unaligned read.
let header: Header = unsafe { std::ptr::read_unaligned(buf.as_ptr() as *const Header) };

// BETTER: bypass unsafe entirely.
use zerocopy::FromBytes;
let header = Header::read_from_prefix(buf).ok_or(Error::Truncated)?;
// Or for streaming parsers:
let mut cur = std::io::Cursor::new(buf);
let header_field = cur.get_u32_le(); // bytes::Buf — endianness-explicit, no transmute.
```

Rules:
1. Any `ptr::read` whose source is a `&[u8]` from I/O, FFI, or `mmap` must be `ptr::read_unaligned` — no exceptions, even if the field happens to be aligned today.
2. Prefer `zerocopy::FromBytes` / `bytes::Buf` over raw pointer arithmetic on byte buffers. They eliminate the unsafe block and make endianness explicit.
3. Add a Miri test under `MIRIFLAGS="-Zmiri-tree-borrows"` for every new unsafe byte-buffer parser. Empirical measurement on LLM-generated Rust: 22 of 40 unsafe samples had UB that passed normal tests + clippy + visual review.

Grep audit:
```bash
rg "ptr::read\(\s*[a-z_][a-z_0-9]*\.as_ptr\(\)\s*as\s*\*const" native/rust/ --type rust -n
rg "transmute::<\s*&\[u8\]" native/rust/ --type rust -n
```

Cross-reference: `rust-sanitizers-miri` — Miri Tree Borrows is the formal aliasing model that catches this class of UB; `crabbook/unsafe_is_unsafe.md` — one unsafe block breaks local reasoning, including in byte-buffer parsers.

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
- [ ] Does any `Drop::drop` implementation contain `.unwrap()`, `.expect()`, or any call that can panic? → move to an explicit `close()`/`flush()` method returning `Result`.
- [ ] Does any `#[no_mangle]` or `#[export_name]` symbol collide with an identically-named symbol in another cdylib crate loaded simultaneously?

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

## Soundness assumes `Drop` may never run

**Severity: CRITICAL for public unsafe APIs**

`mem::forget` is a safe function. `ManuallyDrop::new` is safe. Any public `unsafe` API that relies on a RAII guard running its `Drop` for soundness is unsound — a caller can `mem::forget` the guard.

Concrete rule: if your `unsafe` code establishes a safety invariant via a guard's destructor (e.g., "the raw pointer in slot X is valid because the guard keeps the allocation alive"), the invariant must be stated in the `# Safety` section AND the API must be designed so forgetting the guard is either impossible or benign.

The correct designs:
- `thread::spawn` requires `'static` — no guard needed, lifetime enforces safety.
- `thread::scope` uses a closure + `join` inside the scope before returning — the scope itself is the guard, and its address is captured by the running threads, so forgetting it is prevented by the borrow checker.
- Async cancel-safety: futures polled inside `select!` can be dropped at any `.await`. If your future holds a guard, cancellation may drop it without `Drop` running if the task itself is `mem::forget`-ed by the executor. Design cancel-safe futures to not rely on `Drop` for correctness.

Reference: `crabbook/raii_and_memory_safety.md`

## One `unsafe` breaks local reasoning

**Severity: CRITICAL**

A single `unsafe` block anywhere in the call graph (including in dependencies) can invalidate type invariants codebase-wide. You cannot reason locally about safety just by looking at a single function.

Concrete example: `flatbuffers` calls `str::from_utf8_unchecked` internally. If the buffer contains invalid UTF-8, the resulting `str` violates Rust's invariants. Calling `.chars()` on that `str` — safe code — triggers a panic or UB depending on the version. The `unsafe` is in the dep; the observable failure is in your safe code.

Action items when auditing:
1. `rg 'from_utf8_unchecked\|from_raw_parts\|String::from_raw_parts' native/rust/ --type rust -n` — every hit needs a SAFETY comment tracing back to where the invariant is established.
2. `cargo deny check` — flag any dep with a known `unsafe`-soundness advisory.
3. When a dep's `unsafe` may transit through your API surface, document the assumed invariant in your own `# Safety` section.

Reference: `crabbook/unsafe_is_unsafe.md`

## Manual `unsafe impl Sync/Send` checklist

**Severity: CRITICAL**

Manually implementing `Sync` or `Send` for a type is a promise to the compiler that you guarantee thread safety. Blanket impls on the inner type's fields can silently break this promise.

Failure mode: you wrap `T` in a newtype and write `unsafe impl Sync for MyWrapper<T> {}`. Later, `T` gains an inner `Rc<i32>` field. `Rc` is `!Send + !Sync`, but the blanket impl on `Debug`/`Clone`/`Display` doesn't stop you from sending `MyWrapper<Rc<i32>>` across threads — the compiler accepts it, but a double-free or data race follows at runtime (SIGABRT under TSan).

Checklist before writing `unsafe impl Sync for T` / `unsafe impl Send for T`:
1. List every field type. For each, verify it is `Sync`/`Send` or document why your wrapper maintains the invariant despite the field not being so.
2. Check every trait impl on `T` (especially blanket impls from `Debug`, `Clone`, `Display`). None of them should allow shared access to non-Sync inner state.
3. Add a `static_assertions::assert_impl_all!` or `static_assertions::assert_not_impl_all!` test to catch regressions if inner types change.
4. Tag the `unsafe impl` with a `// SAFETY:` comment listing the fields audited and why the invariant holds.

Reference: `crabbook/send_and_sync.md`

## `ManuallyDrop` + `from_raw_parts` reference fabrication (caution)

**Severity: HIGH — use only as last resort**

It is possible to fabricate a `&String` from a `&str` via `ManuallyDrop<String>` + `String::from_raw_parts`, exploiting the fact that `String` is layout-compatible with `(ptr, len, cap)` and its bytes overlap `str`. This technique is:
- **Formally unsound** under Stacked Borrows: the `String` was never "owned" by the caller, so `from_raw_parts` creates a pointer with wrong provenance.
- **Practically fragile**: any future change to `String`'s internal layout or allocator breaks it silently.
- **Never necessary** in new code: accept `impl AsRef<str>` or `&str` instead of `&String` everywhere.

The only context where this pattern may appear is legacy FFI where a C caller passes a pointer and length pair and a `&String` is required. In that case: document the full invariant, test under Miri with Tree Borrows (`MIRIFLAGS="-Zmiri-tree-borrows"`), and gate the call with `cfg(not(miri))` if Miri rejects it.

Reference: `crabbook/crafting_reference_to_owned.md`

## `#[no_mangle]` and `#[link_section]` symbol collision (edition 2024 hazard)

**Severity: CRITICAL**

In Rust 2024, `#[no_mangle]`, `#[export_name = "..."]`, and `#[link_section = "..."]` must be written as `#[unsafe(no_mangle)]`, `#[unsafe(export_name = "...")]`, and `#[unsafe(link_section = "...")]`. The unsafe wrapper acknowledges a soundness risk that predates the edition: if two compilation units export the same unmangled symbol, the linker silently picks one, causing the wrong function to be called — a soundness bug with no compile-time diagnostic.

In RIPDPI: all four `-android` cdylib crates (`ripdpi-android`, `ripdpi-tunnel-android`, `ripdpi-warp-android`, `ripdpi-relay-android`) export `Java_*` symbols. The JNI naming convention (`Java_<pkg>_<class>_<method>`) provides natural uniqueness, but any `#[no_mangle]` on a non-JNI symbol (e.g., a C-API entry point, a test export, or a cbindgen-generated symbol) must be audited for uniqueness across all cdylib crates.

Audit check: `rg '#\[no_mangle\]|#\[export_name' native/rust/ --type rust -n` — for every hit, verify: (a) the symbol name is unique across all crates that may be loaded simultaneously, and (b) the 2024 `unsafe()` wrapper form is used after edition migration.

Reference: Edition Guide — Unsafe Attributes, RFC 3325.

## Panicking inside `Drop::drop` during unwinding aborts the process

**Severity: CRITICAL**

If a panic is already in progress (stack unwinding), and a `Drop` implementation panics while being called, Rust immediately aborts the process — this is a "double panic". Unlike a first panic, a double panic cannot be caught by `std::panic::catch_unwind`. There is no recovery path.

This is a latent hazard in any RAII guard whose `drop()` performs fallible cleanup: flushing a buffer, sending a shutdown packet, committing a transaction, closing a socket gracefully. Any `.unwrap()`, `.expect()`, or `?` (via a method that panics on error) inside `drop()` is a double-panic bomb that fires only when there is already an error in flight — exactly the worst time.

```rust
// DANGEROUS: double-panic if called during unwind
impl Drop for BufferedWriter {
    fn drop(&mut self) {
        self.flush().unwrap(); // panics -> process abort if already unwinding
    }
}

// CORRECT: log-and-discard in drop(), expose an explicit close() for Result
impl Drop for BufferedWriter {
    fn drop(&mut self) {
        if let Err(e) = self.flush() {
            tracing::error!("flush on drop failed: {e}");
        }
    }
}
impl BufferedWriter {
    pub fn close(mut self) -> Result<()> {
        self.flush()?; // returns error to caller instead of panicking
    }
}
```

Rule: `drop()` MUST NOT panic. Move all fallible cleanup to an explicit `close()` / `commit()` / `flush()` method that returns `Result`. Leave `drop()` as a silent best-effort fallback with error logging only.

Reference: Rustonomicon — Unwinding, Ferrous Systems — Drop, Panic and Abort.

## Related skills

- `rust-sanitizers-miri` -- Miri is the essential tool for testing unsafe code
- `rust-ffi` -- FFI patterns, bindgen, cbindgen
- `rust-debugging` -- debugging panics in unsafe code
- `memory-model` -- aliasing and memory ordering in unsafe

For detailed reference patterns, see [references/unsafe-patterns.md](references/unsafe-patterns.md).
