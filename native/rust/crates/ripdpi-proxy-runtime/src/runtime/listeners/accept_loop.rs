use std::io;
use std::net::{TcpListener, TcpStream};
use std::time::Duration;

use crate::runtime::state::RuntimeState;

use super::client_job::ClientJob;
use super::worker_pool::{ClientWorkerPool, WorkerDrainOutcome};
use super::{RuntimeShutdown, close_rejected_client};

const ACCEPT_IDLE_SLEEP: Duration = Duration::from_millis(25);
const ACCEPT_BATCH_LIMIT: usize = 64;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum AcceptBatch {
    Idle,
    Busy,
    Shutdown,
}

pub(crate) fn run_accept_loop(
    listener: TcpListener,
    state: RuntimeState,
    shutdown: RuntimeShutdown,
    client_capacity: usize,
) -> io::Result<AcceptLoopOutcome> {
    let worker_pool = ClientWorkerPool::new(client_capacity)?;
    let result = poll_accept_loop(listener, state.clone(), shutdown, &worker_pool, client_capacity);
    state.note_listener_stopped();
    let drain_outcome = worker_pool.drain_gracefully(&state);
    Ok(AcceptLoopOutcome { drain_outcome, poll_error_kind: result.err().map(|error| error.kind()) })
}

pub(crate) struct AcceptLoopOutcome {
    pub(crate) drain_outcome: WorkerDrainOutcome,
    pub(crate) poll_error_kind: Option<io::ErrorKind>,
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
        match accept_ready_clients(&listener, &state, worker_pool, client_capacity, &shutdown)? {
            AcceptBatch::Shutdown => return Ok(()),
            AcceptBatch::Idle => std::thread::sleep(ACCEPT_IDLE_SLEEP),
            AcceptBatch::Busy => std::thread::yield_now(),
        }
    }
}

fn accept_ready_clients(
    listener: &TcpListener,
    state: &RuntimeState,
    worker_pool: &ClientWorkerPool,
    client_capacity: usize,
    shutdown: &RuntimeShutdown,
) -> io::Result<AcceptBatch> {
    accept_ready_clients_with(
        || listener.accept().map(|(stream, _addr)| stream),
        || shutdown.requested(),
        |stream| accept_client(stream, state, worker_pool, client_capacity),
    )
}

fn accept_ready_clients_with(
    mut accept: impl FnMut() -> io::Result<TcpStream>,
    mut shutdown_requested: impl FnMut() -> bool,
    mut on_accept: impl FnMut(TcpStream) -> io::Result<()>,
) -> io::Result<AcceptBatch> {
    let mut accepted = 0usize;
    while accepted < ACCEPT_BATCH_LIMIT {
        if shutdown_requested() {
            return Ok(AcceptBatch::Shutdown);
        }
        match accept() {
            Ok(stream) => {
                accepted += 1;
                on_accept(stream)?;
            }
            Err(err) if err.kind() == io::ErrorKind::WouldBlock => {
                return Ok(if accepted == 0 { AcceptBatch::Idle } else { AcceptBatch::Busy });
            }
            Err(err) if err.kind() == io::ErrorKind::Interrupted => continue,
            Err(err) if listener_unusable(&err) => return Err(err),
            Err(err) => {
                // fd exhaustion, memory pressure and pending network errors clear on their own;
                // back off for one idle sleep instead of stopping the listener for every client.
                tracing::warn!("transient accept failure; retrying: {err}");
                return Ok(AcceptBatch::Idle);
            }
        }
    }
    Ok(AcceptBatch::Busy)
}

fn listener_unusable(err: &io::Error) -> bool {
    matches!(err.raw_os_error(), Some(libc::EBADF | libc::EINVAL | libc::ENOTSOCK | libc::EOPNOTSUPP | libc::EFAULT))
}

fn accept_client(
    client: TcpStream,
    state: &RuntimeState,
    worker_pool: &ClientWorkerPool,
    client_capacity: usize,
) -> io::Result<()> {
    let state = state.clone();
    if let Err(err) = client.set_nonblocking(false) {
        // A per-client setup failure rejects that client only, never the listener.
        state.note_client_error(&err);
        close_rejected_client(&client);
        return Ok(());
    }
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

#[cfg(test)]
mod tests {
    use std::cell::Cell;
    use std::net::{Ipv4Addr, SocketAddr};

    use super::*;

    fn queued_clients(count: usize) -> (TcpListener, Vec<TcpStream>) {
        let listener = TcpListener::bind(SocketAddr::from((Ipv4Addr::LOCALHOST, 0))).expect("bind listener");
        listener.set_nonblocking(true).expect("make listener nonblocking");
        let address = listener.local_addr().expect("listener address");
        let clients = (0..count).map(|_| TcpStream::connect(address).expect("connect queued client")).collect();
        (listener, clients)
    }

    #[test]
    fn accept_batch_stops_at_fairness_budget() {
        let (listener, _clients) = queued_clients(ACCEPT_BATCH_LIMIT + 1);
        let accepted = Cell::new(0usize);

        let batch = accept_ready_clients_with(
            || listener.accept().map(|(stream, _addr)| stream),
            || false,
            |stream| {
                accepted.set(accepted.get() + 1);
                drop(stream);
                Ok(())
            },
        )
        .expect("accept first batch");

        assert_eq!(batch, AcceptBatch::Busy);
        assert_eq!(accepted.get(), ACCEPT_BATCH_LIMIT, "one poll must not drain an unbounded accept backlog");
    }

    #[test]
    fn accept_batch_checks_shutdown_between_clients() {
        let (listener, _clients) = queued_clients(8);
        let shutdown_checks = Cell::new(0usize);
        let accepted = Cell::new(0usize);

        let batch = accept_ready_clients_with(
            || listener.accept().map(|(stream, _addr)| stream),
            || {
                let checks = shutdown_checks.get();
                shutdown_checks.set(checks + 1);
                checks >= 2
            },
            |stream| {
                accepted.set(accepted.get() + 1);
                drop(stream);
                Ok(())
            },
        )
        .expect("accept until shutdown");

        assert_eq!(batch, AcceptBatch::Shutdown);
        assert_eq!(accepted.get(), 2, "shutdown must be observed before draining the remaining backlog");
    }

    #[test]
    fn transient_accept_error_backs_off_instead_of_stopping_listener() {
        for errno in [libc::EMFILE, libc::ENFILE, libc::ENOBUFS, libc::ENOMEM, libc::ECONNABORTED, libc::EPROTO] {
            let batch = accept_ready_clients_with(|| Err(io::Error::from_raw_os_error(errno)), || false, |_| Ok(()))
                .unwrap_or_else(|err| panic!("errno {errno} must not end the accept loop: {err}"));
            assert_eq!(batch, AcceptBatch::Idle, "errno {errno} must back off for one idle sleep");
        }
    }

    #[test]
    fn listener_fd_errors_still_stop_the_accept_loop() {
        for errno in [libc::EBADF, libc::EINVAL, libc::ENOTSOCK] {
            let result = accept_ready_clients_with(|| Err(io::Error::from_raw_os_error(errno)), || false, |_| Ok(()));
            assert!(result.is_err(), "errno {errno} means the listener is unusable");
        }
    }
}
