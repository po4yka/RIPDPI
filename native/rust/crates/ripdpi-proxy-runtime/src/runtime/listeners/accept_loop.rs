use std::io;
use std::net::{TcpListener, TcpStream};
use std::sync::Arc as StdArc;
use std::time::Duration;

use ripdpi_proxy_runtime_adapter::model::runtime_api::EmbeddedProxyControl;
use ripdpi_proxy_runtime_adapter::platform::listener as listener_platform;

use crate::process;
use crate::runtime::state::{ClientSlotGuard, RuntimeState};

use super::client_job::ClientJob;
use super::worker_pool::ClientWorkerPool;

const ACCEPT_IDLE_SLEEP: Duration = Duration::from_millis(25);

pub(crate) fn run_accept_loop(
    listener: TcpListener,
    state: RuntimeState,
    control: Option<StdArc<EmbeddedProxyControl>>,
    client_capacity: usize,
) -> io::Result<()> {
    let worker_pool = ClientWorkerPool::new(client_capacity)?;
    let result = poll_accept_loop(listener, state.clone(), control, &worker_pool, client_capacity);
    if let Some(telemetry) = &state.telemetry {
        telemetry.on_listener_stopped();
    }
    worker_pool.drain_gracefully();
    result
}

fn poll_accept_loop(
    listener: TcpListener,
    state: RuntimeState,
    control: Option<StdArc<EmbeddedProxyControl>>,
    worker_pool: &ClientWorkerPool,
    client_capacity: usize,
) -> io::Result<()> {
    listener.set_nonblocking(true)?;

    loop {
        let shutdown_requested =
            control.as_ref().map_or_else(process::shutdown_requested, |value| value.shutdown_requested());
        if shutdown_requested {
            return Ok(());
        }
        if !accept_ready_clients(&listener, &state, worker_pool, client_capacity)? {
            std::thread::sleep(ACCEPT_IDLE_SLEEP);
        }
    }
}

fn accept_ready_clients(
    listener: &TcpListener,
    state: &RuntimeState,
    worker_pool: &ClientWorkerPool,
    client_capacity: usize,
) -> io::Result<bool> {
    let mut accepted = false;
    loop {
        match listener.accept() {
            Ok((stream, _addr)) => {
                accepted = true;
                accept_client(stream, state, worker_pool, client_capacity)?;
            }
            Err(err) if err.kind() == io::ErrorKind::WouldBlock => return Ok(accepted),
            Err(err) if err.kind() == io::ErrorKind::Interrupted => continue,
            Err(err) => return Err(err),
        }
    }
}

fn accept_client(
    client: TcpStream,
    state: &RuntimeState,
    worker_pool: &ClientWorkerPool,
    client_capacity: usize,
) -> io::Result<()> {
    let state = state.clone();
    client.set_nonblocking(false)?;
    if let Err(err) = client.set_nodelay(true) {
        tracing::debug!("set_nodelay on client socket failed (non-fatal): {err}");
    }
    let Some(slot) = ClientSlotGuard::acquire(state.active_clients.clone(), client_capacity) else {
        tracing::warn!("client connection rejected: at capacity");
        if let Some(telemetry) = &state.telemetry {
            telemetry.on_client_slot_exhausted();
        }
        listener_platform::close_rejected_client(&client);
        drop(client);
        return Ok(());
    };
    if let Err(err) = worker_pool.ensure_capacity() {
        tracing::error!("failed to provision client worker: {err}");
        if !worker_pool.has_live_workers() {
            if let Some(telemetry) = &state.telemetry {
                telemetry.on_client_error(&err);
            }
            listener_platform::close_rejected_client(&client);
            drop(slot);
            drop(client);
            return Ok(());
        }
    }
    if let Err(job) = worker_pool.enqueue(ClientJob { client, state: state.clone(), slot }) {
        if let Some(telemetry) = &state.telemetry {
            telemetry.on_client_error(&io::Error::other("client worker pool is closed"));
        }
        listener_platform::close_rejected_client(&job.client);
        drop(job);
        return Ok(());
    }
    if let Some(telemetry) = &state.telemetry {
        telemetry.on_client_accepted();
    }
    Ok(())
}
