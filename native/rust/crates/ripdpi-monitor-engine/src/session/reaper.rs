use std::sync::mpsc::{self, SyncSender};
use std::sync::{Arc, Condvar, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

pub(super) static WORKER_REAPER: std::sync::LazyLock<WorkerReaper> = std::sync::LazyLock::new(WorkerReaper::new);

const REAPER_WORKERS: usize = 4;
const REAPER_QUEUE_CAPACITY: usize = 64;
const REAPER_ENQUEUE_BUDGET: Duration = Duration::from_millis(5);

// Drop order: sender drops before workers are joined, closing their receiver
// loops so every worker can exit before WorkerReaper releases shared pending state.
pub(super) struct WorkerReaper {
    sender: Option<SyncSender<JoinHandle<()>>>,
    workers: Vec<JoinHandle<()>>,
    pending: Arc<(Mutex<usize>, Condvar)>,
}

impl WorkerReaper {
    fn new() -> Self {
        Self::new_with_limits(REAPER_WORKERS, REAPER_QUEUE_CAPACITY)
    }

    fn new_with_limits(worker_count: usize, queue_capacity: usize) -> Self {
        let (sender, receiver) = mpsc::sync_channel::<JoinHandle<()>>(queue_capacity);
        let receiver = Arc::new(Mutex::new(receiver));
        let pending = Arc::new((Mutex::new(0usize), Condvar::new()));
        let mut workers = Vec::with_capacity(worker_count);
        for index in 0..worker_count {
            let receiver = Arc::clone(&receiver);
            let pending = Arc::clone(&pending);
            match thread::Builder::new().name(format!("ripdpi-monitor-reaper-{index}")).spawn(move || {
                loop {
                    let handle = {
                        let receiver = receiver.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
                        receiver.recv()
                    };
                    let Ok(handle) = handle else {
                        break;
                    };
                    let _ = handle.join();
                    finish_reap(&pending);
                }
            }) {
                Ok(worker) => workers.push(worker),
                Err(error) => {
                    log::error!("failed to start diagnostics worker reaper {index}: {error}");
                    break;
                }
            }
        }
        let sender = (!workers.is_empty()).then_some(sender);
        Self { sender, workers, pending }
    }

    pub(super) fn reap(&self, handle: JoinHandle<()>) -> Result<(), JoinHandle<()>> {
        let Some(sender) = &self.sender else {
            return Err(handle);
        };
        let (count, _) = &*self.pending;
        *count.lock().unwrap_or_else(std::sync::PoisonError::into_inner) += 1;
        let deadline = Instant::now() + REAPER_ENQUEUE_BUDGET;
        let mut handle = handle;
        loop {
            match sender.try_send(handle) {
                Ok(()) => return Ok(()),
                Err(mpsc::TrySendError::Full(returned)) if Instant::now() < deadline => {
                    handle = returned;
                    thread::yield_now();
                }
                Err(mpsc::TrySendError::Full(returned) | mpsc::TrySendError::Disconnected(returned)) => {
                    finish_reap(&self.pending);
                    return Err(returned);
                }
            }
        }
    }

    #[cfg(test)]
    fn wait_for_pending_at_most(&self, limit: usize, timeout: Duration) -> bool {
        let (count, changed) = &*self.pending;
        let count = count.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let (count, _) = changed
            .wait_timeout_while(count, timeout, |pending| *pending > limit)
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        *count <= limit
    }
}

impl Drop for WorkerReaper {
    fn drop(&mut self) {
        self.sender.take();
        for worker in self.workers.drain(..) {
            if worker.thread().id() != thread::current().id() {
                let _ = worker.join();
            }
        }
    }
}

fn finish_reap(pending: &Arc<(Mutex<usize>, Condvar)>) {
    let (count, changed) = &**pending;
    let mut count = count.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    debug_assert!(*count > 0, "diagnostics worker reaper pending count underflow");
    *count = count.saturating_sub(1);
    changed.notify_all();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn worker_reaper_joins_workers_concurrently_without_head_of_line_blocking() {
        let reaper = WorkerReaper::new_with_limits(2, 4);
        let (release_first_tx, release_first_rx) = mpsc::channel();
        let (release_second_tx, release_second_rx) = mpsc::channel();
        let first = thread::spawn(move || release_first_rx.recv().expect("wait for first release"));
        let second = thread::spawn(move || release_second_rx.recv().expect("wait for second release"));

        reaper.reap(first).expect("enqueue first worker");
        reaper.reap(second).expect("enqueue second worker");
        release_second_tx.send(()).expect("release second worker");

        assert!(
            reaper.wait_for_pending_at_most(1, Duration::from_secs(1)),
            "a completed worker must be joined while an earlier worker remains blocked"
        );
        release_first_tx.send(()).expect("release first worker");
        assert!(reaper.wait_for_pending_at_most(0, Duration::from_secs(1)), "all workers must be joined");
    }

    #[test]
    fn worker_reaper_rejects_work_after_bounded_queue_saturates() {
        let reaper = WorkerReaper::new_with_limits(1, 1);
        let (release_first_tx, release_first_rx) = mpsc::channel();
        let (release_second_tx, release_second_rx) = mpsc::channel();
        let (release_third_tx, release_third_rx) = mpsc::channel();
        let first = thread::spawn(move || release_first_rx.recv().expect("wait for first release"));
        let second = thread::spawn(move || release_second_rx.recv().expect("wait for second release"));
        let third = thread::spawn(move || release_third_rx.recv().expect("wait for third release"));

        reaper.reap(first).expect("enqueue running worker");
        reaper.reap(second).expect("fill bounded reaper queue");
        let third = reaper.reap(third).expect_err("saturated reaper must reject work without growing its queue");

        release_first_tx.send(()).expect("release first worker");
        release_second_tx.send(()).expect("release second worker");
        release_third_tx.send(()).expect("release rejected worker");
        third.join().expect("join rejected worker in test");
        assert!(reaper.wait_for_pending_at_most(0, Duration::from_secs(1)), "accepted workers must be joined");
    }
}
