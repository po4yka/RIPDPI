use std::sync::Arc;
use std::time::Duration;

use crate::RelaySession;
use crate::state::RelayMuxState;

pub(crate) fn prune_expired_session<S>(state: &mut RelayMuxState<S>, idle_timeout: Duration)
where
    S: RelaySession,
{
    let should_evict = state.active_leases == 0
        && state.cached_session.as_ref().is_some_and(|cached| cached.idle_since.elapsed() >= idle_timeout);
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
