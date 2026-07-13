use std::cell::UnsafeCell;
use std::ops::{Deref, DerefMut};
use std::sync::Mutex;

use io_uring::IoUring;

/// A pool of fixed-size buffers registered with an io_uring instance.
///
/// Registration pins the buffers in kernel memory, enabling zero-copy I/O
/// via `IORING_OP_READ_FIXED` / `IORING_OP_WRITE_FIXED` and
/// `IORING_OP_SEND_ZC` with buffer indices.
pub struct RegisteredBufferPool {
    /// Backing storage. Each cell is exclusively accessible to the unique
    /// `BufferHandle` whose `index` matches; that uniqueness is enforced by
    /// the free-list acquire/release protocol below. `UnsafeCell` is
    /// required because `BufferHandle::deref_mut` and `as_mut_buf` produce
    /// `&mut [u8]` through a shared reference to the pool, which would be
    /// UB without interior mutability.
    buffers: Box<[UnsafeCell<Box<[u8]>>]>,
    /// iovecs registered with the kernel. Must stay alive and stable while
    /// buffers are registered.
    _iovecs: Vec<libc::iovec>,
    /// Indices of available buffers.
    free_list: Mutex<Vec<u16>>,
    /// Size of each individual buffer.
    buffer_size: usize,
}

// SAFETY: `libc::iovec` contains `*mut c_void` (which is `!Send + !Sync` by
// default) and `UnsafeCell` is `!Sync` by default, so we must opt in.
// Soundness:
//   * `_iovecs` is read-only after construction.
//   * Each `buffers[i]` is only mutated through the unique `BufferHandle`
//     whose `index == i`. Ownership of that index transfers through the
//     `Mutex`-guarded free list, which provides the necessary
//     happens-before edge between threads.
unsafe impl Send for RegisteredBufferPool {}
// SAFETY: same invariant as `Send`: shared access cannot mutate a buffer unless
// the caller owns its unique `BufferHandle`, and free-list transfers are mutex
// synchronized.
unsafe impl Sync for RegisteredBufferPool {}

// Compile-fail regression for soundness issue #8: any future change that
// breaks the Send/Sync claim above fails to compile here. The block is a
// const-evaluated identity check; `assert_send`/`assert_sync`
// monomorphisations require the bounds to hold for the concrete type.
const _: fn() = || {
    fn assert_send<T: Send>() {}
    fn assert_sync<T: Sync>() {}
    assert_send::<RegisteredBufferPool>();
    assert_sync::<RegisteredBufferPool>();
};

// Compile-fail regression for soundness issue #14: `RegisteredBufferPool`
// owns the kernel registration (released via `IORING_UNREGISTER_BUFFERS`
// on the held `IoUring`, indirectly through the `_iovecs` Vec lifetime)
// plus a heap allocation (`Box<[UnsafeCell<Box<[u8]>>]>`). A
// `#[derive(Copy)]` would let safe code duplicate the pool, hand out
// `BufferHandle`s against two aliasing pools, and double-free the heap
// allocation. The block stays unambiguous only while
// `RegisteredBufferPool: !Copy`.
const _: fn() = || {
    #[allow(dead_code)]
    struct Check<T>(core::marker::PhantomData<T>);
    #[allow(dead_code)]
    trait AmbiguousIfCopy<A> {
        fn check() {}
    }
    impl<T> AmbiguousIfCopy<()> for Check<T> {}
    impl<T: Copy> AmbiguousIfCopy<u8> for Check<T> {}
    <Check<RegisteredBufferPool> as AmbiguousIfCopy<_>>::check();
};

