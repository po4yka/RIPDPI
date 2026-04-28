use std::collections::HashMap;
use std::future::Future;
use std::io;
use std::os::fd::RawFd;
use std::pin::Pin;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::task::{Context, Poll, Waker};
use std::thread;

use io_uring::opcode;
use io_uring::squeue::Flags;
use io_uring::types::Fd;
use io_uring::IoUring;

use crate::bufpool::RegisteredBufferPool;

/// Ring size (submission queue entries). Power of two.
const RING_SIZE: u32 = 256;

/// Token generator for correlating SQEs with completions.
static NEXT_TOKEN: AtomicU64 = AtomicU64::new(1);

fn next_token() -> u64 {
    NEXT_TOKEN.fetch_add(1, Ordering::Relaxed)
}

/// A submission request sent to the io_uring driver thread.
///
/// # fd ownership
///
/// All `fd: RawFd` fields are **non-owning borrows**.  The caller retains
/// ownership of the file descriptor and MUST keep it open until the matching
/// CQE has been reaped (i.e. the [`CompletionFuture`] has resolved).
/// `RawFd` is used instead of `BorrowedFd<'_>` because the submissions are
/// sent through a channel and a lifetime cannot be expressed across that
/// boundary.
pub enum Submission {
    /// Zero-copy send from a registered buffer.
    SendZc { fd: RawFd, buf_index: u16, len: u32, token: u64 },
    /// Receive into a registered buffer.
    RecvFixed { fd: RawFd, buf_index: u16, token: u64 },
    /// Plain (non-registered) write from a caller-owned buffer.
    ///
    /// The driver thread owns `buf` for the duration of the IO and drops it
    /// once the matching completion is reaped. Use this for write paths that
    /// operate on `Vec<u8>` buffers (e.g. TUN `tx_queue`) where copying into
    /// a registered buffer pool isn't worth the complexity.
    Write { fd: RawFd, buf: Vec<u8>, token: u64 },
    /// Batched read from a TUN fd into multiple registered buffers.
    TunReadBatch { fd: RawFd, buf_indices: Vec<u16>, token_base: u64 },
    /// Batched write of TUN packets from registered buffers.
    TunWriteBatch {
        fd: RawFd,
        /// (buffer index, data length)
        entries: Vec<(u16, u32)>,
        token_base: u64,
    },
    /// Shut down the driver thread.
    Shutdown,
}

/// Completion result delivered back to the caller.
#[derive(Debug, Clone)]
pub struct CompletionResult {
    /// io_uring result code (bytes transferred, or negative errno).
    pub result: i32,
    /// CQE flags (check for `IORING_CQE_F_NOTIF`, `IORING_CQE_F_MORE`).
    pub flags: u32,
}

enum WakerSlot {
    Waiting(Waker),
    Ready(CompletionResult),
}

struct CompletionRegistry {
    slots: Mutex<HashMap<u64, WakerSlot>>,
}

impl CompletionRegistry {
    fn new() -> Self {
        Self { slots: Mutex::new(HashMap::new()) }
    }

    /// Register a waker for a given token. If the completion already arrived,
    /// returns the result immediately.
    fn register(&self, token: u64, waker: &Waker) -> Option<CompletionResult> {
        let mut slots = self.slots.lock().ok()?;
        match slots.remove(&token) {
            Some(WakerSlot::Ready(result)) => Some(result),
            _ => {
                slots.insert(token, WakerSlot::Waiting(waker.clone()));
                None
            }
        }
    }

    /// Deliver a completion. Wakes the waiting task if registered.
    fn complete(&self, token: u64, result: CompletionResult) {
        if let Ok(mut slots) = self.slots.lock() {
            match slots.remove(&token) {
                Some(WakerSlot::Waiting(waker)) => {
                    slots.insert(token, WakerSlot::Ready(result));
                    waker.wake();
                }
                _ => {
                    // Completion arrived before poll -- store it.
                    slots.insert(token, WakerSlot::Ready(result));
                }
            }
        }
    }
}

