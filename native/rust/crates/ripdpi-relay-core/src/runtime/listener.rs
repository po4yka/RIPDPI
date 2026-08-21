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

/// Delay before retrying a listener that just failed to accept its
/// `consecutive_errors`-th connection in a row. Exponential from 100ms, capped
/// at 5s, so a persistent failure such as fd exhaustion degrades into slow
/// retries instead of an error-recording spin.
fn accept_backoff(consecutive_errors: u32) -> Duration {
    const BASE: Duration = Duration::from_millis(100);
    const MAX: Duration = Duration::from_secs(5);
    const MAX_SHIFT: u32 = 6;
    let shift = consecutive_errors.saturating_sub(1).min(MAX_SHIFT);
    BASE.saturating_mul(1_u32 << shift).min(MAX)
}

/// # Cancel safety
///
/// Cancel-safe. `TcpListener::accept` is cancel-safe when the polling timeout
/// drops it, accepted streams are either moved into a runtime-tracked session
/// or dropped synchronously, and admission permits release in `Drop`. The
/// accept-failure backoff sleeps in poll-interval chunks and re-checks the
/// stop flag between chunks, so shutdown latency stays within one poll window
/// even at the longest backoff step.
pub(super) async fn run_accept_loop(
    runtime: Arc<RelayRuntime>,
    backend: Arc<RelayBackend>,
    listener: TcpListener,
    max_concurrent_sessions: usize,
    accept_poll_interval: Duration,
) {
    let admission = SocksSessionAdmission::new(max_concurrent_sessions);
    let mut consecutive_accept_errors: u32 = 0;
    while !runtime.state.stop_requested() {
        match timeout(accept_poll_interval, listener.accept()).await {
            Ok(Ok((stream, _))) => {
                consecutive_accept_errors = 0;
                match admission.try_acquire() {
                    Some(permit) => {
                        spawn_socks_session(Arc::clone(&runtime), Arc::clone(&backend), stream, permit);
                    }
                    None => {
                        drop(stream);
                        runtime.state.record_error(format!(
                            "relay SOCKS admission saturated at {max_concurrent_sessions} sessions"
                        ));
                    }
                }
            }
            Ok(Err(error)) => {
                consecutive_accept_errors = consecutive_accept_errors.saturating_add(1);
                runtime.state.record_error(error.to_string());
                // The wait is chunked at the poll interval so a stop request
                // is honored within one poll window even during the longest
                // backoff step.
                let mut remaining = accept_backoff(consecutive_accept_errors);
                while remaining > Duration::ZERO && !runtime.state.stop_requested() {
                    let step = remaining.min(accept_poll_interval);
                    tokio::time::sleep(step).await;
                    remaining = remaining.saturating_sub(step);
                }
            }
            Err(_) => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use super::SocksSessionAdmission;

    #[test]
    fn accept_backoff_grows_exponentially_and_caps_at_five_seconds() {
        assert_eq!(Duration::from_millis(100), super::accept_backoff(1));
        assert_eq!(Duration::from_millis(200), super::accept_backoff(2));
        assert_eq!(Duration::from_millis(400), super::accept_backoff(3));
        assert_eq!(Duration::from_secs(5), super::accept_backoff(20));
    }

    #[test]
    fn admission_rejects_immediately_and_releases_capacity_on_drop() {
        let admission = SocksSessionAdmission::new(1);

        let permit = admission.try_acquire().expect("first session must be admitted");
        assert!(admission.try_acquire().is_none(), "second session must be rejected without waiting");

        drop(permit);
        assert!(admission.try_acquire().is_some(), "dropping the permit must release admission capacity");
    }
}
