use std::os::fd::RawFd;
use std::sync::atomic::{AtomicU64, Ordering};

/// Token generator for correlating SQEs with completions.
static NEXT_TOKEN: AtomicU64 = AtomicU64::new(1);

/// A submission request sent to the io_uring driver thread.
///
/// # fd ownership
///
/// All `fd: RawFd` fields are **non-owning borrows**.  The caller retains
/// ownership of the file descriptor and MUST keep it open until the matching
/// CQE has been reaped (i.e. the [`CompletionFuture`](crate::ring::CompletionFuture) has resolved).
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
    /// Write from a previously registered buffer (`IORING_OP_WRITE_FIXED`).
    /// The caller owns the `RegisteredBufferPool` slot at `buf_index` and
    /// must keep it valid (i.e. not return it to the pool) until the matching
    /// completion is reaped.
    WriteFixed { fd: RawFd, buf_index: u16, len: u32, token: u64 },
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

pub(crate) fn next_token() -> u64 {
    NEXT_TOKEN.fetch_add(1, Ordering::Relaxed)
}
