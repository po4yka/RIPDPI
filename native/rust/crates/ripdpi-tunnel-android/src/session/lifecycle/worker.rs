use std::os::fd::{IntoRawFd, OwnedFd};
use std::sync::{Arc, Mutex};

use ripdpi_tunnel_core::Stats;
use tokio_util::sync::CancellationToken;

use crate::telemetry::TunnelTelemetryState;

pub(crate) struct WorkerLaunch {
    pub(crate) runtime: Arc<tokio::runtime::Runtime>,
    pub(crate) config: Arc<ripdpi_tunnel_config::Config>,
    pub(crate) owned_fd: OwnedFd,
    pub(crate) cancel: Arc<CancellationToken>,
    pub(crate) stats: Arc<Stats>,
    pub(crate) telemetry: Arc<TunnelTelemetryState>,
    pub(crate) last_error: Arc<Mutex<Option<String>>>,
}

pub(crate) fn launch_tunnel_worker(launch: WorkerLaunch) -> std::io::Result<std::thread::JoinHandle<()>> {
    std::thread::Builder::new().name("ripdpi-tunnel-worker".into()).spawn(move || run_worker(launch))
}

fn run_worker(launch: WorkerLaunch) {
    let WorkerLaunch { runtime, config, owned_fd, cancel, stats, telemetry, last_error } = launch;
    let worker_cancel = cancel.clone();
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        runtime.block_on(ripdpi_tunnel_core::run_tunnel(
            config,
            owned_fd.into_raw_fd(),
            (*worker_cancel).clone(),
            stats,
        ))
    }));

    match result {
        Ok(Ok(())) => {}
        Ok(Err(err)) => {
            record_worker_error(&telemetry, &last_error, format!("worker exited with error: {err}"), err.to_string());
        }
        Err(panic) => {
            let msg = panic_message(&panic);
            record_worker_error(
                &telemetry,
                &last_error,
                format!("worker panicked: {msg}"),
                format!("Tunnel worker panicked: {msg}"),
            );
        }
    }
    telemetry.mark_stopped();
}

fn record_worker_error(
    telemetry: &TunnelTelemetryState,
    last_error: &Mutex<Option<String>>,
    log_message: String,
    stored_message: String,
) {
    telemetry.log_line("worker", "error", &log_message);
    let mut guard = last_error.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    *guard = Some(stored_message.clone());
    drop(guard);
    telemetry.record_error(stored_message);
}

fn panic_message(panic: &(dyn std::any::Any + Send)) -> String {
    if let Some(s) = panic.downcast_ref::<&str>() {
        s.to_string()
    } else if let Some(s) = panic.downcast_ref::<String>() {
        s.clone()
    } else {
        "unknown panic".to_string()
    }
}
