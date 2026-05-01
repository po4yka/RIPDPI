use std::sync::{Arc, Mutex};
use std::time::Instant;

use tokio::sync::OwnedSemaphorePermit;

use crate::state::RelayMuxState;
use crate::RelaySession;

pub(crate) struct LeaseGuard<S>
where
    S: RelaySession,
{
    state: Arc<Mutex<RelayMuxState<S>>>,
    session: Option<Arc<S>>,
    reusable: bool,
    _permit: OwnedSemaphorePermit,
}

impl<S> LeaseGuard<S>
where
    S: RelaySession,
{
    pub(crate) fn new(
        state: Arc<Mutex<RelayMuxState<S>>>,
        session: Arc<S>,
        reusable: bool,
        permit: OwnedSemaphorePermit,
    ) -> Self {
        Self { state, session: Some(session), reusable, _permit: permit }
    }
}

impl<S> Drop for LeaseGuard<S>
where
    S: RelaySession,
{
    fn drop(&mut self) {
        let mut state = self.state.lock().expect("relay mux state");
        if state.active_leases > 0 {
            state.active_leases -= 1;
        }
        if self.reusable {
            let no_active_leases = state.active_leases == 0;
            if let (Some(session), Some(cached)) = (&self.session, state.cached_session.as_mut()) {
                if Arc::ptr_eq(session, &cached.session) && no_active_leases {
                    cached.idle_since = Instant::now();
                }
            }
        }
    }
}
