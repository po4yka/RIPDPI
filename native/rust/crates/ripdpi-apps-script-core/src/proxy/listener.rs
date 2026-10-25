use std::future::Future;
use std::io;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use tokio::net::TcpListener;
use tokio::sync::Mutex;
use tokio::task::JoinSet;
use tokio::time::timeout;

use crate::AppsScriptRuntimeConfig;
use crate::domain_fronter::AppsScriptDomainFronter;
use crate::mitm::MitmCertManager;
use crate::proxy::client_job;
use crate::telemetry::SharedTelemetryState;

const ACCEPT_POLL_INTERVAL: Duration = Duration::from_millis(100);

/// # Cancel safety
///
/// cancel-safe: the accept loop holds no partial-protocol state across awaits;
/// per-connection work runs in spawned JoinSet tasks. On drop the JoinSet aborts
/// every task and `stop_requested` is the explicit shutdown bit, so cancelling
/// the listener future cleanly tears down without corrupting shared state.
pub(crate) async fn run(
    config: &AppsScriptRuntimeConfig,
    relay: Arc<AppsScriptDomainFronter>,
    mitm: Arc<Mutex<MitmCertManager>>,
    telemetry: SharedTelemetryState,
    stop_requested: Arc<AtomicBool>,
) -> io::Result<()> {
    let listener = TcpListener::bind(config.listener_address()).await?;
    let local_address = listener.local_addr()?.to_string();
    telemetry.mark_listener_bound(local_address.clone());
    tracing::info!(
        ring = "relay",
        subsystem = "relay",
        source = "relay",
        kind = "runtime_ready",
        "Apps Script relay listener started addr={local_address}"
    );

    let mut tasks = JoinSet::new();
    // Ordering: stop_requested is polled as a standalone cancellation bit;
    // task shutdown is driven by abort_all(), not by memory published
    // through the atomic.
    while !stop_requested.load(Ordering::Relaxed) {
        match timeout(ACCEPT_POLL_INTERVAL, listener.accept()).await {
            Ok(Ok((stream, _peer))) => {
                let relay = relay.clone();
                let mitm = mitm.clone();
                let telemetry = telemetry.clone();
                let hosts = config.hosts.clone();
                spawn_reaped(&mut tasks, async move {
                    let _ = client_job::handle(stream, relay, mitm, telemetry, hosts).await;
                });
            }
            Ok(Err(error)) => {
                telemetry.record_error(format!("listener accept failed: {error}"));
                return Err(error);
            }
            Err(_) => {}
        }
    }

    tasks.abort_all();
    while let Some(result) = tasks.join_next().await {
        note_finished_task(result);
    }
    tracing::info!(
        ring = "relay",
        subsystem = "relay",
        source = "relay",
        kind = "runtime_stopped",
        "Apps Script relay listener stopped"
    );
    Ok(())
}

/// Spawns a connection task after it removes every finished one.
///
/// A finished task stays in a `JoinSet`, with its whole future-sized cell,
/// until a join method returns it. Without this reaping the set grows with
/// every connection accepted since start, not with live connections.
fn spawn_reaped<F>(tasks: &mut JoinSet<()>, task: F)
where
    F: Future<Output = ()> + Send + 'static,
{
    reap_finished(tasks);
    tasks.spawn(task);
}

/// Removes every finished task and returns how many of them panicked.
fn reap_finished(tasks: &mut JoinSet<()>) -> usize {
    let mut panicked = 0;
    while let Some(result) = tasks.try_join_next() {
        if note_finished_task(result) {
            panicked += 1;
        }
    }
    panicked
}

/// Logs a connection task that panicked, without any connection identifier.
/// Returns true for a panic; a normal end or an abort is not logged.
fn note_finished_task(result: Result<(), tokio::task::JoinError>) -> bool {
    match result {
        Err(error) if error.is_panic() => {
            tracing::warn!(
                ring = "relay",
                subsystem = "relay",
                source = "relay",
                kind = "connection_task_panicked",
                "Apps Script connection task panicked"
            );
            true
        }
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use tokio::task::JoinSet;

    use super::{reap_finished, spawn_reaped};

    #[tokio::test(flavor = "current_thread")]
    async fn finished_connection_tasks_do_not_accumulate() {
        let mut tasks = JoinSet::new();
        for _ in 0..100 {
            spawn_reaped(&mut tasks, async {});
            // Let the short-lived connection task run to completion.
            tokio::task::yield_now().await;
        }

        assert!(tasks.len() <= 1, "finished tasks were kept: {}", tasks.len());
    }

    #[tokio::test(flavor = "current_thread")]
    async fn panicked_connection_tasks_are_reported_when_reaped() {
        let mut tasks = JoinSet::new();
        spawn_reaped(&mut tasks, async { panic!("connection task failure") });
        spawn_reaped(&mut tasks, async {});
        tokio::task::yield_now().await;

        assert_eq!(reap_finished(&mut tasks), 1);
        assert!(tasks.is_empty());
    }
}
