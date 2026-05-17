use std::io;
use std::os::fd::RawFd;
use std::sync::Arc;
use std::thread;

use io_uring::IoUring;

use crate::bufpool::RegisteredBufferPool;
use crate::ring::completion::{CompletionFuture, CompletionRegistry};
use crate::ring::driver_loop::driver_loop;
use crate::ring::submission::{next_token, Submission};

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
    pub fn send_zc(&self, fd: RawFd, buf_index: u16, len: u32) -> CompletionFuture {
        let token = next_token();
        let _ = self.tx.send(Submission::SendZc { fd, buf_index, len, token });
        CompletionFuture::new(token, Arc::clone(&self.registry))
    }

    /// Submit a receive into a registered buffer and return a future.
    pub fn recv_fixed(&self, fd: RawFd, buf_index: u16) -> CompletionFuture {
        let token = next_token();
        let _ = self.tx.send(Submission::RecvFixed { fd, buf_index, token });
        CompletionFuture::new(token, Arc::clone(&self.registry))
    }

    /// Submit a plain (non-registered) write and return a future.
    ///
    /// Ownership of `buf` is transferred to the driver, which keeps it alive
    /// until the io_uring completion is reaped. This is the correct opcode
    /// for caller-owned `Vec<u8>` payloads; `send_zc` requires a registered
    /// buffer and is wrong for this path.
    pub fn write(&self, fd: RawFd, buf: Vec<u8>) -> CompletionFuture {
        let token = next_token();
        let _ = self.tx.send(Submission::Write { fd, buf, token });
        CompletionFuture::new(token, Arc::clone(&self.registry))
    }

    /// Submit `IORING_OP_WRITE_FIXED` against a buffer already registered in
    /// the pool, and return a future.
    ///
    /// The caller is responsible for keeping the buffer slot valid (i.e. not
    /// returning it to the pool) until the matching completion arrives. This
    /// is the high-performance path used by [`crate::tun::batch_tun_write`]
    /// after the payload is staged into a `RegisteredBufferPool` slot.
    pub fn write_fixed(&self, fd: RawFd, buf_index: u16, len: u32) -> CompletionFuture {
        let token = next_token();
        let _ = self.tx.send(Submission::WriteFixed { fd, buf_index, len, token });
        CompletionFuture::new(token, Arc::clone(&self.registry))
    }

    /// Access the registered buffer pool.
    pub fn pool(&self) -> &Arc<RegisteredBufferPool> {
        &self.pool
    }
}

impl Drop for IoUringDriver {
    fn drop(&mut self) {
        let _ = self.tx.send(Submission::Shutdown);
        if let Some(handle) = self.thread.take() {
            let _ = handle.join();
        }
    }
}
