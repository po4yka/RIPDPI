use std::future::Future;
use std::io;
use std::ops::Deref;
use std::sync::{Arc, Mutex, MutexGuard, PoisonError, Weak};

pub trait OwnedSession: Send + Sync {
    fn abort(&self);
    /// Join all owned work. A returned error is terminal: all joins are complete.
    ///
    /// # Cancel safety:
    /// cancel-safe: a cancelled close retains unfinished joins in the session.
    fn close(&self) -> impl Future<Output = io::Result<()>> + Send;
}

/// The carrier handle a registry-backed factory returns. Dropping the last
/// handle (pool eviction, a finished non-reusable lease, an abandoned open)
/// aborts the carrier at once, so its sockets and tasks do not wait for the
/// next `create`. The registry still joins it later.
pub struct Registered<S: OwnedSession>(Arc<S>);

impl<S: OwnedSession> Deref for Registered<S> {
    type Target = S;

    fn deref(&self) -> &S {
        &self.0
    }
}

impl<S: OwnedSession> Drop for Registered<S> {
    fn drop(&mut self) {
        self.0.abort();
    }
}

/// Keeps the join owner after pool eviction or abandonment of an open request.
/// An entry whose handles are all gone is already aborted and waits only for
/// its join.
pub(crate) struct SessionRegistry<S: OwnedSession> {
    state: Mutex<State<S>>,
    /// Serializes joins so one close future at a time runs per entry. It is
    /// never held across a session constructor.
    joining: tokio::sync::Mutex<()>,
}

struct State<S: OwnedSession> {
    closed: bool,
    entries: Vec<Entry<S>>,
    cleanup_error: Option<Arc<io::Error>>,
}

struct Entry<S: OwnedSession> {
    owner: Arc<S>,
    handle: Weak<Registered<S>>,
}

impl<S: OwnedSession> Default for SessionRegistry<S> {
    fn default() -> Self {
        Self {
            state: Mutex::new(State { closed: false, entries: Vec::new(), cleanup_error: None }),
            joining: tokio::sync::Mutex::new(()),
        }
    }
}

fn closed_error() -> io::Error {
    io::Error::new(io::ErrorKind::NotConnected, "relay session factory closed")
}

