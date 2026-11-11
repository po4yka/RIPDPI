use std::sync::{Arc, Mutex};

use tokio::sync::{Mutex as AsyncMutex, OwnedSemaphorePermit, Semaphore, SemaphorePermit};

use crate::health::{invalidate_cached_session, prune_expired_session};
use crate::lease::LeaseGuard;
use crate::state::{CachedSession, RelayMuxState};
use crate::stream::{MuxLease, MuxStream};
use crate::{RelayCapabilities, RelayPoolConfig, RelayPoolHealth, RelaySession, RelaySessionFactory};

/// Establishment attempts one pool runs at once, including initial opens on
/// reusable sessions. A session factory may return only configuration and dial
/// during the open. Established leases do not hold an opening slot.
pub(crate) const MAX_CONCURRENT_OPENS: usize = 8;

#[derive(Clone)]
pub struct RelayMux<F>
where
    F: RelaySessionFactory,
{
    inner: Arc<RelayMuxInner<F>>,
}

struct RelayMuxInner<F>
where
    F: RelaySessionFactory,
{
    factory: F,
    capabilities: RelayCapabilities,
    config: RelayPoolConfig,
    permits: Arc<Semaphore>,
    datagram_permits: Option<Arc<Semaphore>>,
    openings: Semaphore,
    session_creation: AsyncMutex<()>,
    state: Arc<Mutex<RelayMuxState<F::Session>>>,
}

