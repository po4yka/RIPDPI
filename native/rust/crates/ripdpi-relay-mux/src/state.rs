use std::sync::Arc;
use std::time::Instant;

use crate::RelaySession;

pub(crate) struct RelayMuxState<S>
where
    S: RelaySession,
{
    pub(crate) cached_session: Option<CachedSession<S>>,
    pub(crate) active_leases: usize,
    pub(crate) evictions: u64,
    pub(crate) backpressure_events: u64,
}

impl<S> RelayMuxState<S>
where
    S: RelaySession,
{
    pub(crate) fn new() -> Self {
        Self { cached_session: None, active_leases: 0, evictions: 0, backpressure_events: 0 }
    }
}

pub(crate) struct CachedSession<S>
where
    S: RelaySession,
{
    pub(crate) session: Arc<S>,
    pub(crate) idle_since: Instant,
}

impl<S> CachedSession<S>
where
    S: RelaySession,
{
    pub(crate) fn new(session: Arc<S>) -> Self {
        Self { session, idle_since: Instant::now() }
    }
}
