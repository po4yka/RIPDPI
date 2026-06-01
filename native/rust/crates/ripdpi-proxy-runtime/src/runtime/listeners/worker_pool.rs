use std::collections::VecDeque;
use std::io;
use std::sync::{Arc as StdArc, Condvar, Mutex as StdMutex};
use std::thread;
use std::time::{Duration, Instant};

use super::client_job::{ClientJob, process_client_job};

const WORKER_IDLE_TIMEOUT: Duration = Duration::from_secs(30);
const WORKER_PARALLELISM_FALLBACK: usize = 4;
const MAX_BASELINE_WORKERS: usize = 16;
/// Maximum time to wait for in-flight client connections to finish after the
/// listener stops accepting new connections.
const GRACEFUL_DRAIN_TIMEOUT: Duration = Duration::from_secs(3);

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
        let mut state = self.shared.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        state.closed = true;
        self.shared.available.notify_all();
    }

    pub(crate) fn drain_gracefully(&self) {
        self.close();
        let drain_deadline = Instant::now() + GRACEFUL_DRAIN_TIMEOUT;
        while self.has_live_workers() {
            let remaining = drain_deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                tracing::debug!("graceful drain timeout reached; dropping remaining workers");
                break;
            }
            thread::sleep(remaining.min(Duration::from_millis(50)));
        }
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
