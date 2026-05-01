use std::future::Future;
use std::task::{Context, Poll};
use std::thread;

use crate::ring::completion::{CompletionFuture, CompletionResult};
use crate::ring::thread_waker::thread_waker;

/// Block the current thread on a [`CompletionFuture`].
///
/// Used in synchronous relay threads (std::thread, not tokio tasks) to wait
/// for io_uring completions. Implements P5.2.1 of io_uring architecture note by parking the
/// current thread between polls and waking it from the driver thread via a
/// `Thread`-backed `Waker`. The unpark token semantics handle the
/// register/wake/park race without busy-spinning.
pub fn block_on_completion(future: CompletionFuture) -> CompletionResult {
    let waker = thread_waker(thread::current());
    let mut cx = Context::from_waker(&waker);
    let mut future = std::pin::pin!(future);

    loop {
        match future.as_mut().poll(&mut cx) {
            Poll::Ready(result) => return result,
            Poll::Pending => {
                // Park until the matching CQE wakes us. `Thread::unpark` is
                // safe to call before `park`: it leaves an unpark token, so
                // a `wake()` racing against the next `park()` simply makes
                // `park()` return immediately on the next iteration.
                thread::park();
            }
        }
    }
}