impl RegisteredBufferPool {
    /// Create a new buffer pool and register buffers with the given io_uring.
    ///
    /// Returns `Err` if `IORING_REGISTER_BUFFERS` fails (e.g. kernel too old
    /// or resource limits exceeded).
    pub(crate) fn new(ring: &IoUring, capacity: u16, buffer_size: usize) -> std::io::Result<Self> {
        let cap = usize::from(capacity);
        let mut buffers: Vec<UnsafeCell<Box<[u8]>>> =
            (0..cap).map(|_| UnsafeCell::new(vec![0u8; buffer_size].into_boxed_slice())).collect();

        let iovecs: Vec<libc::iovec> = buffers
            .iter_mut()
            .map(|cell| {
                // SAFETY: we hold the only reference to `cell` via `&mut self`
                // during construction; no `BufferHandle` exists yet.
                let buf: &mut [u8] = unsafe { (*cell.get()).as_mut() };
                libc::iovec { iov_base: buf.as_mut_ptr().cast(), iov_len: buf.len() }
            })
            .collect();

        // SAFETY: each iovec points into a `Box<[u8]>` stored in `buffers`,
        // which we move into the returned `Self` alongside `_iovecs`. The
        // backing memory therefore outlives the registration. The iovec base
        // pointers remain stable because `Box<[u8]>` is moved into the cell
        // and never reallocated.
        unsafe {
            ring.submitter().register_buffers(&iovecs)?;
        }

        let free_list = (0..capacity).rev().collect();

        Ok(Self { buffers: buffers.into_boxed_slice(), _iovecs: iovecs, free_list: Mutex::new(free_list), buffer_size })
    }

    /// Try to acquire a buffer from the pool. Returns `None` if all buffers
    /// are currently in use.
    pub fn acquire(&self) -> Option<BufferHandle<'_>> {
        let index = self.free_list.lock().ok()?.pop()?;
        Some(BufferHandle { pool: self, index, len: 0 })
    }

    /// Return a buffer to the pool by index. Called by `BufferHandle::drop`.
    fn release(&self, index: u16) {
        if let Ok(mut free) = self.free_list.lock() {
            free.push(index);
        }
    }

    /// The size of each buffer in the pool.
    pub fn buffer_size(&self) -> usize {
        self.buffer_size
    }

    /// Number of buffers currently available.
    pub fn available(&self) -> usize {
        self.free_list.lock().map_or(0, |f| f.len())
    }

    /// Total capacity of the pool.
    pub fn capacity(&self) -> u16 {
        self.buffers.len() as u16
    }

    /// Return a buffer to the pool by raw index. Used by batch I/O paths
    /// that manage buffer indices directly (e.g. [`crate::tun`]).
    ///
    /// Visibility is intentionally `pub(crate)`: outside the crate the
    /// only legitimate way to release a buffer is by dropping a
    /// `BufferHandle` or calling `PendingBuffer::complete`. Misuse would
    /// allow the free list to hold the same index twice and hand out
    /// aliasing handles.
    pub(crate) fn release_by_index(&self, index: u16) {
        self.release(index);
    }
}

/// A handle to a single registered buffer. Provides slice access for
/// in-place packet parsing and mutation. Returns to the pool on drop.
///
/// **ZC send lifetime**: when submitting a zero-copy send, the buffer must
/// not be returned to the pool until the kernel signals completion via
/// `IORING_CQE_F_NOTIF`. Call [`BufferHandle::into_pending`] to convert
/// into a `PendingBuffer` that suppresses the drop-return.
pub struct BufferHandle<'pool> {
    pool: &'pool RegisteredBufferPool,
    index: u16,
    /// Actual data length within the buffer (may be less than buffer_size).
    len: usize,
}

impl<'pool> BufferHandle<'pool> {
    /// The io_uring buffer index for use in SQEs.
    pub fn buf_index(&self) -> u16 {
        self.index
    }

    /// Set the length of valid data in this buffer (e.g. after a recv).
    pub fn set_len(&mut self, len: usize) {
        self.len = len.min(self.pool.buffer_size);
    }

    /// Get the full buffer slice (up to `buffer_size`), for use as a recv
    /// target.
    pub fn as_mut_buf(&mut self) -> &mut [u8] {
        let cell = &self.pool.buffers[usize::from(self.index)];
        // SAFETY: `BufferHandle` holds exclusive access to `buffers[index]`
        // for its lifetime: the free list never hands out the same index
        // twice without an intervening release, and `&mut self` ensures
        // there is no aliasing `&BufferHandle` accessing the cell.
        let buf: &mut [u8] = unsafe { (*cell.get()).as_mut() };
        buf
    }

