use std::collections::VecDeque;
use std::io;
use std::net::Shutdown;
use std::sync::{Arc as StdArc, Condvar, Mutex as StdMutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use super::client_job::{ClientJob, process_client_job};
use crate::runtime::state::RuntimeState;

const WORKER_IDLE_TIMEOUT: Duration = Duration::from_secs(30);
const WORKER_PARALLELISM_FALLBACK: usize = 4;
const MAX_BASELINE_WORKERS: usize = 16;
/// Maximum time to wait for in-flight client connections to finish after the
/// listener stops accepting new connections.
const GRACEFUL_DRAIN_TIMEOUT: Duration = Duration::from_secs(3);
const FORCED_DRAIN_TIMEOUT: Duration = Duration::from_secs(1);

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

pub(crate) struct ClientWorkerPool {
    shared: StdArc<WorkerPoolShared>,
    workers: StdMutex<Vec<JoinHandle<()>>>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct WorkerDrainOutcome {
    pub(crate) forced_abort: bool,
    pub(crate) worker_panicked: bool,
}

impl ClientWorkerPool {
    pub(crate) fn new(max_workers: usize) -> io::Result<Self> {
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
            workers: StdMutex::new(Vec::new()),
        };
        for _ in 0..min_workers {
            pool.spawn_worker()?;
        }
        Ok(pool)
    }

    pub(crate) fn enqueue(&self, job: ClientJob) -> Result<(), Box<ClientJob>> {
        let mut state = self.shared.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if state.closed {
            return Err(Box::new(job));
        }
        state.jobs.push_back(job);
        self.shared.available.notify_one();
        Ok(())
    }

    pub(crate) fn ensure_capacity(&self) -> io::Result<()> {
        let should_spawn = {
            let mut state = self.shared.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            if state.closed || state.idle_workers > 0 || state.live_workers >= state.max_workers {
                false
            } else {
                state.live_workers += 1;
                true
            }
        };

        if should_spawn && let Err(err) = self.spawn_reserved_worker() {
            let mut state = self.shared.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            state.live_workers = state.live_workers.saturating_sub(1);
            self.shared.available.notify_all();
            return Err(err);
        }

        Ok(())
    }

    pub(crate) fn has_live_workers(&self) -> bool {
        self.shared.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner).live_workers > 0
    }

    pub(crate) fn close(&self) {
        let queued = {
            let mut state = self.shared.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            state.closed = true;
            let queued = state.jobs.drain(..).collect::<Vec<_>>();
            self.shared.available.notify_all();
            queued
        };
        for job in queued {
            let _ = job.client.shutdown(Shutdown::Both);
            job.state.note_client_finished();
            drop(job);
        }
    }

    pub(crate) fn drain_gracefully(&self, state: &RuntimeState) -> WorkerDrainOutcome {
        self.close();
        self.wait_for_workers(Instant::now() + GRACEFUL_DRAIN_TIMEOUT);
        let forced_abort = self.has_live_workers();
        if forced_abort {
            tracing::debug!("graceful drain timeout reached; shutting down active client sockets");
            state.shutdown_active_tcp_sockets();
            self.wait_for_workers(Instant::now() + FORCED_DRAIN_TIMEOUT);
        }
        let worker_panicked = self.join_finished_worker_handles();
        WorkerDrainOutcome { forced_abort, worker_panicked }
    }

    fn wait_for_workers(&self, deadline: Instant) {
        let mut pool_state = self.shared.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        while pool_state.live_workers > 0 {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                break;
            }
            let (next, _) = self
                .shared
                .available
                .wait_timeout(pool_state, remaining)
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            pool_state = next;
        }
    }

    fn join_finished_worker_handles(&self) -> bool {
        let handles = {
            let mut workers = self.workers.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            let mut finished = Vec::new();
            let mut pending = Vec::new();
            for handle in std::mem::take(&mut *workers) {
                if handle.is_finished() {
                    finished.push(handle);
                } else {
                    pending.push(handle);
                }
            }
            *workers = pending;
            finished
        };
        let mut worker_panicked = false;
        for handle in handles {
            worker_panicked |= handle.join().is_err();
        }
        worker_panicked
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
        let handle = thread::Builder::new()
            .name("ripdpi-worker".into())
            .spawn(move || worker_loop(shared))
            .map_err(|err| io::Error::other(format!("failed to spawn client worker thread: {err}")))?;
        self.workers.lock().unwrap_or_else(std::sync::PoisonError::into_inner).push(handle);
        Ok(())
    }
}

