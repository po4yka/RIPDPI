use std::collections::VecDeque;
use std::io;
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::num::NonZeroUsize;
use std::sync::{Arc as StdArc, Condvar, Mutex as StdMutex};
use std::thread;
use std::time::Duration;

use crate::adaptive_fake_ttl::AdaptiveFakeTtlResolver;
use crate::adaptive_tuning::AdaptivePlannerResolver;
use crate::retry_stealth::RetryPacer;
use crate::runtime_policy::RuntimePolicy;
use crate::{current_runtime_telemetry, EmbeddedProxyControl};
use crate::{platform, process};
use mio::net::TcpListener as MioTcpListener;
use mio::{Events, Interest, Poll};
use ripdpi_config::RuntimeConfig;
use socket2::{Domain, Protocol, SockAddr, SockRef, Socket, Type};

use crate::sync::{Arc, AtomicBool, AtomicUsize, Mutex};

use super::state::{flush_autolearn_updates, ClientSlotGuard, RuntimeCleanup, RuntimeState, LISTENER};

const WORKER_IDLE_TIMEOUT: Duration = Duration::from_secs(30);
const WORKER_PARALLELISM_FALLBACK: usize = 4;
const MAX_BASELINE_WORKERS: usize = 16;
/// Maximum time to wait for in-flight client connections to finish after the
/// listener stops accepting new connections.
const GRACEFUL_DRAIN_TIMEOUT: Duration = Duration::from_secs(3);

struct ClientJob {
    client: TcpStream,
    state: RuntimeState,
    slot: ClientSlotGuard,
}

struct WorkerPoolState {
    jobs: VecDeque<ClientJob>,
    live_workers: usize,
    idle_workers: usize,
    min_workers: usize,
    max_workers: usize,
    closed: bool,
}

struct WorkerPoolShared {
    state: StdMutex<WorkerPoolState>,
    available: Condvar,
}

struct ClientWorkerPool {
    shared: StdArc<WorkerPoolShared>,
}

impl ClientWorkerPool {
    fn new(max_workers: usize) -> io::Result<Self> {
        let min_workers = baseline_worker_count(max_workers, detected_parallelism());
        let pool = Self {
            shared: StdArc::new(WorkerPoolShared {
                state: StdMutex::new(WorkerPoolState {
                    jobs: VecDeque::new(),
                    live_workers: 0,
                    idle_workers: 0,
                    min_workers,
                    max_workers,
                    closed: false,
                }),
                available: Condvar::new(),
            }),
        };
        for _ in 0..min_workers {
            pool.spawn_worker()?;
        }
        Ok(pool)
    }

    fn enqueue(&self, job: ClientJob) -> Result<(), Box<ClientJob>> {
        let mut state = self.shared.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if state.closed {
            return Err(Box::new(job));
        }
        state.jobs.push_back(job);
        self.shared.available.notify_one();
        Ok(())
    }

    fn ensure_capacity(&self) -> io::Result<()> {
        let should_spawn = {
            let mut state = self.shared.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            if state.closed || state.idle_workers > 0 || state.live_workers >= state.max_workers {
                false
            } else {
                state.live_workers += 1;
                true
            }
        };

        if should_spawn {
            if let Err(err) = self.spawn_reserved_worker() {
                let mut state = self.shared.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
                state.live_workers = state.live_workers.saturating_sub(1);
                self.shared.available.notify_all();
                return Err(err);
            }
        }

        Ok(())
    }

    fn spawn_worker(&self) -> io::Result<()> {
        {
            let mut state = self.shared.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            state.live_workers += 1;
        }
        if let Err(err) = self.spawn_reserved_worker() {
            let mut state = self.shared.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            state.live_workers = state.live_workers.saturating_sub(1);
            self.shared.available.notify_all();
            return Err(err);
        }
        Ok(())
    }

    fn spawn_reserved_worker(&self) -> io::Result<()> {
        let shared = self.shared.clone();
        thread::Builder::new()
            .name("ripdpi-worker".into())
            .spawn(move || worker_loop(shared))
            .map(|_| ())
            .map_err(|err| io::Error::other(format!("failed to spawn client worker thread: {err}")))
    }

    fn has_live_workers(&self) -> bool {
        self.shared.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner).live_workers > 0
    }

    fn close(&self) {
        let mut state = self.shared.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        state.closed = true;
        self.shared.available.notify_all();
    }
}

impl Drop for ClientWorkerPool {
    fn drop(&mut self) {
        self.close();
    }
}

struct WorkerLifecycle {
    shared: StdArc<WorkerPoolShared>,
}

impl WorkerLifecycle {
    fn new(shared: StdArc<WorkerPoolShared>) -> Self {
        Self { shared }
    }
}

impl Drop for WorkerLifecycle {
    fn drop(&mut self) {
        let mut state = self.shared.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        state.live_workers = state.live_workers.saturating_sub(1);
        self.shared.available.notify_all();
    }
}

