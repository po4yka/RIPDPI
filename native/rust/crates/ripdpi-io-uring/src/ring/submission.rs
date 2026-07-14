use std::os::fd::OwnedFd;
use std::sync::atomic::{AtomicU64, Ordering};

use crate::bufpool::BufferHandle;

/// Token generator for correlating SQEs with completions.
static NEXT_TOKEN: AtomicU64 = AtomicU64::new(1);

/// A submission request sent to the io_uring driver thread.
///
/// Every operation owns a duplicated descriptor through its matching CQE, so
/// closing or reusing the caller's descriptor cannot retarget queued I/O.
pub(crate) enum Submission {
    /// Receive into a registered buffer.
    RecvFixed { fd: OwnedFd, buffer: BufferHandle, token: u64 },
    /// Plain (non-registered) write from a caller-owned buffer.
    ///
    /// The driver thread owns `buf` for the duration of the IO and drops it
    /// once the matching completion is reaped. Use this for write paths that
    /// operate on `Vec<u8>` buffers (e.g. TUN `tx_queue`) where copying into
    /// a registered buffer pool isn't worth the complexity.
    Write { fd: OwnedFd, buf: Vec<u8>, len: u32, token: u64 },
    /// Write from an owned registered-buffer lease (`IORING_OP_WRITE_FIXED`).
    WriteFixed { fd: OwnedFd, buffer: BufferHandle, token: u64 },
    /// Shut down the driver thread.
    Shutdown,
}

pub(crate) fn next_token() -> u64 {
    NEXT_TOKEN.fetch_add(1, Ordering::Relaxed)
}
