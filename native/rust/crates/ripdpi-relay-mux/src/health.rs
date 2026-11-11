use std::sync::Arc;
use std::time::Duration;

use crate::RelaySession;
use crate::state::RelayMuxState;

pub(crate) fn prune_expired_session<S>(state: &mut RelayMuxState<S>, idle_timeout: Duration)
where
    S: RelaySession,
{
    // A closed carrier is dropped at once, even while leases are active: those
    // leases keep their own `Arc`, and new opens must not reuse it.
    let should_evict = state.cached_session.as_ref().is_some_and(|cached| {
        cached.session.is_closed() || (cached.active_leases == 0 && cached.idle_since.elapsed() >= idle_timeout)
    });
    if should_evict {
        state.cached_session = None;
        state.evictions += 1;
    }
}

pub(crate) fn invalidate_cached_session<S>(state: &mut RelayMuxState<S>, session: &Arc<S>)
where
    S: RelaySession,
{
    if state.cached_session.as_ref().is_some_and(|cached| Arc::ptr_eq(&cached.session, session)) {
        state.cached_session = None;
        state.evictions += 1;
    }
}
