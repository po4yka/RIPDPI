use ripdpi_io_uring::{BufferHandle, CompletionFuture, CompletionResult, IoUringDriver};

pub(super) fn acquire_registered_buffer(driver: &IoUringDriver) -> Option<BufferHandle> {
    driver.acquire_buffer()
}

/// Block the current thread on a `CompletionFuture`.
/// Delegates to [`ripdpi_io_uring::block_on_completion`].
pub(super) fn block_on_completion(future: CompletionFuture) -> CompletionResult {
    ripdpi_io_uring::block_on_completion(future)
}