impl<F> RelayMux<F>
where
    F: RelaySessionFactory,
{
    pub fn new(factory: F, config: RelayPoolConfig) -> Self {
        let max_active_leases = config.max_active_leases.max(1);
        Self {
            inner: Arc::new(RelayMuxInner {
                capabilities: factory.capabilities(),
                factory,
                config,
                permits: Arc::new(Semaphore::new(max_active_leases)),
                datagram_permits: config.max_active_datagram_leases.map(|max| Arc::new(Semaphore::new(max.max(1)))),
                openings: Semaphore::new(MAX_CONCURRENT_OPENS),
                session_creation: AsyncMutex::new(()),
                state: Arc::new(Mutex::new(RelayMuxState::new())),
            }),
        }
    }

    /// Stop factory-owned work after normal or forced stream draining.
    ///
    /// # Cancel safety
    /// Unfinished factory cleanup and the cached session remain owned for retry.
    pub async fn shutdown(&self) -> Result<(), F::Error> {
        self.inner.factory.shutdown().await?;
        self.inner.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner).cached_session = None;
        Ok(())
    }

    pub fn capabilities(&self) -> RelayCapabilities {
        self.inner.capabilities
    }

    pub fn health(&self) -> RelayPoolHealth {
        // Recover poison: lease accounting is advisory.
        let mut state = self.inner.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        prune_expired_session(&mut state, self.inner.config.idle_timeout);
        RelayPoolHealth {
            idle_streams: usize::from(
                self.inner.capabilities.reusable
                    && state.cached_session.as_ref().is_some_and(|cached| cached.active_leases == 0),
            ),
            busy_streams: state.active_leases,
            evictions: state.evictions,
            idle_timeout: self.inner.config.idle_timeout,
            backpressure_events: state.backpressure_events,
        }
    }

    /// Opens a stream to `target` on the cached carrier, or on a new one.
    ///
    /// # Cancel safety:
    ///
    /// cancel-safe: the lease and permit are released on drop. Dropping the
    /// future while the carrier open is in flight evicts that carrier from the
    /// cache, because a black-holed carrier never returns an error.
    pub async fn open_stream(
        &self,
        target: &str,
    ) -> Result<MuxStream<<F::Session as RelaySession>::Stream, F::Session>, F::Error> {
        let permit = self.acquire_permit(&self.inner.permits).await;
        let opening = self.acquire_opening_permit().await;
        let (session, from_cache, guard) = self.session_for_open(permit).await?;

        let evict = EvictOnCancel::new(self, &session);
        let opened = session.open_stream(target).await;
        evict.disarm();
        drop(opening);
        match opened {
            Ok(stream) => Ok(MuxStream::new(stream, guard)),
            Err(error) => {
                // The carrier refused only this open: keep it cached and do not re-dial.
                if !session.is_carrier_failure(&error) {
                    return Err(error);
                }
                self.invalidate_failed_open(Some(&session));
                if !from_cache {
                    return Err(error);
                }
                // A cache hit may be stale: the server or NAT closed the
                // carrier during the idle window, so the first lease on it
                // fails through no fault of the caller. Evict the corpse and
                // retry ONCE on a freshly created carrier; the original error
                // stays the surfaced cause if the retry path cannot even set
                // up.
                drop(guard);
                let retry_permit = self.acquire_permit(&self.inner.permits).await;
                let opening = self.acquire_opening_permit().await;
                let Ok((fresh, _, retry_guard)) = self.session_for_open(retry_permit).await else {
                    return Err(error);
                };
                let evict = EvictOnCancel::new(self, &fresh);
                let opened = fresh.open_stream(target).await;
                evict.disarm();
                drop(opening);
                match opened {
                    Ok(stream) => Ok(MuxStream::new(stream, retry_guard)),
                    Err(retry_error) => {
                        if fresh.is_carrier_failure(&retry_error) {
                            self.invalidate_failed_open(Some(&fresh));
                        }
                        Err(retry_error)
                    }
                }
            }
        }
    }

    /// Opens a datagram lease on the cached carrier, or on a new one.
    ///
    /// # Cancel safety:
    ///
    /// cancel-safe: with the same carrier eviction as [`Self::open_stream`].
    pub async fn open_datagram(
        &self,
    ) -> Result<MuxLease<<F::Session as RelaySession>::Datagram, F::Session>, F::Error> {
        let permit = self.acquire_permit(self.datagram_permits()).await;
        let opening = self.acquire_opening_permit().await;
        let (session, from_cache, guard) = self.session_for_datagram(permit).await?;

        let evict = EvictOnCancel::new(self, &session);
        let opened = session.open_datagram().await;
        evict.disarm();
        drop(opening);
        match opened {
            Ok(datagram) => Ok(MuxLease::new(datagram, guard)),
            Err(error) => {
                // The carrier refused only this open: keep it cached and do not re-dial.
                if !session.is_carrier_failure(&error) {
                    return Err(error);
                }
                self.invalidate_failed_open(Some(&session));
                if !from_cache {
                    return Err(error);
                }
                // Mirror open_stream's single stale-carrier retry.
                drop(guard);
                let retry_permit = self.acquire_permit(self.datagram_permits()).await;
                let opening = self.acquire_opening_permit().await;
                let Ok((fresh, _, retry_guard)) = self.session_for_datagram(retry_permit).await else {
                    return Err(error);
                };
                let evict = EvictOnCancel::new(self, &fresh);
                let opened = fresh.open_datagram().await;
                evict.disarm();
                drop(opening);
                match opened {
                    Ok(datagram) => Ok(MuxLease::new(datagram, retry_guard)),
                    Err(retry_error) => {
                        if fresh.is_carrier_failure(&retry_error) {
                            self.invalidate_failed_open(Some(&fresh));
                        }
                        Err(retry_error)
                    }
                }
            }
        }
    }

    fn datagram_permits(&self) -> &Arc<Semaphore> {
        self.inner.datagram_permits.as_ref().unwrap_or(&self.inner.permits)
    }

    /// # Cancel safety:
    /// cancel-safe: cancellation loses the FIFO queue position and releases
    /// any admission permit acquired by this future.
    async fn acquire_permit(&self, permits: &Arc<Semaphore>) -> OwnedSemaphorePermit {
        if let Ok(permit) = permits.clone().try_acquire_owned() {
            return permit;
        }

        // Recover poison: lease accounting is advisory.
        self.inner.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner).backpressure_events += 1;
        permits.clone().acquire_owned().await.expect("relay mux semaphore unexpectedly closed")
    }

    /// # Cancel safety:
    /// cancel-safe: cancellation removes the waiter without reserving a slot.
    async fn acquire_opening_permit(&self) -> SemaphorePermit<'_> {
        // Infallible: the opening semaphore is never closed.
        self.inner.openings.acquire().await.expect("relay mux opening semaphore unexpectedly closed")
    }

    /// # Cancel safety:
    /// cancel-safe: construction owns the permit; a ready session is leased
    /// without another await and never enters the stream cache.
    async fn session_for_datagram(
        &self,
        permit: OwnedSemaphorePermit,
    ) -> Result<(Arc<F::Session>, bool, LeaseGuard<F::Session>), F::Error> {
        if self.inner.factory.datagrams_use_shared_session() {
            return self.session_for_open(permit).await;
        }
        let session = self.inner.factory.create_datagram_session().await?;
        self.inner.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner).active_leases += 1;
        let guard = LeaseGuard::new(self.inner.state.clone(), Arc::clone(&session), false, permit);
        Ok((session, false, guard))
    }

    /// Selects a session and reserves its lease before releasing the cache lock.
    ///
    /// # Cancel safety:
    /// cancel-safe: the pool releases its permit and creation gate on drop;
    /// the factory must cancel or retain construction work.
    async fn session_for_open(
        &self,
        permit: OwnedSemaphorePermit,
    ) -> Result<(Arc<F::Session>, bool, LeaseGuard<F::Session>), F::Error> {
        if !self.inner.capabilities.reusable {
            let session = self.inner.factory.create_session().await?;
            self.inner.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner).active_leases += 1;
            let guard = LeaseGuard::new(self.inner.state.clone(), Arc::clone(&session), false, permit);
            return Ok((session, false, guard));
        }

        if let Some(session) = self.reserve_cached_session() {
            let guard = LeaseGuard::new(self.inner.state.clone(), Arc::clone(&session), true, permit);
            return Ok((session, true, guard));
        }

        // Cancellation releases the singleflight gate and the admission permit.
        let _creation_guard = self.inner.session_creation.lock().await;
        if let Some(session) = self.reserve_cached_session() {
            let guard = LeaseGuard::new(self.inner.state.clone(), Arc::clone(&session), true, permit);
            return Ok((session, true, guard));
        }
        let created = self.inner.factory.create_session().await?;
        let mut state = self.inner.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        prune_expired_session(&mut state, self.inner.config.idle_timeout);
        let from_cache = state.cached_session.is_some();
        let cached = state.cached_session.get_or_insert_with(|| CachedSession::new(created));
        let session = Arc::clone(&cached.session);
        cached.active_leases += 1;
        state.active_leases += 1;
        let guard = LeaseGuard::new(self.inner.state.clone(), Arc::clone(&session), true, permit);
        Ok((session, from_cache, guard))
    }

    fn reserve_cached_session(&self) -> Option<Arc<F::Session>> {
        let mut state = self.inner.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        prune_expired_session(&mut state, self.inner.config.idle_timeout);
        let cached = state.cached_session.as_mut()?;
        let session = Arc::clone(&cached.session);
        cached.active_leases += 1;
        state.active_leases += 1;
        Some(session)
    }

    fn invalidate_failed_open(&self, session: Option<&Arc<F::Session>>) {
        // Recover poison: lease accounting is advisory.
        let mut state = self.inner.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if let Some(session) = session {
            invalidate_cached_session(&mut state, session);
        }
    }

    /// Poison the shared state mutex (test-only) so the poison-recovery paths
    /// can be exercised without `unsafe` or a real concurrent-panic race.
    #[cfg(test)]
    pub(crate) fn poison_for_test(&self) {
        let state = Arc::clone(&self.inner.state);
        // A thread that panics while holding the lock leaves the mutex poisoned.
        let _ = std::thread::spawn(move || {
            let _held = state.lock().expect("lock to poison");
            panic!("intentional poison");
        })
        .join();
    }
}

/// Evicts `session` from the reuse cache when dropped while armed. An open that
/// the caller abandoned (for example at its connect deadline) is the only sign
/// of a silently black-holed carrier, which never returns an error.
struct EvictOnCancel<'a, F>
where
    F: RelaySessionFactory,
{
    mux: &'a RelayMux<F>,
    session: &'a Arc<F::Session>,
    armed: bool,
}

impl<'a, F> EvictOnCancel<'a, F>
where
    F: RelaySessionFactory,
{
    fn new(mux: &'a RelayMux<F>, session: &'a Arc<F::Session>) -> Self {
        Self { mux, session, armed: true }
    }

    fn disarm(mut self) {
        self.armed = false;
    }
}

impl<F> Drop for EvictOnCancel<'_, F>
where
    F: RelaySessionFactory,
{
    fn drop(&mut self) {
        if self.armed {
            self.mux.invalidate_failed_open(Some(self.session));
        }
    }
}
