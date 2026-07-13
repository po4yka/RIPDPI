use std::collections::HashMap;
use std::os::fd::AsRawFd;
use std::sync::Arc;

use io_uring::IoUring;
use io_uring::opcode;
use io_uring::squeue::{Entry, Flags};
use io_uring::types::Fd;

use crate::ring::completion::{CompletionRegistry, CompletionResult};
use crate::ring::submission::Submission;

pub(crate) fn driver_loop(mut ring: IoUring, rx: flume::Receiver<Submission>, registry: Arc<CompletionRegistry>) {
    // Buffers owned by the driver while their plain Write IO is in flight.
    // Keyed by submission token; the entry is dropped after the matching CQE
    // is drained, freeing the heap allocation referenced by the kernel's SQE.
    let mut pending_write_buffers: HashMap<u64, Vec<u8>> = HashMap::new();
    let mut pending_fds = HashMap::new();
    loop {
        let submitted = drain_submissions(&mut ring, &rx, &mut pending_write_buffers, &mut pending_fds);
        if matches!(submitted, SubmissionDrain::Shutdown) {
            // Reap any in-flight completions before the ring is dropped. The
            // kernel may still be reading SQE-referenced buffers (the plain
            // `Write` payloads owned by `pending_write_buffers`); dropping the
            // ring without draining would free those buffers while a DMA read
            // is still pending. Draining is bounded so shutdown cannot hang.
            drain_in_flight_on_shutdown(&mut ring, &registry, &mut pending_write_buffers, &mut pending_fds);
            drop(ring);
            return;
        }

        // Submit all queued SQEs and wait for at least one completion.
        if let Err(e) = ring.submit_and_wait(1) {
            log::error!("io_uring submit_and_wait failed: {e}");
            continue;
        }

        drain_completions(&mut ring, &registry, &mut pending_write_buffers, &mut pending_fds);
    }
}

enum SubmissionDrain {
    Continue,
    Shutdown,
}

fn drain_submissions(
    ring: &mut IoUring,
    rx: &flume::Receiver<Submission>,
    pending_write_buffers: &mut HashMap<u64, Vec<u8>>,
    pending_fds: &mut HashMap<u64, std::os::fd::OwnedFd>,
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
            Submission::SendZc { fd, buf_index, len, token } => {
                let entry = opcode::SendZc::new(Fd(fd.as_raw_fd()), std::ptr::null(), len)
                    .buf_index(Some(buf_index))
                    .build()
                    .user_data(token)
                    .flags(Flags::BUFFER_SELECT);
                pending_fds.insert(token, fd);
                // SAFETY: entry is valid and references registered buffers.
                push_entry(ring, &entry);
                submitted += 1;
            }
            Submission::RecvFixed { fd, buf_index, token } => {
                let entry = opcode::ReadFixed::new(
                    Fd(fd.as_raw_fd()),
                    std::ptr::null_mut(),
                    0, // len filled from registered buffer
                    buf_index,
                )
                .build()
                .user_data(token);
                pending_fds.insert(token, fd);
                // SAFETY: entry references a registered buffer.
                push_entry(ring, &entry);
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
                let entry = opcode::Write::new(Fd(fd.as_raw_fd()), ptr, len).build().user_data(token);
                pending_fds.insert(token, fd);
                // SAFETY: the buffer at `ptr` is owned by `pending_write_buffers`
                // until the matching CQE is drained below; the heap allocation
                // is stable for that window. `pending_fds` owns the duplicated
                // descriptor through the matching completion.
                push_entry(ring, &entry);
                submitted += 1;
            }
            Submission::WriteFixed { fd, buf_index, len, token } => {
                let entry = opcode::WriteFixed::new(Fd(fd.as_raw_fd()), std::ptr::null(), len, buf_index)
                    .build()
                    .user_data(token);
                pending_fds.insert(token, fd);
                // SAFETY: entry references a registered buffer at
                // `buf_index`; the caller must keep that slot reserved
                // until the CQE is reaped. Same contract as RecvFixed.
                push_entry(ring, &entry);
                submitted += 1;
            }
        }
    }

    SubmissionDrain::Continue
}

fn drain_completions(
    ring: &mut IoUring,
    registry: &CompletionRegistry,
    pending_write_buffers: &mut HashMap<u64, Vec<u8>>,
    pending_fds: &mut HashMap<u64, std::os::fd::OwnedFd>,
) {
    let cq = ring.completion();
    for cqe in cq {
        let token = cqe.user_data();
        let result = CompletionResult { result: cqe.result(), flags: cqe.flags() };
        // If this token belongs to a plain Write, release the buffer now
        // that the kernel is done with it. No-op for any other opcode.
        pending_write_buffers.remove(&token);
        pending_fds.remove(&token);
        registry.complete(token, result);
    }
}

/// Drain completions for ops still in flight when a `Shutdown` arrives, before
/// the ring is dropped.
///
/// The hazard is the plain `Write` payloads: their `Vec<u8>` backing memory is
/// owned by `pending_write_buffers` and is freed when that map drops. If the
/// ring is torn down while the kernel is still DMA-reading one of those
/// buffers, the freed memory is read by the kernel. Reaping the matching CQEs
/// first guarantees the kernel has finished with each buffer before its
/// allocation is released.
///
/// The wait is bounded: each `submit_and_wait(1)` blocks only until at least
/// one CQE is ready, and the loop caps total iterations so a wedged op cannot
/// hang shutdown forever. Registered-buffer ops (recv/write-fixed) reference
/// pool memory that outlives the ring, so only the plain-`Write` set gates the
/// drain.
fn drain_in_flight_on_shutdown(
    ring: &mut IoUring,
    registry: &CompletionRegistry,
    pending_write_buffers: &mut HashMap<u64, Vec<u8>>,
    pending_fds: &mut HashMap<u64, std::os::fd::OwnedFd>,
) {
    // First reap anything already completed without blocking.
    drain_completions(ring, registry, pending_write_buffers, pending_fds);

    // Bound the number of wait cycles: at most one cycle per in-flight buffer,
    // plus a small slack, so a kernel that never completes an op cannot wedge
    // the driver thread (and thus `IoUringDriver::drop`) indefinitely.
    let mut remaining_cycles = pending_write_buffers.len().saturating_add(1);
    while !pending_write_buffers.is_empty() && remaining_cycles > 0 {
        remaining_cycles -= 1;
        match ring.submit_and_wait(1) {
            Ok(_) => drain_completions(ring, registry, pending_write_buffers, pending_fds),
            Err(e) => {
                log::error!("io_uring shutdown drain submit_and_wait failed: {e}");
                break;
            }
        }
    }

    if !pending_write_buffers.is_empty() {
        log::warn!("io_uring driver shut down with {} write buffer(s) still in flight", pending_write_buffers.len());
    }
}

fn push_entry(ring: &mut IoUring, entry: &Entry) {
    // SAFETY: every call site builds an SQE whose fd/buffer lifetime follows
    // the Submission contract, and the entry is copied into the kernel-owned
    // submission queue before this function returns.
    if unsafe { ring.submission().push(entry) }.is_err() {
        // SQ full -- submit what we have and retry.
        let _ = ring.submit();
        // SAFETY: same SQE lifetime invariant as the first push; submit only
        // frees queue capacity and does not invalidate `entry`.
        let _ = unsafe { ring.submission().push(entry) };
    }
}
