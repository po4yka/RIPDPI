use std::sync::Arc;
use std::time::Duration;

use tokio::net::TcpListener;
use tokio::sync::{OwnedSemaphorePermit, Semaphore};
use tokio::time::timeout;

use super::RelayRuntime;
use super::session::spawn_socks_session;
use crate::backend::RelayBackend;

struct SocksSessionAdmission {
    permits: Arc<Semaphore>,
}

impl SocksSessionAdmission {
    fn new(limit: usize) -> Self {
        Self { permits: Arc::new(Semaphore::new(limit)) }
    }

    /// cancel-safe: synchronous non-blocking acquisition; dropping the owned permit releases admission capacity.
    fn try_acquire(&self) -> Option<OwnedSemaphorePermit> {
        Arc::clone(&self.permits).try_acquire_owned().ok()
    }
}

/// # Cancel safety
///
/// Cancel-safe. `TcpListener::accept` is cancel-safe when the polling timeout
/// drops it, accepted streams are either moved into a runtime-tracked session
/// or dropped synchronously, and admission permits release in `Drop`.
pub(super) async fn run_accept_loop(
    runtime: Arc<RelayRuntime>,
    backend: Arc<RelayBackend>,
    listener: TcpListener,
    max_concurrent_sessions: usize,
    accept_poll_interval: Duration,
) {
    let admission = SocksSessionAdmission::new(max_concurrent_sessions);
    while !runtime.state.stop_requested() {
        match timeout(accept_poll_interval, listener.accept()).await {
            Ok(Ok((stream, _))) => match admission.try_acquire() {
                Some(permit) => {
                    spawn_socks_session(Arc::clone(&runtime), Arc::clone(&backend), stream, permit);
                }
                None => {
                    drop(stream);
                    runtime
                        .state
                        .record_error(format!("relay SOCKS admission saturated at {max_concurrent_sessions} sessions"));
                }
            },
            Ok(Err(error)) => runtime.state.record_error(error.to_string()),
            Err(_) => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use super::SocksSessionAdmission;

    #[test]
    fn admission_rejects_immediately_and_releases_capacity_on_drop() {
        let admission = SocksSessionAdmission::new(1);

        let permit = admission.try_acquire().expect("first session must be admitted");
        assert!(admission.try_acquire().is_none(), "second session must be rejected without waiting");

        drop(permit);
        assert!(admission.try_acquire().is_some(), "dropping the permit must release admission capacity");
    }
}
