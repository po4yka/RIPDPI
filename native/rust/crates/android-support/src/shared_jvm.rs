//! Process-lifetime-shared handle to the JNI invocation interface.
//!
//! JNI callback structs across the L8 adapter crates (VPN-socket protection,
//! runtime-readiness push, per-flow app attribution) all need to retain a
//! `JavaVM` so a worker thread can `attach_current_thread` on demand. Each such
//! site used to mint its own handle with an inline
//! `unsafe { JavaVM::from_raw(vm.get_raw()) }`, duplicating both the `unsafe`
//! block and its multi-paragraph SAFETY rationale eight times across six crates.
//!
//! [`SharedJvm`] collapses those into a **single** auditable `unsafe` site
//! ([`SharedJvm::new`]) and stores the handle behind an `Arc`, so the
//! invocation-interface handle is shared by reference count rather than by
//! re-deriving the raw pointer at every call site. This is purely a structural
//! hardening — behavior is identical to the prior inline handles — whose value
//! is the compiler-enforced single-owner invariant (see the guard at the bottom
//! of this module).

use std::ops::Deref;
use std::sync::Arc;

use jni::JavaVM;

/// Reference-counted handle to the process-global JNI invocation interface
/// (`JavaVM`).
///
/// Construct one with [`SharedJvm::new`]. The wrapped `JavaVM` is reachable
/// through [`Deref`], so call sites keep using `shared_jvm.attach_current_thread(..)`
/// exactly as they used a naked `JavaVM`.
#[derive(Clone)]
pub struct SharedJvm(Arc<JavaVM>);

impl SharedJvm {
    /// Duplicate the live `JavaVM` invocation-interface handle into a shared,
    /// reference-counted wrapper.
    ///
    /// This is the **single** `JavaVM::from_raw` call site in the RIPDPI native
    /// workspace. All JNI adapter crates route their handle duplication through
    /// here so the `unsafe` reasoning lives in exactly one place.
    #[must_use]
    pub fn new(vm: &JavaVM) -> Self {
        // SAFETY: `vm.get_raw()` returns the `*mut sys::JavaVM` invocation-interface
        // pointer the JVM published in `JNI_OnLoad`. Per the JNI specification that
        // pointer is valid for the entire process lifetime — the invocation
        // interface outlives all native code, and RIPDPI never calls
        // `DestroyJavaVM` — so it is a valid, non-null pointer for this call.
        //
        // `JavaVM::from_raw`'s documented contract is a null check plus a copy of
        // the thin `*mut sys::JavaVM` wrapper: it takes no ownership of VM
        // resources and does not mutate VM state. `jni::JavaVM` implements no
        // `Drop`, so dropping this handle (or the `Arc` wrapping it, at any
        // reference count) can never reach `DestroyJavaVM` — there is no
        // double-free / double-destroy path. (Implementation note, not load-bearing:
        // jni 0.22 additionally funnels every `from_raw` through one process
        // `OnceLock` singleton, so all handles are clones of a single instance.)
        //
        // The resulting handle is used solely to `attach_current_thread`, which the
        // JNI invocation interface is explicitly designed to serve concurrently
        // from any thread, so duplicating the handle introduces no aliasing hazard.
        let duplicate = unsafe { JavaVM::from_raw(vm.get_raw()) };
        Self(Arc::new(duplicate))
    }
}

impl Deref for SharedJvm {
    type Target = JavaVM;

    fn deref(&self) -> &Self::Target {
        self.0.as_ref()
    }
}

// Compile-fail regression (epic-june-2026-audit-remediation,
// centralize-unsafe-javavm-from-raw): the JNI invocation handle MUST be stored
// behind `Arc`, never as a naked `JavaVM` field. If the inner type is ever
// changed away from `Arc<JavaVM>` — re-introducing the per-site `from_raw`
// double-owner shape the audit flagged — this guard stops compiling, so the
// audited single-owner invariant cannot be silently regressed.
const _: fn(SharedJvm) -> Arc<JavaVM> = |shared| shared.0;

// The JNI callback structs that store a `SharedJvm` rely on it being thread-safe
// (they move the handle onto a worker thread and `attach_current_thread` from
// there). Lock that contract in at compile time.
const _: () = {
    const fn assert_send_sync_clone<T: Send + Sync + Clone>() {}
    assert_send_sync_clone::<SharedJvm>();
};
