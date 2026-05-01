use std::io;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

use tokio::net::TcpListener;
use tokio::sync::Mutex;
use tokio::task::JoinSet;
use tokio::time::timeout;

use crate::domain_fronter::AppsScriptDomainFronter;
use crate::mitm::MitmCertManager;
use crate::proxy::client_job;
use crate::telemetry::SharedTelemetryState;
use crate::AppsScriptRuntimeConfig;

const ACCEPT_POLL_INTERVAL: Duration = Duration::from_millis(100);

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
                tasks.spawn(async move {
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
    while tasks.join_next().await.is_some() {}
    tracing::info!(
        ring = "relay",
        subsystem = "relay",
        source = "relay",
        kind = "runtime_stopped",
        "Apps Script relay listener stopped"
    );
    Ok(())
}
