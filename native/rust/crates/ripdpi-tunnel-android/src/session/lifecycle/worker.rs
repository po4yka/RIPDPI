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
    // cancel-safe: run_tunnel holds a CancellationToken and exits the io_loop
    // cleanly when cancelled.  OwnedFd ownership transfers into run_tunnel's
    // async frame; tun-rs AsyncDevice::Drop closes the fd exactly once on any
    // exit path (normal return, cancellation, or panic unwind inside
    // catch_unwind).  No fd is orphaned even if block_on panics before the
    // future's first poll: OwnedFd remains live inside the pinned async future
    // until run_tunnel's first statement (into_raw_fd) executes.
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
