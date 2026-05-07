use std::io;
use std::net::{TcpListener, TcpStream};
use std::sync::Arc as StdArc;
use std::time::Duration;

use mio::net::TcpListener as MioTcpListener;
use mio::{Events, Interest, Poll};
use ripdpi_proxy_runtime_adapter::model::config::client_capacity;
use ripdpi_proxy_runtime_adapter::model::runtime_api::EmbeddedProxyControl;
use ripdpi_proxy_runtime_adapter::platform::listener as listener_platform;

use crate::process;
use crate::runtime::state::{ClientSlotGuard, RuntimeState};

use super::client_job::ClientJob;
use super::worker_pool::ClientWorkerPool;

const LISTENER: mio::Token = mio::Token(0);

pub(crate) fn run_accept_loop(
    listener: TcpListener,
    state: RuntimeState,
    control: Option<StdArc<EmbeddedProxyControl>>,
) -> io::Result<()> {
    let worker_pool = ClientWorkerPool::new(client_capacity(&state.config))?;
    let result = poll_accept_loop(listener, state.clone(), control, &worker_pool);
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
) -> io::Result<()> {
    listener.set_nonblocking(true)?;
    let mut listener = MioTcpListener::from_std(listener);
    let mut poll = Poll::new()?;
    poll.registry().register(&mut listener, LISTENER, Interest::READABLE)?;
    let mut events = Events::with_capacity(256);

    loop {
        let shutdown_requested =
            control.as_ref().map_or_else(process::shutdown_requested, |value| value.shutdown_requested());
        if shutdown_requested {
            return Ok(());
        }
        match poll.poll(&mut events, Some(Duration::from_millis(250))) {
            Ok(()) => {}
            Err(err) if err.kind() == io::ErrorKind::Interrupted => continue,
            Err(err) => return Err(err),
        }
        for event in &events {
            if event.token() != LISTENER {
                continue;
            }
            accept_ready_clients(&mut listener, &state, worker_pool)?;
        }
    }
}

fn accept_ready_clients(
    listener: &mut MioTcpListener,
    state: &RuntimeState,
    worker_pool: &ClientWorkerPool,
) -> io::Result<()> {
    loop {
        match listener.accept() {
            Ok((stream, _addr)) => accept_client(stream, state, worker_pool)?,
            Err(err) if err.kind() == io::ErrorKind::WouldBlock => return Ok(()),
            Err(err) if err.kind() == io::ErrorKind::Interrupted => continue,
            Err(err) => return Err(err),
        }
    }
}

fn accept_client(stream: mio::net::TcpStream, state: &RuntimeState, worker_pool: &ClientWorkerPool) -> io::Result<()> {
    let state = state.clone();
    let client = mio_to_std_stream(stream);
    client.set_nonblocking(false)?;
    if let Err(err) = client.set_nodelay(true) {
        tracing::debug!("set_nodelay on client socket failed (non-fatal): {err}");
    }
    let Some(slot) = ClientSlotGuard::acquire(state.active_clients.clone(), client_capacity(&state.config)) else {
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

fn mio_to_std_stream(stream: mio::net::TcpStream) -> TcpStream {
    use std::os::fd::{FromRawFd, IntoRawFd};

    let fd = stream.into_raw_fd();
    // SAFETY: ownership of the file descriptor is moved out of the mio stream
    // and transferred directly into the std stream without duplication.
    unsafe { TcpStream::from_raw_fd(fd) }
}
