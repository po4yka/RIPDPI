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
    use std::sync::Mutex;

    static FREE_CALLS: AtomicUsize = AtomicUsize::new(0);
    // `cargo test` runs tests in parallel by default. Every test in
    // this module that reads or writes `FREE_CALLS` MUST hold this
    // mutex for its body so the store-0 / drop-handle / load-N
    // sequence is atomic w.r.t. sibling tests using the same
    // counter. Without it, a parallel `CountFree::free` from a
    // peer test inflates the count and fails the assertion.
    static FREE_CALLS_MUTEX: Mutex<()> = Mutex::new(());

    struct CountFree;

    impl FreeFunction<u8> for CountFree {
        unsafe fn free(_: *mut u8) {
            FREE_CALLS.fetch_add(1, Ordering::SeqCst);
        }
    }

    #[test]
    fn scoped_handle_calls_free_exactly_once_on_drop() {
        let _guard = FREE_CALLS_MUTEX.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        FREE_CALLS.store(0, Ordering::SeqCst);
        let mut storage = 0u8;
        let raw: *mut u8 = &mut storage;
        {
            // SAFETY: `raw` points to a live stack `u8`; `CountFree`'s
            // `free` impl only increments a counter (no actual
            // deallocation), so the pointer's allocation-API dual is
            // trivially "do nothing". Handle drops at end of block.
            let handle: ScopedHandle<u8, CountFree> = unsafe { ScopedHandle::from_ptr(raw) }.expect("non-null");
            assert_eq!(handle.as_ptr(), raw);
            assert_eq!(FREE_CALLS.load(Ordering::SeqCst), 0, "no free yet");
        }
        assert_eq!(FREE_CALLS.load(Ordering::SeqCst), 1, "exactly one free on drop");
    }

    #[test]
    fn scoped_handle_take_suppresses_free() {
        let _guard = FREE_CALLS_MUTEX.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        FREE_CALLS.store(0, Ordering::SeqCst);
        let mut storage = 0u8;
        let raw: *mut u8 = &mut storage;
        // SAFETY: see `scoped_handle_calls_free_exactly_once_on_drop`;
        // the test's `CountFree::free` does not deallocate.
        let handle: ScopedHandle<u8, CountFree> = unsafe { ScopedHandle::from_ptr(raw) }.expect("non-null");
        let released = handle.take();
        assert_eq!(released, raw, "take must return the original pointer");
        assert_eq!(FREE_CALLS.load(Ordering::SeqCst), 0, "take suppresses free");
    }

    #[test]
    fn scoped_handle_from_ptr_returns_none_for_null() {
        // SAFETY: `from_ptr`'s contract holds vacuously for null —
        // the impl's `NonNull::new` returns `None` before any
        // dereference and never calls `F::free`.
        let result: Option<ScopedHandle<u8, CountFree>> = unsafe { ScopedHandle::from_ptr(std::ptr::null_mut()) };
        assert!(result.is_none(), "null pointer must not produce a handle");
    }

    #[test]
    fn scoped_handle_double_drop_does_not_double_free() {
        // Construct two distinct handles to two distinct pointers
        // and assert each frees exactly once. Demonstrates the
        // wrapper does not share state across instances.
        let _guard = FREE_CALLS_MUTEX.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        FREE_CALLS.store(0, Ordering::SeqCst);
        let mut a = 1u8;
        let mut b = 2u8;
        {
            // SAFETY: `&mut a` is a live exclusive borrow; the test
            // `CountFree::free` does not deallocate. h1 drops at end
            // of block.
            let _h1: ScopedHandle<u8, CountFree> = unsafe { ScopedHandle::from_ptr(&mut a) }.expect("h1");
            // SAFETY: `&mut b` is a separate live exclusive borrow
            // for a distinct stack slot; the same rationale as h1
            // applies.
            let _h2: ScopedHandle<u8, CountFree> = unsafe { ScopedHandle::from_ptr(&mut b) }.expect("h2");
        }
        assert_eq!(FREE_CALLS.load(Ordering::SeqCst), 2, "two handles freed once each");
    }

    #[test]
    fn scoped_handle_take_round_trip_frees_exactly_once() {
        // FFI ownership-transfer regression for issue #15.
        // Models the `rust_alloc` / `rust_free` paired-export shape
        // documented in docs/rust-soundness-policy.md § "FFI
        // ownership shapes": Rust hands the pointer back to the
        // C side via `take()` (no free runs), then a separate
        // "C-side free" path reclaims it (one free counted). The
        // discipline is the same as a real
        // `rust_alloc_FOO` / `rust_free_FOO` pair where the holder
        // type transitions ownership exactly once across the
        // boundary.
        //
        // Uses a per-test `BoundaryFree` impl with its own atomic
        // counter so the test does not race with other
        // `FREE_CALLS`-mutating tests in this module.
        static BOUNDARY_CALLS: AtomicUsize = AtomicUsize::new(0);
        struct BoundaryFree;
        impl FreeFunction<u8> for BoundaryFree {
            unsafe fn free(_: *mut u8) {
                BOUNDARY_CALLS.fetch_add(1, Ordering::SeqCst);
            }
        }
        BOUNDARY_CALLS.store(0, Ordering::SeqCst);
        let mut storage = 0u8;
        let raw: *mut u8 = &mut storage;
        // SAFETY: `&mut storage` is a live exclusive borrow; the
        // test `BoundaryFree::free` does not deallocate.
        let handle: ScopedHandle<u8, BoundaryFree> = unsafe { ScopedHandle::from_ptr(raw) }.expect("non-null");
        let across_boundary = handle.take();
        assert_eq!(BOUNDARY_CALLS.load(Ordering::SeqCst), 0, "take must NOT trigger F::free");
        // Simulated foreign-side `rust_free_FOO(ptr)`:
        // SAFETY: pointer round-tripped through `take()`; the
        // wrapper's Drop is suppressed, so this is the unique
        // reclamation site.
        unsafe { BoundaryFree::free(across_boundary) };
        assert_eq!(BOUNDARY_CALLS.load(Ordering::SeqCst), 1, "free must be called exactly once across the boundary");
    }

    #[test]
    #[allow(invalid_from_utf8)] // the test deliberately feeds invalid UTF-8 to the validating constructors
    fn safe_string_from_utf8_rejects_invalid_input() {
        // Invalid-UTF-8 regression for issue #17.
        // The workspace's recommended alternative to
        // `String::from_utf8_unchecked` for untrusted input is
        // `String::from_utf8`, which performs release-mode UTF-8
        // validation and returns `Result<String, FromUtf8Error>`.
        // This test exercises that defence end-to-end against the
        // three canonical hostile byte sequences a network/file/FFI
        // input might produce: a lone continuation byte, an
        // unfinished multi-byte sequence, and an over-long
        // encoding. Each MUST produce `Err`; an unchecked
        // constructor would silently invalidate the `str`
        // invariant and reach UB on the next iteration.

        // Lone continuation byte (0x80 with no leading start byte).
        let lone_continuation: Vec<u8> = vec![0x80];
        assert!(String::from_utf8(lone_continuation.clone()).is_err(), "lone continuation byte must be rejected",);

        // Unfinished 3-byte sequence (0xE0 starts a 3-byte
        // sequence; we provide only 1 of the 2 continuation bytes).
        let unfinished_multi_byte: Vec<u8> = vec![0xE0, 0xA0];
        assert!(
            String::from_utf8(unfinished_multi_byte.clone()).is_err(),
            "unfinished multi-byte sequence must be rejected",
        );

        // Over-long encoding (`/` encoded as a 2-byte sequence
        // instead of 1 ASCII byte). This is the historical
        // CVE-2000-0884-style attack vector — over-long encodings
        // can bypass naive ASCII filters.
        let overlong: Vec<u8> = vec![0xC0, 0xAF];
        assert!(
            String::from_utf8(overlong.clone()).is_err(),
            "over-long encoding must be rejected (canonical security risk)",
        );

        // Lossy conversion of the same hostile inputs MUST succeed
        // by substituting U+FFFD REPLACEMENT CHARACTER. Use this
        // shape only for best-effort logging / classification —
        // never round-trip lossy-converted bytes back to a
        // protocol consumer.
        for bad in [lone_continuation, unfinished_multi_byte, overlong] {
            let lossy = String::from_utf8_lossy(&bad);
            assert!(lossy.contains('\u{FFFD}'), "lossy conversion must emit U+FFFD for invalid input: {bad:02x?}",);
        }

        // Valid UTF-8 round-trips through `String::from_utf8`
        // unchanged. This is the happy path; the rejection cases
        // above are the load-bearing ones.
        let valid_ascii: Vec<u8> = b"PAYLOAD".to_vec();
        let valid_unicode: Vec<u8> = "café".as_bytes().to_vec();
        assert_eq!(String::from_utf8(valid_ascii).unwrap(), "PAYLOAD");
        assert_eq!(String::from_utf8(valid_unicode).unwrap(), "café");

        // The same inputs through the borrowed `str::from_utf8`
        // — same result, no allocation.
        assert!(std::str::from_utf8(&[0x80]).is_err());
        assert!(std::str::from_utf8(b"PAYLOAD").is_ok());
    }

    #[test]
    fn vec_with_capacity_spare_capacity_round_trip_models_recv_fill() {
        // Raw-buffer-transfer regression for issue #16.
        // The workspace's recommended alternative to
        // `Vec::from_raw_parts` for the "Rust allocates, foreign code
        // writes" pattern is `Vec::with_capacity(N)` +
        // `spare_capacity_mut()` + `set_len(n)`. This test exercises
        // that exact shape end-to-end so a future regression that
        // introduces `Vec::from_raw_parts` instead of this idiom
        // would have an explicit, named alternative test to
        // reference.
        //
        // Models a foreign `recv`-style fill: Rust allocates a Vec
        // with a capacity ceiling, hands `spare_capacity_mut()` (a
        // `&mut [MaybeUninit<u8>]` — the typed safe wrapper around
        // the spare region) to a simulated foreign filler, the
        // filler writes `n` bytes through `MaybeUninit::write`,
        // and Rust calls `set_len(n)` to publish the initialised
        // prefix. No `Vec::from_raw_parts` is needed; the
        // allocation never leaves Rust ownership.
        use std::mem::MaybeUninit;

        fn simulated_recv_fill(buffer: &mut [MaybeUninit<u8>]) -> usize {
            // Foreign code's invariant: writes `n` bytes through
            // `MaybeUninit::write`, returns `n`. Never reads
            // uninitialised memory.
            let payload: [u8; 7] = *b"PAYLOAD";
            for (slot, byte) in buffer.iter_mut().zip(payload.iter()) {
                slot.write(*byte);
            }
            payload.len()
        }

        const CAPACITY: usize = 32;
        let mut v: Vec<u8> = Vec::with_capacity(CAPACITY);
        assert_eq!(v.len(), 0, "freshly allocated");
        assert!(v.capacity() >= CAPACITY, "with_capacity is a lower bound");

        let n = simulated_recv_fill(v.spare_capacity_mut());
        assert!(n <= v.capacity(), "filler must not overrun capacity");

        // SAFETY: the simulated_recv_fill contract guarantees the
        // first `n` bytes of the spare region are now valid `u8`
        // values written via `MaybeUninit::write`. `n <= capacity`
        // is asserted above. This is the canonical
        // `with_capacity + spare_capacity_mut + set_len` idiom
        // documented in docs/rust-soundness-policy.md § "`Vec::
        // from_raw_parts` ownership transfer" as the workspace's
        // recommended alternative to `Vec::from_raw_parts`.
        unsafe { v.set_len(n) };

        assert_eq!(v.len(), n, "len now reflects the initialised prefix");
        assert_eq!(&v[..], b"PAYLOAD", "initialised bytes are observable");
        // Capacity remains the original (or larger) — `set_len`
        // does not shrink the allocation.
        assert!(v.capacity() >= CAPACITY, "capacity unchanged by set_len");
    }

    #[test]
    fn scoped_handle_size_invariant_for_ffi_holder() {
        // FFI ownership-transfer regression for issue #15.
        // `ScopedHandle<T, F>` is the canonical holder between
        // `Box::into_raw` (or any C `*` allocation) and the
        // matching free; its in-memory layout MUST be exactly
        // one machine pointer plus the zero-sized `PhantomData<F>`
        // marker. A future field addition (e.g. a "was_freed"
        // bool) would silently bloat the holder beyond a pointer
        // and break ABI for any FFI caller that mirrors the type.
        // The wrapper is move-only (no Copy/Clone), so this also
        // doubles as a structural assertion that no field was
        // added that would force the trait derivations down a
        // different path.
        assert_eq!(
            std::mem::size_of::<ScopedHandle<u8, CountFree>>(),
            std::mem::size_of::<*mut u8>(),
            "ScopedHandle must remain one machine pointer wide",
        );
        assert_eq!(
            std::mem::align_of::<ScopedHandle<u8, CountFree>>(),
            std::mem::align_of::<*mut u8>(),
            "ScopedHandle must keep pointer alignment",
        );
    }

    #[test]
    fn scoped_handle_frees_even_on_panic_unwind() {
        let _guard = FREE_CALLS_MUTEX.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        FREE_CALLS.store(0, Ordering::SeqCst);
        let mut storage = 0u8;
        let raw: *mut u8 = &mut storage;
        let result = std::panic::catch_unwind(|| {
            // SAFETY: see `scoped_handle_calls_free_exactly_once_on_drop`;
            // the panic-unwind inside `catch_unwind` exercises Drop
            // on the live handle, which is the discipline this test
            // pins down.
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
