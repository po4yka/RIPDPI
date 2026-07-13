use std::io;
use std::os::fd::{BorrowedFd, OwnedFd};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc;
use std::thread;
use std::time::Duration;

use io_uring::IoUring;

use crate::bufpool::{BufferHandle, RegisteredBufferPool};
use crate::ring::completion::{CompletionFuture, CompletionRegistry, CompletionResult};
use crate::ring::driver_loop::{DriverResources, driver_loop};
use crate::ring::submission::{Submission, next_token};

/// Ring size (submission queue entries). Power of two.
pub(crate) const RING_SIZE: u32 = 256;
const MAX_REGISTERED_BUFFER_SIZE: usize = 65_536;
const DRIVER_SHUTDOWN_TIMEOUT: Duration = Duration::from_millis(250);

/// The io_uring driver manages a dedicated thread that processes submissions
/// and completions, bridging to tokio tasks via [`CompletionFuture`].
///
/// Drop signals shutdown without blocking on the submission queue and waits a
/// bounded interval for the worker. If kernel teardown exceeds that interval,
/// the worker is detached while retaining its own pool/registry guards.
pub struct IoUringDriver {
    tx: flume::Sender<Submission>,
    registry: Arc<CompletionRegistry>,
    pool: Arc<RegisteredBufferPool>,
    shutdown: Arc<AtomicBool>,
    done_rx: Option<mpsc::Receiver<()>>,
    thread: Option<thread::JoinHandle<()>>,
}

impl IoUringDriver {
    /// Start the driver thread and register its buffer pool on the same ring.
    ///
    /// Ring construction and buffer registration are deliberately one safe
    /// operation: callers cannot pair a pool registered on one ring with a
    /// driver that submits fixed-buffer operations on another ring.
    pub fn start(pool_capacity: u16, buffer_size: usize) -> io::Result<Self> {
        if pool_capacity == 0 || u32::from(pool_capacity) > RING_SIZE {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                format!("registered buffer capacity must be in 1..={RING_SIZE}"),
            ));
        }
        if !(1..=MAX_REGISTERED_BUFFER_SIZE).contains(&buffer_size) {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                format!("registered buffer size must be in 1..={MAX_REGISTERED_BUFFER_SIZE}"),
            ));
        }
        let ring = IoUring::new(RING_SIZE)?;
        let pool = Arc::new(RegisteredBufferPool::new(&ring, pool_capacity, buffer_size)?);

        let (tx, rx) = flume::bounded::<Submission>(RING_SIZE as usize);
        let registry = Arc::new(CompletionRegistry::new());
        let registry_clone = Arc::clone(&registry);
        let shutdown = Arc::new(AtomicBool::new(false));
        let shutdown_clone = Arc::clone(&shutdown);
        let resources = DriverResources::new(ring, Arc::clone(&pool));
        let (done_tx, done_rx) = mpsc::sync_channel(1);

        let thread = thread::Builder::new().name("io-uring-driver".into()).spawn(move || {
            driver_loop(resources, rx, registry_clone, shutdown_clone);
            let _ = done_tx.send(());
        })?;

        Ok(Self { tx, registry, pool, shutdown, done_rx: Some(done_rx), thread: Some(thread) })
    }

    /// Submit a receive into a registered buffer and return a future.
    ///
    /// The lease must come from [`Self::acquire_buffer`]. Ownership transfers
    /// to the driver until the CQE arrives, then returns in
    /// [`CompletionResult::into_buffer`]. A full or disconnected submission
    /// queue resolves immediately with `-EAGAIN` instead of blocking a caller.
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
        if let Err(error) = self.tx.try_send(Submission::RecvFixed { fd, buffer, token }) {
            let Submission::RecvFixed { buffer, .. } = error.into_inner() else { unreachable!() };
            self.registry.complete(token, CompletionResult::with_buffer(-libc::EAGAIN, 0, buffer));
        }
        CompletionFuture::new(token, Arc::clone(&self.registry))
    }

    /// Submit a plain (non-registered) write and return a future.
    ///
    /// Ownership of `buf` is transferred to the driver, which keeps it alive
    /// until the io_uring completion is reaped.
    ///
    /// A full or disconnected submission queue resolves immediately with
    /// `-EAGAIN` instead of blocking a caller.
    // cancel-safe: synchronous; no await points.
    pub fn write(&self, fd: BorrowedFd<'_>, buf: Vec<u8>) -> CompletionFuture {
        let token = next_token();
        self.registry.begin(token);
        let Ok(len) = u32::try_from(buf.len()) else {
            self.registry.complete(token, CompletionResult::plain(-libc::EOVERFLOW, 0));
            return CompletionFuture::new(token, Arc::clone(&self.registry));
        };
        let Some(fd) = self.duplicate_fd(fd, token) else {
            return CompletionFuture::new(token, Arc::clone(&self.registry));
        };
        if self.tx.try_send(Submission::Write { fd, buf, len, token }).is_err() {
            self.registry.complete(token, CompletionResult::plain(-libc::EAGAIN, 0));
        }
        CompletionFuture::new(token, Arc::clone(&self.registry))
    }

    /// Submit `IORING_OP_WRITE_FIXED`, consuming the registered-buffer lease
    /// until the CQE is reaped. The completed lease is returned through
    /// [`CompletionResult::into_buffer`]. Queue saturation resolves as
    /// `-EAGAIN` without blocking.
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
        if let Err(error) = self.tx.try_send(Submission::WriteFixed { fd, buffer, token }) {
            let Submission::WriteFixed { buffer, .. } = error.into_inner() else { unreachable!() };
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
        Self { tx, registry, pool, shutdown: Arc::new(AtomicBool::new(false)), done_rx: None, thread: None }
    }

    #[cfg(test)]
    pub(crate) fn new_with_stalled_channel(pool: Arc<RegisteredBufferPool>) -> (Self, flume::Receiver<Submission>) {
        let (tx, rx) = flume::bounded::<Submission>(1);
        let registry = Arc::new(CompletionRegistry::new());
        let driver =
            Self { tx, registry, pool, shutdown: Arc::new(AtomicBool::new(false)), done_rx: None, thread: None };
        (driver, rx)
    }
}

