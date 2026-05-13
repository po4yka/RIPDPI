use std::os::fd::{IntoRawFd, OwnedFd};
use std::sync::{Arc, Mutex};

use ripdpi_tunnel_core::Stats;
use tokio_util::sync::CancellationToken;

use crate::telemetry::TunnelTelemetryState;

mod root_helper;
mod worker_error;

use self::root_helper::{register_for_worker, unregister_for_worker};
use self::worker_error::record_worker_result;

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
    let root_helper_registered = register_for_worker(&config);
    let worker_cancel = cancel.clone();
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        runtime.block_on(ripdpi_tunnel_core::run_tunnel(
            config,
            owned_fd.into_raw_fd(),
            (*worker_cancel).clone(),
            stats,
        ))
    }));

    record_worker_result(result, &telemetry, &last_error);
    if root_helper_registered {
        unregister_for_worker();
    }
    telemetry.mark_stopped();
}
