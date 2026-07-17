use std::os::fd::OwnedFd;
use std::sync::mpsc::SyncSender;
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
    pub(crate) startup_ready: SyncSender<()>,
}

pub(crate) fn launch_tunnel_worker(launch: WorkerLaunch) -> std::io::Result<std::thread::JoinHandle<()>> {
    std::thread::Builder::new().name("ripdpi-tunnel-worker".into()).spawn(move || run_worker(launch))
}

fn run_worker(launch: WorkerLaunch) {
    let WorkerLaunch { runtime, config, owned_fd, cancel, stats, telemetry, last_error, startup_ready } = launch;
    let root_helper_generation = register_for_worker(&config);
    let worker_cancel = cancel.clone();
    // Callsite cancellation contract: run_tunnel_with_ready is NOT cancel-safe
    // if its future is dropped directly. Running stop cancels the token and
    // joins this worker while block_on keeps polling the future to shutdown.
    // Starting stop only cancels the token; the start-side fail-closed reaper
    // later owns the join so JNI stop does not block on a hung startup. OwnedFd
    // ownership transfers into the async frame before polling; if block_on
    // panics before the first poll, unwind drops that future-owned fd. After
    // polling, tun-rs AsyncDevice::Drop closes the fd exactly once.
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        runtime.block_on(ripdpi_tunnel_core::run_tunnel_with_ready(
            config,
            owned_fd,
            (*worker_cancel).clone(),
            stats,
            move || {
                let _ = startup_ready.send(());
            },
        ))
    }));

    record_worker_result(result, &telemetry, &last_error);
    if let Some(generation) = root_helper_generation {
        unregister_for_worker(generation);
    }
    telemetry.mark_stopped();
}
