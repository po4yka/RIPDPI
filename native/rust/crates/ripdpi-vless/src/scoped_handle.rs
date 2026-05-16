//! Generic RAII wrapper for refcount-managed FFI handles.
//!
//! Centralises the "alloc / use / free exactly once" discipline that
//! refcount-managed C handles require. Generic over a `FreeFunction`
//! trait so the wrapper can be unit-tested with a counting mock
//! without linking against any real FFI library.
//!
//! Background: prior to the 2026-05-16 H1 BoringSSL ClientHello hook
//! commit, `ripdpi-vless::reality` manually managed an `SSL_SESSION`
//! refcount across an unsafe block. The H1 patch removed that
//! codepath entirely, but the RAII shape captured here is the
//! general-purpose tool the audit at
//! `docs/architecture/reality-ssl-session-drop-audit.md` recommended.
//! Future FFI surfaces in this crate (and others) should consume
//! `ScopedHandle` rather than hand-roll the discipline.

/// Trait carrying the free function for a `ScopedHandle<T, F>`.
/// Implementors are zero-sized marker types that select the
/// appropriate C-side free at compile time.
///
/// # Safety
///
/// `free` must:
/// 1. Accept exactly one ownership claim on `ptr` (no double-free).
/// 2. Be safe to call from a `Drop` impl, i.e. not unwind across the
///    FFI boundary.
pub trait FreeFunction<T> {
    /// Release the handle. Called exactly once when the owning
    /// `ScopedHandle` is dropped.
    ///
    /// # Safety
    ///
    /// `ptr` must be the same non-null pointer the wrapper was
    /// constructed with.
    unsafe fn free(ptr: *mut T);
}

/// Owns a non-null raw pointer and calls `F::free` exactly once on
/// drop. Constructed only from a non-null pointer; `take()` releases
/// ownership without freeing for the unusual case where the
/// underlying refcount is bumped by a different API and the caller
/// wants to suppress the auto-free.
pub struct ScopedHandle<T, F: FreeFunction<T>> {
    ptr: std::ptr::NonNull<T>,
    _phantom: std::marker::PhantomData<F>,
}

// SAFETY: ScopedHandle hands its pointer to a C-side free function.
// For refcount-managed FFI handles in the BoringSSL family, the free
// function is documented as thread-safe (atomic refcount decrement).
// The wrapper itself does not dereference the pointer. Send is sound
// when `T: Send`; consumers that need Sync must provide it via
// interior-synchronisation on the FFI side.
unsafe impl<T: Send, F: FreeFunction<T>> Send for ScopedHandle<T, F> {}

impl<T, F: FreeFunction<T>> ScopedHandle<T, F> {
    /// Construct from a non-null raw pointer. Returns `None` for
    /// null inputs so the wrapper invariant ("non-null") is
    /// structural rather than depending on a runtime check elsewhere.
    ///
    /// # Safety
    ///
    /// `ptr` must be a valid pointer returned by an allocation API
    /// whose dual is `F::free`. The caller transfers ownership to
    /// the wrapper.
    pub unsafe fn from_ptr(ptr: *mut T) -> Option<Self> {
        std::ptr::NonNull::new(ptr).map(|nn| Self { ptr: nn, _phantom: std::marker::PhantomData })
    }

    /// Read-only access to the owned pointer. The pointer remains
    /// owned by the wrapper; do not pass it to the free function.
    pub fn as_ptr(&self) -> *mut T {
        self.ptr.as_ptr()
    }

    /// Release ownership without freeing. After `take`, the caller
    /// is responsible for the free call. Useful when the C-side API
    /// transfers ownership back (rare).
    pub fn take(self) -> *mut T {
        let raw = self.ptr.as_ptr();
        std::mem::forget(self);
        raw
    }
}

impl<T, F: FreeFunction<T>> Drop for ScopedHandle<T, F> {
    fn drop(&mut self) {
        // SAFETY: from_ptr requires the caller to provide a pointer
        // whose dual is F::free; we call it exactly once here before
        // the wrapper is discarded.
        unsafe { F::free(self.ptr.as_ptr()) };
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    static FREE_CALLS: AtomicUsize = AtomicUsize::new(0);

    struct CountFree;

    impl FreeFunction<u8> for CountFree {
        unsafe fn free(_: *mut u8) {
            FREE_CALLS.fetch_add(1, Ordering::SeqCst);
        }
    }

    #[test]
    fn scoped_handle_calls_free_exactly_once_on_drop() {
        FREE_CALLS.store(0, Ordering::SeqCst);
        let mut storage = 0u8;
        let raw: *mut u8 = &mut storage;
        {
            let handle: ScopedHandle<u8, CountFree> = unsafe { ScopedHandle::from_ptr(raw) }.expect("non-null");
            assert_eq!(handle.as_ptr(), raw);
            assert_eq!(FREE_CALLS.load(Ordering::SeqCst), 0, "no free yet");
        }
        assert_eq!(FREE_CALLS.load(Ordering::SeqCst), 1, "exactly one free on drop");
    }

    #[test]
    fn scoped_handle_take_suppresses_free() {
        FREE_CALLS.store(0, Ordering::SeqCst);
        let mut storage = 0u8;
        let raw: *mut u8 = &mut storage;
        let handle: ScopedHandle<u8, CountFree> = unsafe { ScopedHandle::from_ptr(raw) }.expect("non-null");
        let released = handle.take();
        assert_eq!(released, raw, "take must return the original pointer");
        assert_eq!(FREE_CALLS.load(Ordering::SeqCst), 0, "take suppresses free");
    }

    #[test]
    fn scoped_handle_from_ptr_returns_none_for_null() {
        let result: Option<ScopedHandle<u8, CountFree>> = unsafe { ScopedHandle::from_ptr(std::ptr::null_mut()) };
        assert!(result.is_none(), "null pointer must not produce a handle");
    }

    #[test]
    fn scoped_handle_double_drop_does_not_double_free() {
        // Construct two distinct handles to two distinct pointers
        // and assert each frees exactly once. Demonstrates the
        // wrapper does not share state across instances.
        FREE_CALLS.store(0, Ordering::SeqCst);
        let mut a = 1u8;
        let mut b = 2u8;
        {
            let _h1: ScopedHandle<u8, CountFree> = unsafe { ScopedHandle::from_ptr(&mut a) }.expect("h1");
            let _h2: ScopedHandle<u8, CountFree> = unsafe { ScopedHandle::from_ptr(&mut b) }.expect("h2");
        }
        assert_eq!(FREE_CALLS.load(Ordering::SeqCst), 2, "two handles freed once each");
    }

    #[test]
    fn scoped_handle_frees_even_on_panic_unwind() {
        FREE_CALLS.store(0, Ordering::SeqCst);
        let mut storage = 0u8;
        let raw: *mut u8 = &mut storage;
        let result = std::panic::catch_unwind(|| {
            let _handle: ScopedHandle<u8, CountFree> = unsafe { ScopedHandle::from_ptr(raw) }.expect("non-null");
            panic!("simulated handshake failure between alloc and free");
        });
        assert!(result.is_err(), "panic propagated");
        assert_eq!(
            FREE_CALLS.load(Ordering::SeqCst),
            1,
            "free must fire on panic-unwind drop — this is the safety guarantee the audit requires",
        );
    }
}
