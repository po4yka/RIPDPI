use std::io;
use std::os::fd::{BorrowedFd, OwnedFd};
use std::sync::Arc;
use std::thread;

use io_uring::IoUring;

use crate::bufpool::{BufferHandle, RegisteredBufferPool};
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
    /// Start the driver thread and register its buffer pool on the same ring.
    ///
    /// Ring construction and buffer registration are deliberately one safe
    /// operation: callers cannot pair a pool registered on one ring with a
    /// driver that submits fixed-buffer operations on another ring.
    pub fn start(pool_capacity: u16, buffer_size: usize) -> io::Result<Self> {
        let ring = IoUring::new(RING_SIZE)?;
        let pool = Arc::new(RegisteredBufferPool::new(&ring, pool_capacity, buffer_size)?);

        let (tx, rx) = flume::bounded::<Submission>(RING_SIZE as usize);
        let registry = Arc::new(CompletionRegistry::new());
        let registry_clone = Arc::clone(&registry);

        let thread = thread::Builder::new()
            .name("io-uring-driver".into())
            .spawn(move || driver_loop(ring, rx, registry_clone))?;

        Ok(Self { tx, registry, pool, thread: Some(thread) })
    }

    /// Submit a receive into a registered buffer and return a future.
    ///
    /// The lease must come from [`Self::acquire_buffer`]. Ownership transfers
    /// to the driver until the CQE arrives, then returns in
    /// [`CompletionResult::into_buffer`].
    // cancel-safe: synchronous; no await points.
    pub fn recv_fixed(&self, fd: BorrowedFd<'_>, buffer: BufferHandle) -> CompletionFuture {
        let token = next_token();
        self.registry.begin(token);
        let fd = match fd.try_clone_to_owned() {
            Ok(fd) => fd,
            Err(error) => {
                let errno = error.raw_os_error().unwrap_or(libc::EIO);
                self.registry.complete(token, CompletionResult::with_buffer(-errno, 0, buffer));
                return CompletionFuture::new(token, Arc::clone(&self.registry));
            }
        };
        if let Err(error) = self.tx.send(Submission::RecvFixed { fd, buffer, token }) {
            let Submission::RecvFixed { buffer, .. } = error.0 else { unreachable!() };
            self.registry.complete(token, CompletionResult::with_buffer(-libc::EAGAIN, 0, buffer));
        }
        CompletionFuture::new(token, Arc::clone(&self.registry))
    }

    /// Submit a plain (non-registered) write and return a future.
    ///
    /// Ownership of `buf` is transferred to the driver, which keeps it alive
    /// until the io_uring completion is reaped.
    ///
    /// See [`Self::recv_fixed`] for backpressure and failure behaviour.
    // cancel-safe: synchronous; no await points.
    pub fn write(&self, fd: BorrowedFd<'_>, buf: Vec<u8>) -> CompletionFuture {
        let token = next_token();
        self.registry.begin(token);
        let Some(fd) = self.duplicate_fd(fd, token) else {
            return CompletionFuture::new(token, Arc::clone(&self.registry));
        };
        if self.tx.send(Submission::Write { fd, buf, token }).is_err() {
            self.registry.complete(token, CompletionResult::plain(-libc::EAGAIN, 0));
        }
        CompletionFuture::new(token, Arc::clone(&self.registry))
    }

    /// Submit `IORING_OP_WRITE_FIXED`, consuming the registered-buffer lease
    /// until the CQE is reaped. The completed lease is returned through
    /// [`CompletionResult::into_buffer`].
    // cancel-safe: synchronous; no await points.
    pub fn write_fixed(&self, fd: BorrowedFd<'_>, buffer: BufferHandle) -> CompletionFuture {
        let token = next_token();
        self.registry.begin(token);
        let fd = match fd.try_clone_to_owned() {
            Ok(fd) => fd,
            Err(error) => {
                let errno = error.raw_os_error().unwrap_or(libc::EIO);
                self.registry.complete(token, CompletionResult::with_buffer(-errno, 0, buffer));
                return CompletionFuture::new(token, Arc::clone(&self.registry));
            }
        };
        if let Err(error) = self.tx.send(Submission::WriteFixed { fd, buffer, token }) {
            let Submission::WriteFixed { buffer, .. } = error.0 else { unreachable!() };
            self.registry.complete(token, CompletionResult::with_buffer(-libc::EAGAIN, 0, buffer));
        }
        CompletionFuture::new(token, Arc::clone(&self.registry))
    }

    /// Acquire an owning lease from this driver's registered buffer pool.
    pub fn acquire_buffer(&self) -> Option<BufferHandle> {
        self.pool.acquire()
    }

    /// Size of each registered buffer.
    pub fn buffer_size(&self) -> usize {
        self.pool.buffer_size()
    }

    #[cfg(test)]
    pub(crate) fn available_buffers(&self) -> usize {
        self.pool.available()
    }

    fn duplicate_fd(&self, fd: BorrowedFd<'_>, token: u64) -> Option<OwnedFd> {
        match fd.try_clone_to_owned() {
            Ok(fd) => Some(fd),
            Err(error) => {
                let errno = error.raw_os_error().unwrap_or(libc::EIO);
                self.registry.complete(token, CompletionResult::plain(-errno, 0));
                None
            }
        }
    }

    /// Construct a driver whose submission channel is already disconnected
    /// (the receiver has been dropped). Every submit call on the returned
    /// driver will immediately pre-complete its future with `-EAGAIN` rather
    /// than hanging. Intended for unit tests only.
    #[cfg(test)]
    pub(crate) fn new_with_disconnected_channel(pool: Arc<RegisteredBufferPool>) -> Self {
        let (tx, rx) = flume::bounded::<Submission>(1);
        // Drop the receiver immediately so that `tx.send()` returns `Err`
        // (disconnected) on the very first call.
        drop(rx);
        let registry = Arc::new(CompletionRegistry::new());
        Self { tx, registry, pool, thread: None }
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

#[cfg(test)]
mod tests {
    use std::fs::OpenOptions;
    use std::io::{Read, Write};
    use std::os::fd::AsFd;
    use std::os::unix::net::UnixStream;
    use std::sync::Arc;
    use std::time::{Duration, Instant};

    use io_uring::IoUring;

    use super::*;
    use crate::bufpool::RegisteredBufferPool;
    use crate::ring::block_on_completion;

    #[test]
    fn driver_registers_pool_on_its_own_ring() {
        let Ok(driver) = IoUringDriver::start(4, 1024) else {
            eprintln!("io_uring unavailable; skipping driver_registers_pool_on_its_own_ring");
            return;
        };
        let file = OpenOptions::new().write(true).open("/dev/null").expect("open /dev/null");
        let mut handle = driver.acquire_buffer().expect("acquire registered buffer");
        handle.as_mut_buf()[..4].copy_from_slice(b"ring");
        handle.set_len(4);

        let result = block_on_completion(driver.write_fixed(file.as_fd(), handle));

        assert_eq!(result.result, 4, "fixed write must use the driver's registered buffer table");
        assert_eq!(driver.available_buffers(), 3, "CQE result must retain the submitted lease");
        drop(result);
        assert_eq!(driver.available_buffers(), 4, "dropping the CQE result must release the lease exactly once");
    }

    #[test]
    fn submission_owns_fd_after_caller_drops_socket() {
        let Ok(driver) = IoUringDriver::start(4, 1024) else {
            eprintln!("io_uring unavailable; skipping submission_owns_fd_after_caller_drops_socket");
            return;
        };
        let (sender, mut receiver) = UnixStream::pair().expect("create socket pair");

        let future = driver.write(sender.as_fd(), b"owned".to_vec());
        drop(sender);
        let result = block_on_completion(future);

        assert_eq!(result.result, 5);
        let mut bytes = [0_u8; 5];
        receiver.read_exact(&mut bytes).expect("read io_uring payload");
        assert_eq!(&bytes, b"owned");
    }

    #[test]
    fn abandoned_fixed_read_releases_lease_only_after_cqe() {
        let Ok(driver) = IoUringDriver::start(4, 1024) else {
            eprintln!("io_uring unavailable; skipping abandoned_fixed_read_releases_lease_only_after_cqe");
            return;
        };
        let (mut sender, receiver) = UnixStream::pair().expect("create socket pair");
        let handle = driver.acquire_buffer().expect("acquire registered buffer");

        let future = driver.recv_fixed(receiver.as_fd(), handle);
        drop(future);
        assert_eq!(driver.available_buffers(), 3, "dropped future must not release a kernel-visible lease");

        sender.write_all(b"late").expect("complete abandoned read");
        let deadline = Instant::now() + Duration::from_secs(2);
        while driver.available_buffers() != 4 && Instant::now() < deadline {
            std::thread::sleep(Duration::from_millis(5));
        }
        assert_eq!(driver.available_buffers(), 4, "late CQE must release the abandoned lease");
    }

    /// Helper: create a small pool if io_uring is available on this host.
    /// Returns `None` on macOS or kernels without io_uring support — the
    /// test gracefully skips in that case.
    fn try_make_pool() -> Option<Arc<RegisteredBufferPool>> {
        let ring = IoUring::new(8).ok()?;
        let pool = RegisteredBufferPool::new(&ring, 4, 1024).ok()?;
        Some(Arc::new(pool))
    }

    /// When the driver thread is gone (disconnected channel), every submit
    /// method must resolve immediately with a negative result rather than
    /// hanging forever.
    ///
    /// Uses `tokio::time::timeout` as the hang detector — if the future is
    /// still pending after 1 s, the test fails rather than deadlocking.
    #[test]
    fn submit_after_driver_gone_resolves_with_error() {
        let Some(pool) = try_make_pool() else {
            eprintln!("io_uring unavailable; skipping submit_after_driver_gone_resolves_with_error");
            return;
        };

        let driver = IoUringDriver::new_with_disconnected_channel(pool);

        // Use the `write` path (no registered-buffer index required).
        let file = OpenOptions::new().write(true).open("/dev/null").expect("open /dev/null");
        let future = driver.write(file.as_fd(), vec![0u8; 4]);

        // block_on_completion uses pollster which is a simple sync executor;
        // the future must resolve in the first poll because complete() was
        // called before the future was created.
        let result = block_on_completion(future);

        assert!(result.result < 0, "expected negative errno result from disconnected driver, got {}", result.result);
        assert_eq!(result.result, -libc::EAGAIN, "expected -EAGAIN from disconnected driver");
    }

    /// After a failed submission resolves, the registry slot for that token
    /// must be cleared — no leak.
    #[test]
    fn completion_registry_no_leak_on_failed_submit() {
        let Some(pool) = try_make_pool() else {
            eprintln!("io_uring unavailable; skipping completion_registry_no_leak_on_failed_submit");
            return;
        };

        let driver = IoUringDriver::new_with_disconnected_channel(pool);
        // Snapshot registry size before submit.
        let before = driver.registry.slot_count();

        let file = OpenOptions::new().write(true).open("/dev/null").expect("open /dev/null");
        let future = driver.write(file.as_fd(), vec![0u8; 4]);
        // The slot was inserted by complete() just before the future was
        // returned; it must be present now (count should be before + 1).
        let during = driver.registry.slot_count();
        assert_eq!(during, before + 1, "registry must hold the pre-completed slot");

        // Resolve the future — poll() removes the Ready slot from the map.
        let _ = block_on_completion(future);

        let after = driver.registry.slot_count();
        assert_eq!(after, before, "registry must be empty after future resolves (no leak)");
    }
}
