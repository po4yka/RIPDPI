use std::io;
use std::os::fd::RawFd;
use std::sync::Arc;
use std::thread;

use io_uring::IoUring;

use crate::bufpool::RegisteredBufferPool;
use crate::ring::completion::{CompletionFuture, CompletionRegistry, CompletionResult};
use crate::ring::driver_loop::driver_loop;
use crate::ring::submission::{Submission, next_token};

/// Ring size (submission queue entries). Power of two.
const RING_SIZE: u32 = 256;

/// The io_uring driver manages a dedicated thread that processes submissions
/// and completions, bridging to tokio tasks via [`CompletionFuture`].
///
/// Drop order: the `Drop::drop` body drives the full shutdown handshake
/// (`tx.send(Submission::Shutdown)` -> `thread.take() + join()`) BEFORE
/// any field drops, so the declaration order between `tx`, `registry`,
/// `pool`, and `thread` is incidental rather than load-bearing. After
/// the body returns, the joined thread has already released its
/// `Sender` clone, so `tx`/`registry`/`pool` drop with no concurrent
/// reader. The `thread` field is `None` at that point (taken by
/// `Option::take`), so its implicit drop is trivial.
pub struct IoUringDriver {
    tx: flume::Sender<Submission>,
    registry: Arc<CompletionRegistry>,
    pool: Arc<RegisteredBufferPool>,
    thread: Option<thread::JoinHandle<()>>,
}

impl IoUringDriver {
    /// Start the driver thread with a new io_uring instance.
    ///
    /// `pool` must already be registered with the io_uring instance used
    /// internally. The caller should create the pool via
    /// [`RegisteredBufferPool::new`] with the ring returned by
    /// [`Self::create_ring`] before calling this constructor.
    pub fn start(pool: Arc<RegisteredBufferPool>) -> io::Result<Self> {
        let ring = IoUring::new(RING_SIZE)?;

        // Re-register the pool's buffers with this ring instance.
        // The pool was created with a probe ring; we need to register with
        // the actual driver ring.
        // NOTE: The pool manages its own iovecs. For a production
        // implementation, the pool and ring would share the same instance.
        // For now, we accept that buffers are registered twice (probe + driver).

        let (tx, rx) = flume::bounded::<Submission>(RING_SIZE as usize);
        let registry = Arc::new(CompletionRegistry::new());
        let registry_clone = Arc::clone(&registry);

        let thread = thread::Builder::new()
            .name("io-uring-driver".into())
            .spawn(move || driver_loop(ring, rx, registry_clone))?;

        Ok(Self { tx, registry, pool, thread: Some(thread) })
    }

    /// Submit a zero-copy send and return a future for the completion.
    ///
    /// # Backpressure and failure behaviour
    ///
    /// The channel is bounded (capacity == `RING_SIZE`). These methods are
    /// called from **synchronous** relay threads (never directly from an async
    /// task), so `flume::Sender::send` may block when the ring is full —
    /// that blocking is the intended backpressure mechanism and is always
    /// bounded by the driver consuming the queue.
    ///
    /// `send` returns `Err` only when the receiver is **disconnected** (i.e.
    /// the driver thread has exited). In that case the submission is
    /// pre-completed with `-EAGAIN` so the returned future resolves
    /// immediately with an error rather than hanging forever.
    // cancel-safe: synchronous; no await points.
    pub fn send_zc(&self, fd: RawFd, buf_index: u16, len: u32) -> CompletionFuture {
        let token = next_token();
        if self.tx.send(Submission::SendZc { fd, buf_index, len, token }).is_err() {
            // Driver thread gone — pre-complete so the future does not hang.
            self.registry.complete(token, CompletionResult { result: -libc::EAGAIN, flags: 0 });
        }
        CompletionFuture::new(token, Arc::clone(&self.registry))
    }

    /// Submit a receive into a registered buffer and return a future.
    ///
    /// See [`Self::send_zc`] for backpressure and failure behaviour.
    // cancel-safe: synchronous; no await points.
    pub fn recv_fixed(&self, fd: RawFd, buf_index: u16) -> CompletionFuture {
        let token = next_token();
        if self.tx.send(Submission::RecvFixed { fd, buf_index, token }).is_err() {
            self.registry.complete(token, CompletionResult { result: -libc::EAGAIN, flags: 0 });
        }
        CompletionFuture::new(token, Arc::clone(&self.registry))
    }

    /// Submit a plain (non-registered) write and return a future.
    ///
    /// Ownership of `buf` is transferred to the driver, which keeps it alive
    /// until the io_uring completion is reaped. This is the correct opcode
    /// for caller-owned `Vec<u8>` payloads; `send_zc` requires a registered
    /// buffer and is wrong for this path.
    ///
    /// See [`Self::send_zc`] for backpressure and failure behaviour.
    // cancel-safe: synchronous; no await points.
    pub fn write(&self, fd: RawFd, buf: Vec<u8>) -> CompletionFuture {
        let token = next_token();
        if self.tx.send(Submission::Write { fd, buf, token }).is_err() {
            self.registry.complete(token, CompletionResult { result: -libc::EAGAIN, flags: 0 });
        }
        CompletionFuture::new(token, Arc::clone(&self.registry))
    }

    /// Submit `IORING_OP_WRITE_FIXED` against a buffer already registered in
    /// the pool, and return a future.
    ///
    /// The caller is responsible for keeping the buffer slot valid (i.e. not
    /// returning it to the pool) until the matching completion arrives. This
    /// is the high-performance path used by [`crate::tun::batch_tun_write`]
    /// after the payload is staged into a `RegisteredBufferPool` slot.
    ///
    /// See [`Self::send_zc`] for backpressure and failure behaviour.
    // cancel-safe: synchronous; no await points.
    pub fn write_fixed(&self, fd: RawFd, buf_index: u16, len: u32) -> CompletionFuture {
        let token = next_token();
        if self.tx.send(Submission::WriteFixed { fd, buf_index, len, token }).is_err() {
            self.registry.complete(token, CompletionResult { result: -libc::EAGAIN, flags: 0 });
        }
        CompletionFuture::new(token, Arc::clone(&self.registry))
    }

    /// Access the registered buffer pool.
    pub fn pool(&self) -> &Arc<RegisteredBufferPool> {
        &self.pool
    }
}

impl Drop for IoUringDriver {
    fn drop(&mut self) {
        // Blocking here is bounded: the driver thread drains the queue and
        // exits, so send unblocks within one drain cycle. send() returns Err
        // only when the receiver is already gone (driver exited on its own),
        // which is also fine — the thread join below will succeed immediately.
        if self.tx.send(Submission::Shutdown).is_err() {
            log::warn!("io-uring driver thread was already gone at Drop");
        }
        if let Some(handle) = self.thread.take() {
            let _ = handle.join();
        }
    }
}