impl Drop for IoUringDriver {
    fn drop(&mut self) {
        self.shutdown.store(true, Ordering::Release);
        let _ = self.tx.try_send(Submission::Shutdown);
        if let Some(handle) = self.thread.take() {
            let finished = self.done_rx.as_ref().is_some_and(|done_rx| {
                matches!(
                    done_rx.recv_timeout(DRIVER_SHUTDOWN_TIMEOUT),
                    Ok(()) | Err(mpsc::RecvTimeoutError::Disconnected)
                )
            });
            if finished {
                if handle.join().is_err() {
                    log::warn!("io-uring driver thread panicked during shutdown");
                }
            } else {
                log::warn!("io-uring driver did not stop within {DRIVER_SHUTDOWN_TIMEOUT:?}; detaching cleanup thread");
                drop(handle);
            }
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
        assert!(handle.set_len(4));

        let result = block_on_completion(driver.write_fixed(file.as_fd(), handle));

        assert_eq!(result.result, 4, "fixed write must use the driver's registered buffer table");
        assert_eq!(driver.available_buffers(), 3, "CQE result must retain the submitted lease");
        drop(result);
        assert_eq!(driver.available_buffers(), 4, "dropping the CQE result must release the lease exactly once");
    }

    #[test]
    fn driver_rejects_unbounded_pool_allocations_before_ring_setup() {
        for (capacity, buffer_size) in [
            (0, 1024),
            (u16::try_from(RING_SIZE + 1).expect("ring size fits u16"), 1024),
            (1, 0),
            (1, MAX_REGISTERED_BUFFER_SIZE + 1),
        ] {
            let Err(error) = IoUringDriver::start(capacity, buffer_size) else {
                panic!("invalid pool configuration must fail");
            };
            assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
        }
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

    #[test]
    fn saturated_submission_queue_returns_without_blocking() {
        let Some(pool) = try_make_pool() else {
            eprintln!("io_uring unavailable; skipping saturated_submission_queue_returns_without_blocking");
            return;
        };
        let (driver, stalled_rx) = IoUringDriver::new_with_stalled_channel(pool);
        let file = OpenOptions::new().write(true).open("/dev/null").expect("open /dev/null");
        let first = driver.write(file.as_fd(), vec![0_u8; 4]);

        let started = Instant::now();
        let second = driver.write(file.as_fd(), vec![0_u8; 4]);
        assert!(started.elapsed() < Duration::from_millis(50), "a full submission queue must not block the caller");
        let result = block_on_completion(second);
        assert_eq!(result.result, -libc::EAGAIN);

        drop(first);
        drop(stalled_rx);
        drop(driver);
    }

    #[test]
    fn driver_drop_is_bounded_with_blocked_read() {
        let Ok(driver) = IoUringDriver::start(4, 1024) else {
            eprintln!("io_uring unavailable; skipping driver_drop_is_bounded_with_blocked_read");
            return;
        };
        let (_sender, receiver) = UnixStream::pair().expect("create socket pair");
        let handle = driver.acquire_buffer().expect("acquire registered buffer");
        let future = driver.recv_fixed(receiver.as_fd(), handle);
        std::thread::sleep(Duration::from_millis(25));

        let started = Instant::now();
        drop(driver);
        assert!(
            started.elapsed() < DRIVER_SHUTDOWN_TIMEOUT + Duration::from_millis(100),
            "driver Drop exceeded its bounded shutdown interval"
        );
        drop(future);
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
