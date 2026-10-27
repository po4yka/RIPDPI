use std::future::Future;
use std::io;
use std::ops::Deref;
use std::sync::{Arc, Mutex, MutexGuard, PoisonError, Weak};

pub trait OwnedSession: Send + Sync {
    fn abort(&self);
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
}

struct Entry<S: OwnedSession> {
    owner: Arc<S>,
    handle: Weak<Registered<S>>,
}

impl<S: OwnedSession> Default for SessionRegistry<S> {
    fn default() -> Self {
        Self { state: Mutex::new(State { closed: false, entries: Vec::new() }), joining: tokio::sync::Mutex::new(()) }
    }
}

fn closed_error() -> io::Error {
    io::Error::new(io::ErrorKind::NotConnected, "relay session factory closed")
}

impl<S: OwnedSession> SessionRegistry<S> {
    fn state(&self) -> MutexGuard<'_, State<S>> {
        self.state.lock().unwrap_or_else(PoisonError::into_inner)
    }

    fn remove(&self, owner: &Arc<S>) {
        self.state().entries.retain(|entry| !Arc::ptr_eq(&entry.owner, owner));
    }

    /// Joins released entries, then runs `create` outside every lock, so
    /// concurrent dials and `shutdown` do not queue behind a slow handshake.
    ///
    /// # Cancel safety
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
                // A failed join stays registered for shutdown to report; it
                // must not block carriers that have nothing to do with it.
                if owner.close().await.is_ok() {
                    self.remove(&owner);
                }
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

    /// # Cancel safety
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
        let mut first_error = None;
        for owner in owners {
            match owner.close().await {
                Ok(()) => self.remove(&owner),
                Err(error) => {
                    first_error.get_or_insert(error);
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
    use std::time::Duration;
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

    #[tokio::test]
    async fn a_failed_join_of_a_released_carrier_does_not_block_new_sessions() {
        let registry = SessionRegistry::default();
        let aborts = Arc::new(AtomicUsize::new(0));
        let closes = Arc::new(AtomicUsize::new(0));
        drop(registry.create(async { Ok(Session { fail: true, ..session(&aborts, &closes) }) }).await.expect("first"));
        let second = registry.create(async { Ok(session(&aborts, &closes)) }).await.expect("unrelated new carrier");
        let third = registry.create(async { Ok(session(&aborts, &closes)) }).await.expect("still admits carriers");
        assert!(registry.shutdown().await.is_err(), "shutdown still reports the failed join");
        drop((second, third));
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
        assert_eq!(registry.state().entries.len(), 1);
        assert!(registry.shutdown().await.is_err(), "failure remains owned");
        assert_eq!(closes.load(Ordering::SeqCst), 3);
        drop((first, second));
    }
}
