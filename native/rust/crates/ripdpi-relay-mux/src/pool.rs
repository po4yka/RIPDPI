use std::sync::{Arc, Mutex};

use tokio::sync::{Mutex as AsyncMutex, OwnedSemaphorePermit, Semaphore, SemaphorePermit};

use crate::health::{invalidate_cached_session, prune_expired_session};
use crate::lease::LeaseGuard;
use crate::lifecycle::PoolLifecycle;
use crate::state::{CachedSession, RelayMuxState};
use crate::stream::{MuxLease, MuxStream};
use crate::{RelayCapabilities, RelayOpenError, RelayPoolConfig, RelayPoolHealth, RelaySession, RelaySessionFactory};

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

// Lock order: lifecycle read -> lease/opening permits -> creation -> state.
// Shutdown releases state before waking callers or waiting for lifecycle write.
struct RelayMuxInner<F>
where
    F: RelaySessionFactory,
{
    factory: F,
    lifecycle: PoolLifecycle,
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
                lifecycle: PoolLifecycle::default(),
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
    /// # Cancel safety:
    /// cancel-safe: admission stays closed; unfinished factory cleanup remains owned for retry.
    pub async fn shutdown(&self) -> Result<(), F::Error> {
        {
            // Cache publication uses this lock before checking terminal admission.
            let _state = self.inner.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            self.inner.lifecycle.close();
        }
        // Waking a caller can re-enter health(), so no state lock is held here.
        self.inner.lifecycle.notify_closed();
        self.inner.permits.close();
        if let Some(permits) = &self.inner.datagram_permits {
            permits.close();
        }
        self.inner.openings.close();
        let _cleanup = self.inner.lifecycle.drain().await;
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
    ) -> Result<MuxStream<<F::Session as RelaySession>::Stream, F::Session>, RelayOpenError<F::Error>> {
        self.inner.lifecycle.open(self.open_stream_inner(target)).await
    }

    /// # Cancel safety:
    /// cancel-safe: permits and the lease guard release on drop; the session owns partial opens.
    async fn open_stream_inner(
        &self,
        target: &str,
    ) -> Result<MuxStream<<F::Session as RelaySession>::Stream, F::Session>, RelayOpenError<F::Error>> {
        let permit = self.acquire_permit(&self.inner.permits).await?;
        let opening = self.acquire_opening_permit().await?;
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
                    return Err(error.into());
                }
                self.invalidate_failed_open(Some(&session));
                if !from_cache {
                    return Err(error.into());
                }
                // A cache hit may be stale: the server or NAT closed the
                // carrier during the idle window, so the first lease on it
                // fails through no fault of the caller. Evict the corpse and
                // retry ONCE on a freshly created carrier; the original error
                // stays the surfaced cause if the retry path cannot even set
                // up.
                drop(guard);
                let retry_permit = self.acquire_permit(&self.inner.permits).await?;
                let opening = self.acquire_opening_permit().await?;
                let Ok((fresh, _, retry_guard)) = self.session_for_open(retry_permit).await else {
                    return Err(error.into());
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
                        Err(retry_error.into())
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
    ) -> Result<MuxLease<<F::Session as RelaySession>::Datagram, F::Session>, RelayOpenError<F::Error>> {
        self.inner.lifecycle.open(self.open_datagram_inner()).await
    }

    /// # Cancel safety:
    /// cancel-safe: permits and the lease guard release on drop; the session owns partial opens.
    async fn open_datagram_inner(
        &self,
    ) -> Result<MuxLease<<F::Session as RelaySession>::Datagram, F::Session>, RelayOpenError<F::Error>> {
        let permit = self.acquire_permit(self.datagram_permits()).await?;
        let opening = self.acquire_opening_permit().await?;
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
                    return Err(error.into());
                }
                self.invalidate_failed_open(Some(&session));
                if !from_cache {
                    return Err(error.into());
                }
                // Mirror open_stream's single stale-carrier retry.
                drop(guard);
                let retry_permit = self.acquire_permit(self.datagram_permits()).await?;
                let opening = self.acquire_opening_permit().await?;
                let Ok((fresh, _, retry_guard)) = self.session_for_datagram(retry_permit).await else {
                    return Err(error.into());
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
                        Err(retry_error.into())
                    }
                }
            }
        }
    }

    fn datagram_permits(&self) -> &Arc<Semaphore> {
        self.inner.datagram_permits.as_ref().unwrap_or(&self.inner.permits)
    }

    /// # Cancel safety:
    /// cancel-safe: cancellation drops a waiter without consuming a permit; it loses its queue position.
    async fn acquire_permit(&self, permits: &Arc<Semaphore>) -> Result<OwnedSemaphorePermit, RelayOpenError<F::Error>> {
        match permits.clone().try_acquire_owned() {
            Ok(permit) => return Ok(permit),
            Err(tokio::sync::TryAcquireError::Closed) => return Err(RelayOpenError::Closed),
            Err(tokio::sync::TryAcquireError::NoPermits) => {}
        }
        self.inner.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner).backpressure_events += 1;
        permits.clone().acquire_owned().await.map_err(|_| RelayOpenError::Closed)
    }

    /// # Cancel safety:
    /// cancel-safe: cancellation consumes no opening slot, but loses its queue position.
    async fn acquire_opening_permit(&self) -> Result<SemaphorePermit<'_>, RelayOpenError<F::Error>> {
        self.inner.openings.acquire().await.map_err(|_| RelayOpenError::Closed)
    }

    /// # Cancel safety:
    /// cancel-safe: the pool releases admission on drop; the factory must retain or cancel construction work.
    async fn session_for_datagram(
        &self,
        permit: OwnedSemaphorePermit,
    ) -> Result<(Arc<F::Session>, bool, LeaseGuard<F::Session>), RelayOpenError<F::Error>> {
        if self.inner.factory.datagrams_use_shared_session() {
            return self.session_for_open(permit).await;
        }
        let session = self.inner.factory.create_datagram_session().await?;
        let mut state = self.inner.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if self.inner.lifecycle.is_closed() {
            return Err(RelayOpenError::Closed);
        }
        state.active_leases += 1;
        drop(state);
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
    ) -> Result<(Arc<F::Session>, bool, LeaseGuard<F::Session>), RelayOpenError<F::Error>> {
        if !self.inner.capabilities.reusable {
            let session = self.inner.factory.create_session().await?;
            let mut state = self.inner.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            if self.inner.lifecycle.is_closed() {
                return Err(RelayOpenError::Closed);
            }
            state.active_leases += 1;
            drop(state);
            let guard = LeaseGuard::new(self.inner.state.clone(), Arc::clone(&session), false, permit);
            return Ok((session, false, guard));
        }

        if let Some(session) = self.reserve_cached_session()? {
            let guard = LeaseGuard::new(self.inner.state.clone(), Arc::clone(&session), true, permit);
            return Ok((session, true, guard));
        }

        // Cancellation releases the singleflight gate and the admission permit.
        let _creation_guard = self.inner.session_creation.lock().await;
        if let Some(session) = self.reserve_cached_session()? {
            let guard = LeaseGuard::new(self.inner.state.clone(), Arc::clone(&session), true, permit);
            return Ok((session, true, guard));
        }
        let created = self.inner.factory.create_session().await?;
        let mut state = self.inner.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if self.inner.lifecycle.is_closed() {
            return Err(RelayOpenError::Closed);
        }
        prune_expired_session(&mut state, self.inner.config.idle_timeout);
        let from_cache = state.cached_session.is_some();
        let cached = state.cached_session.get_or_insert_with(|| CachedSession::new(created));
        let session = Arc::clone(&cached.session);
        cached.active_leases += 1;
        state.active_leases += 1;
        let guard = LeaseGuard::new(self.inner.state.clone(), Arc::clone(&session), true, permit);
        Ok((session, from_cache, guard))
    }

    fn reserve_cached_session(&self) -> Result<Option<Arc<F::Session>>, RelayOpenError<F::Error>> {
        let mut state = self.inner.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        prune_expired_session(&mut state, self.inner.config.idle_timeout);
        if self.inner.lifecycle.is_closed() {
            return Err(RelayOpenError::Closed);
        }
        let Some(cached) = state.cached_session.as_mut() else {
            return Ok(None);
        };
        let session = Arc::clone(&cached.session);
        cached.active_leases += 1;
        state.active_leases += 1;
        Ok(Some(session))
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
