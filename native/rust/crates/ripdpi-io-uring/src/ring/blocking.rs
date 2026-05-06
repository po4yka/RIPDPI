use crate::ring::completion::{CompletionFuture, CompletionResult};

/// Block the current thread on a [`CompletionFuture`].
///
/// Used in synchronous relay threads (std::thread, not tokio tasks) to wait
/// for io_uring completions. `pollster` provides the same park-on-wake
/// behaviour as the former local RawWaker loop without keeping a custom waker
/// vtable in this crate.
pub fn block_on_completion(future: CompletionFuture) -> CompletionResult {
    pollster::block_on(future)
}
