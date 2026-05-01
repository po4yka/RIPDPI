use std::collections::HashMap;
use std::sync::Arc;

use io_uring::opcode;
use io_uring::squeue::{Entry, Flags};
use io_uring::types::Fd;
use io_uring::IoUring;

use crate::ring::completion::{CompletionRegistry, CompletionResult};
use crate::ring::submission::Submission;

pub(crate) fn driver_loop(mut ring: IoUring, rx: flume::Receiver<Submission>, registry: Arc<CompletionRegistry>) {
    // Buffers owned by the driver while their plain Write IO is in flight.
    // Keyed by submission token; the entry is dropped after the matching CQE
    // is drained, freeing the heap allocation referenced by the kernel's SQE.
    let mut pending_write_buffers: HashMap<u64, Vec<u8>> = HashMap::new();
    loop {
        let submitted = drain_submissions(&mut ring, &rx, &mut pending_write_buffers);
        if matches!(submitted, SubmissionDrain::Shutdown) {
            return;
        }

        // Submit all queued SQEs and wait for at least one completion.
        if let Err(e) = ring.submit_and_wait(1) {
            log::error!("io_uring submit_and_wait failed: {e}");
            continue;
        }

        drain_completions(&mut ring, &registry, &mut pending_write_buffers);
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
                let entry = opcode::SendZc::new(Fd(fd), std::ptr::null(), len)
                    .buf_index(Some(buf_index))
                    .build()
                    .user_data(token)
                    .flags(Flags::BUFFER_SELECT);
                // SAFETY: entry is valid and references registered buffers.
                push_entry(ring, &entry);
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
                let entry = opcode::Write::new(Fd(fd), ptr, len).build().user_data(token);
                // SAFETY: the buffer at `ptr` is owned by `pending_write_buffers`
                // until the matching CQE is drained below; the heap allocation
                // is stable for that window. `fd` follows the same caller-keeps-
                // open contract documented on `Submission`.
                push_entry(ring, &entry);
                submitted += 1;
            }
            Submission::WriteFixed { fd, buf_index, len, token } => {
                let entry = opcode::WriteFixed::new(Fd(fd), std::ptr::null(), len, buf_index).build().user_data(token);
                // SAFETY: entry references a registered buffer at
                // `buf_index`; the caller must keep that slot reserved
                // until the CQE is reaped. Same contract as RecvFixed.
                push_entry(ring, &entry);
                submitted += 1;
            }
            Submission::TunReadBatch { fd, buf_indices, token_base } => {
                for (i, &buf_idx) in buf_indices.iter().enumerate() {
                    let token = token_base + i as u64;
                    let entry =
                        opcode::ReadFixed::new(Fd(fd), std::ptr::null_mut(), 0, buf_idx).build().user_data(token);
                    // SAFETY: submission buffers and fds live until the completion is reaped;
                    // see SAFETY notes on SendZc/RecvFixed arms above for the full contract.
                    push_entry(ring, &entry);
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
                    push_entry(ring, &entry);
                    submitted += 1;
                }
            }
        }
    }

    SubmissionDrain::Continue
}

fn drain_completions(
    ring: &mut IoUring,
    registry: &CompletionRegistry,
    pending_write_buffers: &mut HashMap<u64, Vec<u8>>,
) {
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

fn push_entry(ring: &mut IoUring, entry: &Entry) {
    // SAFETY: every call site builds an SQE whose fd/buffer lifetime follows
    // the Submission contract, and the entry is copied into the kernel-owned
    // submission queue before this function returns.
    unsafe {
        if ring.submission().push(entry).is_err() {
            // SQ full -- submit what we have and retry.
            let _ = ring.submit();
            let _ = ring.submission().push(entry);
        }
    }
}
