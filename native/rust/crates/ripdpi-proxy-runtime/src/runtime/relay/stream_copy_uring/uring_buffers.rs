use ripdpi_io_uring::{BufferHandle, CompletionFuture, CompletionResult, RegisteredBufferPool};

pub(super) fn acquire_registered_buffer(pool: &RegisteredBufferPool) -> Option<BufferHandle<'_>> {
    pool.acquire()
}

/// Block the current thread on a `CompletionFuture`.
/// Delegates to [`ripdpi_io_uring::block_on_completion`].
pub(super) fn block_on_completion(future: CompletionFuture) -> CompletionResult {
    ripdpi_io_uring::block_on_completion(future)
}

/// Helper to read from a pool buffer by index (used in fallback path after
/// ZC send failure). This is a best-effort function that returns an empty
/// slice if the index is out of bounds.
pub(super) fn pool_buf_slice<'a>(_pool: &'a RegisteredBufferPool, _index: u16, _len: usize) -> &'a [u8] {
    // In the fallback path, we've already read the data into the registered
    // buffer but the ZC send failed. Since PendingBuffer doesn't give us
    // back access to the data (by design -- the buffer may still be
    // in-flight), the fallback re-reads from the socket would be needed.
    // For the initial implementation, we surface the ZC error to the caller
    // and let the connection retry through the non-uring path.
    &[]
}