impl Drop for ClientWorkerPool {
    fn drop(&mut self) {
        self.close();
        // Bounded shutdown deliberately detaches still-running workers. They
        // retain `shared` until their socket-close path returns; Drop must not
        // turn an elapsed diagnostic deadline into an unbounded join.
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
                if state.closed {
                    state.idle_workers = state.idle_workers.saturating_sub(1);
                    return;
                }
                if let Some(job) = state.jobs.pop_front() {
                    state.idle_workers = state.idle_workers.saturating_sub(1);
                    break job;
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

fn detected_parallelism() -> usize {
    ripdpi_proxy_runtime_adapter::platform::process::detected_parallelism(WORKER_PARALLELISM_FALLBACK)
}

fn baseline_worker_count(max_workers: usize, parallelism: usize) -> usize {
    if max_workers == 0 {
        return 0;
    }
    max_workers.min(parallelism.saturating_mul(2).clamp(1, MAX_BASELINE_WORKERS))
}

#[cfg(test)]
mod tests {
    use std::io::Read;
    use std::net::{Ipv4Addr, TcpListener, TcpStream};
    use std::sync::atomic::{AtomicBool, Ordering};

    use ripdpi_proxy_runtime_adapter::model::config::RuntimeConfig;

    use super::*;

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

    #[test]
    fn closing_pool_drops_queued_jobs_before_workers_can_start_them() {
        let pool = ClientWorkerPool::new(0).expect("zero-worker pool");
        let state = RuntimeState::new(RuntimeConfig::default(), None);
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind client pair");
        let mut peer = TcpStream::connect(listener.local_addr().expect("listener address")).expect("connect peer");
        let (queued, _) = listener.accept().expect("accept queued client");
        let slot = state.acquire_client_slot(1).expect("client slot");
        assert!(pool.enqueue(ClientJob { client: queued, state, slot }).is_ok(), "queue client job");

        pool.close();

        peer.set_read_timeout(Some(Duration::from_secs(1))).expect("set peer timeout");
        let mut byte = [0u8; 1];
        assert_eq!(peer.read(&mut byte).unwrap_or(0), 0, "queued client must be closed during shutdown");
    }

    #[test]
    fn drain_joins_every_owned_worker_without_a_detached_reaper() {
        let pool = ClientWorkerPool::new(1).expect("worker pool");
        let state = RuntimeState::new(RuntimeConfig::default(), None);

        let outcome = pool.drain_gracefully(&state);

        assert!(!pool.has_live_workers(), "terminal drain must own and join every worker");
        assert!(!outcome.worker_panicked);
    }

    #[test]
    fn joining_worker_handles_reports_a_worker_panic() {
        let pool = ClientWorkerPool::new(0).expect("worker pool");
        pool.workers
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .push(thread::spawn(|| panic!("worker failed")));
        while !pool.workers.lock().unwrap_or_else(std::sync::PoisonError::into_inner)[0].is_finished() {
            thread::yield_now();
        }

        assert!(pool.join_finished_worker_handles());
    }

    #[test]
    fn joining_worker_handles_does_not_block_on_later_worker_after_a_panic() {
        let pool = ClientWorkerPool::new(0).expect("worker pool");
        let later_worker_completed = StdArc::new(AtomicBool::new(false));
        let later_worker_flag = later_worker_completed.clone();
        let (started_tx, started_rx) = std::sync::mpsc::channel();
        let (release_tx, release_rx) = std::sync::mpsc::channel();
        let mut workers = pool.workers.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        workers.push(thread::spawn(|| panic!("first worker failed")));
        workers.push(thread::spawn(move || {
            started_tx.send(()).expect("signal blocked worker");
            release_rx.recv().expect("release blocked worker");
            later_worker_flag.store(true, Ordering::Release);
        }));
        drop(workers);
        started_rx.recv().expect("wait for later worker to block");
        while !pool.workers.lock().unwrap_or_else(std::sync::PoisonError::into_inner)[0].is_finished() {
            thread::yield_now();
        }

        assert!(pool.join_finished_worker_handles());
        assert_eq!(pool.workers.lock().unwrap_or_else(std::sync::PoisonError::into_inner).len(), 1);
        assert!(
            !later_worker_completed.load(Ordering::Acquire),
            "bounded cleanup must not join a still-running worker",
        );
        release_tx.send(()).expect("release later worker");
        while !pool.workers.lock().unwrap_or_else(std::sync::PoisonError::into_inner)[0].is_finished() {
            thread::yield_now();
        }
        assert!(!pool.join_finished_worker_handles());
        assert!(later_worker_completed.load(Ordering::Acquire));
    }
}
