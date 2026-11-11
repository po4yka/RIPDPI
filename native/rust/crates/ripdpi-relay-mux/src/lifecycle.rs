use std::future::Future;
use std::sync::atomic::{AtomicBool, Ordering};

use tokio::sync::{Notify, RwLock, RwLockWriteGuard};

use crate::RelayOpenError;

#[derive(Default)]
pub(crate) struct PoolLifecycle {
    closed: AtomicBool,
    stopped: Notify,
    opens: RwLock<()>,
}

impl PoolLifecycle {
    pub(crate) fn is_closed(&self) -> bool {
        self.closed.load(Ordering::Acquire)
    }

    pub(crate) fn close(&self) {
        // Publish terminal admission before waking opens; Acquire checks observe it.
        self.closed.store(true, Ordering::Release);
    }

    pub(crate) fn notify_closed(&self) {
        self.stopped.notify_waiters();
    }

    /// # Cancel safety:
    /// cancel-safe: notification is registered before checking persistent closure.
    async fn closed(&self) {
        let stopped = self.stopped.notified();
        tokio::pin!(stopped);
        stopped.as_mut().enable();
        if !self.is_closed() {
            stopped.await;
        }
    }

    /// # Cancel safety:
    /// cancel-safe: dropping the operation releases its read guard and owned open state.
    pub(crate) async fn open<T, E>(
        &self,
        operation: impl Future<Output = Result<T, RelayOpenError<E>>>,
    ) -> Result<T, RelayOpenError<E>> {
        let _active = tokio::select! {
            biased;
            () = self.closed() => return Err(RelayOpenError::Closed),
            active = self.opens.read() => active,
        };
        tokio::select! {
            biased;
            () = self.closed() => Err(RelayOpenError::Closed),
            result = operation => {
                if self.is_closed() {
                    drop(result);
                    Err(RelayOpenError::Closed)
                } else {
                    result
                }
            },
        }
    }

    /// # Cancel safety:
    /// cancel-safe: closure persists and unfinished operations keep their read guards.
    pub(crate) async fn drain(&self) -> RwLockWriteGuard<'_, ()> {
        self.opens.write().await
    }
}
