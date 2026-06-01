use std::io;
use std::net::{TcpListener, TcpStream};
use std::time::Duration;

use crate::runtime::state::RuntimeState;

use super::client_job::ClientJob;
use super::worker_pool::ClientWorkerPool;
use super::{RuntimeShutdown, close_rejected_client};

const ACCEPT_IDLE_SLEEP: Duration = Duration::from_millis(25);

pub(crate) fn run_accept_loop(
    listener: TcpListener,
    state: RuntimeState,
    shutdown: RuntimeShutdown,
    client_capacity: usize,
) -> io::Result<()> {
    let worker_pool = ClientWorkerPool::new(client_capacity)?;
    let result = poll_accept_loop(listener, state.clone(), shutdown, &worker_pool, client_capacity);
    state.note_listener_stopped();
    worker_pool.drain_gracefully();
    result
}

fn poll_accept_loop(
    listener: TcpListener,
    state: RuntimeState,
    shutdown: RuntimeShutdown,
    worker_pool: &ClientWorkerPool,
    client_capacity: usize,
) -> io::Result<()> {
    listener.set_nonblocking(true)?;

    loop {
        if shutdown.requested() {
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
    let Some(slot) = state.acquire_client_slot(client_capacity) else {
        tracing::warn!("client connection rejected: at capacity");
        state.note_client_slot_exhausted();
        close_rejected_client(&client);
        drop(client);
        return Ok(());
    };
    if let Err(err) = worker_pool.ensure_capacity() {
        tracing::error!("failed to provision client worker: {err}");
        if !worker_pool.has_live_workers() {
            state.note_client_error(&err);
            close_rejected_client(&client);
            drop(slot);
            drop(client);
            return Ok(());
        }
    }
    if let Err(job) = worker_pool.enqueue(ClientJob { client, state: state.clone(), slot }) {
        state.note_client_error(&io::Error::other("client worker pool is closed"));
        close_rejected_client(&job.client);
        drop(job);
        return Ok(());
    }
    state.note_client_accepted();
    Ok(())
}