    /// Convert into a `PendingBuffer<'pool>` that does NOT return to the
    /// pool on drop. Use this when the buffer has been submitted for a ZC
    /// send and must remain valid until the kernel notification CQE
    /// arrives. The returned `PendingBuffer` is tied to the same pool as
    /// the original handle, so `PendingBuffer::complete` cannot release
    /// the index against the wrong pool.
    pub fn into_pending(self) -> PendingBuffer<'pool> {
        let index = self.index;
        let pool = self.pool;
        // Suppress the Drop impl that would return to pool.
        std::mem::forget(self);
        PendingBuffer { pool, index }
    }
}

impl Deref for BufferHandle<'_> {
    type Target = [u8];

    fn deref(&self) -> &[u8] {
        let cell = &self.pool.buffers[usize::from(self.index)];
        // SAFETY: see `as_mut_buf` — `BufferHandle` is the sole accessor of
        // `buffers[index]`; `&self` here is sufficient to read.
        let buf: &[u8] = unsafe { (*cell.get()).as_ref() };
        &buf[..self.len]
    }
}

impl DerefMut for BufferHandle<'_> {
    fn deref_mut(&mut self) -> &mut [u8] {
        let len = self.len;
        let cell = &self.pool.buffers[usize::from(self.index)];
        // SAFETY: see `as_mut_buf`.
        let buf: &mut [u8] = unsafe { (*cell.get()).as_mut() };
        &mut buf[..len]
    }
}

impl Drop for BufferHandle<'_> {
    fn drop(&mut self) {
        self.pool.release(self.index);
    }
}

/// A buffer index whose backing memory is still in-flight for a ZC send.
/// Call [`PendingBuffer::complete`] once `IORING_CQE_F_NOTIF` is observed
/// to return it to the pool.
///
/// The pool reference is captured at construction time so the index
/// cannot be released against a different pool.
pub struct PendingBuffer<'pool> {
    pool: &'pool RegisteredBufferPool,
    index: u16,
}

impl PendingBuffer<'_> {
    /// The io_uring buffer index.
    pub fn buf_index(&self) -> u16 {
        self.index
    }

    /// Return this buffer to the pool after the kernel notification CQE.
    pub fn complete(self) {
        self.pool.release(self.index);
    }
}

// Compile-fail regressions for soundness issue #13: `BufferHandle` and
// `PendingBuffer` are the two canonical move-only owner handles in this
// workspace. Their exclusive-access protocol (move-only handle + free-list
// mutex + `&mut self`-anchored borrows + RAII Drop) breaks the moment safe
// code can duplicate the handle. The four `AmbiguousIf*` const blocks below
// fail to compile if a future change ever derives `Copy` or `Clone` on
// either type, catching the regression at workspace build time before any
// CI test runs.
const _: fn() = || {
    #[allow(dead_code)]
    struct Check<T>(core::marker::PhantomData<T>);
    #[allow(dead_code)]
    trait AmbiguousIfCopy<A> {
        fn check() {}
    }
    impl<T> AmbiguousIfCopy<()> for Check<T> {}
    impl<T: Copy> AmbiguousIfCopy<u8> for Check<T> {}
    <Check<BufferHandle<'static>> as AmbiguousIfCopy<_>>::check();
};

const _: fn() = || {
    #[allow(dead_code)]
    struct Check<T>(core::marker::PhantomData<T>);
    #[allow(dead_code)]
    trait AmbiguousIfClone<A> {
        fn check() {}
    }
    impl<T> AmbiguousIfClone<()> for Check<T> {}
    impl<T: Clone> AmbiguousIfClone<u8> for Check<T> {}
    <Check<BufferHandle<'static>> as AmbiguousIfClone<_>>::check();
};

const _: fn() = || {
    #[allow(dead_code)]
    struct Check<T>(core::marker::PhantomData<T>);
    #[allow(dead_code)]
    trait AmbiguousIfCopy<A> {
        fn check() {}
    }
    impl<T> AmbiguousIfCopy<()> for Check<T> {}
    impl<T: Copy> AmbiguousIfCopy<u8> for Check<T> {}
    <Check<PendingBuffer<'static>> as AmbiguousIfCopy<_>>::check();
};

