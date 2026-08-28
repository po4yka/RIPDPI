use std::future::Future;
use std::io;
use std::sync::Arc;

use tokio::sync::Mutex;

pub(crate) trait OwnedSession: Send + Sync {
    fn abort(&self);
    fn close(&self) -> impl Future<Output = io::Result<()>> + Send;
}

/// Keeps the join owner after pool eviction or abandonment of an open request.
/// No weak references escape: registry-only strong ownership is quiescent.
pub(crate) struct SessionRegistry<S>(Mutex<State<S>>);

struct State<S> {
    closed: bool,
    sessions: Vec<Arc<S>>,
}

impl<S> Default for SessionRegistry<S> {
    fn default() -> Self {
        Self(Mutex::new(State { closed: false, sessions: Vec::new() }))
    }
}

impl<S: OwnedSession> SessionRegistry<S> {
    /// # Cancel safety
    /// Cleanup retains entries until joined. The constructor must itself own
    /// any work it starts before an await; registering the result cannot repair
    /// a detached task inside a cancelled constructor.
    pub(crate) async fn create(&self, create: impl Future<Output = io::Result<S>> + Send) -> io::Result<Arc<S>> {
        let mut state = self.0.lock().await;
        if state.closed {
            return Err(io::Error::new(io::ErrorKind::NotConnected, "relay session factory closed"));
        }
        let sessions = &mut state.sessions;
        let mut index = 0;
        while index < sessions.len() {
            if Arc::strong_count(&sessions[index]) == 1 {
                sessions[index].abort();
                sessions[index].close().await?;
                sessions.remove(index);
            } else {
                index += 1;
            }
        }
        let session = Arc::new(create.await?);
        sessions.push(Arc::clone(&session));
        Ok(session)
    }

    /// # Cancel safety
    /// All entries survive cancellation until their close future completes.
    /// Every session is signalled before the first close is awaited.
    pub(crate) async fn shutdown(&self) -> io::Result<()> {
        let mut state = self.0.lock().await;
        state.closed = true;
        let sessions = &mut state.sessions;
        for session in sessions.iter() {
            session.abort();
        }
        let mut first_error = None;
        let mut index = 0;
        while index < sessions.len() {
            match sessions[index].close().await {
                Ok(()) => {
                    sessions.remove(index);
                }
                Err(error) => {
                    if first_error.is_none() {
                        first_error = Some(error);
                    }
                    index += 1;
                }
            }
        }
        first_error.map_or(Ok(()), Err)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
    use tokio::sync::Notify;

    struct Session {
        aborts: Arc<AtomicUsize>,
        closes: Arc<AtomicUsize>,
        release: Option<Arc<Notify>>,
        fail: bool,
    }
    impl OwnedSession for Session {
        fn abort(&self) {
            self.aborts.fetch_add(1, Ordering::SeqCst);
        }
        async fn close(&self) -> io::Result<()> {
            if let Some(release) = &self.release {
                release.notified().await;
            }
            self.closes.fetch_add(1, Ordering::SeqCst);
            if self.fail { Err(io::Error::other("observed cleanup failure")) } else { Ok(()) }
        }
    }
    fn session(aborts: &Arc<AtomicUsize>, closes: &Arc<AtomicUsize>) -> Session {
        Session { aborts: aborts.clone(), closes: closes.clone(), release: None, fail: false }
    }

    #[tokio::test]
    async fn reap_closes_evicted_owner_before_new_session_and_shutdown_closes_admission() {
        let registry = SessionRegistry::default();
        let aborts = Arc::new(AtomicUsize::new(0));
        let closes = Arc::new(AtomicUsize::new(0));
        drop(registry.create(async { Ok(session(&aborts, &closes)) }).await.expect("first"));
        let second = registry.create(async { Ok(session(&aborts, &closes)) }).await.expect("second");
        assert_eq!(closes.load(Ordering::SeqCst), 1);
        registry.shutdown().await.expect("shutdown");
        assert_eq!(closes.load(Ordering::SeqCst), 2);
        let polled = AtomicBool::new(false);
        let result = registry
            .create(async {
                polled.store(true, Ordering::SeqCst);
                Ok(session(&aborts, &closes))
            })
            .await;
        assert!(result.is_err());
        assert!(!polled.load(Ordering::SeqCst), "closed factory cannot start a constructor");
        drop(second);
    }

    #[tokio::test]
    async fn cancelled_shutdown_retains_owners_and_signals_all_before_joining() {
        let registry = SessionRegistry::default();
        let aborts = Arc::new(AtomicUsize::new(0));
        let closes = Arc::new(AtomicUsize::new(0));
        let release = Arc::new(Notify::new());
        let first = registry
            .create(async { Ok(Session { release: Some(release.clone()), ..session(&aborts, &closes) }) })
            .await
            .expect("first");
        let second = registry.create(async { Ok(session(&aborts, &closes)) }).await.expect("second");
        let mut shutdown = Box::pin(registry.shutdown());
        std::future::poll_fn(|cx| {
            assert!(shutdown.as_mut().poll(cx).is_pending());
            std::task::Poll::Ready(())
        })
        .await;
        assert_eq!(aborts.load(Ordering::SeqCst), 2);
        drop(shutdown);
        assert_eq!(registry.0.lock().await.sessions.len(), 2);
        release.notify_one();
        registry.shutdown().await.expect("retry");
        assert_eq!(closes.load(Ordering::SeqCst), 2);
        drop((first, second));
    }

    #[tokio::test]
    async fn cleanup_failure_does_not_skip_other_owners_or_allow_reopen() {
        let registry = SessionRegistry::default();
        let aborts = Arc::new(AtomicUsize::new(0));
        let closes = Arc::new(AtomicUsize::new(0));
        let first =
            registry.create(async { Ok(Session { fail: true, ..session(&aborts, &closes) }) }).await.expect("first");
        let second = registry.create(async { Ok(session(&aborts, &closes)) }).await.expect("second");
        assert!(registry.shutdown().await.is_err());
        assert_eq!(closes.load(Ordering::SeqCst), 2);
        assert_eq!(registry.0.lock().await.sessions.len(), 1);
        assert!(registry.shutdown().await.is_err(), "failure remains owned");
        assert_eq!(closes.load(Ordering::SeqCst), 3);
        drop((first, second));
    }
}
