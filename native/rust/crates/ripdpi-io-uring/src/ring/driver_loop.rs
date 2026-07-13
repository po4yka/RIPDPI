use std::collections::HashMap;
use std::os::fd::AsRawFd;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use io_uring::IoUring;
use io_uring::opcode;
use io_uring::squeue::Entry;
use io_uring::types::{CancelBuilder, Fd, SubmitArgs, Timespec};

use crate::BufferHandle;
use crate::ring::completion::{CompletionRegistry, CompletionResult};
use crate::ring::submission::Submission;

const SUBMISSION_BUDGET: u32 = 256;
const COMPLETION_WAIT: Duration = Duration::from_millis(10);
const SHUTDOWN_CANCEL_TIMEOUT: Duration = Duration::from_millis(50);

/// Keeps the ring ahead of its registered backing pool in every drop path,
/// including failure to spawn the driver thread.
pub(crate) struct DriverResources {
    ring: IoUring,
    _pool: Arc<crate::bufpool::RegisteredBufferPool>,
}

impl DriverResources {
    pub(crate) fn new(ring: IoUring, pool: Arc<crate::bufpool::RegisteredBufferPool>) -> Self {
        Self { ring, _pool: pool }
    }
}

enum InFlight {
    PlainWrite { _fd: std::os::fd::OwnedFd, _buffer: Vec<u8> },
    FixedRead { _fd: std::os::fd::OwnedFd, buffer: BufferHandle },
    FixedWrite { _fd: std::os::fd::OwnedFd, buffer: BufferHandle },
}

impl InFlight {
    fn complete(self, result: i32, flags: u32) -> CompletionResult {
        match self {
            Self::PlainWrite { .. } => CompletionResult::plain(result, flags),
            Self::FixedRead { mut buffer, .. } => {
                if result > 0 {
                    buffer.set_len(result as usize);
                }
                CompletionResult::with_buffer(result, flags, buffer)
            }
            Self::FixedWrite { buffer, .. } => CompletionResult::with_buffer(result, flags, buffer),
        }
    }
}

pub(crate) fn driver_loop(
    resources: DriverResources,
    rx: flume::Receiver<Submission>,
    registry: Arc<CompletionRegistry>,
    shutdown: Arc<AtomicBool>,
) {
    let DriverResources { mut ring, _pool } = resources;
    let mut in_flight: HashMap<u64, InFlight> = HashMap::new();
    loop {
        drain_completions(&mut ring, &registry, &mut in_flight);
        if shutdown.load(Ordering::Acquire) {
            break;
        }

        let block_for_first = in_flight.is_empty();
        if matches!(
            drain_submissions(&mut ring, &rx, &registry, &mut in_flight, block_for_first, &shutdown),
            SubmissionDrain::Shutdown
        ) {
            break;
        }

        if !in_flight.is_empty()
            && let Err(error) = submit_and_wait_bounded(&ring, COMPLETION_WAIT)
        {
            log::error!("io_uring bounded submit/wait failed: {error}");
            break;
        }
        drain_completions(&mut ring, &registry, &mut in_flight);
    }

    shutdown_ring(ring, rx, &registry, in_flight);
    drop(_pool);
}

enum SubmissionDrain {
    Continue,
    Shutdown,
}

fn drain_submissions(
    ring: &mut IoUring,
    rx: &flume::Receiver<Submission>,
    registry: &CompletionRegistry,
    in_flight: &mut HashMap<u64, InFlight>,
    block_for_first: bool,
    shutdown: &AtomicBool,
) -> SubmissionDrain {
    let mut processed = 0u32;
    while processed < SUBMISSION_BUDGET {
        if shutdown.load(Ordering::Acquire) {
            return SubmissionDrain::Shutdown;
        }
        let sub = if processed == 0 && block_for_first {
            match rx.recv() {
                Ok(s) => s,
                Err(_) => return SubmissionDrain::Shutdown,
            }
        } else {
            match rx.try_recv() {
                Ok(s) => s,
                Err(_) => break,
            }
        };
        processed += 1;

        match sub {
            Submission::Shutdown => {
                return SubmissionDrain::Shutdown;
            }
            Submission::RecvFixed { fd, mut buffer, token } => {
                let entry = opcode::ReadFixed::new(
                    Fd(fd.as_raw_fd()),
                    buffer.as_mut_ptr(),
                    buffer.capacity_u32(),
                    buffer.buf_index(),
                )
                .build()
                .user_data(token);
                if push_entry(ring, &entry) {
                    in_flight.insert(token, InFlight::FixedRead { _fd: fd, buffer });
                } else {
                    registry.complete(token, CompletionResult::with_buffer(-libc::EBUSY, 0, buffer));
                }
            }
            Submission::Write { fd, buf, token } => {
                let len = buf.len() as u32;
                let ptr = buf.as_ptr();
                // Take ownership of the buffer until the kernel finishes
                // the IO. Vec's heap allocation does not move when the
                // metadata is inserted into the HashMap, so `ptr` remains
                // valid for the lifetime of the SQE.
                let entry = opcode::Write::new(Fd(fd.as_raw_fd()), ptr, len).build().user_data(token);
                // SAFETY: the buffer at `ptr` is owned by `in_flight`
                // until the matching CQE is drained below; the heap allocation
                // is stable for that window. The matching resource also owns
                // the duplicated descriptor through completion.
                if push_entry(ring, &entry) {
                    in_flight.insert(token, InFlight::PlainWrite { _fd: fd, _buffer: buf });
                } else {
                    registry.complete(token, CompletionResult::plain(-libc::EBUSY, 0));
                }
            }
            Submission::WriteFixed { fd, buffer, token } => {
                let entry =
                    opcode::WriteFixed::new(Fd(fd.as_raw_fd()), buffer.as_ptr(), buffer.len_u32(), buffer.buf_index())
                        .build()
                        .user_data(token);
                if push_entry(ring, &entry) {
                    in_flight.insert(token, InFlight::FixedWrite { _fd: fd, buffer });
                } else {
                    registry.complete(token, CompletionResult::with_buffer(-libc::EBUSY, 0, buffer));
                }
            }
        }
    }

    SubmissionDrain::Continue
}

