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
unsafe impl Sync for RegisteredBufferPool {}

impl RegisteredBufferPool {
    /// Create a new buffer pool and register buffers with the given io_uring.
    ///
    /// Returns `Err` if `IORING_REGISTER_BUFFERS` fails (e.g. kernel too old
    /// or resource limits exceeded).
    pub fn new(ring: &IoUring, capacity: u16, buffer_size: usize) -> std::io::Result<Self> {
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
        self.free_list.lock().map(|f| f.len()).unwrap_or(0)
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