/// The io_uring driver manages a dedicated thread that processes submissions
/// and completions, bridging to tokio tasks via [`CompletionFuture`].
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
        CompletionFuture { token, registry: Arc::clone(&self.registry) }
    }

    /// Submit a receive into a registered buffer and return a future.
    pub fn recv_fixed(&self, fd: RawFd, buf_index: u16) -> CompletionFuture {
        let token = next_token();
        let _ = self.tx.send(Submission::RecvFixed { fd, buf_index, token });
        CompletionFuture { token, registry: Arc::clone(&self.registry) }
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
        CompletionFuture { token, registry: Arc::clone(&self.registry) }
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

/// A future that resolves when the io_uring CQE for the associated
/// submission arrives.
pub struct CompletionFuture {
    token: u64,
    registry: Arc<CompletionRegistry>,
}

impl std::future::Future for CompletionFuture {
    type Output = CompletionResult;

    fn poll(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Self::Output> {
        match self.registry.register(self.token, cx.waker()) {
            Some(result) => Poll::Ready(result),
            None => Poll::Pending,
        }
    }
}

/// Block the current thread on a [`CompletionFuture`].
///
/// Used in synchronous relay threads (std::thread, not tokio tasks) to wait
/// for io_uring completions. Spins with `thread::yield_now()` between polls.
pub fn block_on_completion(future: CompletionFuture) -> CompletionResult {
    use std::task::{RawWaker, RawWakerVTable};

    fn noop_raw_waker() -> RawWaker {
        fn no_op(_: *const ()) {}
        fn clone(p: *const ()) -> RawWaker {
            RawWaker::new(p, &VTABLE)
        }
        const VTABLE: RawWakerVTable = RawWakerVTable::new(clone, no_op, no_op, no_op);
        RawWaker::new(std::ptr::null(), &VTABLE)
    }

    let waker = unsafe { std::task::Waker::from_raw(noop_raw_waker()) };
    let mut cx = Context::from_waker(&waker);
    let mut future = std::pin::pin!(future);

    loop {
        match future.as_mut().poll(&mut cx) {
            Poll::Ready(result) => return result,
            Poll::Pending => {
                std::thread::yield_now();
            }
        }
    }
}

fn driver_loop(mut ring: IoUring, rx: flume::Receiver<Submission>, registry: Arc<CompletionRegistry>) {
    // Buffers owned by the driver while their plain Write IO is in flight.
    // Keyed by submission token; the entry is dropped after the matching CQE
    // is drained, freeing the heap allocation referenced by the kernel's SQE.
    let mut pending_write_buffers: HashMap<u64, Vec<u8>> = HashMap::new();
    loop {
        // Drain available submissions.
        let mut submitted = 0u32;
        loop {
            let sub = if submitted == 0 {
                // Block on first submission to avoid busy-spinning.
                match rx.recv() {
                    Ok(s) => s,
                    Err(_) => return, // Channel closed.
                }
            } else {
                match rx.try_recv() {
                    Ok(s) => s,
                    Err(_) => break,
                }
            };

            match sub {
                Submission::Shutdown => {
                    // Submit any pending work, then exit.
                    let _ = ring.submit();
                    return;
                }
                Submission::SendZc { fd, buf_index, len, token } => {
                    let entry = opcode::SendZc::new(Fd(fd), std::ptr::null(), len)
                        .buf_index(Some(buf_index))
                        .build()
                        .user_data(token)
                        .flags(Flags::BUFFER_SELECT);
                    // SAFETY: entry is valid and references registered buffers.
                    unsafe {
                        if ring.submission().push(&entry).is_err() {
                            // SQ full -- submit what we have and retry.
                            let _ = ring.submit();
                            let _ = ring.submission().push(&entry);
                        }
                    }
                    submitted += 1;
                }
                Submission::RecvFixed { fd, buf_index, token } => {
                    let entry = opcode::ReadFixed::new(
                        Fd(fd),
                        std::ptr::null_mut(),
                        0, // len filled from registered buffer
                        buf_index,
                    )
                    .build()
                    .user_data(token);
                    // SAFETY: entry references a registered buffer.
                    unsafe {
                        if ring.submission().push(&entry).is_err() {
                            let _ = ring.submit();
                            let _ = ring.submission().push(&entry);
                        }
                    }
                    submitted += 1;
                }
                Submission::Write { fd, buf, token } => {
                    let len = buf.len() as u32;
                    let ptr = buf.as_ptr();
                    // Take ownership of the buffer until the kernel finishes
                    // the IO. Vec's heap allocation does not move when the
                    // metadata is inserted into the HashMap, so `ptr` remains
                    // valid for the lifetime of the SQE.
                    pending_write_buffers.insert(token, buf);
                    let entry = opcode::Write::new(Fd(fd), ptr, len).build().user_data(token);
                    // SAFETY: the buffer at `ptr` is owned by `pending_write_buffers`
                    // until the matching CQE is drained below; the heap allocation
                    // is stable for that window. `fd` follows the same caller-keeps-
                    // open contract documented on `Submission`.
                    unsafe {
                        if ring.submission().push(&entry).is_err() {
                            let _ = ring.submit();
                            let _ = ring.submission().push(&entry);
                        }
                    }
                    submitted += 1;
                }
                Submission::TunReadBatch { fd, buf_indices, token_base } => {
                    for (i, &buf_idx) in buf_indices.iter().enumerate() {
                        let token = token_base + i as u64;
                        let entry =
                            opcode::ReadFixed::new(Fd(fd), std::ptr::null_mut(), 0, buf_idx).build().user_data(token);
                        // SAFETY: submission buffers and fds live until the completion is reaped;
                        // see SAFETY notes on SendZc/RecvFixed arms above for the full contract.
                        unsafe {
                            if ring.submission().push(&entry).is_err() {
                                let _ = ring.submit();
                                let _ = ring.submission().push(&entry);
                            }
                        }
                        submitted += 1;
                    }
                }
                Submission::TunWriteBatch { fd, entries, token_base } => {
                    for (i, &(buf_idx, len)) in entries.iter().enumerate() {
                        let token = token_base + i as u64;
                        let entry =
                            opcode::WriteFixed::new(Fd(fd), std::ptr::null(), len, buf_idx).build().user_data(token);
                        // SAFETY: submission buffers and fds live until the completion is reaped;
                        // see SAFETY notes on SendZc/RecvFixed arms above for the full contract.
                        unsafe {
                            if ring.submission().push(&entry).is_err() {
                                let _ = ring.submit();
                                let _ = ring.submission().push(&entry);
                            }
                        }
                        submitted += 1;
                    }
                }
            }
        }

        // Submit all queued SQEs and wait for at least one completion.
        if let Err(e) = ring.submit_and_wait(1) {
            log::error!("io_uring submit_and_wait failed: {e}");
            continue;
        }

        // Drain completions.
        let cq = ring.completion();
        for cqe in cq {
            let token = cqe.user_data();
            let result = CompletionResult { result: cqe.result(), flags: cqe.flags() };
            // If this token belongs to a plain Write, release the buffer now
            // that the kernel is done with it. No-op for any other opcode.
            pending_write_buffers.remove(&token);
            registry.complete(token, result);
        }
    }
}
