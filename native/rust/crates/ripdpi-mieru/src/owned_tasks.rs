//! Join ownership for one carrier or logical stream's two I/O pumps.
use std::future::Future;

use tokio::sync::Mutex;
use tokio::task::{AbortHandle, JoinError, JoinSet};

use crate::{MieruError, Result};

struct State {
    tasks: JoinSet<()>,
    panicked: bool,
}

pub(crate) struct OwnedTasks {
    state: Mutex<State>,
    aborts: [AbortHandle; 2],
}

impl OwnedTasks {
    pub(crate) fn spawn<R, W>(reader: R, writer: W) -> Self
    where
        R: Future<Output = ()> + Send + 'static,
        W: Future<Output = ()> + Send + 'static,
    {
        let mut tasks = JoinSet::new();
        let aborts = [tasks.spawn(reader), tasks.spawn(writer)];
        Self { state: Mutex::new(State { tasks, panicked: false }), aborts }
    }

    pub(crate) fn abort(&self) {
        for task in &self.aborts {
            task.abort();
        }
    }

    /// Reap completed tasks without blocking carrier admission. A failed task
    /// remains an observed failure on every subsequent close attempt.
    pub(crate) fn reap_finished(&self) -> Result<bool> {
        let Ok(mut state) = self.state.try_lock() else { return Ok(false) };
        while let Some(result) = state.tasks.try_join_next() {
            state.record(result);
        }
        state.result()?;
        Ok(state.tasks.is_empty())
    }

    /// # Cancel safety
    /// Cancel-safe: JoinSet remains in the mutex if this future is cancelled;
    /// another caller can finish joining the same aborted tasks.
    pub(crate) async fn close(&self) -> Result<()> {
        self.abort();
        let mut state = self.state.lock().await;
        while let Some(result) = state.tasks.join_next().await {
            state.record(result);
        }
        state.result()
    }
}

impl State {
    fn record(&mut self, result: std::result::Result<(), JoinError>) {
        if result.is_err_and(|error| !error.is_cancelled()) {
            self.panicked = true;
        }
    }

    fn result(&self) -> Result<()> {
        if self.panicked { Err(MieruError::Protocol("Mieru I/O worker panicked".into())) } else { Ok(()) }
    }
}

impl Drop for OwnedTasks {
    fn drop(&mut self) {
        self.abort();
    }
}

#[cfg(test)]
mod tests {
    use std::sync::{Arc, Mutex as StdMutex};

    use super::*;

    struct Resource(Arc<StdMutex<usize>>);
    impl Drop for Resource {
        fn drop(&mut self) {
            *self.0.lock().expect("drop counter") += 1;
        }
    }

    #[tokio::test]
    async fn close_joins_both_owned_resources_and_is_idempotent() {
        let count = Arc::new(StdMutex::new(0));
        let first = Resource(Arc::clone(&count));
        let second = Resource(Arc::clone(&count));
        let tasks = OwnedTasks::spawn(
            async move {
                let _resource = first;
                std::future::pending::<()>().await;
            },
            async move {
                let _resource = second;
                std::future::pending::<()>().await;
            },
        );
        tasks.close().await.expect("join");
        assert_eq!(*count.lock().expect("counter"), 2);
        assert!(tasks.reap_finished().expect("drained"));
        tasks.close().await.expect("repeat close");
    }

    #[tokio::test]
    async fn cancelled_close_keeps_join_owners_for_retry() {
        let count = Arc::new(StdMutex::new(0));
        let first = Resource(Arc::clone(&count));
        let second = Resource(Arc::clone(&count));
        let tasks = OwnedTasks::spawn(
            async move {
                let _resource = first;
                std::future::pending::<()>().await;
            },
            async move {
                let _resource = second;
                std::future::pending::<()>().await;
            },
        );
        let mut close = Box::pin(tasks.close());
        std::future::poll_fn(|cx| {
            assert!(close.as_mut().poll(cx).is_pending());
            std::task::Poll::Ready(())
        })
        .await;
        drop(close);
        tasks.close().await.expect("retry must drain retained workers");
        assert_eq!(*count.lock().expect("counter"), 2);
        assert!(tasks.reap_finished().expect("drained"));
    }

    #[tokio::test]
    async fn observed_worker_panic_stays_failed_after_repeated_close() {
        let (started, observed) = tokio::sync::oneshot::channel();
        let tasks = OwnedTasks::spawn(
            async move {
                let _ = started.send(());
                panic!("test worker failure");
            },
            std::future::pending(),
        );
        observed.await.expect("worker started");
        tokio::task::yield_now().await;
        assert!(tasks.close().await.is_err());
        assert!(tasks.close().await.is_err(), "a second close must not erase observed failure");
    }
}