impl<S: OwnedSession> SessionRegistry<S> {
    fn state(&self) -> MutexGuard<'_, State<S>> {
        self.state.lock().unwrap_or_else(PoisonError::into_inner)
    }

    fn finish_close(&self, owner: &Arc<S>, result: io::Result<()>) {
        let mut state = self.state();
        state.entries.retain(|entry| !Arc::ptr_eq(&entry.owner, owner));
        if let Err(error) = result {
            state.cleanup_error.get_or_insert_with(|| Arc::new(error));
        }
    }

    fn cleanup_result(&self) -> io::Result<()> {
        self.state().cleanup_error.as_ref().map_or(Ok(()), |error| Err(io::Error::new(error.kind(), Arc::clone(error))))
    }

    /// Joins released entries, then runs `create` outside every lock, so
    /// concurrent dials and `shutdown` do not queue behind a slow handshake.
    ///
    /// # Cancel safety:
    ///
    /// cancel-safe: entries stay registered until their close future
    /// completes. The constructor must itself own any work it starts before
    /// an await; registering the result cannot repair a detached task inside
    /// a cancelled constructor.
    pub(crate) async fn create(
        &self,
        create: impl Future<Output = io::Result<S>> + Send,
    ) -> io::Result<Arc<Registered<S>>> {
        if self.state().closed {
            return Err(closed_error());
        }
        if let Ok(_joining) = self.joining.try_lock() {
            let released: Vec<Arc<S>> = self
                .state()
                .entries
                .iter()
                .filter(|entry| entry.handle.strong_count() == 0)
                .map(|entry| Arc::clone(&entry.owner))
                .collect();
            for owner in released {
                let result = owner.close().await;
                self.finish_close(&owner, result);
            }
        }
        let owner = Arc::new(create.await?);
        let handle = Arc::new(Registered(Arc::clone(&owner)));
        {
            let mut state = self.state();
            if !state.closed {
                state.entries.push(Entry { owner, handle: Arc::downgrade(&handle) });
                return Ok(handle);
            }
        }
        // Shutdown ran while the carrier was being built.
        drop(handle);
        owner.close().await?;
        Err(closed_error())
    }

    /// # Cancel safety:
    ///
    /// cancel-safe: all entries survive cancellation until their close future
    /// completes. Every session is signalled before the first close is awaited.
    pub(crate) async fn shutdown(&self) -> io::Result<()> {
        let signalled: Vec<Arc<S>> = {
            let mut state = self.state();
            state.closed = true;
            state.entries.iter().map(|entry| Arc::clone(&entry.owner)).collect()
        };
        for owner in &signalled {
            owner.abort();
        }
        let _joining = self.joining.lock().await;
        let owners: Vec<Arc<S>> = self.state().entries.iter().map(|entry| Arc::clone(&entry.owner)).collect();
        for owner in owners {
            let result = owner.close().await;
            self.finish_close(&owner, result);
        }
        self.cleanup_result()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
    use std::time::Duration;
    use tokio::sync::Notify;

    #[derive(Debug)]
    struct CleanupFailure(&'static str);

    impl std::fmt::Display for CleanupFailure {
        fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            formatter.write_str(self.0)
        }
    }

    impl std::error::Error for CleanupFailure {}

    struct Session {
        aborts: Arc<AtomicUsize>,
        closes: Arc<AtomicUsize>,
        release: Option<Arc<Notify>>,
        failure: Option<(io::ErrorKind, &'static str)>,
    }
    impl OwnedSession for Session {
        fn abort(&self) {
            self.aborts.fetch_add(1, Ordering::SeqCst);
        }
        /// # Cancel safety:
        /// cancel-safe: cancellation before release preserves the session.
        async fn close(&self) -> io::Result<()> {
            if let Some(release) = &self.release {
                release.notified().await;
            }
            self.closes.fetch_add(1, Ordering::SeqCst);
            if let Some((kind, marker)) = self.failure {
                Err(io::Error::new(kind, CleanupFailure(marker)))
            } else {
                Ok(())
            }
        }
    }
    fn session(aborts: &Arc<AtomicUsize>, closes: &Arc<AtomicUsize>) -> Session {
        Session { aborts: aborts.clone(), closes: closes.clone(), release: None, failure: None }
    }

    /// Polls `future` once and asserts that it is still pending.
    async fn poll_pending<F: Future + Unpin>(future: &mut F) {
        std::future::poll_fn(|cx| {
            assert!(std::pin::Pin::new(&mut *future).poll(cx).is_pending());
            std::task::Poll::Ready(())
        })
        .await;
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
    async fn dropping_the_last_handle_aborts_the_carrier_without_waiting_for_a_create() {
        let registry = SessionRegistry::default();
        let aborts = Arc::new(AtomicUsize::new(0));
        let closes = Arc::new(AtomicUsize::new(0));
        let handle = registry.create(async { Ok(session(&aborts, &closes)) }).await.expect("first");
        let lease = Arc::clone(&handle);
        drop(handle);
        assert_eq!(aborts.load(Ordering::SeqCst), 0, "a live lease keeps the carrier");
        drop(lease);
        assert_eq!(aborts.load(Ordering::SeqCst), 1, "the released carrier is aborted at once");
        registry.shutdown().await.expect("shutdown joins the released carrier");
        assert_eq!(closes.load(Ordering::SeqCst), 1);
        assert!(registry.state().entries.is_empty());
    }

    /// # Cancel safety:
    /// cancel-safe: the registry retains unfinished owners and the test drops all returned handles.
    #[tokio::test]
    async fn a_failed_join_of_a_released_carrier_does_not_block_new_sessions() {
        let registry = SessionRegistry::default();
        let aborts = Arc::new(AtomicUsize::new(0));
        let closes = Arc::new(AtomicUsize::new(0));
        drop(
            registry
                .create(async {
                    Ok(Session {
                        failure: Some((io::ErrorKind::PermissionDenied, "observed cleanup failure")),
                        ..session(&aborts, &closes)
                    })
                })
                .await
                .expect("first"),
        );
        let second = registry.create(async { Ok(session(&aborts, &closes)) }).await.expect("unrelated new carrier");
        let third = registry.create(async { Ok(session(&aborts, &closes)) }).await.expect("still admits carriers");
        assert!(registry.shutdown().await.is_err(), "shutdown still reports the failed join");
        drop((second, third));
    }

    /// # Cancel safety:
    /// cancel-safe: the registry owns each session until its close completes.
    #[tokio::test]
    async fn completed_cleanup_failures_release_owners_and_preserve_the_first_error() {
        let registry = SessionRegistry::default();
        let aborts = Arc::new(AtomicUsize::new(0));
        let closes = Arc::new(AtomicUsize::new(0));
        let mut released = Vec::new();
        for index in 0..32 {
            let failure = if index == 0 {
                (io::ErrorKind::PermissionDenied, "first cleanup failure")
            } else {
                (io::ErrorKind::ConnectionReset, "later cleanup failure")
            };
            let handle = registry
                .create(async { Ok(Session { failure: Some(failure), ..session(&aborts, &closes) }) })
                .await
                .expect("failed owner is admitted before cleanup");
            released.push(Arc::downgrade(&handle.0));
            drop(handle);
        }
        let healthy = registry.create(async { Ok(session(&aborts, &closes)) }).await.expect("healthy carrier");
        assert!(released.iter().all(|owner| owner.upgrade().is_none()), "completed failed owners must be destroyed");
        assert_eq!(registry.state().entries.len(), 1, "only the healthy owner remains registered");
        for _ in 0..2 {
            let error = registry.shutdown().await.expect_err("the first cleanup error remains visible");
            assert_eq!(error.kind(), io::ErrorKind::PermissionDenied);
            assert_eq!(error.to_string(), "first cleanup failure");
            let original = error
                .get_ref()
                .and_then(|source| source.downcast_ref::<Arc<io::Error>>())
                .expect("the original error stays shared");
            let marker = original
                .get_ref()
                .and_then(|source| source.downcast_ref::<CleanupFailure>())
                .expect("the typed failure payload remains available");
            assert_eq!(marker.0, "first cleanup failure");
        }
        assert!(registry.state().entries.is_empty());
        drop(healthy);
    }

    #[tokio::test]
    async fn a_slow_constructor_does_not_serialize_other_creates() {
        let registry = SessionRegistry::default();
        let aborts = Arc::new(AtomicUsize::new(0));
        let closes = Arc::new(AtomicUsize::new(0));
        let release = Arc::new(Notify::new());
        let mut slow = Box::pin(registry.create(async {
            release.notified().await;
            Ok(session(&aborts, &closes))
        }));
        poll_pending(&mut slow).await;
        let fast =
            tokio::time::timeout(Duration::from_secs(5), registry.create(async { Ok(session(&aborts, &closes)) }))
                .await
                .expect("a second create must not wait for the first constructor")
                .expect("fast");
        release.notify_one();
        let slow = slow.await.expect("slow");
        assert_eq!(registry.state().entries.len(), 2);
        drop((slow, fast));
    }

    #[tokio::test]
    async fn a_carrier_built_after_shutdown_is_closed_and_refused() {
        let registry = SessionRegistry::default();
        let aborts = Arc::new(AtomicUsize::new(0));
        let closes = Arc::new(AtomicUsize::new(0));
        let release = Arc::new(Notify::new());
        let mut late = Box::pin(registry.create(async {
            release.notified().await;
            Ok(session(&aborts, &closes))
        }));
        poll_pending(&mut late).await;
        tokio::time::timeout(Duration::from_secs(5), registry.shutdown())
            .await
            .expect("shutdown must not wait for an in-flight constructor")
            .expect("shutdown");
        release.notify_one();
        assert!(late.await.is_err());
        assert_eq!((aborts.load(Ordering::SeqCst), closes.load(Ordering::SeqCst)), (1, 1));
        assert!(registry.state().entries.is_empty());
    }

    /// # Cancel safety:
    /// cancel-safe: the registry keeps unfinished close ownership across a dropped shutdown.
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
        poll_pending(&mut shutdown).await;
        assert_eq!(aborts.load(Ordering::SeqCst), 2);
        drop(shutdown);
        assert_eq!(registry.state().entries.len(), 2);
        let unfinished = Arc::downgrade(&first.0);
        drop(first);
        assert!(unfinished.upgrade().is_some(), "a cancelled close keeps its owner");
        release.notify_one();
        registry.shutdown().await.expect("retry");
        assert!(unfinished.upgrade().is_none(), "a completed close releases its owner");
        assert_eq!(closes.load(Ordering::SeqCst), 2);
        drop(second);
    }

    /// # Cancel safety:
    /// cancel-safe: every completed close is observed before the registry is dropped.
    #[tokio::test]
    async fn cleanup_failure_does_not_skip_other_owners_or_allow_reopen() {
        let registry = SessionRegistry::default();
        let aborts = Arc::new(AtomicUsize::new(0));
        let closes = Arc::new(AtomicUsize::new(0));
        let first = registry
            .create(async {
                Ok(Session {
                    failure: Some((io::ErrorKind::PermissionDenied, "observed cleanup failure")),
                    ..session(&aborts, &closes)
                })
            })
            .await
            .expect("first");
        let second = registry.create(async { Ok(session(&aborts, &closes)) }).await.expect("second");
        assert!(registry.shutdown().await.is_err());
        assert_eq!(closes.load(Ordering::SeqCst), 2);
        assert!(registry.state().entries.is_empty());
        assert!(registry.shutdown().await.is_err(), "failure remains recorded");
        assert_eq!(closes.load(Ordering::SeqCst), 2, "completed owners are not closed twice");
        drop((first, second));
    }
}