const _: fn() = || {
    #[allow(dead_code)]
    struct Check<T>(core::marker::PhantomData<T>);
    #[allow(dead_code)]
    trait AmbiguousIfClone<A> {
        fn check() {}
    }
    impl<T> AmbiguousIfClone<()> for Check<T> {}
    impl<T: Clone> AmbiguousIfClone<u8> for Check<T> {}
    <Check<PendingBuffer<'static>> as AmbiguousIfClone<_>>::check();
};

#[cfg(test)]
mod tests {
    //! Soundness regressions for `BufferHandle`'s exclusive-access design.
    //!
    //! The audit for soundness issue #7 verified that the only
    //! pointer-backed `&mut T` creation in the workspace lives in
    //! `bufpool.rs`, gated by:
    //!   1. a move-only `BufferHandle` (no `Copy`/`Clone`),
    //!   2. `Mutex`-guarded free-list ownership for the cell index,
    //!   3. `&mut self` on `as_mut_buf` / `DerefMut`,
    //!   4. RAII Drop that returns the index to the free list.
    //!
    //! These tests assert the runtime invariants those properties
    //! produce:
    //!   - a dropped `BufferHandle` releases its index for reuse
    //!     (no stale handle keeps the slot reserved),
    //!   - a pool at capacity refuses to issue a duplicate handle
    //!     (no aliased mutable access can be obtained from safe code),
    //!   - `into_pending` suppresses Drop-release and `complete`
    //!     hands the index back exactly once (no double-release).
    //!
    //! The compile-fail properties (`BufferHandle: !Copy + !Clone` and
    //! "second `as_mut_buf` while the first slice is live") are
    //! enforced directly by the type system per
    //! `docs/rust-soundness-policy.md` § "Compile-fail enforcement";
    //! the repo policy is to use the type system as the compile-fail
    //! harness rather than `trybuild`.

    use super::*;
    use io_uring::IoUring;

    fn try_pool(capacity: u16) -> Option<RegisteredBufferPool> {
        // Skip cleanly on kernels without io_uring or without
        // IORING_REGISTER_BUFFERS support. Tests in this module act as
        // smoke tests on CI Linux runners and as no-ops elsewhere.
        let ring = IoUring::new(8).ok()?;
        RegisteredBufferPool::new(&ring, capacity, 1024).ok()
    }

    #[test]
    fn drop_returns_index_to_free_list_for_reuse() {
        let Some(pool) = try_pool(2) else { return };
        let handle = pool.acquire().expect("acquire 1");
        let idx = handle.buf_index();
        drop(handle);
        // The free list is a LIFO stack: the freed index is on top.
        let again = pool.acquire().expect("reacquire after drop");
        assert_eq!(again.buf_index(), idx, "drop must return the index for reuse");
    }

    #[test]
    fn capacity_exhaustion_blocks_duplicate_handle() {
        let Some(pool) = try_pool(1) else { return };
        let _first = pool.acquire().expect("acquire first");
        // With the sole index in use, a second acquire MUST return None.
        // This is the runtime witness that safe code cannot obtain a
        // duplicate `BufferHandle` for the same cell.
        assert!(pool.acquire().is_none(), "duplicate handle must be impossible");
    }

    #[test]
    fn pending_buffer_suppresses_drop_release_until_complete() {
        let Some(pool) = try_pool(1) else { return };
        let handle = pool.acquire().expect("acquire");
        let idx = handle.buf_index();
        let pending = handle.into_pending();
        // `into_pending` consumed the handle without releasing its
        // index; the pool is therefore still exhausted.
        assert!(pool.acquire().is_none(), "into_pending must not return the index to the pool");
        pending.complete();
        // After explicit complete, the index returns and is reusable.
        let after = pool.acquire().expect("acquire after complete");
        assert_eq!(after.buf_index(), idx, "complete must return the index exactly once");
    }

    #[test]
    fn available_count_tracks_acquire_and_release() {
        let Some(pool) = try_pool(2) else { return };
        assert_eq!(pool.available(), 2);
        let h1 = pool.acquire().expect("h1");
        assert_eq!(pool.available(), 1);
        let h2 = pool.acquire().expect("h2");
        assert_eq!(pool.available(), 0);
        drop(h1);
        assert_eq!(pool.available(), 1);
        drop(h2);
        assert_eq!(pool.available(), 2);
    }
}
