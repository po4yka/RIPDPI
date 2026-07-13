use std::cell::UnsafeCell;
use std::ops::{Deref, DerefMut};
use std::sync::{Arc, Mutex};

use io_uring::IoUring;

/// A pool of fixed-size buffers registered with an io_uring instance.
///
/// Registration pins the buffers in kernel memory, enabling zero-copy I/O
/// via `IORING_OP_READ_FIXED` / `IORING_OP_WRITE_FIXED`.
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
// owns the backing memory and iovecs for the driver-owned kernel registration
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
    pub(crate) fn acquire(self: &Arc<Self>) -> Option<BufferHandle> {
        let index = self.free_list.lock().ok()?.pop()?;
        Some(BufferHandle { pool: Arc::clone(self), index, len: 0 })
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
    #[cfg(test)]
    pub fn available(&self) -> usize {
        self.free_list.lock().map_or(0, |f| f.len())
    }
}

/// A handle to a single registered buffer. Provides slice access for
/// in-place packet parsing and mutation. Returns to the pool on drop.
///
/// The handle owns an [`Arc`] to its originating pool. Moving it into a driver
/// submission therefore keeps both the registered allocation and its exact
/// pool identity alive until the matching CQE is reaped.
pub struct BufferHandle {
    pool: Arc<RegisteredBufferPool>,
    index: u16,
    /// Actual data length within the buffer (may be less than buffer_size).
    len: usize,
}

impl BufferHandle {
    pub(crate) fn belongs_to(&self, pool: &Arc<RegisteredBufferPool>) -> bool {
        Arc::ptr_eq(&self.pool, pool)
    }

    /// The io_uring buffer index. Kept crate-private so safe callers cannot
    /// submit an index from another ring or release the slot twice.
    pub(crate) fn buf_index(&self) -> u16 {
        self.index
    }

    /// Set the length of valid data in this buffer (e.g. after a recv).
    #[must_use = "a rejected length must not be submitted as if it fit the registered buffer"]
    pub fn set_len(&mut self, len: usize) -> bool {
        if len > self.pool.buffer_size {
            return false;
        }
        self.len = len;
        true
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

    pub(crate) fn as_ptr(&self) -> *const u8 {
        let cell = &self.pool.buffers[usize::from(self.index)];
        // SAFETY: the move-only handle is the sole accessor for this slot and
        // the boxed allocation remains stable while the owning Arc is alive.
        unsafe { (*cell.get()).as_ptr() }
    }

    pub(crate) fn as_mut_ptr(&mut self) -> *mut u8 {
        let cell = &self.pool.buffers[usize::from(self.index)];
        // SAFETY: the move-only handle is the sole accessor for this slot and
        // the boxed allocation remains stable while the owning Arc is alive.
        unsafe { (*cell.get()).as_mut_ptr() }
    }

    pub(crate) fn len_u32(&self) -> u32 {
        u32::try_from(self.len).unwrap_or(u32::MAX)
    }

    pub(crate) fn capacity_u32(&self) -> u32 {
        u32::try_from(self.pool.buffer_size).unwrap_or(u32::MAX)
    }
}

impl Deref for BufferHandle {
    type Target = [u8];

    fn deref(&self) -> &[u8] {
        let cell = &self.pool.buffers[usize::from(self.index)];
        // SAFETY: see `as_mut_buf` — `BufferHandle` is the sole accessor of
        // `buffers[index]`; `&self` here is sufficient to read.
        let buf: &[u8] = unsafe { (*cell.get()).as_ref() };
        &buf[..self.len]
    }
}

impl DerefMut for BufferHandle {
    fn deref_mut(&mut self) -> &mut [u8] {
        let len = self.len;
        let cell = &self.pool.buffers[usize::from(self.index)];
        // SAFETY: see `as_mut_buf`.
        let buf: &mut [u8] = unsafe { (*cell.get()).as_mut() };
        &mut buf[..len]
    }
}

impl Drop for BufferHandle {
    fn drop(&mut self) {
        self.pool.release(self.index);
    }
}

// Compile-fail regression for soundness issue #13: `BufferHandle` is the
// canonical move-only owner handle in this workspace. Its exclusive-access protocol (move-only handle + free-list
// mutex + `&mut self`-anchored borrows + RAII Drop) breaks the moment safe
// code can duplicate the handle. The two `AmbiguousIf*` const blocks below
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
    <Check<BufferHandle> as AmbiguousIfCopy<_>>::check();
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
    <Check<BufferHandle> as AmbiguousIfClone<_>>::check();
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
    //!
    //! The compile-fail properties (`BufferHandle: !Copy + !Clone` and
    //! "second `as_mut_buf` while the first slice is live") are
    //! enforced directly by the type system per
    //! `docs/rust-soundness-policy.md` § "Compile-fail enforcement";
    //! the repo policy is to use the type system as the compile-fail
    //! harness rather than `trybuild`.

    use super::*;
    use io_uring::IoUring;

    fn try_pool(capacity: u16) -> Option<Arc<RegisteredBufferPool>> {
        // Skip cleanly on kernels without io_uring or without
        // IORING_REGISTER_BUFFERS support. Tests in this module act as
        // smoke tests on CI Linux runners and as no-ops elsewhere.
        let ring = IoUring::new(8).ok()?;
        RegisteredBufferPool::new(&ring, capacity, 1024).ok().map(Arc::new)
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
