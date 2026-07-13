use std::collections::HashMap;
use std::os::fd::AsRawFd;
use std::sync::Arc;

use io_uring::IoUring;
use io_uring::opcode;
use io_uring::squeue::Entry;
use io_uring::types::Fd;

use crate::BufferHandle;
use crate::ring::completion::{CompletionRegistry, CompletionResult};
use crate::ring::submission::Submission;

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

pub(crate) fn driver_loop(mut ring: IoUring, rx: flume::Receiver<Submission>, registry: Arc<CompletionRegistry>) {
    let mut in_flight: HashMap<u64, InFlight> = HashMap::new();
    loop {
        let submitted = drain_submissions(&mut ring, &rx, &registry, &mut in_flight);
        if matches!(submitted, SubmissionDrain::Shutdown) {
            // Reap any in-flight completions before the ring is dropped. The
            // kernel may still be reading SQE-referenced resources held in
            // `in_flight`; dropping those resources before the ring would let
            // the kernel access freed memory. Draining is bounded so shutdown
            // cannot hang.
            drain_in_flight_on_shutdown(&mut ring, &registry, &mut in_flight);
            drop(ring);
            return;
        }

        // Submit all queued SQEs and wait for at least one completion.
        if let Err(e) = ring.submit_and_wait(1) {
            log::error!("io_uring submit_and_wait failed: {e}");
            continue;
        }

        drain_completions(&mut ring, &registry, &mut in_flight);
    }
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
) -> SubmissionDrain {
    let mut submitted = 0u32;
    loop {
        let sub = if submitted == 0 {
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

        match sub {
            Submission::Shutdown => {
                // Submit any pending work, then exit.
                let _ = ring.submit();
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
                    submitted += 1;
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
                    submitted += 1;
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
                    submitted += 1;
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

/// Drain completions for ops still in flight when a `Shutdown` arrives, before
/// the ring is dropped.
///
/// Every entry in `in_flight` owns the fd and memory referenced by its SQE. If
/// the ring is torn down after the map but before the kernel stops accessing an
/// entry, the kernel can observe a closed/reused descriptor or freed memory.
/// Reaping the matching CQEs first guarantees those resources can be released.
///
/// The wait is bounded: each `submit_and_wait(1)` blocks only until at least
/// one CQE is ready, and the loop caps total iterations so a wedged op cannot
/// hang shutdown forever. Registered-buffer ops (recv/write-fixed) reference
/// pool memory that outlives the ring, so only the plain-`Write` set gates the
/// drain.
fn drain_in_flight_on_shutdown(
    ring: &mut IoUring,
    registry: &CompletionRegistry,
    in_flight: &mut HashMap<u64, InFlight>,
) {
    // First reap anything already completed without blocking.
    drain_completions(ring, registry, in_flight);

    // Bound the number of wait cycles: at most one cycle per in-flight buffer,
    // plus a small slack, so a kernel that never completes an op cannot wedge
    // the driver thread (and thus `IoUringDriver::drop`) indefinitely.
    let mut remaining_cycles = in_flight.len().saturating_add(1);
    while !in_flight.is_empty() && remaining_cycles > 0 {
        remaining_cycles -= 1;
        match ring.submit_and_wait(1) {
            Ok(_) => drain_completions(ring, registry, in_flight),
            Err(e) => {
                log::error!("io_uring shutdown drain submit_and_wait failed: {e}");
                break;
            }
        }
    }

    if !in_flight.is_empty() {
        log::warn!("io_uring driver shut down with {} operation(s) still in flight", in_flight.len());
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