fn drain_completions(ring: &mut IoUring, registry: &CompletionRegistry, in_flight: &mut HashMap<u64, InFlight>) {
    let cq = ring.completion();
    for cqe in cq {
        let token = cqe.user_data();
        let result = in_flight.remove(&token).map_or_else(
            || CompletionResult::plain(cqe.result(), cqe.flags()),
            |resource| resource.complete(cqe.result(), cqe.flags()),
        );
        registry.complete(token, result);
    }
}

fn shutdown_ring(
    mut ring: IoUring,
    rx: flume::Receiver<Submission>,
    registry: &CompletionRegistry,
    mut in_flight: HashMap<u64, InFlight>,
) {
    let _ = ring.submit();
    drain_completions(&mut ring, registry, &mut in_flight);

    if !in_flight.is_empty() {
        let cancel =
            ring.submitter().register_sync_cancel(Some(Timespec::from(SHUTDOWN_CANCEL_TIMEOUT)), CancelBuilder::any());
        if let Err(error) = cancel
            && !matches!(error.raw_os_error(), Some(libc::ENOENT | libc::EINVAL))
        {
            log::debug!("io_uring synchronous shutdown cancellation failed: {error}");
        }
        let _ = submit_and_wait_bounded(&ring, COMPLETION_WAIT);
        drain_completions(&mut ring, registry, &mut in_flight);
    }

    let remaining = in_flight.len();
    // Closing the ring happens while every SQE-referenced resource is still
    // owned by `in_flight`. Even if cancellation did not yield a CQE, kernel
    // teardown can therefore finish before those resources are returned.
    drop(ring);

    for (token, resource) in in_flight {
        registry.complete(token, resource.complete(-libc::ECANCELED, 0));
    }
    for submission in rx.try_iter() {
        complete_cancelled_submission(registry, submission);
    }
    registry.fail_pending(-libc::ECANCELED);

    if remaining != 0 {
        log::debug!("io_uring driver closed with {remaining} operation(s) cancelled during teardown");
    }
}

fn complete_cancelled_submission(registry: &CompletionRegistry, submission: Submission) {
    match submission {
        Submission::RecvFixed { buffer, token, .. } | Submission::WriteFixed { buffer, token, .. } => {
            registry.complete(token, CompletionResult::with_buffer(-libc::ECANCELED, 0, buffer));
        }
        Submission::Write { token, .. } => registry.complete(token, CompletionResult::plain(-libc::ECANCELED, 0)),
        Submission::Shutdown => {}
    }
}

fn submit_and_wait_bounded(ring: &IoUring, timeout: Duration) -> std::io::Result<()> {
    if ring.params().is_feature_ext_arg() {
        let timeout = Timespec::from(timeout);
        let args = SubmitArgs::new().timespec(&timeout);
        match ring.submitter().submit_with_args(1, &args) {
            Ok(_) => Ok(()),
            Err(error) if matches!(error.raw_os_error(), Some(libc::ETIME | libc::ETIMEDOUT | libc::EINTR)) => Ok(()),
            Err(error) => Err(error),
        }
    } else {
        ring.submit()?;
        std::thread::sleep(timeout);
        Ok(())
    }
}

fn push_entry(ring: &mut IoUring, entry: &Entry) -> bool {
    // SAFETY: every call site builds an SQE whose fd/buffer lifetime follows
    // the Submission contract, and the entry is copied into the kernel-owned
    // submission queue before this function returns.
    if unsafe { ring.submission().push(entry) }.is_err() {
        // SQ full -- submit what we have and retry.
        let _ = ring.submit();
        // SAFETY: same SQE lifetime invariant as the first push; submit only
        // frees queue capacity and does not invalidate `entry`.
        return unsafe { ring.submission().push(entry) }.is_ok();
    }
    true
}
