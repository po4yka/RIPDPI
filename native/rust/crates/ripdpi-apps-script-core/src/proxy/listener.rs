use std::future::Future;
use std::io;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use tokio::net::TcpListener;
use tokio::sync::{Mutex, Semaphore};
use tokio::task::JoinSet;
use tokio::time::timeout;

use crate::AppsScriptRuntimeConfig;
use crate::domain_fronter::AppsScriptDomainFronter;
use crate::mitm::MitmCertManager;
use crate::proxy::client_job;
use crate::telemetry::SharedTelemetryState;

const ACCEPT_POLL_INTERVAL: Duration = Duration::from_millis(100);
/// Most client connections served at once. Each one holds a task, a socket and
/// up to a 16 MiB request body; a connection above the cap is closed at once.
pub(crate) const MAX_CLIENT_CONNECTIONS: usize = 256;

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
    let connection_slots = Arc::new(Semaphore::new(MAX_CLIENT_CONNECTIONS));
    // Ordering: stop_requested is polled as a standalone cancellation bit;
    // task shutdown is driven by abort_all(), not by memory published
    // through the atomic.
    while !stop_requested.load(Ordering::Relaxed) {
        match timeout(ACCEPT_POLL_INTERVAL, listener.accept()).await {
            Ok(Ok((stream, _peer))) => {
                let Ok(slot) = connection_slots.clone().try_acquire_owned() else {
                    // Closing at once is the backpressure; queued connections would still pin fds.
                    drop(stream);
                    continue;
                };
                let relay = relay.clone();
                let mitm = mitm.clone();
                let telemetry = telemetry.clone();
                let hosts = config.hosts.clone();
                spawn_reaped(&mut tasks, async move {
                    let _slot = slot;
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
    use std::sync::Arc;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::time::Duration;

    use tokio::io::AsyncReadExt;
    use tokio::net::TcpStream;
    use tokio::sync::Mutex;
    use tokio::task::JoinSet;
    use tokio::time::timeout;

    use super::{reap_finished, run, spawn_reaped};
    use crate::AppsScriptRuntimeConfig;
    use crate::domain_fronter::AppsScriptDomainFronter;
    use crate::mitm::MitmCertManager;
    use crate::telemetry::RelayTelemetryState;

    /// True when the peer closes the connection within `wait`.
    async fn closed_within(stream: &mut TcpStream, wait: Duration) -> bool {
        let mut byte = [0u8; 1];
        matches!(timeout(wait, stream.read(&mut byte)).await, Ok(Ok(0) | Err(_)))
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn connections_above_the_cap_are_closed() {
        let dir = std::env::temp_dir().join(format!("ripdpi-listener-cap-{}", std::process::id()));
        let mut config =
            AppsScriptRuntimeConfig::from_json(r#"{"scriptIds":["script-id"],"authKey":"secret"}"#).expect("config");
        config.listen_host = "127.0.0.1".to_string();
        config.listen_port = 0;
        let telemetry = Arc::new(RelayTelemetryState::new(&config));
        let relay = Arc::new(AppsScriptDomainFronter::new(&config, telemetry.clone()));
        let mitm = Arc::new(Mutex::new(MitmCertManager::new_in(&dir).expect("MITM CA")));
        let stop = Arc::new(AtomicBool::new(false));
        let snapshot_config = config.clone();
        let server = {
            let (telemetry, stop) = (telemetry.clone(), stop.clone());
            tokio::spawn(async move { run(&config, relay, mitm, telemetry, stop).await })
        };
        let address = loop {
            if let Some(address) = telemetry.snapshot(&snapshot_config).listener_address {
                break address;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        };

        // Idle clients that never send a SOCKS greeting fill every slot.
        let mut idle = Vec::new();
        for _ in 0..super::MAX_CLIENT_CONNECTIONS {
            idle.push(TcpStream::connect(&address).await.expect("connect idle client"));
        }
        let mut extra = TcpStream::connect(&address).await.expect("connect extra client");
        assert!(closed_within(&mut extra, Duration::from_secs(5)).await, "a connection above the cap must be closed");
        assert!(!closed_within(&mut idle[0], Duration::from_millis(200)).await, "admitted clients stay open");

        stop.store(true, Ordering::Relaxed);
        server.await.expect("listener task").expect("listener result");
        let _ = std::fs::remove_dir_all(&dir);
    }

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
