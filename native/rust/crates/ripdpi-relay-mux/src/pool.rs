use std::sync::{Arc, Mutex};

use tokio::sync::{OwnedSemaphorePermit, Semaphore};

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
                state: Arc::new(Mutex::new(RelayMuxState::new())),
            }),
        }
    }

    pub fn capabilities(&self) -> RelayCapabilities {
        self.inner.capabilities
    }

    pub fn health(&self) -> RelayPoolHealth {
        let mut state = self.inner.state.lock().expect("relay mux state");
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
        let session = self.session_for_open().await?;
        self.mark_lease_started();

        match session.open_stream(target).await {
            Ok(stream) => Ok(MuxStream::new(
                stream,
                LeaseGuard::new(self.inner.state.clone(), session, self.inner.capabilities.reusable, permit),
            )),
            Err(error) => {
                self.finish_failed_open(Some(&session));
                Err(error)
            }
        }
    }

    pub async fn open_datagram(
        &self,
    ) -> Result<MuxLease<<F::Session as RelaySession>::Datagram, F::Session>, F::Error> {
        let permit = self.acquire_permit().await;
        let session = self.session_for_open().await?;
        self.mark_lease_started();

        match session.open_datagram().await {
            Ok(datagram) => Ok(MuxLease::new(
                datagram,
                LeaseGuard::new(self.inner.state.clone(), session, self.inner.capabilities.reusable, permit),
            )),
            Err(error) => {
                self.finish_failed_open(Some(&session));
                Err(error)
            }
        }
    }

    async fn acquire_permit(&self) -> OwnedSemaphorePermit {
        if let Ok(permit) = self.inner.permits.clone().try_acquire_owned() {
            return permit;
        }

        self.inner.state.lock().expect("relay mux state").backpressure_events += 1;
        self.inner.permits.clone().acquire_owned().await.expect("relay mux semaphore unexpectedly closed")
    }

    async fn session_for_open(&self) -> Result<Arc<F::Session>, F::Error> {
        if !self.inner.capabilities.reusable {
            return self.inner.factory.create_session().await;
        }

        {
            let mut state = self.inner.state.lock().expect("relay mux state");
            prune_expired_session(&mut state, self.inner.config.idle_timeout);
            if let Some(session) = state.cached_session.as_ref().map(|cached| Arc::clone(&cached.session)) {
                return Ok(session);
            }
        }

        let created = self.inner.factory.create_session().await?;
        let mut state = self.inner.state.lock().expect("relay mux state");
        prune_expired_session(&mut state, self.inner.config.idle_timeout);
        if let Some(session) = state.cached_session.as_ref().map(|cached| Arc::clone(&cached.session)) {
            return Ok(session);
        }
        state.cached_session = Some(CachedSession::new(Arc::clone(&created)));
        Ok(created)
    }

    fn mark_lease_started(&self) {
        let mut state = self.inner.state.lock().expect("relay mux state");
        prune_expired_session(&mut state, self.inner.config.idle_timeout);
        state.active_leases += 1;
    }

    fn finish_failed_open(&self, session: Option<&Arc<F::Session>>) {
        let mut state = self.inner.state.lock().expect("relay mux state");
        if state.active_leases > 0 {
            state.active_leases -= 1;
        }
        if let Some(session) = session {
            invalidate_cached_session(&mut state, session);
        }
    }
}
