use std::ops::{Deref, DerefMut};
use std::sync::Mutex;

use io_uring::IoUring;

/// Default buffer size matching the relay `RELAY_BUF` (16 KiB).
pub const DEFAULT_BUFFER_SIZE: usize = 16_384;
/// Default pool capacity (256 buffers = 4 MiB at 16 KiB each).
pub const DEFAULT_POOL_CAPACITY: u16 = 256;

/// A pool of fixed-size buffers registered with an io_uring instance.
///
/// Registration pins the buffers in kernel memory, enabling zero-copy I/O
/// via `IORING_OP_READ_FIXED` / `IORING_OP_WRITE_FIXED` and
/// `IORING_OP_SEND_ZC` with buffer indices.
pub struct RegisteredBufferPool {
    /// Backing storage: each `Vec<u8>` is exactly `buffer_size` bytes.
    buffers: Vec<Vec<u8>>,
    /// iovecs registered with the kernel. Must stay alive and stable while
    /// buffers are registered.
    _iovecs: Vec<libc::iovec>,
    /// Indices of available buffers.
    free_list: Mutex<Vec<u16>>,
    /// Size of each individual buffer.
    buffer_size: usize,
}

// SAFETY: The internal buffers are heap-allocated and pinned via registration.
// The Mutex protects the free list. The pool itself does not implement io_uring
// operations -- callers pass buffer indices to the IoUringDriver.
unsafe impl Send for RegisteredBufferPool {}
unsafe impl Sync for RegisteredBufferPool {}

impl RegisteredBufferPool {
    /// Create a new buffer pool and register buffers with the given io_uring.
    ///
    /// Returns `Err` if `IORING_REGISTER_BUFFERS` fails (e.g. kernel too old
    /// or resource limits exceeded).
    pub fn new(ring: &IoUring, capacity: u16, buffer_size: usize) -> std::io::Result<Self> {
        let cap = usize::from(capacity);
        let mut buffers: Vec<Vec<u8>> = (0..cap).map(|_| vec![0u8; buffer_size]).collect();

        let iovecs: Vec<libc::iovec> = buffers
            .iter_mut()
            .map(|buf| libc::iovec { iov_base: buf.as_mut_ptr().cast(), iov_len: buf.len() })
            .collect();

        // SAFETY: iovecs point to valid, live buffers that outlive the registration.
        unsafe {
            ring.submitter().register_buffers(&iovecs)?;
        }

        let free_list = (0..capacity).rev().collect();

        Ok(Self { buffers, _iovecs: iovecs, free_list: Mutex::new(free_list), buffer_size })
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
    pub fn release_by_index(&self, index: u16) {
        self.release(index);
    }

    /// Get a read-only slice of a registered buffer by index. Returns an
    /// empty slice if the index is out of bounds.
    pub fn buf_slice(&self, index: u16, len: usize) -> &[u8] {
        self.buffers.get(usize::from(index)).map(|buf| &buf[..len.min(buf.len())]).unwrap_or(&[])
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
        debug_assert!(len <= self.pool.buffer_size);
        self.len = len.min(self.pool.buffer_size);
    }

    /// Get the full buffer slice (up to `buffer_size`), for use as a recv
    /// target.
    pub fn as_mut_buf(&mut self) -> &mut [u8] {
        // SAFETY: BufferHandle has exclusive logical ownership of buffers[index]
        // (enforced by the free-list acquire/release protocol).
        let idx = usize::from(self.index);
        let ptr = self.pool.buffers[idx].as_ptr() as *mut u8;
        let len = self.pool.buffers[idx].len();
        unsafe { std::slice::from_raw_parts_mut(ptr, len) }
    }

    /// Convert into a `PendingBuffer` that does NOT return to the pool on
    /// drop. Use this when the buffer has been submitted for a ZC send and
    /// must remain valid until the kernel notification CQE arrives.
    pub fn into_pending(self) -> PendingBuffer {
        let index = self.index;
        // Suppress the Drop impl that would return to pool.
        std::mem::forget(self);
        PendingBuffer { index }
    }
}

impl Deref for BufferHandle<'_> {
    type Target = [u8];

    fn deref(&self) -> &[u8] {
        &self.pool.buffers[usize::from(self.index)][..self.len]
    }
}

impl DerefMut for BufferHandle<'_> {
    fn deref_mut(&mut self) -> &mut [u8] {
        // SAFETY: BufferHandle has exclusive logical ownership of buffers[index]
        // (enforced by the free-list acquire/release protocol).
        let idx = usize::from(self.index);
        let ptr = self.pool.buffers[idx].as_ptr() as *mut u8;
        let len = self.len;
        unsafe { std::slice::from_raw_parts_mut(ptr, len) }
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
pub struct PendingBuffer {
    index: u16,
}

impl PendingBuffer {
    /// The io_uring buffer index.
    pub fn buf_index(&self) -> u16 {
        self.index
    }

    /// Return this buffer to the pool after the kernel notification CQE.
    pub fn complete(self, pool: &RegisteredBufferPool) {
        pool.release(self.index);
    }
}
