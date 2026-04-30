//! Loom concurrency tests for the `MonitorSession` synchronisation pattern.
//!
//! Since `MonitorSession` uses `std::sync` primitives internally, these tests
//! replicate the same synchronisation pattern with `loom::sync` to exhaustively
//! verify that all interleavings of start/cancel/destroy are safe.
//!
//! Run with: cargo test -p ripdpi-monitor --features loom -- loom

#[cfg(feature = "loom")]
mod tests {
    use loom::sync::atomic::{AtomicBool, Ordering};
    use loom::sync::{Arc, Mutex};
    use loom::thread::{self, JoinHandle};

    /// Minimal model of `MonitorSession`'s synchronisation pattern.
    ///
    /// Mirrors the three fields of `MonitorSession` using loom primitives so that
    /// loom can exhaustively explore all thread interleavings.
    struct MonitorModel {
        cancel: Arc<AtomicBool>,
        worker: Arc<Mutex<Option<JoinHandle<()>>>>,
    }

    impl MonitorModel {
        fn new() -> Self {
            Self { cancel: Arc::new(AtomicBool::new(false)), worker: Arc::new(Mutex::new(None)) }
        }

        /// Returns `true` if a new scan was started, `false` if one was already running.
        ///
        /// Matches the logic of `MonitorSession::start_scan`: lock worker, guard against
        /// double-start, clear cancel flag, spawn worker thread, store handle.
        fn start_scan<F: FnOnce() + Send + 'static>(&self, scan_fn: F) -> bool {
            let mut guard = self.worker.lock().unwrap();
            if guard.is_some() {
                return false;
            }
            self.cancel.store(false, Ordering::Release);
            let cancel = self.cancel.clone();
            let handle = thread::spawn(move || {
                // Worker checks cancel flag before doing work -- same as production
                // run_scan which polls `cancel` throughout its execution.
                if !cancel.load(Ordering::Acquire) {
                    scan_fn();
                }
            });
            *guard = Some(handle);
            true
        }

        fn cancel_scan(&self) {
            self.cancel.store(true, Ordering::Release);
        }

        /// Matches `MonitorSession::try_join_worker` exactly: holds the worker lock
        /// across the `join()` call. This is safe because the worker thread never
        /// acquires the worker lock itself (it only touches `shared` and `cancel`).
        fn try_join_worker(&self) {
            let mut guard = match self.worker.lock() {
                Ok(g) => g,
                Err(_) => return,
            };
            if let Some(handle) = guard.take() {
                let _ = handle.join();
            }
        }

        fn destroy(&self) {
            self.cancel_scan();
            self.try_join_worker();
        }
    }

    #[test]
    fn loom_start_then_destroy_no_deadlock() {
        loom::model(|| {
            let model = Arc::new(MonitorModel::new());

            let m1 = model.clone();
            let t_start = thread::spawn(move || m1.start_scan(|| {}));

            let m2 = model.clone();
            let t_destroy = thread::spawn(move || {
                m2.destroy();
            });

            let started = t_start.join().unwrap();
            t_destroy.join().unwrap();

            // If start_scan won the race it stores a handle; destroy may have
            // run before start_scan in some interleavings, leaving the handle
            // behind.  In that case we clean it up here to verify no deadlock.
            // The key invariant is: no panic, no deadlock, and the worker guard
            // is always acquirable after both threads finish.
            if started {
                // Join any handle that destroy may have missed (racing interleaving).
                model.try_join_worker();
            }
            assert!(model.worker.lock().unwrap().is_none());
        });
    }

    #[test]
    fn loom_concurrent_start_rejected() {
        loom::model(|| {
            let model = Arc::new(MonitorModel::new());

            let m1 = model.clone();
            let t1 = thread::spawn(move || m1.start_scan(|| {}));

            let m2 = model.clone();
            let t2 = thread::spawn(move || m2.start_scan(|| {}));

            let r1 = t1.join().unwrap();
            let r2 = t2.join().unwrap();

            // Exactly one concurrent start must win; the other must be rejected.
            assert_ne!(r1, r2, "exactly one concurrent start must win");

            model.destroy();
        });
    }

    #[test]
    fn loom_cancel_signals_worker() {
        loom::model(|| {
            let model = Arc::new(MonitorModel::new());
            let saw_work = Arc::new(AtomicBool::new(false));

            let saw = saw_work.clone();
            let started = model.start_scan(move || {
                // scan_fn only runs if the worker observed cancel=false at spawn time.
                saw.store(true, Ordering::Relaxed);
            });
            assert!(started);

            let m1 = model.clone();
            let t_cancel = thread::spawn(move || m1.cancel_scan());
            t_cancel.join().unwrap();

            model.try_join_worker();

            // Cancel flag must be set after cancel_scan in all interleavings.
            assert!(model.cancel.load(Ordering::Acquire));
        });
    }
}