fn worker_loop(shared: StdArc<WorkerPoolShared>) {
    let _lifecycle = WorkerLifecycle::new(shared.clone());

    loop {
        let job = {
            let mut state = shared.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            state.idle_workers += 1;
            loop {
                if let Some(job) = state.jobs.pop_front() {
                    state.idle_workers = state.idle_workers.saturating_sub(1);
                    break job;
                }
                if state.closed {
                    state.idle_workers = state.idle_workers.saturating_sub(1);
                    return;
                }
                if state.live_workers > state.min_workers {
                    let (next_state, timeout) = shared
                        .available
                        .wait_timeout(state, WORKER_IDLE_TIMEOUT)
                        .unwrap_or_else(std::sync::PoisonError::into_inner);
                    state = next_state;
                    if timeout.timed_out()
                        && state.jobs.is_empty()
                        && !state.closed
                        && state.live_workers > state.min_workers
                    {
                        state.idle_workers = state.idle_workers.saturating_sub(1);
                        return;
                    }
                } else {
                    state = shared.available.wait(state).unwrap_or_else(std::sync::PoisonError::into_inner);
                }
            }
        };
        process_client_job(job);
    }
}

fn process_client_job(job: ClientJob) {
    let ClientJob { client, state, slot } = job;
    let _slot = slot;
    let result = super::handshake::handle_client(client, &state);
    if let Err(err) = &result {
        let shutting_down = state.control.as_ref().map_or_else(process::shutdown_requested, |c| c.shutdown_requested());
        if shutting_down && is_connection_closed_error(err) {
            tracing::debug!("ripdpi client error during shutdown (expected): {err}");
        } else {
            tracing::error!("ripdpi client error: {err}");
        }
        if let Some(telemetry) = &state.telemetry {
            telemetry.on_client_error(err);
        }
    }
    if let Some(telemetry) = &state.telemetry {
        telemetry.on_client_finished();
    }
}

/// Returns `true` for I/O errors that are expected when the proxy shuts down
/// while clients still have active connections (e.g. ECONNRESET, EPIPE).
fn is_connection_closed_error(err: &io::Error) -> bool {
    matches!(
        err.kind(),
        io::ErrorKind::ConnectionReset
            | io::ErrorKind::ConnectionAborted
            | io::ErrorKind::BrokenPipe
            | io::ErrorKind::UnexpectedEof
    )
}

fn detected_parallelism() -> usize {
    thread::available_parallelism().map(NonZeroUsize::get).unwrap_or(WORKER_PARALLELISM_FALLBACK)
}

fn baseline_worker_count(max_workers: usize, parallelism: usize) -> usize {
    if max_workers == 0 {
        return 0;
    }
    max_workers.min(parallelism.saturating_mul(2).clamp(1, MAX_BASELINE_WORKERS))
}

pub(super) fn build_listener(config: &RuntimeConfig) -> io::Result<TcpListener> {
    let listen_addr = SocketAddr::new(config.network.listen.listen_ip, config.network.listen.listen_port);
    let domain = match listen_addr {
        SocketAddr::V4(_) => Domain::IPV4,
        SocketAddr::V6(_) => Domain::IPV6,
    };
    let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))?;
    socket.set_reuse_address(true)?;
    socket.bind(&SockAddr::from(listen_addr))?;
    socket.listen(1024)?;
    let listener: TcpListener = socket.into();
    listener.set_nonblocking(true)?;
    Ok(listener)
}

