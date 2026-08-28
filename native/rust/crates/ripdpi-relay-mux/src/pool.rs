use std::sync::{Arc, Mutex};

use tokio::sync::{Mutex as AsyncMutex, OwnedSemaphorePermit, Semaphore};

use crate::health::{invalidate_cached_session, prune_expired_session};
use crate::lease::LeaseGuard;
use crate::state::{CachedSession, RelayMuxState};
use crate::stream::{MuxLease, MuxStream};
use crate::{RelayCapabilities, RelayPoolConfig, RelayPoolHealth, RelaySession, RelaySessionFactory};

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
                self.inner.capabilities.reusable && state.active_leases == 0 && state.cached_session.is_some(),
            ),
            busy_streams: state.active_leases,
            evictions: state.evictions,
            idle_timeout: self.inner.config.idle_timeout,
            backpressure_events: state.backpressure_events,
        }
    }

    pub async fn open_stream(
        &self,
        target: &str,
    ) -> Result<MuxStream<<F::Session as RelaySession>::Stream, F::Session>, F::Error> {
        let permit = self.acquire_permit().await;
        let (session, from_cache) = self.session_for_open().await?;
        self.mark_lease_started();
        let guard =
            LeaseGuard::new(self.inner.state.clone(), Arc::clone(&session), self.inner.capabilities.reusable, permit);

        match session.open_stream(target).await {
            Ok(stream) => Ok(MuxStream::new(stream, guard)),
            Err(error) => {
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
                let Ok((fresh, _)) = self.session_for_open().await else {
                    return Err(error);
                };
                self.mark_lease_started();
                let retry_guard = LeaseGuard::new(
                    self.inner.state.clone(),
                    Arc::clone(&fresh),
                    self.inner.capabilities.reusable,
                    self.acquire_permit().await,
                );
                match fresh.open_stream(target).await {
                    Ok(stream) => Ok(MuxStream::new(stream, retry_guard)),
                    Err(retry_error) => {
                        self.invalidate_failed_open(Some(&fresh));
                        Err(retry_error)
                    }
                }
            }
        }
    }

    pub async fn open_datagram(
        &self,
    ) -> Result<MuxLease<<F::Session as RelaySession>::Datagram, F::Session>, F::Error> {
        let permit = self.acquire_permit().await;
        let (session, from_cache) = self.session_for_open().await?;
        self.mark_lease_started();
        let guard =
            LeaseGuard::new(self.inner.state.clone(), Arc::clone(&session), self.inner.capabilities.reusable, permit);

        match session.open_datagram().await {
            Ok(datagram) => Ok(MuxLease::new(datagram, guard)),
            Err(error) => {
                self.invalidate_failed_open(Some(&session));
                if !from_cache {
                    return Err(error);
                }
                // Mirror open_stream's single stale-carrier retry.
                drop(guard);
                let Ok((fresh, _)) = self.session_for_open().await else {
                    return Err(error);
                };
                self.mark_lease_started();
                let retry_guard = LeaseGuard::new(
                    self.inner.state.clone(),
                    Arc::clone(&fresh),
                    self.inner.capabilities.reusable,
                    self.acquire_permit().await,
                );
                match fresh.open_datagram().await {
                    Ok(datagram) => Ok(MuxLease::new(datagram, retry_guard)),
                    Err(retry_error) => {
                        self.invalidate_failed_open(Some(&fresh));
                        Err(retry_error)
                    }
                }
            }
        }
    }

    async fn acquire_permit(&self) -> OwnedSemaphorePermit {
        if let Ok(permit) = self.inner.permits.clone().try_acquire_owned() {
            return permit;
        }

        // Recover poison: lease accounting is advisory.
        self.inner.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner).backpressure_events += 1;
        self.inner.permits.clone().acquire_owned().await.expect("relay mux semaphore unexpectedly closed")
    }

    /// Returns the session to open a lease on plus whether it came from the
    /// reuse cache (`true` means the carrier may be stale — the caller gets
    /// one fresh-carrier retry on a failed open).
    async fn session_for_open(&self) -> Result<(Arc<F::Session>, bool), F::Error> {
        if !self.inner.capabilities.reusable {
            return Ok((self.inner.factory.create_session().await?, false));
        }

        if let Some(session) = self.cached_session() {
            return Ok((session, true));
        }

        // The guard is intentionally held across factory creation: it is the
        // singleflight owner. Tokio's guard is drop-safe, so cancellation of
        // the owner releases the gate and lets the next waiter retry.
        let _creation_guard = self.inner.session_creation.lock().await;
        if let Some(session) = self.cached_session() {
            return Ok((session, true));
        }
        let created = self.inner.factory.create_session().await?;
        // Recover poison: lease accounting is advisory.
        let mut state = self.inner.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        prune_expired_session(&mut state, self.inner.config.idle_timeout);
        if let Some(session) = state.cached_session.as_ref().map(|cached| Arc::clone(&cached.session)) {
            return Ok((session, true));
        }
        state.cached_session = Some(CachedSession::new(Arc::clone(&created)));
        Ok((created, false))
    }

    fn cached_session(&self) -> Option<Arc<F::Session>> {
        // Recover poison: lease accounting is advisory.
        let mut state = self.inner.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        prune_expired_session(&mut state, self.inner.config.idle_timeout);
        state.cached_session.as_ref().map(|cached| Arc::clone(&cached.session))
    }

    fn mark_lease_started(&self) {
        // Recover poison: lease accounting is advisory.
        let mut state = self.inner.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        prune_expired_session(&mut state, self.inner.config.idle_timeout);
        state.active_leases += 1;
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
