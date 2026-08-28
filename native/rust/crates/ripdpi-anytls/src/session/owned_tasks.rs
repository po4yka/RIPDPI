use std::future::Future;
use std::sync::{Arc, Mutex as StdMutex, PoisonError};

use tokio::sync::Mutex;
use tokio::task::{AbortHandle, JoinError, JoinSet};

use super::AnyTlsError;

#[derive(Default)]
struct State {
    tasks: JoinSet<()>,
    closed: bool,
    failed: bool,
}

/// Synchronous registration also covers FIN sends originating in Drop. Finished
/// tasks are reaped before admission, so history does not grow the registry.
#[derive(Default)]
pub(super) struct OwnedTasks {
    active: StdMutex<State>,
    draining: Mutex<Option<State>>,
}

impl OwnedTasks {
    pub(super) fn spawn(&self, future: impl Future<Output = ()> + Send + 'static) -> Result<AbortHandle, AnyTlsError> {
        let mut state = self.active.lock().unwrap_or_else(PoisonError::into_inner);
        while let Some(result) = state.tasks.try_join_next() {
            state.record(result);
        }
        if state.failed {
            state.closed = true;
            state.tasks.abort_all();
            return Err(AnyTlsError::Io("AnyTLS owned worker panicked".into()));
        }
        if state.closed {
            return Err(AnyTlsError::SessionClosed);
        }
        Ok(state.tasks.spawn(future))
    }

    pub(super) fn cancel(&self) {
        let mut state = self.active.lock().unwrap_or_else(PoisonError::into_inner);
        state.closed = true;
        state.tasks.abort_all();
        // A set already moved to `draining` was aborted before that move.
    }

    /// # Cancel safety
    /// The drain slot retains the JoinSet across cancellation. Admission closes
    /// and all workers are aborted before the first await; retry resumes joins.
    pub(super) async fn close(&self) -> Result<(), AnyTlsError> {
        self.cancel();
        let mut draining = self.draining.lock().await;
        if draining.is_none() {
            let mut active = self.active.lock().unwrap_or_else(PoisonError::into_inner);
            *draining = Some(State { tasks: std::mem::take(&mut active.tasks), closed: true, failed: active.failed });
        }
        let state = draining.as_mut().expect("drain slot initialized");
        while let Some(result) = state.tasks.join_next().await {
            state.record(result);
        }
        state.result()
    }
}

impl State {
    fn record(&mut self, result: Result<(), JoinError>) {
        if result.is_err_and(|error| !error.is_cancelled()) {
            self.failed = true;
        }
    }
    fn result(&self) -> Result<(), AnyTlsError> {
        if self.failed { Err(AnyTlsError::Io("AnyTLS owned worker panicked".into())) } else { Ok(()) }
    }
}

impl Drop for OwnedTasks {
    fn drop(&mut self) {
        self.active.get_mut().unwrap_or_else(PoisonError::into_inner).tasks.abort_all();
        if let Some(state) = self.draining.get_mut() {
            state.tasks.abort_all();
        }
    }
}

/// Only application clients/streams own this guard. Background workers never
/// hold it, so dropping the last application owner breaks pending handshakes.
pub(super) struct Owner(pub(super) Arc<OwnedTasks>);
impl Drop for Owner {
    fn drop(&mut self) {
        self.0.cancel();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    struct Resource(Arc<AtomicUsize>);
    impl Drop for Resource {
        fn drop(&mut self) {
            self.0.fetch_add(1, Ordering::Relaxed);
        }
    }

    #[tokio::test]
    async fn cancelled_drain_retains_all_joins_and_rejects_new_work() {
        let tasks = OwnedTasks::default();
        let released = Arc::new(AtomicUsize::new(0));
        for _ in 0..3 {
            let resource = Resource(released.clone());
            tasks
                .spawn(async move {
                    let _resource = resource;
                    std::future::pending::<()>().await;
                })
                .expect("spawn");
        }
        let mut close = Box::pin(tasks.close());
        std::future::poll_fn(|cx| {
            assert!(close.as_mut().poll(cx).is_pending());
            std::task::Poll::Ready(())
        })
        .await;
        drop(close);
        assert!(tasks.spawn(std::future::pending()).is_err());
        tasks.close().await.expect("retry drain");
        assert_eq!(released.load(Ordering::Relaxed), 3);
        tasks.close().await.expect("idempotent drain");
    }

    #[tokio::test]
    async fn observed_panic_is_sticky_and_finished_history_is_reaped() {
        let tasks = OwnedTasks::default();
        tasks.spawn(async {}).expect("first");
        tokio::task::yield_now().await;
        tasks.spawn(async { panic!("test worker failure") }).expect("reap then spawn");
        assert_eq!(tasks.active.lock().expect("active").tasks.len(), 1);
        tokio::task::yield_now().await;
        assert!(tasks.close().await.is_err());
        assert!(tasks.close().await.is_err());
    }
}