pub(super) fn run_proxy_with_listener_internal(
    config: RuntimeConfig,
    listener: TcpListener,
    control: Option<StdArc<EmbeddedProxyControl>>,
) -> io::Result<()> {
    let mut config = config;
    if config.network.default_ttl == 0 {
        config.network.default_ttl = platform::detect_default_ttl()?;
    }
    let cache = RuntimePolicy::load(&config);
    let adaptive_tuning = AdaptivePlannerResolver::load(&config);
    let evolver_enabled = config.adaptive.strategy_evolution;
    let evolver_epsilon = config.adaptive.evolution_epsilon_permil as f64 / 1000.0;
    let state = RuntimeState {
        config: Arc::new(config),
        cache: Arc::new(Mutex::new(cache)),
        adaptive_fake_ttl: Arc::new(Mutex::new(AdaptiveFakeTtlResolver::default())),
        adaptive_tuning: Arc::new(Mutex::new(adaptive_tuning)),
        retry_stealth: Arc::new(Mutex::new(RetryPacer::default())),
        strategy_evolver: Arc::new(Mutex::new(crate::strategy_evolver::StrategyEvolver::new(
            evolver_enabled,
            evolver_epsilon,
        ))),
        active_clients: Arc::new(AtomicUsize::new(0)),
        telemetry: control.as_ref().and_then(|value| value.telemetry_sink()).or_else(current_runtime_telemetry),
        runtime_context: control.as_ref().and_then(|value| value.runtime_context()),
        control: control.clone(),
        ttl_unavailable: Arc::new(AtomicBool::new(false)),
    };
    let _cleanup = RuntimeCleanup {
        config: state.config.clone(),
        cache: state.cache.clone(),
        adaptive_tuning: state.adaptive_tuning.clone(),
    };
    let worker_pool = ClientWorkerPool::new(state.config.network.max_open.max(1) as usize)?;
    listener.set_nonblocking(true)?;
    let mut listener = MioTcpListener::from_std(listener);
    let mut poll = Poll::new()?;
    poll.registry().register(&mut listener, LISTENER, Interest::READABLE)?;
    let mut events = Events::with_capacity(256);
    if let Some(telemetry) = &state.telemetry {
        telemetry.on_listener_started(
            SocketAddr::new(state.config.network.listen.listen_ip, state.config.network.listen.listen_port),
            state.config.network.max_open as usize,
            state.config.groups.len(),
        );
    }
    if let Ok(mut cache) = state.cache.lock() {
        flush_autolearn_updates(&state, &mut cache);
    }

    let result = loop {
        let shutdown_requested =
            control.as_ref().map_or_else(process::shutdown_requested, |value| value.shutdown_requested());
        if shutdown_requested {
            break Ok(());
        }
        match poll.poll(&mut events, Some(Duration::from_millis(250))) {
            Ok(()) => {}
            Err(err) if err.kind() == io::ErrorKind::Interrupted => continue,
            Err(err) => break Err(err),
        }
        for event in &events {
            if event.token() != LISTENER {
                continue;
            }
            loop {
                match listener.accept() {
                    Ok((stream, _addr)) => {
                        let state = state.clone();
                        let client = mio_to_std_stream(stream);
                        client.set_nonblocking(false)?;
                        if let Err(err) = client.set_nodelay(true) {
                            tracing::debug!("set_nodelay on client socket failed (non-fatal): {err}");
                        }
                        let Some(_slot) = ClientSlotGuard::acquire(
                            state.active_clients.clone(),
                            state.config.network.max_open as usize,
                        ) else {
                            tracing::warn!("client connection rejected: at capacity");
                            if let Some(telemetry) = &state.telemetry {
                                telemetry.on_client_slot_exhausted();
                            }
                            let _ = SockRef::from(&client).set_linger(Some(Duration::ZERO));
                            drop(client);
                            continue;
                        };
                        if let Err(err) = worker_pool.ensure_capacity() {
                            tracing::error!("failed to provision client worker: {err}");
                            if !worker_pool.has_live_workers() {
                                if let Some(telemetry) = &state.telemetry {
                                    telemetry.on_client_error(&err);
                                }
                                let _ = SockRef::from(&client).set_linger(Some(Duration::ZERO));
                                drop(_slot);
                                drop(client);
                                continue;
                            }
                        }
                        if let Err(job) = worker_pool.enqueue(ClientJob { client, state: state.clone(), slot: _slot }) {
                            if let Some(telemetry) = &state.telemetry {
                                telemetry.on_client_error(&io::Error::other("client worker pool is closed"));
                            }
                            let _ = SockRef::from(&job.client).set_linger(Some(Duration::ZERO));
                            drop(job);
                            continue;
                        }
                        if let Some(telemetry) = &state.telemetry {
                            telemetry.on_client_accepted();
                        }
                    }
                    Err(err) if err.kind() == io::ErrorKind::WouldBlock => break,
                    Err(err) if err.kind() == io::ErrorKind::Interrupted => continue,
                    Err(err) => return Err(err),
                }
            }
        }
    };
    if let Some(telemetry) = &state.telemetry {
        telemetry.on_listener_stopped();
    }

    // Give in-flight client connections a brief grace period to finish before
    // the worker pool is dropped.  The pool `close()` prevents new jobs from
    // being enqueued while existing workers drain their current job.
    worker_pool.close();
    let drain_deadline = std::time::Instant::now() + GRACEFUL_DRAIN_TIMEOUT;
    while worker_pool.has_live_workers() {
        let remaining = drain_deadline.saturating_duration_since(std::time::Instant::now());
        if remaining.is_zero() {
            tracing::debug!("graceful drain timeout reached; dropping remaining workers");
            break;
        }
        thread::sleep(remaining.min(Duration::from_millis(50)));
    }

    result
}

fn mio_to_std_stream(stream: mio::net::TcpStream) -> TcpStream {
    use std::os::fd::{FromRawFd, IntoRawFd};

    let fd = stream.into_raw_fd();
    // SAFETY: ownership of the file descriptor is moved out of the mio stream
    // and transferred directly into the std stream without duplication.
    unsafe { TcpStream::from_raw_fd(fd) }
}

#[cfg(test)]
mod tests {
    use super::baseline_worker_count;

    #[test]
    fn baseline_worker_count_respects_client_limit() {
        assert_eq!(baseline_worker_count(1, 8), 1);
        assert_eq!(baseline_worker_count(4, 1), 2);
    }

    #[test]
    fn baseline_worker_count_caps_initial_pool_growth() {
        assert_eq!(baseline_worker_count(512, 32), 16);
        assert_eq!(baseline_worker_count(128, 8), 16);
    }
}
